package com.blockreality.api.physics.pfsf;

import ai.onnxruntime.*;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
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
 * <p>Input (5 channels): [1, Lx, Ly, Lz, 5] — occ, E, ν, ρ, Rcomp (normalized)</p>
 * <p>Output (10 channels): [1, Lx, Ly, Lz, 10] — σ(6) + u(3) + φ(1)</p>
 *
 * <p>The φ channel (index 9) is fed directly into the existing PFSF failure
 * detection pipeline, providing FEM-quality physics at FNO inference speed.</p>
 *
 * <p>Only handles islands routed here by {@link HybridPhysicsRouter}.</p>
 *
 * @since v1.0 (BIFROST Sprint 1)
 * @see HybridPhysicsRouter
 * @see IPFSFRuntime
 */
public class OnnxPFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-ONNX");

    // ── Normalization constants (must match brml/pipeline/auto_train.py) ──
    private static final float E_SCALE   = 200e9f;    // steel E
    private static final float RHO_SCALE = 7850.0f;   // steel density
    private static final float RC_SCALE  = 250.0f;    // steel Rcomp

    private OrtEnvironment env;
    private OrtSession session;
    private int gridSize;        // L from model metadata
    private float vmScale = 1.0f; // denormalization scale
    private boolean available = false;

    // ── Material lookup (shared with PFSFEngineInstance) ──
    private Function<BlockPos, RMaterial> materialLookup;
    private Function<BlockPos, Boolean> anchorLookup;

    /**
     * Load an ONNX model.
     *
     * @param modelPath Path to .onnx file (or .npz directory)
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

            // Try GPU execution provider, fall back to CPU
            try {
                opts.addCUDA(0);
                LOGGER.info("[ONNX] CUDA execution provider enabled");
            } catch (OrtException e) {
                LOGGER.info("[ONNX] CUDA not available, using CPU");
            }

            session = env.createSession(modelPath, opts);

            // Extract grid size from input shape
            Map<String, NodeInfo> inputs = session.getInputInfo();
            if (!inputs.isEmpty()) {
                NodeInfo firstInput = inputs.values().iterator().next();
                if (firstInput.getInfo() instanceof TensorInfo ti) {
                    long[] shape = ti.getShape();
                    // Expected: [1, L, L, L, 5]
                    if (shape.length == 5) {
                        gridSize = (int) shape[1];
                    }
                }
            }
            if (gridSize <= 0) gridSize = 16; // fallback

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
     * @param island Structure island to analyze
     * @return InferenceResult with stress/displacement/phi fields, or null on failure
     */
    public InferenceResult infer(StructureIsland island) {
        if (!available || session == null) return null;
        if (materialLookup == null) return null;

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
        int maxDim = Math.max(lx, Math.max(ly, lz));

        // Pad to grid size
        int L = Math.max(gridSize, maxDim);
        if (L > gridSize) {
            LOGGER.debug("[ONNX] Island {} too large ({}>{}), skipping", island.getId(), maxDim, gridSize);
            return null;
        }

        try {
            // Build input tensor [1, L, L, L, 5]
            float[] input = new float[L * L * L * 5];
            BlockPos origin = new BlockPos(minX, minY, minZ);

            for (BlockPos pos : members) {
                int ix = pos.getX() - minX;
                int iy = pos.getY() - minY;
                int iz = pos.getZ() - minZ;
                int baseIdx = ((ix * L + iy) * L + iz) * 5;

                RMaterial mat = materialLookup.apply(pos);
                if (mat == null) continue;

                boolean isAnchor = anchorLookup != null && anchorLookup.apply(pos);

                input[baseIdx]     = isAnchor ? 2.0f / 2.0f : 1.0f / 2.0f; // occ (normalized)
                input[baseIdx + 1] = (float)(mat.getYoungsModulusPa() / E_SCALE);
                input[baseIdx + 2] = (float) mat.getPoissonsRatio();
                input[baseIdx + 3] = (float)(mat.getDensity() / RHO_SCALE);
                input[baseIdx + 4] = (float)(mat.getRcomp() / RC_SCALE);
            }

            // Create ONNX tensor
            long[] shape = {1, L, L, L, 5};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);

            // Run inference
            String inputName = session.getInputNames().iterator().next();
            OrtSession.Result result = session.run(
                    Collections.singletonMap(inputName, inputTensor));

            // Extract output [1, L, L, L, 10]
            float[][][][] output = (float[][][][]) result.get(0).getValue();
            // Note: ONNX Runtime returns [1, L, L, L, 10] but Java arrays are nested

            inputTensor.close();
            result.close();

            return new InferenceResult(origin, lx, ly, lz, L, output[0], vmScale);

        } catch (OrtException e) {
            LOGGER.error("[ONNX] Inference failed for island {}: {}", island.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Shutdown and release ONNX resources.
     */
    public void shutdown() {
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) {}
            session = null;
        }
        available = false;
    }

    /**
     * Inference result — 10-channel physics fields.
     */
    public static class InferenceResult {
        private final BlockPos origin;
        private final int lx, ly, lz, L;
        private final float[][][] data; // [L][L][L] → 10 channels flattened
        private final float vmScale;

        InferenceResult(BlockPos origin, int lx, int ly, int lz, int L,
                        float[][][] rawOutput, float vmScale) {
            this.origin = origin;
            this.lx = lx; this.ly = ly; this.lz = lz;
            this.L = L;
            this.data = rawOutput;
            this.vmScale = vmScale;
        }

        /** Get PFSF-compatible phi value at world position. */
        public float getPhi(BlockPos pos) {
            int ix = pos.getX() - origin.getX();
            int iy = pos.getY() - origin.getY();
            int iz = pos.getZ() - origin.getZ();
            if (ix < 0 || ix >= L || iy < 0 || iy >= L || iz < 0 || iz >= L) return 0;
            return data[ix][iy][iz * 10 + 9] * vmScale;
        }

        /** Get von Mises stress at world position (computed from σ tensor). */
        public float getVonMises(BlockPos pos) {
            int ix = pos.getX() - origin.getX();
            int iy = pos.getY() - origin.getY();
            int iz = pos.getZ() - origin.getZ();
            if (ix < 0 || ix >= L || iy < 0 || iy >= L || iz < 0 || iz >= L) return 0;

            int base = iz * 10;
            float sxx = data[ix][iy][base];
            float syy = data[ix][iy][base + 1];
            float szz = data[ix][iy][base + 2];
            float txy = data[ix][iy][base + 3];
            float tyz = data[ix][iy][base + 4];
            float txz = data[ix][iy][base + 5];

            return (float) Math.sqrt(
                sxx*sxx + syy*syy + szz*szz
                - sxx*syy - syy*szz - sxx*szz
                + 3 * (txy*txy + tyz*tyz + txz*txz)
            ) * vmScale;
        }

        /** Get stress utilization ratio (for failure detection). */
        public float getStressRatio(BlockPos pos, float rcomp) {
            if (rcomp <= 0) return 0;
            return getVonMises(pos) / (rcomp * 1e6f);
        }

        public BlockPos getOrigin() { return origin; }
    }
}
