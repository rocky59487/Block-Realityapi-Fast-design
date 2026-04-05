package com.blockreality.api.physics;

/**
 * 結構引擎介面 — 「黑盒子」設計（v3fix AD 1.2.5）。
 *
 * 接受純快照，輸出結構分析結果。
 * 不持有任何 Minecraft Level 參照 — 可在任意線程執行。
 *
 * 實作：
 *   - BFSConnectivityAnalyzer (現有 BFS 引擎，速度優先)
 *   - SupportPathAnalyzer (帶權重應力 BFS)
 *   未來可換成 FEM 求解器而不改介面
 *
 * @since 1.0.0
 */
public interface IStructureEngine {
    /**
     * 對快照執行結構分析。
     * @param snapshot 唯讀世界快照（不含 Minecraft API）
     * @return 結構分析結果（unstable blocks, structure groups, stress map）
     */
    StressField compute(RWorldSnapshot snapshot);
}
