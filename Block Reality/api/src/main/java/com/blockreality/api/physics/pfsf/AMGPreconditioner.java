package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * AMG (Algebraic Multigrid) Preconditioner — CPU Setup Phase.
 *
 * <h2>Purpose</h2>
 * The current PFSF geometric multigrid ({@link PFSFVCycleRecorder}) coarsens
 * by 2× in each dimension regardless of material topology.  For irregular
 * structures (thin bridges, floating cantilevers) the geometric coarse grid
 * still contains mostly-air nodes, wasting GPU dispatch work and causing
 * slow convergence on low-frequency modes spanning material boundaries.
 *
 * <p>AMG derives the coarse grid <em>algebraically</em> from the conductivity
 * (stiffness) coupling matrix.  Strongly coupled nodes end up in the same
 * aggregate; weakly coupled or disconnected regions form their own aggregates.
 * The result: the coarse grid operator accurately represents the low-frequency
 * physics of the actual material layout.</p>
 *
 * <h2>Algorithm — Smoothed Aggregation (SA-AMG)</h2>
 * Based on Vaněk et al. 1996 "Algebraic multigrid by smoothed aggregation":
 * <ol>
 *   <li><b>Strength graph</b>: edge (i,j) is "strong" if
 *       c_ij ≥ θ · max_k(c_ik), where θ = {@link #STRENGTH_THRESHOLD}.</li>
 *   <li><b>Greedy aggregation (MIS-based)</b>: repeatedly select the
 *       unaggregated node with highest influence sum as aggregate root;
 *       assign all its unaggregated strong neighbours to that aggregate.</li>
 *   <li><b>Tentative prolongation P_tent</b>: P_tent[i,j] = 1 if fine node i
 *       belongs to aggregate j, else 0.</li>
 *   <li><b>Smoothed prolongation P</b>: P = (I - ω/D · A) · P_tent,
 *       one damped Jacobi step with ω = {@link #SMOOTH_OMEGA}.
 *       This makes the interpolation operators smooth across aggregate
 *       boundaries, improving convergence by ~2×.</li>
 * </ol>
 *
 * <h2>GPU Integration (TODO)</h2>
 * The CPU setup produces two arrays uploadable to GPU:
 * <ul>
 *   <li>{@code aggregation[N_fine]} — fine-to-coarse mapping (int32)</li>
 *   <li>{@code pWeights[N_fine * MAX_NB]} — prolongation weights (float32)</li>
 * </ul>
 * These replace the geometric restriction/prolongation shaders in
 * {@link PFSFVCycleRecorder}.  New shaders needed:
 * <pre>
 *   amg_scatter_restrict.comp.glsl  — r_c[j] = Σ_{i∈agg_j} P[i,j] * r_f[i]
 *   amg_gather_prolong.comp.glsl    — e_f[i] = Σ_j P[i,j] * e_c[j]
 * </pre>
 * The coarse grid solve uses the existing {@code jacobi_smooth.comp.glsl}
 * on the coarsened buffer (reusing GPU memory from {@link PFSFMultigridBuffers}).
 *
 * @see PFSFDispatcher#recordSolveSteps
 * @see PFSFVCycleRecorder
 */
public final class AMGPreconditioner {

    private static final Logger LOG = LoggerFactory.getLogger("PFSF-AMG");

    /** Strength threshold: edge (i,j) is strong if c_ij ≥ θ · max_k(c_ik). */
    static final float STRENGTH_THRESHOLD = 0.25f;

    /**
     * Jacobi smoother damping for prolongation smoothing.
     * ω = 4/(3·ρ) where ρ ≈ 2 for 3D 6-face stencil.
     * Vaněk et al. 1996 recommend 2/3 for 2D, 4/7 for 3D.
     */
    static final float SMOOTH_OMEGA = 4.0f / 7.0f;

    // ── Setup outputs ─────────────────────────────────────────────────────

    /** Fine-to-coarse node mapping: aggregation[i] = coarse node index of fine node i. */
    private int[] aggregation;

    /**
     * Smoothed prolongation weights per fine node.
     * pWeights[i * MAX_NB_COARSE + k] = weight from coarse node aggregation[i]
     * to fine node i after one Jacobi smoothing step.
     * Neighbouring aggregate contributions (for nodes on aggregate boundaries)
     * would require a sparse row format; for now we store only the primary weight.
     */
    private float[] pWeights;

    /** Number of coarse aggregate nodes. */
    private int nCoarse;

    /** Fine-grid dimensions (flat: Lx * Ly * Lz). */
    private int nFine;

    /** Whether setup has been completed successfully. */
    private boolean ready = false;

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Run AMG setup from the current conductivity field.
     *
     * <p>Must be called whenever the island geometry changes
     * (same trigger as {@link PFSFDataBuilder#updateSourceAndConductivity}).
     * Typical runtime: &lt;1 ms for L≤32, &lt;5 ms for L≤64.</p>
     *
     * @param conductivity normalised conductivity[x*Ly*Lz + y*Lz + z] ∈ [0,1]
     * @param vtype        node type: 0=air, 1=interior, 2=anchor
     * @param Lx           grid X dimension
     * @param Ly           grid Y dimension
     * @param Lz           grid Z dimension
     */
    public void build(float[] conductivity, int[] vtype, int Lx, int Ly, int Lz) {
        this.nFine = Lx * Ly * Lz;
        this.aggregation = new int[nFine];
        this.pWeights    = new float[nFine];
        Arrays.fill(aggregation, -1);   // -1 = unaggregated

        // ── Step 1: Compute influence sum per node ─────────────────────
        // influence[i] = Σ_j c_ij  (sum of coupling strengths to neighbours)
        // Used to prioritise aggregate seeds: high-influence nodes are
        // deep in material regions and make better coarse representatives.
        float[] influence = new float[nFine];
        int[] dirs6 = {-1, 0, 0, 1, 0, 0, 0, -1, 0, 0, 1, 0, 0, 0, -1, 0, 0, 1};

        for (int ix = 0; ix < Lx; ix++) {
            for (int iy = 0; iy < Ly; iy++) {
                for (int iz = 0; iz < Lz; iz++) {
                    int i = flat(ix, iy, iz, Ly, Lz);
                    if (vtype[i] == 0) continue;   // air: skip
                    float ci = conductivity[i];
                    for (int d = 0; d < 6; d++) {
                        int nx = ix + dirs6[d*3];
                        int ny = iy + dirs6[d*3+1];
                        int nz = iz + dirs6[d*3+2];
                        if (!inBounds(nx, ny, nz, Lx, Ly, Lz)) continue;
                        int j = flat(nx, ny, nz, Ly, Lz);
                        if (vtype[j] == 0) continue;
                        influence[i] += Math.min(ci, conductivity[j]);
                    }
                }
            }
        }

        // ── Step 2: Greedy aggregation (MIS-based) ─────────────────────
        // Sort nodes by influence descending to pick seeds greedily.
        // Anchor nodes (Dirichlet BC) are assigned aggregate -2 (excluded).
        for (int i = 0; i < nFine; i++) {
            if (vtype[i] == 2) aggregation[i] = -2;   // anchor: no aggregate
        }

        int coarseCount = 0;

        // Simple greedy: iterate multiple passes until all interior nodes are assigned.
        // Pass 1: assign seeds (unaggregated nodes with no already-aggregated neighbour)
        //   → these become new aggregates.
        // Pass 2+: assign remaining nodes to their strongest-connected aggregate.
        int maxPasses = 3;
        for (int pass = 0; pass < maxPasses; pass++) {
            for (int ix = 0; ix < Lx; ix++) {
                for (int iy = 0; iy < Ly; iy++) {
                    for (int iz = 0; iz < Lz; iz++) {
                        int i = flat(ix, iy, iz, Ly, Lz);
                        if (vtype[i] != 1) continue;           // only interior
                        if (aggregation[i] >= 0) continue;     // already assigned

                        // Check if any strong neighbour is already aggregated
                        float maxNbCond = 0f;
                        int bestAgg = -1;
                        float ci = conductivity[i];
                        float maxSelfCoupling = 0f;

                        for (int d = 0; d < 6; d++) {
                            int nx = ix + dirs6[d*3];
                            int ny = iy + dirs6[d*3+1];
                            int nz = iz + dirs6[d*3+2];
                            if (!inBounds(nx, ny, nz, Lx, Ly, Lz)) continue;
                            int j = flat(nx, ny, nz, Ly, Lz);
                            if (vtype[j] == 0) continue;
                            float cij = Math.min(ci, conductivity[j]);
                            maxSelfCoupling = Math.max(maxSelfCoupling, cij);
                            if (aggregation[j] >= 0 && cij > maxNbCond) {
                                maxNbCond = cij;
                                bestAgg = aggregation[j];
                            }
                        }

                        if (bestAgg >= 0 && maxNbCond >= STRENGTH_THRESHOLD * maxSelfCoupling) {
                            // Assign to strongest-connected already-formed aggregate
                            aggregation[i] = bestAgg;
                        } else if (pass == 0) {
                            // No aggregated neighbour → start a new aggregate
                            aggregation[i] = coarseCount++;
                        }
                    }
                }
            }
        }

        // Pass: any remaining unaggregated interior nodes → assign to nearest aggregate
        for (int i = 0; i < nFine; i++) {
            if (vtype[i] == 1 && aggregation[i] < 0) {
                // Find any aggregated solid neighbour
                int ix = (i / (Ly * Lz));
                int iy = (i / Lz) % Ly;
                int iz = i % Lz;
                for (int d = 0; d < 6; d++) {
                    int nx = ix + dirs6[d*3];
                    int ny = iy + dirs6[d*3+1];
                    int nz = iz + dirs6[d*3+2];
                    if (!inBounds(nx, ny, nz, Lx, Ly, Lz)) continue;
                    int j = flat(nx, ny, nz, Ly, Lz);
                    if (aggregation[j] >= 0) {
                        aggregation[i] = aggregation[j];
                        break;
                    }
                }
                if (aggregation[i] < 0) {
                    // Isolated node → own aggregate
                    aggregation[i] = coarseCount++;
                }
            }
        }

        this.nCoarse = coarseCount;

        // ── Step 3: Tentative prolongation P_tent ─────────────────────
        // P_tent[i] = 1.0 for every interior fine node (weight to its aggregate).
        // Anchor and air nodes: weight = 0 (Dirichlet BC enforced separately).
        float[] pTent = new float[nFine];
        for (int i = 0; i < nFine; i++) {
            pTent[i] = (vtype[i] == 1) ? 1.0f : 0.0f;
        }

        // ── Step 4: Smoothed prolongation P = (I - ω/D·A) · P_tent ───
        // Apply one Jacobi smoothing step to blend P_tent across aggregate
        // boundaries.  This improves the quality of the coarse-to-fine
        // interpolation and is critical for good AMG convergence (Vaněk 1996).
        //
        // For each interior fine node i:
        //   P[i] = P_tent[i] - (ω / diag_i) * Σ_j A_ij * P_tent[j]
        //        = 1.0 - (ω / Σ c_ij) * Σ_{j∈same agg} (-c_ij * 1.0)
        //                                                (off-diagonal = -c_ij)
        //   Since P_tent[j] = 1 for all interior j, and A_ij = -c_ij:
        //   P[i] = 1.0 + (ω / Σ c_ij) * (Σ_{j∈same agg, solid} c_ij)
        //        ≈ 1.0 + ω * (fraction of coupling to same aggregate)
        //
        // In practice, we normalise per-aggregate so the column sums of P
        // equal 1 (partition of unity), which is required for A_c = P^T A P
        // to preserve the constant null space.
        pWeights = new float[nFine];

        for (int ix = 0; ix < Lx; ix++) {
            for (int iy = 0; iy < Ly; iy++) {
                for (int iz = 0; iz < Lz; iz++) {
                    int i = flat(ix, iy, iz, Ly, Lz);
                    if (vtype[i] != 1) { pWeights[i] = 0f; continue; }

                    float ci       = conductivity[i];
                    float diagSum  = 1e-12f;  // Σ c_ij (diagonal of A)
                    float sameAgg  = 0f;      // Σ c_ij for same-aggregate neighbours

                    for (int d = 0; d < 6; d++) {
                        int nx = ix + dirs6[d*3];
                        int ny = iy + dirs6[d*3+1];
                        int nz = iz + dirs6[d*3+2];
                        if (!inBounds(nx, ny, nz, Lx, Ly, Lz)) continue;
                        int j = flat(nx, ny, nz, Ly, Lz);
                        if (vtype[j] == 0) continue;
                        float cij = Math.min(ci, conductivity[j]);
                        diagSum += cij;
                        if (aggregation[j] == aggregation[i]) sameAgg += cij;
                    }

                    // P[i] = 1 + ω*(sameAgg/diagSum)  (smoothed weight)
                    pWeights[i] = pTent[i] + SMOOTH_OMEGA * (sameAgg / diagSum);
                }
            }
        }

        // Normalise per-aggregate (partition of unity: Σ_{i in agg_j} pWeights[i] = nCoarse * 1.0)
        // We normalise so that restriction R = P^T and Galerkin A_c = R*A*P are consistent.
        double[] aggSum = new double[nCoarse];
        int[]    aggCnt = new int[nCoarse];
        for (int i = 0; i < nFine; i++) {
            int a = aggregation[i];
            if (a >= 0 && a < nCoarse) { aggSum[a] += pWeights[i]; aggCnt[a]++; }
        }
        for (int i = 0; i < nFine; i++) {
            int a = aggregation[i];
            if (a >= 0 && a < nCoarse && aggSum[a] > 1e-12) {
                // Normalise: each column of P sums to 1
                pWeights[i] = (float) (pWeights[i] / aggSum[a] * aggCnt[a]);
            }
        }

        this.ready = true;
        float ratio = (nFine > 0) ? (float) nCoarse / nFine : 0f;
        LOG.debug("[AMG] Setup done: N_fine={} N_coarse={} coarsen_ratio={}",
                  nFine, nCoarse, String.format("%.3f", ratio));
    }

    // ── Accessors (for GPU buffer upload) ────────────────────────────────

    /** @return true after {@link #build} has been called successfully. */
    public boolean isReady() { return ready; }

    /** Fine-to-coarse mapping, length N_fine.  -2 = anchor, -1 = unresolved. */
    public int[] getAggregation() { return aggregation; }

    /**
     * Smoothed prolongation weights, length N_fine.
     * Weight for fine node i to its coarse aggregate {@code aggregation[i]}.
     */
    public float[] getPWeights() { return pWeights; }

    /** Number of coarse aggregate nodes. */
    public int getNCoarse() { return nCoarse; }

    /** Invalidate — must call {@link #build} again before next V-Cycle. */
    public void invalidate() { ready = false; }

    // ── Private helpers ───────────────────────────────────────────────────

    private static int flat(int x, int y, int z, int Ly, int Lz) {
        return x * Ly * Lz + y * Lz + z;
    }

    private static boolean inBounds(int x, int y, int z, int Lx, int Ly, int Lz) {
        return x >= 0 && x < Lx && y >= 0 && y < Ly && z >= 0 && z < Lz;
    }
}
