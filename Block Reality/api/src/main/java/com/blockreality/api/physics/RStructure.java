package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;

/**
 * R 結構 — 運行時結構概覽資料類。
 *
 * 想法.docx 規定的統一結構查詢介面，整合以下散布資訊：
 *   - BFSConnectivityAnalyzer 的連通分量 (nodeSet)
 *   - SupportPathAnalyzer 的應力結果 (stressMap, failures)
 *   - AnchorContinuityChecker 的錨定點 (anchorPoints)
 *
 * 用途：
 *   1. /br_stress 指令提供結構摘要
 *   2. Construction Intern 模組查詢結構狀態
 *   3. 客戶端視覺化（傳送結構概覽到客戶端）
 *   4. 藍圖系統標注結構健康度
 *
 * 不可變 record — 線程安全，可從異步物理線程傳回主線程。
 *
 * @param structureId     結構唯一識別碼（由 region hash 生成）
 * @param nodeSet         結構中所有 RBlock 的位置集合
 * @param anchorPoints    錨定點位置集合
 * @param stressMap       每個方塊的應力比 (0.0 ~ 1.0+)
 * @param compositeR      結構綜合抗性指標（所有節點的平均 combinedStrength）
 * @param totalLoad       結構總荷載 (kg)
 * @param maxStress       結構中最高應力比
 * @param failureCount    已判定失效的方塊數
 * @param computeTimeMs   計算耗時 (ms)
 */
public record RStructure(
    long structureId,
    Set<BlockPos> nodeSet,
    Set<BlockPos> anchorPoints,
    Map<BlockPos, Float> stressMap,
    double compositeR,
    double totalLoad,
    float maxStress,
    int failureCount,
    double computeTimeMs
) {

    /**
     * 結構中的方塊總數。
     */
    public int blockCount() {
        return nodeSet.size();
    }

    /**
     * 錨定點數量。
     */
    public int anchorCount() {
        return anchorPoints.size();
    }

    /**
     * 結構健康度評分 (0.0 = 即將崩塌, 1.0 = 完全安全)。
     *
     * 計算方式：
     *   - 基準分 = 1.0 - maxStress（最大應力越低越安全）
     *   - 失效懲罰 = failureCount / blockCount（有失效方塊時扣分）
     *   - 錨定加成 = min(anchorCount / blockCount × 10, 0.2)（更多錨定點 = 更穩定）
     */
    public float healthScore() {
        if (nodeSet.isEmpty()) return 0f;

        float baseScore = 1.0f - Math.min(maxStress, 1.0f);
        float failurePenalty = (float) failureCount / blockCount();
        float anchorBonus = Math.min((float) anchorCount() / blockCount() * 10f, 0.2f);

        return Math.max(0f, Math.min(1f, baseScore - failurePenalty + anchorBonus));
    }

    /**
     * 結構是否安全（無失效方塊且最大應力 < 0.8）。
     */
    public boolean isStable() {
        return failureCount == 0 && maxStress < 0.8f;
    }

    /**
     * 結構是否處於危險狀態（有失效方塊或最大應力 > 0.9）。
     */
    public boolean isCritical() {
        return failureCount > 0 || maxStress > 0.9f;
    }

    @Override
    public String toString() {
        return String.format(
            "RStructure{id=%d, blocks=%d, anchors=%d, compositeR=%.1f, load=%.0fkg, " +
            "maxStress=%.2f, failures=%d, health=%.0f%%, time=%.1fms}",
            structureId, blockCount(), anchorCount(), compositeR, totalLoad,
            maxStress, failureCount, healthScore() * 100, computeTimeMs
        );
    }

}
