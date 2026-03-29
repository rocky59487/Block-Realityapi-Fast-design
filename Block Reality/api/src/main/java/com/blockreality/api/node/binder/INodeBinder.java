package com.blockreality.api.node.binder;

import com.blockreality.api.node.NodeGraph;

/**
 * ★ review-fix ICReM-7: 節點綁定器介面。
 *
 * API 層只定義介面，具體實作由模組層（fastdesign/architect）提供。
 * 這確保 API 不依賴任何具體節點類型或渲染設定。
 *
 * 綁定器的職責：
 *   - pushToSettings: 評估後將節點輸出推送到運行時設定
 *   - pullFromSettings: 初始化時從運行時設定填入節點輸入
 *   - init/cleanup: 生命週期管理
 */
public interface INodeBinder {

    /** 初始化綁定器。 */
    void init();

    /** 清理綁定器。 */
    void cleanup();

    /** 將節點圖輸出推送到運行時設定。 */
    void pushToSettings(NodeGraph graph);

    /** 從運行時設定拉取值填入節點輸入。 */
    void pullFromSettings(NodeGraph graph);
}
