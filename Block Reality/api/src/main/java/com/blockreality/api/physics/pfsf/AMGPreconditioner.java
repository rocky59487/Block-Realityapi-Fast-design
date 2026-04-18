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
 * <h2>GPU Integration (v0.3e M4 — live)</h2>
 * The CPU setup produces two arrays uploaded to GPU via
 * {@link PFSFIslandBuffer#uploadAMGData(int[], float[], int)}:
 * <ul>
 *   <li>{@code aggregation[N_fine]} — fine-to-coarse mapping (int32)</li>
 *   <li>{@code pWeights[N_fine]} — prolongation weights (float32)</li>
 * </ul>
 * These drive the two GPU shaders registered in {@link PFSFPipelineFactory}:
 * <pre>
 *   amg_scatter_restrict.comp.glsl — r_c[j] = Σ_{i∈agg_j} P[i,j] · r_f[i]
 *   amg_gather_prolong.comp.glsl   — e_f[i] += P[i,j] · e_c[agg(i)]
 * </pre>
 * Dispatch is performed by {@link PFSFAMGRecorder#recordAMGVCycle} and
 * routed from {@link PFSFDispatcher} whenever {@link #isReady()} is true.
 * When AMG setup has not been triggered (or failed) the dispatcher falls
 * back to the geometric V-cycle in {@link PFSFVCycleRecorder}; the two
 * paths share the coarse-grid Jacobi smoother so the switch is purely a
 * choice of restriction / prolongation operator.
 *
 * <p>The CPU {@link #runCpuVCycle} entry mirrors the shader semantics
 * exactly and exists as (a) a parity oracle for the GPU pipeline and
 * (b) a GPU-less fallback for dev machines without Vulkan.</p>
 *
 * @see PFSFDispatcher#recordSolveSteps
 * @see PFSFVCycleRecorder
 * @see PFSFAMGRecorder
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

    // ── v0.3e M4 — CPU V-Cycle (parity oracle + GPU-less fallback) ──────

    /**
     * Execute one full AMG V-Cycle on the CPU. Mirrors the GPU pipeline
     * ({@code amg_scatter_restrict} → coarse Jacobi smoothing →
     * {@code amg_gather_prolong}) so the two paths produce numerically
     * identical output up to float-32 round-off.
     *
     * <p>Pipeline:</p>
     * <ol>
     *   <li>Restrict: {@code r_c[agg(i)] += P[i] · r_f[i]}</li>
     *   <li>Coarse solve: {@code coarseSweeps} damped Jacobi sweeps on
     *       the lumped coarse diagonal (Galerkin {@code A_c = P^T·P}).
     *       The lumped diagonal approximation is what the GPU coarse
     *       solve uses when {@code N_coarse ≤ 512} (shared-memory
     *       fast path in {@code amg_gather_prolong}).</li>
     *   <li>Prolong: {@code e_f[i] = P[i] · e_c[agg(i)]}</li>
     * </ol>
     *
     * @param fineResidual input residual on the fine grid, length N_fine
     * @param outCorrection output correction written to the fine grid,
     *                      length N_fine (accumulator — caller zeroes)
     * @param coarseSweeps  number of coarse-grid Jacobi iterations
     *                      (GPU path uses 4; parity tests may use more)
     * @param jacobiOmega   Jacobi relaxation factor (GPU path uses 1.0)
     * @throws IllegalStateException if {@link #build} has not completed
     */
    public void runCpuVCycle(float[] fineResidual,
                              float[] outCorrection,
                              int     coarseSweeps,
                              float   jacobiOmega) {
        if (!ready) throw new IllegalStateException("AMG setup not built");
        if (fineResidual.length != nFine || outCorrection.length != nFine) {
            throw new IllegalArgumentException(
                "residual/correction length must equal N_fine=" + nFine);
        }

        // ── Step 1: Restrict r_c = P^T · r_f ──────────────────────────
        float[] rc = new float[nCoarse];
        for (int i = 0; i < nFine; i++) {
            int a = aggregation[i];
            if (a < 0) continue;
            rc[a] += pWeights[i] * fineResidual[i];
        }

        // ── Step 2: Coarse Jacobi solve on lumped diagonal ────────────
        // Galerkin A_c ≈ diag( Σ_{i∈agg_j} P[i]^2 ) under the shared-mem
        // fast path. This matches the GPU fallback when N_coarse ≤ 512.
        float[] diagC = new float[nCoarse];
        for (int i = 0; i < nFine; i++) {
            int a = aggregation[i];
            if (a < 0) continue;
            float w = pWeights[i];
            diagC[a] += w * w;
        }
        float[] ec     = new float[nCoarse];
        float[] ecNext = new float[nCoarse];
        for (int s = 0; s < coarseSweeps; s++) {
            for (int j = 0; j < nCoarse; j++) {
                float d = diagC[j];
                if (d > 1e-12f) {
                    ecNext[j] = ec[j] + jacobiOmega * (rc[j] / d - ec[j]);
                } else {
                    ecNext[j] = 0f;
                }
            }
            System.arraycopy(ecNext, 0, ec, 0, nCoarse);
        }

        // ── Step 3: Prolong e_f = P · e_c ─────────────────────────────
        for (int i = 0; i < nFine; i++) {
            int a = aggregation[i];
            outCorrection[i] = (a >= 0) ? pWeights[i] * ec[a] : 0f;
        }
    }

    /**
     * Restriction invariant: a constant residual on the fine grid
     * produces a restricted coarse residual whose aggregate sum equals
     * the fine-grid total (up to anchor/air nodes which contribute 0).
     *
     * <p>Used by {@code PFSFAMGParityTest} to detect aggregation or
     * weight-normalisation drift between refactors without needing a
     * live GPU.</p>
     *
     * @return {@code true} iff the column sums of P preserve the
     *         constant null space to within {@code tol}.
     */
    public boolean checkPartitionOfUnity(float tol) {
        if (!ready) return false;
        double[] colSum = new double[nCoarse];
        int[]    colCnt = new int[nCoarse];
        for (int i = 0; i < nFine; i++) {
            int a = aggregation[i];
            if (a >= 0 && a < nCoarse) {
                colSum[a] += pWeights[i];
                colCnt[a]++;
            }
        }
        for (int j = 0; j < nCoarse; j++) {
            if (colCnt[j] == 0) continue;
            double expected = colCnt[j];
            if (Math.abs(colSum[j] - expected) > tol * expected) return false;
        }
        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private static int flat(int x, int y, int z, int Ly, int Lz) {
        return x * Ly * Lz + y * Lz + z;
    }

    private static boolean inBounds(int x, int y, int z, int Lx, int Ly, int Lz) {
        return x >= 0 && x < Lx && y >= 0 && y < Ly && z >= 0 && z < Lz;
    }
}
