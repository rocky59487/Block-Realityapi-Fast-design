package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.api.config.BRConfig;
import com.blockreality.fastdesign.client.node.NodeGraph;
import com.blockreality.fastdesign.config.FastDesignConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * 即時預覽橋接器 — 設計報告 §12.1 N3-7
 *
 * 在每幀渲染前將節點值注入 runtime 系統：
 *   1. 檢查各 Binder 是否有髒值
 *   2. 將髒值推送到對應 runtime 物件
 *   3. MutableRenderConfig 覆蓋在 RenderLevelStageEvent.AFTER_SETUP 時注入
 *
 * 使用方式：
 *   MinecraftForge.EVENT_BUS.register(LivePreviewBridge.getInstance());
 */
@OnlyIn(Dist.CLIENT)
public class LivePreviewBridge {

    private static final Logger LOGGER = LogManager.getLogger("LivePreview");
    private static final LivePreviewBridge INSTANCE = new LivePreviewBridge();

    public static LivePreviewBridge getInstance() { return INSTANCE; }

    @Nullable private NodeGraph graph;
    @Nullable private RenderConfigBinder renderBinder;
    @Nullable private MaterialBinder materialBinder;
    @Nullable private PhysicsBinder physicsBinder;
    @Nullable private ShaderBinder shaderBinder;
    @Nullable private FastDesignConfigBinder fdConfigBinder;

    private final MutableRenderConfig renderConfig = MutableRenderConfig.getInstance();
    private final MaterialBinder.MaterialContext materialContext = new MaterialBinder.MaterialContext();
    private final ShaderBinder.UniformContext uniformContext = new ShaderBinder.UniformContext();

    private boolean active = false;
    private long lastApplyMs = 0;

    // ─── 初始化 ───

    /**
     * 綁定到節點圖。在節點畫布開啟時呼叫。
     */
    public void bindGraph(NodeGraph graph) {
        this.graph = graph;

        renderBinder = new RenderConfigBinder();
        materialBinder = new MaterialBinder();
        physicsBinder = new PhysicsBinder();
        shaderBinder = new ShaderBinder();
        fdConfigBinder = new FastDesignConfigBinder();

        renderBinder.bind(graph);
        materialBinder.bind(graph);
        physicsBinder.bind(graph);
        shaderBinder.bind(graph);
        fdConfigBinder.bind(graph);

        renderConfig.setOverrideActive(true);
        active = true;

        LOGGER.info("LivePreviewBridge 已綁定：render={}, material={}, physics={}, shader={}, fdConfig={}",
                renderBinder.bindingCount(), materialBinder.bindingCount(),
                physicsBinder.bindingCount(), shaderBinder.bindingCount(),
                fdConfigBinder.bindingCount());
    }

    /**
     * 解除綁定（節點畫布關閉時呼叫）。
     */
    public void unbind() {
        active = false;
        renderConfig.setOverrideActive(false);
        renderConfig.resetToDefaults();
        graph = null;
        renderBinder = null;
        materialBinder = null;
        physicsBinder = null;
        shaderBinder = null;
        fdConfigBinder = null;
        LOGGER.info("LivePreviewBridge 已解除綁定");
    }

    // ─── 每幀更新 ───

    /**
     * Forge 渲染事件 — 在 AFTER_SETUP 階段注入覆蓋值。
     */
    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!active || graph == null) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;

        applyAll();
    }

    /**
     * 手動觸發全部應用（用於非渲染場景的測試）。
     */
    public void applyAll() {
        if (!active) return;

        long now = System.currentTimeMillis();
        // 限流：最多每 16ms（60fps）應用一次
        if (now - lastApplyMs < 16) return;
        lastApplyMs = now;

        // 渲染配置
        if (renderBinder != null) {
            renderBinder.apply(renderConfig);
        }

        // 材料
        if (materialBinder != null) {
            materialBinder.apply(materialContext);
        }

        // 物理（寫入 ForgeConfigSpec）
        if (physicsBinder != null) {
            physicsBinder.apply(BRConfig.INSTANCE);
        }

        // Shader uniform
        if (shaderBinder != null) {
            shaderBinder.apply(uniformContext);
        }

        // FastDesign 配置
        if (fdConfigBinder != null) {
            fdConfigBinder.apply(null); // FastDesignConfig 使用 static fields
        }
    }

    /**
     * 從 runtime 拉回所有值到節點（初始化同步）。
     */
    public void pullAll() {
        if (!active) return;
        if (renderBinder != null) renderBinder.pull(renderConfig);
        if (physicsBinder != null) physicsBinder.pull(BRConfig.INSTANCE);
        if (fdConfigBinder != null) fdConfigBinder.pull(null);
    }

    // ─── 重新綁定（圖結構變更後） ───

    public void rebind() {
        if (graph != null && active) {
            bindGraph(graph);
        }
    }

    // ─── 存取 ───

    public boolean isActive() { return active; }
    @Nullable public NodeGraph getGraph() { return graph; }
    public MutableRenderConfig getRenderConfig() { return renderConfig; }
    public ShaderBinder.UniformContext getUniformContext() { return uniformContext; }
    public MaterialBinder.MaterialContext getMaterialContext() { return materialContext; }
}
