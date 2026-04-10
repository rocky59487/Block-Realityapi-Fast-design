package com.blockreality.api.physics.pfsf;

import ai.onnxruntime.*;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * ONNX-based PFSF runtime — loads a trained FNO3DMultiField model
 * and runs inference for irregular structures.
 *
 * <p>Input (5 channels): [1, L, L, L, 5] — occ, E, ν, ρ, Rcomp (normalized)</p>
 * <p>Output (10 channels): [1, L, L, L, 10] — σ(6) + u(3) + φ(1)</p>
 *
 * <p>The φ channel (index 9) is fed directly into the existing PFSF failure
 * detection pipeline, providing FEM-quality physics at FNO inference speed.</p>
 *
 * @since v1.0 (BIFROST)
 * @see HybridPhysicsRouter
 */
public class OnnxPFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-ONNX");

    // Normalization constants (MUST match brml/pipeline/auto_train.py)
    private static final float E_SCALE   = 200e9f;
    private static final float RHO_SCALE = 7850.0f;
    private static final float RC_SCALE  = 250.0f;
    private static final float RT_SCALE  = 500.0f;    // steel Rtens

    private OrtEnvironment env;
    private OrtSession session;
    private int gridSize;
    private float vmScale = 1.0f;
    private boolean available = false;

    private Function<BlockPos, RMaterial> materialLookup;
    private Function<BlockPos, Boolean> anchorLookup;

    /**
     * Load an ONNX model.
     *
     * @param modelPath Path to .onnx file
     * @return true if loaded successfully
     */
    public boolean loadModel(String modelPath) {
        try {
            Path path = Path.of(modelPath);
            if (!Files.exists(path)) {
                LOGGER.warn("[ONNX] Model not found: {}", modelPath);
                return false;
            }

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

            try {
                opts.addCUDA(0);
                LOGGER.info("[ONNX] CUDA execution provider enabled");
            } catch (OrtException e) {
                LOGGER.info("[ONNX] CUDA not available, using CPU");
            }

            session = env.createSession(modelPath, opts);

            // Extract grid size from input shape: [1, L, L, L, 5]
            Map<String, NodeInfo> inputs = session.getInputInfo();
            if (!inputs.isEmpty()) {
                NodeInfo firstInput = inputs.values().iterator().next();
                if (firstInput.getInfo() instanceof TensorInfo ti) {
                    long[] shape = ti.getShape();
                    if (shape.length == 5 && (shape[4] == 5 || shape[4] == 6)) {
                        gridSize = (int) shape[1];
                    }
                }
            }
            if (gridSize <= 0) gridSize = 16;

            available = true;
            LOGGER.info("[ONNX] Model loaded: {} (grid={})", modelPath, gridSize);
            return true;

        } catch (OrtException e) {
            LOGGER.error("[ONNX] Failed to load model: {}", e.getMessage());
            available = false;
            return false;
        }
    }

    public boolean isAvailable() { return available; }

    /** Alias for {@link #isAvailable()} — used by PFSFScheduler and surrogate integration. */
    public boolean isReady() { return available; }

    public int getGridSize() { return gridSize; }

    public void setMaterialLookup(Function<BlockPos, RMaterial> lookup) {
        this.materialLookup = lookup;
    }

    public void setAnchorLookup(Function<BlockPos, Boolean> lookup) {
        this.anchorLookup = lookup;
    }

    /**
     * Run FNO inference on a single island.
     *
     * <p>The hybrid model's phi head (channel 9) is trained against PFSF CPU Jacobi phi,
     * which is invariant to sigmaMax normalization (A×phi=b both sides cancel sigmaMax).
     * Therefore the model output phi is already in PFSF phi space and can be fed
     * directly into {@code failure_scan} without additional normalization.
     *
     * @return InferenceResult with 10-channel physics fields, or null on failure
     */
    public InferenceResult infer(StructureIsland island) {
        if (!available || session == null || materialLookup == null) return null;

        Set<BlockPos> members = island.getMembers();
        if (members.isEmpty()) return null;

        // Compute AABB
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : members) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int lx = maxX - minX + 1, ly = maxY - minY + 1, lz = maxZ - minZ + 1;
        if (Math.max(lx, Math.max(ly, lz)) > gridSize) return null;

        int L = gridSize;
        BlockPos origin = new BlockPos(minX, minY, minZ);

        try {
            // ── Build input tensor [1, L, L, L, 5] in row-major order ──
            // Index: batch*L*L*L*5 + x*L*L*5 + y*L*5 + z*5 + channel
            float[] input = new float[1 * L * L * L * 6];

            for (BlockPos pos : members) {
                int ix = pos.getX() - minX;
                int iy = pos.getY() - minY;
                int iz = pos.getZ() - minZ;

                RMaterial mat = materialLookup.apply(pos);
                if (mat == null) continue;

                int base = ((ix * L + iy) * L + iz) * 6;

                // 6ch — must match Python training normalization
                input[base]     = 1.0f;                                          // occupancy
                input[base + 1] = (float)(mat.getYoungsModulusPa() / E_SCALE);  // E
                input[base + 2] = (float) mat.getPoissonsRatio();                // nu
                input[base + 3] = (float)(mat.getDensity() / RHO_SCALE);         // density
                input[base + 4] = (float)(mat.getRcomp() / RC_SCALE);            // Rcomp
                input[base + 5] = (float)(mat.getRtens() / RT_SCALE);            // Rtens
            }

            long[] shape = {1, L, L, L, 6};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);

            // ── Run inference ──
            String inputName = session.getInputNames().iterator().next();
            try (OrtSession.Result result = session.run(
                    Collections.singletonMap(inputName, inputTensor))) {

                inputTensor.close();

                // Output shape: [1, L, L, L, 10]
                // ONNX Runtime Java returns float[][][][][] for 5D tensors
                Object rawValue = result.get(0).getValue();
                float[] flat;

                if (rawValue instanceof float[][][][][] arr5d) {
                    // Standard 5D nested array: [1][L][L][L][10]
                    flat = flatten5D(arr5d, L, 10);
                } else if (rawValue instanceof float[] arr1d) {
                    // Some backends return flat array
                    flat = arr1d;
                } else {
                    LOGGER.error("[ONNX] Unexpected output type: {}", rawValue.getClass().getName());
                    return null;
                }

                // Hybrid model: phi (ch9) trained against PFSF Jacobi phi.
                // PFSF phi is invariant to sigmaMax normalization, so vmScale=1.0f is correct.
                // No additional scaling needed — phi is already in PFSF phi space.
                return new InferenceResult(origin, lx, ly, lz, L, flat, vmScale);
            }

        } catch (OrtException e) {
            LOGGER.error("[ONNX] Inference failed for island {}: {}", island.getId(), e.getMessage());
            return null;
        }
    }

    /** Flatten [1][L][L][L][C] nested array to [L*L*L*C] row-major. */
    private static float[] flatten5D(float[][][][][] arr, int L, int C) {
        float[] flat = new float[L * L * L * C];
        float[][][][] batch0 = arr[0];
        for (int x = 0; x < L; x++)
            for (int y = 0; y < L; y++)
                for (int z = 0; z < L; z++)
                    System.arraycopy(batch0[x][y][z], 0, flat, ((x * L + y) * L + z) * C, C);
        return flat;
    }

    public void shutdown() {
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) {}
            session = null;
        }
        available = false;
    }

    /**
     * Inference result — 10-channel physics fields stored as flat row-major array.
     *
     * Layout: flat[((x * L + y) * L + z) * 10 + channel]
     *   channel 0-5: stress tensor (σ_xx, σ_yy, σ_zz, τ_xy, τ_yz, τ_xz)
     *   channel 6-8: displacement (u_x, u_y, u_z)
     *   channel 9:   PFSF-compatible phi
     */
    public static class InferenceResult {
        private final BlockPos origin;
        private final int lx, ly, lz, L;
        private final float[] data;  // flat [L*L*L*10]
        private final float vmScale;

        InferenceResult(BlockPos origin, int lx, int ly, int lz, int L,
                        float[] flatData, float vmScale) {
            this.origin = origin;
            this.lx = lx; this.ly = ly; this.lz = lz;
            this.L = L;
            this.data = flatData;
            this.vmScale = vmScale;
        }

        private int idx(int ix, int iy, int iz, int ch) {
            return ((ix * L + iy) * L + iz) * 10 + ch;
        }

        private boolean inBounds(int ix, int iy, int iz) {
            return ix >= 0 && ix < L && iy >= 0 && iy < L && iz >= 0 && iz < L;
        }

        /** Get PFSF-compatible phi value at world position. */
        public float getPhi(BlockPos pos) {
            int ix = pos.getX() - origin.getX();
            int iy = pos.getY() - origin.getY();
            int iz = pos.getZ() - origin.getZ();
            if (!inBounds(ix, iy, iz)) return 0;
            return data[idx(ix, iy, iz, 9)] * vmScale;
        }

        /** Get von Mises stress at world position (from predicted σ tensor). */
        public float getVonMises(BlockPos pos) {
            int ix = pos.getX() - origin.getX();
            int iy = pos.getY() - origin.getY();
            int iz = pos.getZ() - origin.getZ();
            if (!inBounds(ix, iy, iz)) return 0;

            float sxx = data[idx(ix, iy, iz, 0)] * vmScale;
            float syy = data[idx(ix, iy, iz, 1)] * vmScale;
            float szz = data[idx(ix, iy, iz, 2)] * vmScale;
            float txy = data[idx(ix, iy, iz, 3)] * vmScale;
            float tyz = data[idx(ix, iy, iz, 4)] * vmScale;
            float txz = data[idx(ix, iy, iz, 5)] * vmScale;

            return (float) Math.sqrt(
                sxx*sxx + syy*syy + szz*szz
                - sxx*syy - syy*szz - sxx*szz
                + 3 * (txy*txy + tyz*tyz + txz*txz)
            );
        }

        /** Get stress utilization ratio. */
        public float getStressRatio(BlockPos pos, float rcompMPa) {
            if (rcompMPa <= 0) return 0;
            return getVonMises(pos) / (rcompMPa * 1e6f);
        }

        /** Get displacement at world position (3 components). */
        public float[] getDisplacement(BlockPos pos) {
            int ix = pos.getX() - origin.getX();
            int iy = pos.getY() - origin.getY();
            int iz = pos.getZ() - origin.getZ();
            if (!inBounds(ix, iy, iz)) return new float[]{0, 0, 0};
            return new float[]{
                data[idx(ix, iy, iz, 6)],
                data[idx(ix, iy, iz, 7)],
                data[idx(ix, iy, iz, 8)],
            };
        }

        public BlockPos getOrigin() { return origin; }
        public int getGridSize() { return L; }
    }
}
