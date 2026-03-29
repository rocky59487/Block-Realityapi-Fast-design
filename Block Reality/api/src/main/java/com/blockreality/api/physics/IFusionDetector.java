package com.blockreality.api.physics;

/**
 * 融合偵測器介面。
 * 接受快照，輸出 RC 融合節點清單。
 * 純計算，無 Minecraft API 呼叫。
 *
 * 實作：RCFusionDetector（靜態工廠方法版本）
 *
 * @since 1.0.0
 */
public interface IFusionDetector {
    /**
     * 偵測快照中的 RC 融合對。
     * @param snapshot 唯讀快照
     * @return 融合結果（升級的節點清單 + 蜂窩位置集合）
     */
    FusionResult detect(RWorldSnapshot snapshot);
}
