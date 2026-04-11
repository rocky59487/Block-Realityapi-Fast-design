package com.blockreality.api.physics.fluid;

import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * FNOFluid3D ONNX 推理器 — 流體專屬，對應 BIFROSTModelRegistry 的 "bifrost_fluid" 模型。
 *
 * <h3>Tensor 規格</h3>
 * <pre>
 * 輸入  [1, N, N, N, 8]  channels: vx, vy, vz, pressure, density, vof, typeOcc, phi
 * 輸出  [1, N, N, N, 4]  channels: vx_next, vy_next, vz_next, pressure_next
 * </pre>
 * 其中 N = subGridN（從模型 meta 動態讀取），對應 blockL = N/SUB 個 block。
 *
 * <h3>歸一化（與 Python 訓練管線對齊）</h3>
 * <pre>
 * vx/vy/vz      / V_SCALE   (10 m/s)
 * pressure      / P_SCALE   (101325 Pa)
 * density       / RHO_SCALE (1000 kg/m³)
 * vof           not scaled  ([0,1])
 * typeOcc       not scaled  ({0,1})
 * phi           / PHI_SCALE (101325 Pa)
 * </pre>
 *
 * <p>使用方式：
 * <pre>
 * OnnxFluidRuntime rt = new OnnxFluidRuntime();
 * if (rt.loadModel("config/blockreality/models/bifrost_fluid.onnx")) {
 *     FluidInferenceResult r = rt.infer(region);
 *     float p = r.getPressure(bx, by, bz);
 * }
 * rt.shutdown();
 * </pre>
 *
 * @see BIFROSTModelRegistry
 */
