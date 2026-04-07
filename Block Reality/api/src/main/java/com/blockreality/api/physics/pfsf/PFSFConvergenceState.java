package com.blockreality.api.physics.pfsf;

/**
 * PFSF 收斂狀態 — 從 PFSFIslandBuffer 提取的迭代狀態。
 *
 * <p>追蹤 Chebyshev 半迭代進度、頻譜半徑、φ_max 歷史（發散/振盪偵測）、
 * 以及 GPU 端 damping 啟停。每個 island 一個實例。</p>
 *
 * <p>P1 重構：原本散佈在 PFSFIslandBuffer 的 package-private 欄位，
 * 現集中到此類以單一職責管理。</p>
 */
public final class PFSFConvergenceState {

    /** Chebyshev 迭代計數器（含 warmup） */
    int chebyshevIter = 0;

    /** 頻譜半徑覆蓋值（崩塌後 ×0.92） */
    float rhoSpecOverride;

    /** φ_max 歷史：t-1 */
    float maxPhiPrev = 0;

    /** φ_max 歷史：t-2（C5-fix 振盪偵測） */
    float maxPhiPrevPrev = 0;

    /** GPU 端 damping 啟用旗標（M1-fix） */
    boolean dampingActive = false;

    public PFSFConvergenceState(int Lmax) {
        this.rhoSpecOverride = PFSFScheduler.estimateSpectralRadius(Lmax);
    }

    /** 崩塌後重置（保守重啟） */
    public void onCollapseRestart(int Lmax) {
        chebyshevIter = 0;
        rhoSpecOverride = PFSFScheduler.estimateSpectralRadius(Lmax) * 0.92f;
    }

    /** 完全重置（buffer 重新分配時） */
    public void reset(int Lmax) {
        chebyshevIter = 0;
        rhoSpecOverride = PFSFScheduler.estimateSpectralRadius(Lmax);
        maxPhiPrev = 0;
        maxPhiPrevPrev = 0;
        dampingActive = false;
    }

    // ─── Getters（供外部唯讀存取） ───
    public int getChebyshevIter() { return chebyshevIter; }
    public float getRhoSpecOverride() { return rhoSpecOverride; }
    public float getMaxPhiPrev() { return maxPhiPrev; }
    public float getMaxPhiPrevPrev() { return maxPhiPrevPrev; }
    public boolean isDampingActive() { return dampingActive; }
}
