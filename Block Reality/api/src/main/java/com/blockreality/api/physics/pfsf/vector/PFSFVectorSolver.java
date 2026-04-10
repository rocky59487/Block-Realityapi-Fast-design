package com.blockreality.api.physics.pfsf.vector;

/**
 * PFSF 混合向量場求解器 — 架構預備 stub。
 *
 * <p>v2 Phase D (探索性)：在高應力巨集塊（8³）中實例化局部 3D LSM 向量求解器，
 * 以精確計算剪力和扭轉行為。全域仍維持低成本純量場。</p>
 *
 * <p>當前為 stub 實作 — 永遠回傳「純量場已足夠」。
 * 完整實作計畫於 v2.1。</p>
 *
 * <p>架構設計：</p>
 * <ul>
 *   <li>掃描巨集塊應力比 > 0.7 → 啟動向量求解</li>
 *   <li>純量 φ 梯度作為向量場的 Neumann BC</li>
 *   <li>局部 8³×3 float = 6KB，完全放入 shared memory</li>
 *   <li>結果回饋修正純量場的 conductivity</li>
 * </ul>
 */
public final class PFSFVectorSolver {

    private PFSFVectorSolver() {}

    /**
     * 判斷指定巨集塊是否需要向量求解。
     *
     * @param stressRatio 巨集塊最大應力比（φ/maxPhi），0-2
     * @return true 若應啟動向量求解（目前永遠 false）
     */
    public static boolean isVectorSolveNeeded(float stressRatio) {
        // WSS-HQR: activate for macro-blocks with stress ratio > 0.7
        return stressRatio > 0.7f;
    }

    /**
     * CPU-side hook for local vector field inspection (optional diagnostics).
     * The actual solve runs on the GPU via {@link PFSFVectorRecorder#recordVectorSolve}.
     */
    public static void solveLocalVector(int macroBlockX, int macroBlockY, int macroBlockZ) {
        // GPU dispatch is the primary path (PFSFVectorRecorder.recordVectorSolve).
        // This CPU entry point is reserved for future diagnostic readback.
    }
}