public class OnnxFluidRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("BIFROST-Fluid");

    // ─── 歸一化常數（必須與 brml/train/train_fnofluid.py 一致） ───
    public static final float V_SCALE   = 10.0f;       // m/s
    public static final float P_SCALE   = 101325.0f;   // Pa
    public static final float RHO_SCALE = 1000.0f;     // kg/m³ (water)
    public static final float PHI_SCALE = 101325.0f;   // Pa (potential ≈ pressure)

    // 輸入通道數（必須與 ModelSpec(8, 4) 一致）
    private static final int IN_CHANNELS  = 8;
    private static final int OUT_CHANNELS = 4;

    private OrtEnvironment env;
    private OrtSession session;
    private boolean available = false;

    private int subGridN = -1;  // spatial size read from model (e.g. 40 = 4 blocks × SUB)
    private int blockL   = -1;  // = subGridN / SUB

    // ─── 生命週期 ───

    /**
     * 載入 ONNX 模型檔案，讀取輸入形狀並驗證通道數。
     *
     * @param modelPath .onnx 檔案路徑
     * @return 是否成功
     */
    public boolean loadModel(String modelPath) {
        try {
            if (!Files.exists(Path.of(modelPath))) {
                LOGGER.warn("[BIFROST-Fluid] Model not found: {}", modelPath);
                return false;
            }
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            session = env.createSession(modelPath, opts);

            // 讀取輸入形狀 [1, N, N, N, 8]
            NodeInfo inputInfo = session.getInputInfo().entrySet().iterator().next().getValue();
            long[] shape = ((TensorInfo) inputInfo.getInfo()).getShape();
            if (shape.length != 5 || shape[4] != IN_CHANNELS) {
                LOGGER.error("[BIFROST-Fluid] Unexpected input shape: expected [1,N,N,N,{}], got {}",
                    IN_CHANNELS, java.util.Arrays.toString(shape));
                session.close(); env.close();
                return false;
            }
            subGridN = (int) shape[1]; // N
            if (subGridN % FluidRegion.SUB != 0) {
                LOGGER.error("[BIFROST-Fluid] subGridN={} not divisible by SUB={}", subGridN, FluidRegion.SUB);
                session.close(); env.close();
                return false;
            }
            blockL = subGridN / FluidRegion.SUB;
            available = true;
            LOGGER.info("[BIFROST-Fluid] Loaded: subGridN={}, blockL={}", subGridN, blockL);
            return true;
        } catch (OrtException e) {
            LOGGER.error("[BIFROST-Fluid] Load failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isReady() { return available; }
    public int getSubGridN() { return subGridN; }
    public int getBlockL()   { return blockL; }

    /**
     * 執行推理。
     *
     * <p>流程：
     * <ol>
     *   <li>從 {@link FluidRegion} block-level 陣列廣播至 subGridN³ sub-cells（nearest-neighbor）</li>
     *   <li>送入 ONNX session</li>
     *   <li>輸出 sub-cell 壓力/速度算術均值 → {@link FluidInferenceResult}</li>
     * </ol>
     *
     * @param region 流體區域（必須 sizeX == sizeY == sizeZ == blockL）
     * @return 推理結果；若失敗回傳 null
     */
    @Nullable
    public FluidInferenceResult infer(FluidRegion region) {
        if (!available || session == null) return null;
        if (region.getSizeX() != blockL || region.getSizeY() != blockL || region.getSizeZ() != blockL) {
            LOGGER.warn("[BIFROST-Fluid] Region size {}×{}×{} != expected {}",
                region.getSizeX(), region.getSizeY(), region.getSizeZ(), blockL);
            return null;
        }
        try {
            float[] inputData = packSubCells(region);
            long[] shape = {1, subGridN, subGridN, subGridN, IN_CHANNELS};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);

            try (OrtSession.Result result = session.run(Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor))) {
                float[] outputData = (float[]) ((OnnxTensor) result.get(0)).getValue();
                return unpackResult(outputData, region);
            } finally {
                inputTensor.close();
            }
        } catch (OrtException e) {
            LOGGER.error("[BIFROST-Fluid] Inference failed: {}", e.getMessage());
            return null;
        }
    }

    public void shutdown() {
        available = false;
        try {
            if (session != null) { session.close(); session = null; }
            if (env != null) { env.close(); env = null; }
        } catch (OrtException e) {
            LOGGER.warn("[BIFROST-Fluid] Shutdown error: {}", e.getMessage());
        }
    }

    // ─── Tensor Packing ───

    /**
     * Block-level 廣播至 sub-cell input tensor。
     * 每個 block (bx,by,bz) 的值廣播到 10³ sub-cells。
     * channels: [vx, vy, vz, pressure, density, vof, typeOcc, phi]
     */
    private float[] packSubCells(FluidRegion r) {
        int N = subGridN;
        float[] buf = new float[N * N * N * IN_CHANNELS];
        float[] phi      = r.getPhi();
        float[] pressure = r.getPressure();
        float[] density  = r.getDensity();
        float[] volume   = r.getVolume();
        byte[]  type     = r.getType();
        float[] vx       = r.getVx();
        float[] vy       = r.getVy();
        float[] vz       = r.getVz();

        int SUB = FluidRegion.SUB;
        for (int bz = 0; bz < blockL; bz++) {
            for (int by = 0; by < blockL; by++) {
                for (int bx = 0; bx < blockL; bx++) {
                    int bIdx = r.flatIndex(bx, by, bz);
                    float pPhi  = phi[bIdx]      / PHI_SCALE;
                    float pPres = pressure[bIdx] / P_SCALE;
                    float pRho  = density[bIdx]  / RHO_SCALE;
                    float pVol  = volume[bIdx];
                    float pType = ((type[bIdx] & 0xFF) != FluidType.SOLID_WALL.getId()) ? 1f : 0f;

                    for (int sz = 0; sz < SUB; sz++) {
                        for (int sy = 0; sy < SUB; sy++) {
                            for (int sx = 0; sx < SUB; sx++) {
                                int gx = bx * SUB + sx;
                                int gy = by * SUB + sy;
                                int gz = bz * SUB + sz;
                                int sIdx = r.subFlat(bx, by, bz, sx, sy, sz);
                                int outIdx = (gx + gy * N + gz * N * N) * IN_CHANNELS;
                                // sub-cell velocities (already m/s from NS solver; default 0 for first tick)
                                buf[outIdx    ] = (sIdx < vx.length ? vx[sIdx] : 0f) / V_SCALE;
                                buf[outIdx + 1] = (sIdx < vy.length ? vy[sIdx] : 0f) / V_SCALE;
                                buf[outIdx + 2] = (sIdx < vz.length ? vz[sIdx] : 0f) / V_SCALE;
                                buf[outIdx + 3] = pPres;
                                buf[outIdx + 4] = pRho;
                                buf[outIdx + 5] = pVol;
                                buf[outIdx + 6] = pType;
                                buf[outIdx + 7] = pPhi;
                            }
                        }
                    }
                }
            }
        }
        return buf;
    }

    /**
     * 解析輸出 tensor，對 10³ sub-cells 算術均值 → block-level result。
     * channels: [vx_next, vy_next, vz_next, pressure_next]
     */
    private FluidInferenceResult unpackResult(float[] output, FluidRegion r) {
        int N = subGridN;
        int SUB = FluidRegion.SUB;
        float[] pArr  = new float[blockL * blockL * blockL];
        float[] vxArr = new float[blockL * blockL * blockL];
        float[] vyArr = new float[blockL * blockL * blockL];
        float[] vzArr = new float[blockL * blockL * blockL];

        float scale = 1f / (SUB * SUB * SUB);
        for (int bz = 0; bz < blockL; bz++) {
            for (int by = 0; by < blockL; by++) {
                for (int bx = 0; bx < blockL; bx++) {
                    float sumVx = 0, sumVy = 0, sumVz = 0, sumP = 0;
                    for (int sz = 0; sz < SUB; sz++) {
                        for (int sy = 0; sy < SUB; sy++) {
                            for (int sx = 0; sx < SUB; sx++) {
                                int gx = bx * SUB + sx;
                                int gy = by * SUB + sy;
                                int gz = bz * SUB + sz;
                                int base = (gx + gy * N + gz * N * N) * OUT_CHANNELS;
                                sumVx += output[base    ] * V_SCALE;
                                sumVy += output[base + 1] * V_SCALE;
                                sumVz += output[base + 2] * V_SCALE;
                                sumP  += output[base + 3] * P_SCALE;
                            }
                        }
                    }
                    int bIdx = bx + by * blockL + bz * blockL * blockL;
                    vxArr[bIdx] = sumVx * scale;
                    vyArr[bIdx] = sumVy * scale;
                    vzArr[bIdx] = sumVz * scale;
                    pArr[bIdx]  = sumP  * scale;
                }
            }
        }
        return new FluidInferenceResult(pArr, vxArr, vyArr, vzArr, blockL);
    }

    // ─── 結果類 ───

    /** 推理結果：block-level 平均壓力與速度（10³ sub-cells 算術均值） */
    public static final class FluidInferenceResult {
        private final float[] pressure;
        private final float[] vx, vy, vz;
        private final int L;

        FluidInferenceResult(float[] pressure, float[] vx, float[] vy, float[] vz, int L) {
            this.pressure = pressure;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.L = L;
        }

        private int flat(int bx, int by, int bz) { return bx + by * L + bz * L * L; }

        /** block-averaged 壓力 (Pa) */
        public float getPressure(int bx, int by, int bz) { return pressure[flat(bx, by, bz)]; }
        /** block-averaged 速度 X (m/s) */
        public float getVx(int bx, int by, int bz)       { return vx[flat(bx, by, bz)]; }
        /** block-averaged 速度 Y (m/s) */
        public float getVy(int bx, int by, int bz)       { return vy[flat(bx, by, bz)]; }
        /** block-averaged 速度 Z (m/s) */
        public float getVz(int bx, int by, int bz)       { return vz[flat(bx, by, bz)]; }

        /** flat 索引版本（供 FluidGPUEngine tickRegionML 批次寫入） */
        public float getPressureFlat(int flatIdx)         { return pressure[flatIdx]; }
        public int getL()                                  { return L; }
    }
}
