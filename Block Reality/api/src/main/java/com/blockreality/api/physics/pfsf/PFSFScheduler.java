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

        if (iter == 1) {
            return 2.0f / (2.0f - rhoSq);
        }

        // 遞推計算（避免存全表）
        float omega = 1.0f;
        omega = 2.0f / (2.0f - rhoSq); // iter=1
        for (int k = 2; k <= iter; k++) {
            float denom = 4.0f - rhoSq * omega;
            if (denom < 0.01f) break;  // A6-fix: 防止分母趨近零
            omega = 4.0f / denom;
        }
        // A6-fix: 硬性上限 + NaN 防護
        omega = Math.min(omega, 1.98f);
        if (Float.isNaN(omega) || Float.isInfinite(omega)) omega = 1.0f;
        return omega;
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
            if (denom < 0.01f) { table[k] = table[k - 1]; continue; }
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
            // 已執行足夠多步且無新擾動 → warm-start 已收斂
            return 0;
        }

        if (hasCollapse) return STEPS_COLLAPSE;
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
        LOGGER.debug("[PFSF] Conservative restart on island {}, rhoSpec={:.4f}",
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
    public static boolean checkDivergence(PFSFIslandBuffer buf, float maxPhiNow) {
        if (buf.maxPhiPrev > 0 && maxPhiNow > buf.maxPhiPrev * DIVERGENCE_RATIO) {
            buf.chebyshevIter = 0;
            LOGGER.warn("[PFSF] Divergence detected on island {} (phi: {:.1f} → {:.1f}), resetting Chebyshev",
                    buf.getIslandId(), buf.maxPhiPrev, maxPhiNow);
            buf.maxPhiPrev = maxPhiNow;
            return true;
        }
        buf.maxPhiPrev = maxPhiNow;
        return false;
    }
}
