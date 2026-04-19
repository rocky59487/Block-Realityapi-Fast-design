package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 迭代排程器 — Chebyshev 半迭代加速 + 保守重啟 + 發散熔斷。
 *
 * <p>Chebyshev 半迭代（Wang 2015, SIGGRAPH Asia）透過動態調整每步步長 ω，
 * 使 Jacobi 迭代收斂速度提升約一個數量級。</p>
 *
 * <p>保守重啟機制在崩塌發生時重置 ω 回 1.0（純 Jacobi），
 * 避免拓撲突變導致 Chebyshev 過度外插。</p>
 *
 * 參考：PFSF 手冊 §4.3
 */
public final class PFSFScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Scheduler");

    /** 預算 omega 表（最多 64 步） */
    private static final int OMEGA_TABLE_SIZE = 64;

    private PFSFScheduler() {}

    // ═══════════════════════════════════════════════════════════════
    //  Chebyshev Omega 排程
    // ═══════════════════════════════════════════════════════════════

    /**
     * 計算 Chebyshev 半迭代的 omega 值。
     *
     * <pre>
     * iter=0: ω = 1.0
     * iter=1: ω = 2 / (2 - ρ²)
     * iter≥2: ω = 4 / (4 - ρ² × ω_{k-1})
     * </pre>
     *
     * @param iter     當前迭代索引（從 0 開始，已扣除 warmup）
     * @param rhoSpec  頻譜半徑估計
     * @return omega 值（≥ 1.0）
     */
    public static float computeOmega(int iter, float rhoSpec) {
        if (iter <= 0) return 1.0f;

        float rhoSq = rhoSpec * rhoSpec;
        // 安全檢查：rhoSq >= 1 時 Chebyshev 無法收斂，退回純 Jacobi
        if (rhoSq >= 1.0f) {
            LOGGER.warn("[PFSF] Invalid rhoSpec={} (rhoSq >= 1), falling back to omega=1.0", rhoSpec);
            return 1.0f;
        }

        if (iter == 1) {
            return 2.0f / (2.0f - rhoSq);
        }

        // 遞推計算（避免存全表）
        float omegaPrev = 1.0f;
        float omega = 2.0f / (2.0f - rhoSq); // iter=1
        for (int k = 2; k <= iter; k++) {
            float denom = 4.0f - rhoSq * omega;
            if (denom < OMEGA_DENOM_EPSILON) {
                // A6-fix: 分母趨近零 → 回傳上一步的穩定值（非當前 stale 值）
                omega = omegaPrev;
                break;
            }
            omegaPrev = omega;
            float omegaNew = 4.0f / denom;
            // 超過 2.0 表示遞迴已發散，不應繼續
            if (omegaNew > 2.0f || Float.isNaN(omegaNew)) {
                LOGGER.debug("[PFSF] Chebyshev omega diverged at k={}, using omegaPrev={}", k, omegaPrev);
                omega = omegaPrev;
                break;
            }
            omega = omegaNew;
        }
        return Math.min(omega, MAX_OMEGA);
    }

    /**
     * 預算 omega 表（靜態查找，避免每步遞推）。
     */
    public static float[] precomputeOmegaTable(float rhoSpec) {
        float[] table = new float[OMEGA_TABLE_SIZE];
        float rhoSq = rhoSpec * rhoSpec;

        table[0] = 1.0f;
        if (OMEGA_TABLE_SIZE > 1) {
            table[1] = 2.0f / (2.0f - rhoSq);
        }
        for (int k = 2; k < OMEGA_TABLE_SIZE; k++) {
            float denom = 4.0f - rhoSq * table[k - 1];
            if (denom < OMEGA_DENOM_EPSILON) { table[k] = table[k - 1]; continue; }
            table[k] = Math.min(4.0f / denom, 1.98f);
            if (Float.isNaN(table[k])) table[k] = 1.0f;
        }
        return table;
    }

    // ═══════════════════════════════════════════════════════════════
    //  頻譜半徑估計
    // ═══════════════════════════════════════════════════════════════

    /**
     * 估計 3D 正規網格上 Laplacian 的頻譜半徑。
     *
     * <pre>
     * ρ_spec = cos(π / Lmax) × SAFETY_MARGIN
     * </pre>
     *
     * D2 注：此為正規均勻網格的近似值。不規則 island 形狀或材料
     * 變化可能導致實際頻譜半徑偏離。SAFETY_MARGIN (0.95) 和
     * 保守重啟機制（§4.3.1）共同確保數值穩定性。
     *
     * @param Lmax 網格最大維度
     * @return 頻譜半徑估計 ∈ (0, 1)
     */
    public static float estimateSpectralRadius(int Lmax) {
        if (Lmax <= 1) return 0.5f;
        return (float) (Math.cos(Math.PI / Lmax) * SAFETY_MARGIN);
    }

    // ═══════════════════════════════════════════════════════════════
    //  迭代步數推薦
    // ═══════════════════════════════════════════════════════════════

    /**
     * 決定每 tick 此 island 應跑多少步迭代。
     *
     * @param buf     island buffer
     * @param isDirty 是否有結構變更
     * @param hasCollapse 是否正在連鎖崩塌
     * @return 推薦步數（0 = 已收斂，無需更新）
     */
    public static int recommendSteps(PFSFIslandBuffer buf, boolean isDirty, boolean hasCollapse) {
        if (!isDirty && buf.chebyshevIter > OMEGA_TABLE_SIZE) {
            return 0;
        }

        if (hasCollapse) {
            // Sub-stepping：根據 island 高度動態調整步數
            // 確保應力資訊能在 1-2 tick 內傳遞到建築最頂端
            int height = buf.getLy();
            int dynamicSteps = Math.max(STEPS_COLLAPSE, (int) (height * 1.5));
            return Math.min(dynamicSteps, 128);  // 硬上限防止超長計算
        }
        if (isDirty) return STEPS_MAJOR;
        return STEPS_MINOR;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tick Omega（含 Warmup 保護）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 取得當前 tick 的 omega 值，並遞增計數器。
     * 前 WARMUP_STEPS 步使用 omega=1（純 Jacobi），之後進入 Chebyshev 加速。
     */
    public static float getTickOmega(PFSFIslandBuffer buf) {
        float omega;
        if (buf.chebyshevIter < WARMUP_STEPS) {
            omega = 1.0f;
        } else {
            int chebyIter = buf.chebyshevIter - WARMUP_STEPS;
            omega = computeOmega(
                    Math.min(chebyIter, OMEGA_TABLE_SIZE - 1),
                    buf.rhoSpecOverride);
        }
        buf.chebyshevIter++;
        return omega;
    }

    // ═══════════════════════════════════════════════════════════════
    //  保守重啟（§4.3.1）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 崩塌觸發時重啟 Chebyshev 計數器。
     * 前 WARMUP_STEPS 步退回純 Jacobi，再逐漸爬回加速模式。
     */
    public static void onCollapseTriggered(PFSFIslandBuffer buf) {
        buf.chebyshevIter = 0;
        // 崩塌後拓撲不規則，頻譜半徑估計再壓低 8%
        buf.rhoSpecOverride = estimateSpectralRadius(buf.getLmax()) * 0.92f;
        LOGGER.debug("[PFSF] Conservative restart on island {}, rhoSpec={}",
                buf.getIslandId(), buf.rhoSpecOverride);
    }

    // ═══════════════════════════════════════════════════════════════
    //  殘差發散熔斷（§4.3.2）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 檢查 phi 最大值是否在發散（成長超過 DIVERGENCE_RATIO）。
     * 若偵測到發散，重啟 Chebyshev。
     *
     * @param buf       island buffer
     * @param maxPhiNow 當前 phi 最大值
     * @return true 若偵測到發散並已重啟
     */
    /**
     * 檢查 phi 最大值是否在發散或振盪。
     * C5-fix: 追蹤連續 3 tick 的趨勢，檢測振盪模式。
     *
     * @return true 若偵測到發散/振盪並已重啟
     */
    public static boolean checkDivergence(PFSFIslandBuffer buf, float maxPhiNow) {
        float prev = buf.maxPhiPrev;
        float prevPrev = buf.maxPhiPrevPrev;

        // D4+M5-fix: NaN 偵測 — 重置 Chebyshev 並啟用 damping，但保留 prev 歷史
        if (Float.isNaN(maxPhiNow) || Float.isInfinite(maxPhiNow)) {
            buf.chebyshevIter = 0;
            buf.dampingActive = true;
            LOGGER.error("[PFSF] NaN/Inf detected on island {}! Emergency reset + damping enabled.",
                    buf.getIslandId());
            // M5-fix: 不重置 prev 為 0（會干擾後續 divergence 判定），
            // 改為標記為「上次是 NaN」讓下一次比對跳過
            buf.maxPhiPrevPrev = buf.maxPhiPrev;
            buf.maxPhiPrev = -1.0f;  // 特殊標記：-1 表示上一次是 NaN
            return true;
        }

        // M5-fix: 跳過 NaN 後的第一次比對
        if (prev < 0) {
            buf.maxPhiPrevPrev = 0;
            buf.maxPhiPrev = maxPhiNow;
            return false;
        }

        // Check 1: 急遽成長（原有邏輯）
        if (prev > 0 && maxPhiNow > prev * DIVERGENCE_RATIO) {
            buf.chebyshevIter = 0;
            LOGGER.warn("[PFSF] Divergence on island {} (phi: {} → {}), resetting Chebyshev",
                    buf.getIslandId(), prev, maxPhiNow);
            buf.maxPhiPrevPrev = prev;
            buf.maxPhiPrev = maxPhiNow;
            return true;
        }

        // C5-fix: Check 2: 振盪偵測（增→減→增 or 減→增→減）
        if (prevPrev > 0 && prev > 0 && maxPhiNow > 0) {
            boolean wasGrowing = prev > prevPrev;
            boolean isGrowing = maxPhiNow > prev;
            boolean oscillating = wasGrowing != isGrowing;  // 方向改變

            if (oscillating) {
                buf.oscillationCount++;
            } else {
                buf.oscillationCount = 0;
            }

            float amplitude = Math.abs(maxPhiNow - prev) / prev;
            // Check 2a: 短期振盪（原始邏輯）— 幅度 > 10%
            if (oscillating && amplitude > 0.10f) {
                buf.chebyshevIter = 0;
                buf.dampingActive = true;
                LOGGER.warn("[PFSF] Oscillation on island {} (amplitude {}), enabling damping",
                        buf.getIslandId(), amplitude);
                buf.maxPhiPrevPrev = prev;
                buf.maxPhiPrev = maxPhiNow;
                return true;
            }
            // Check 2b: 持續低幅振盪（新增）— 連續 5+ tick 方向交替
            if (buf.oscillationCount >= 5 && amplitude > 0.02f) {
                buf.chebyshevIter = 0;
                buf.dampingActive = true;
                LOGGER.warn("[PFSF] Persistent oscillation on island {} ({} ticks, amplitude {})",
                        buf.getIslandId(), buf.oscillationCount, amplitude);
                buf.oscillationCount = 0;
                buf.maxPhiPrevPrev = prev;
                buf.maxPhiPrev = maxPhiNow;
                return true;
            }
        }

        // Check 3（新增）: Macro-block 區域發散偵測
        // 全域 maxPhi 穩定但某區域殘差急遽成長 → 局部發散
        if (buf.cachedMacroResiduals != null && buf.prevMaxMacroResidual > 0) {
            float maxResidual = 0;
            for (float r : buf.cachedMacroResiduals) {
                if (r > maxResidual) maxResidual = r;
            }
            if (maxResidual > buf.prevMaxMacroResidual * 2.0f) {
                buf.chebyshevIter = 0;
                LOGGER.warn("[PFSF] Localized divergence on island {} (macro residual: {} → {})",
                        buf.getIslandId(), buf.prevMaxMacroResidual, maxResidual);
                buf.prevMaxMacroResidual = maxResidual;
                buf.maxPhiPrevPrev = prev;
                buf.maxPhiPrev = maxPhiNow;
                return true;
            }
            buf.prevMaxMacroResidual = maxResidual;
        } else if (buf.cachedMacroResiduals != null) {
            float maxResidual = 0;
            for (float r : buf.cachedMacroResiduals) {
                if (r > maxResidual) maxResidual = r;
            }
            buf.prevMaxMacroResidual = maxResidual;
        }

        // M1-fix: 穩定後關閉 damping（連續 3 tick 變化 < 1%）
        if (buf.dampingActive && prev > 0) {
            float change = Math.abs(maxPhiNow - prev) / prev;
            if (change < DAMPING_SETTLE_THRESHOLD) {
                buf.dampingActive = false;
            }
        }

        buf.maxPhiPrevPrev = prev;
        buf.maxPhiPrev = maxPhiNow;
        return false;
    }

    // ═════════════════════════════════════════��═════════════════════
    //  v2: Macro-block 自適應迭代（靜止結構省 90% ALU）
    // ═══════════════════════════════════════════════════════════════

    /** 巨集塊尺寸：8×8×8 體素 */
    public static final int MACRO_BLOCK_SIZE = 8;

    /** 殘差收斂閾值：低於此值的巨集塊視為已收斂，跳過計算 */
    public static final float MACRO_BLOCK_CONVERGENCE_THRESHOLD = 1e-4f;

    // 遲滯閾值：避免 macro-block 在臨界值附近每 tick 反覆啟用/停用（chatter）
    // 啟用閾值較高（需更大殘差才重新啟動），停用閾值較低（需更小殘差才停止）
    public static final float MACRO_BLOCK_ACTIVATE_THRESHOLD   = 1.5e-4f;
    public static final float MACRO_BLOCK_DEACTIVATE_THRESHOLD = 0.8e-4f;

    /**
     * 判斷指定巨集塊是否活躍（殘差 > 閾值）。
     *
     * <p>由 failure_scan.comp.glsl 在每次掃描時計算 per-macroblock 最大殘差，
     * 寫入 macroBlockResidual[] buffer。此方法在 CPU 端讀取判定。</p>
     *
     * @param residuals  per-macroblock 殘差陣列（由 GPU readback）
     * @param blockIndex 巨集塊索引
     * @return true 若需要繼續迭代
     */
    /**
     * 判斷指定巨集塊是否活躍（含遲滯機制）。
     *
     * <p>遲滯避免 chatter：殘差在閾值附近時，
     * 使用不同的啟用/停用閾值防止每 tick 反覆切換。</p>
     *
     * @param residuals  per-macroblock 殘差陣列
     * @param blockIndex 巨集塊索引
     * @param wasActive  前一 tick 此巨集塊是否活躍
     * @return true 若需要繼續迭代
     */
    public static boolean isMacroBlockActive(float[] residuals, int blockIndex, boolean wasActive) {
        if (residuals == null || blockIndex < 0 || blockIndex >= residuals.length) {
            return true; // 保守策略：資料不可用時視為活躍
        }
        float r = residuals[blockIndex];
        // 遲滯：已活躍時需降到較低閾值才停用，已停用時需升到較高閾值才啟用
        if (wasActive) {
            return r > MACRO_BLOCK_DEACTIVATE_THRESHOLD;
        } else {
            return r > MACRO_BLOCK_ACTIVATE_THRESHOLD;
        }
    }

    /** 向下相容：無遲滯版本（保守策略） */
    public static boolean isMacroBlockActive(float[] residuals, int blockIndex) {
        return isMacroBlockActive(residuals, blockIndex, true);
    }

    /**
     * 計算 island 中活躍巨集塊的比例。
     *
     * @param residuals per-macroblock 殘差��列
     * @return 活躍比例 ∈ [0, 1]
     */
    /**
     * 計算 island 中活躍巨集塊的比例（含遲滯）。
     *
     * @param residuals    per-macroblock 殘差陣列
     * @param prevActive   前一 tick 各巨集塊是否活躍（null 則全部視為活躍）
     * @return 活躍比例 ∈ [0, 1]
     */
    public static float getActiveRatio(float[] residuals, boolean[] prevActive) {
        if (residuals == null || residuals.length == 0) return 1.0f;
        int active = 0;
        for (int i = 0; i < residuals.length; i++) {
            boolean wasActive = (prevActive == null || i >= prevActive.length) || prevActive[i];
            if (isMacroBlockActive(residuals, i, wasActive)) active++;
        }
        return (float) active / residuals.length;
    }

    /** 向下相容：無遲滯版本 */
    public static float getActiveRatio(float[] residuals) {
        return getActiveRatio(residuals, null);
    }
}
