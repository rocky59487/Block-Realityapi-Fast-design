package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.fastdesign.client.node.NodeGraph;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/**
 * 資料綁定介面 — 設計報告 §12.1 N3
 *
 * 將節點圖的端口值綁定到 Runtime 物件（渲染配置、材料、物理引擎等）。
 *
 * 生命週期：
 *   1. bind(graph) — 載入節點圖時呼叫，建立綁定映射
 *   2. apply(target) — 每幀呼叫，將節點值推送到 runtime
 *   3. pull(target) — 首次載入或外部修改時，從 runtime 拉回值到節點
 *   4. isDirty() — 是否有任何綁定值變更
 *
 * @param <T> Runtime 目標型別
 */
@OnlyIn(Dist.CLIENT)
public interface IBinder<T> {

    /**
     * 綁定到節點圖。掃描所有相關節點，建立端口→欄位映射。
     */
    void bind(NodeGraph graph);

    /**
     * 將節點端口的當前值推送到 runtime 目標物件。
     * 僅在 isDirty() 為 true 時需要呼叫。
     */
    void apply(T target);

    /**
     * 從 runtime 目標物件拉回值到節點端口。
     * 用於初始化或外部修改同步。
     */
    void pull(T target);

    /**
     * 是否有任何綁定的端口值發生變更。
     */
    boolean isDirty();

    /**
     * 清除髒標記。
     */
    void clearDirty();

    /**
     * 綁定的欄位數量。
     */
    int bindingCount();
}
