package com.blockreality.api.physics.pfsf;

/**
 * PFSF-ML 特徵提取器 — 從 island 求解器狀態提取特徵向量，
 * 供自適應求解器參數調優（RBGS/PCG 步數分配、多網格策略）使用。
 *
 * <p>設計原則：</p>
 * <ul>
 *   <li>零 GPU 讀回 — 所有特徵從 CPU 端已有的快取/狀態取得</li>
 *   <li>無狀態 — 不持有引用，每次呼叫獨立提取</li>
 *   <li>固定維度 — 特徵向量維度固定為 {@link #FEATURE_DIM}，便於 ML 模型固定輸入</li>
 * </ul>
 *
 * <p>特徵清單（v1, 12 維）：</p>
 * <pre>
 *   [0]  log2(N)                        — 網格規模（對數）
 *   [1]  aspectRatio                    — 最長/最短邊比（各向異性指標）
 *   [2]  chebyshevIter / 64             — 正規化迭代進度
 *   [3]  rhoSpec                        — 當前頻譜半徑估算
 *   [4]  log10(prevMaxMacroResidual+ε)  — 殘差量級
 *   [5]  residualDropRate               — 殘差下降率（上一 tick）
 *   [6]  oscillationCount / 10          — 正規化震盪強度
 *   [7]  dampingActive ? 1 : 0          — 阻尼旗標
 *   [8]  stableTickCount / 100          — 正規化穩定度
 *   [9]  macroBlockVariance             — macro-block 殘差變異係數
 *   [10] lodLevel / LOD_DORMANT         — 正規化 LOD
 *   [11] hasPCG ? 1 : 0                — PCG 是否啟用
 * </pre>
 */
public final class IslandFeatureExtractor {

    private IslandFeatureExtractor() {}

    /** 特徵向量維度（v1）。 */
    public static final int FEATURE_DIM = 12;

    private static final float EPS = 1e-20f;

    /**
     * 從 island buffer 提取特徵向量。
     *
     * @param buf 已分配的 island buffer（不得為 null）
     * @return float[FEATURE_DIM] 特徵向量，所有值已正規化至合理範圍
     */
    public static float[] extract(PFSFIslandBuffer buf) {
        float[] f = new float[FEATURE_DIM];

        int Lx = buf.getLx(), Ly = buf.getLy(), Lz = buf.getLz();
        int N = Lx * Ly * Lz;

        // [0] log2(N) — 網格規模（典型範圍 10~18 for 1K~256K voxels）
        f[0] = (float)(Math.log(Math.max(N, 1)) / Math.log(2));

        // [1] aspectRatio — 最長邊/最短邊（各向異性結構 >> 1）
        int minDim = Math.min(Lx, Math.min(Ly, Lz));
        int maxDim = Math.max(Lx, Math.max(Ly, Lz));
        f[1] = (minDim > 0) ? (float) maxDim / minDim : 1.0f;

        // [2] chebyshevIter 正規化（相對 64，與 PFSFScheduler.OMEGA_TABLE_SIZE 一致）
        f[2] = (float) buf.chebyshevIter / 64.0f;

        // [3] 頻譜半徑
        f[3] = buf.rhoSpecOverride;

        // [4] 殘差量級（log10）
        f[4] = (float) Math.log10(Math.max(buf.prevMaxMacroResidual, EPS));

        // [5] 殘差下降率
        float[] residuals = buf.cachedMacroResiduals;
        float currentMax = maxOfArray(residuals);
        float prevMax = buf.prevMaxMacroResidual;
        f[5] = (prevMax > EPS) ? currentMax / prevMax : 1.0f;

        // [6] 震盪強度
        f[6] = Math.min(buf.oscillationCount / 10.0f, 1.0f);

        // [7] 阻尼旗標
        f[7] = buf.dampingActive ? 1.0f : 0.0f;

        // [8] 穩定度
        f[8] = Math.min(buf.stableTickCount / 100.0f, 1.0f);

        // [9] macro-block 殘差變異係數（CV = σ/μ）
        f[9] = coefficientOfVariation(residuals);

        // [10] LOD 等級
        f[10] = (float) buf.lodLevel / Math.max(PFSFConstants.LOD_DORMANT, 1);

        // [11] PCG 啟用狀態
        f[11] = buf.isPCGAllocated() ? 1.0f : 0.0f;

        return f;
    }

    /**
     * 特徵名稱（用於日誌 / 可視化）。
     */
    public static String[] featureNames() {
        return new String[]{
                "log2_N", "aspect_ratio", "cheby_progress", "rho_spec",
                "log10_residual", "residual_drop_rate", "oscillation",
                "damping", "stability", "residual_cv", "lod", "pcg_active"
        };
    }

    // ─── 輔助方法 ───

    private static float maxOfArray(float[] arr) {
        if (arr == null || arr.length == 0) return 0;
        float max = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > max) max = arr[i];
        }
        return max;
    }

    private static float coefficientOfVariation(float[] arr) {
        if (arr == null || arr.length < 2) return 0;
        float sum = 0, sum2 = 0;
        int n = arr.length;
        for (float v : arr) { sum += v; sum2 += v * v; }
        float mean = sum / n;
        if (mean < EPS) return 0;
        float variance = sum2 / n - mean * mean;
        return (float) Math.sqrt(Math.max(variance, 0)) / mean;
    }
}
