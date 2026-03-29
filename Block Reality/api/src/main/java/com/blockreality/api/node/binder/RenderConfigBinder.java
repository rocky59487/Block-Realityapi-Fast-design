package com.blockreality.api.node.binder;

import com.blockreality.api.node.NodeGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * ★ review-fix ICReM-7: RenderConfigBinder 代理層。
 *
 * API 層只提供靜態代理介面，具體實作由模組層透過 setImplementation() 註冊。
 * 這確保 API 不依賴 EffectToggleNode、QualityPresetNode 或 BRRenderSettings 的內部細節。
 *
 * 模組層（fastdesign）在初始化時呼叫:
 *   RenderConfigBinder.setImplementation(new FastDesignRenderConfigBinder());
 */
public final class RenderConfigBinder {

    private RenderConfigBinder() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-NodeBinder");

    @Nullable
    private static INodeBinder implementation;

    /**
     * 設定具體實作。由模組層在初始化時呼叫。
     */
    public static void setImplementation(INodeBinder impl) {
        implementation = impl;
        LOG.info("[NodeBinder] 渲染設定綁定器已註冊: {}", impl.getClass().getSimpleName());
    }

    /**
     * 初始化綁定器。
     */
    public static void init() {
        if (implementation != null) {
            implementation.init();
        } else {
            LOG.debug("[NodeBinder] 無綁定器實作，跳過 init");
        }
    }

    /**
     * 清理綁定器。
     */
    public static void cleanup() {
        if (implementation != null) {
            implementation.cleanup();
        }
    }

    /**
     * 將節點圖輸出推送到運行時設定。
     */
    public static void pushToSettings(NodeGraph graph) {
        if (implementation != null) {
            implementation.pushToSettings(graph);
        }
    }

    /**
     * 從運行時設定拉取值填入節點輸入。
     */
    public static void pullFromSettings(NodeGraph graph) {
        if (implementation != null) {
            implementation.pullFromSettings(graph);
        }
    }

    /**
     * 檢查是否有已註冊的實作。
     */
    public static boolean hasImplementation() {
        return implementation != null;
    }
}
