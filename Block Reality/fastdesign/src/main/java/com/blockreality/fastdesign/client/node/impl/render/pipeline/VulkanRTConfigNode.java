package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.render.rt.BRRTSettings;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.client.render.rt.RTEffect;
import com.blockreality.api.client.rendering.BRRTCompositor;
import com.blockreality.fastdesign.client.node.*;

/**
 * Vulkan 光線追蹤配置節點。
 *
 * <p>允許使用者透過節點編輯器介面即時調整 Vulkan RT 的品質參數與預算控制開關。
 *
 * <h3>端口分組</h3>
 * <ul>
 *   <li><b>基本參數</b>：AO 半徑、最大彈射次數、降噪演算法</li>
 *   <li><b>效果開關（RTEffect 預算控制）</b>：RTAO、陰影、反射、SVGF 降噪、DAG GI</li>
 * </ul>
 *
 * <p>效果開關透過 {@link BRRTCompositor#enableRTEffect} / {@link BRRTCompositor#disableRTEffect}
 * 直接作用於 {@link com.blockreality.api.client.rendering.vulkan.VkRTPipeline} 的
 * {@code EnumSet<RTEffect>}，無需重啟 RT pipeline。
 *
 * <p>RTAO 開關同步寫入 {@link BRRTSettings}，與 {@code VkRTPipeline.enableEffect(RTAO)} 保持一致
 * （兩者在 {@code enableEffect} 實作中已互相同步）。
 */
@OnlyIn(Dist.CLIENT)
public class VulkanRTConfigNode extends BRNode {

    // 定義內部 Enum 作為 Node 端口選項
    public enum DenoiserOpt { NONE, SVGF, NRD }

    public VulkanRTConfigNode() {
        super("VulkanRTConfig", "光追設定", "render", NodeColor.RENDER);

        // ── 基本品質參數 ──────────────────────────────────────────────
        addInput("aoRadius",    "AO 取樣半徑",   PortType.FLOAT, 2.5f).range(0.5f, 5.0f).step(0.1f);
        addInput("maxBounces",  "最大彈射次數",  PortType.INT,   1   ).range(0, 3);
        addInput("denoiser",    "降噪演算法",    PortType.ENUM,  DenoiserOpt.NRD);

        // ── RTEffect 預算控制開關 ──────────────────────────────────────
        // RTAO：同步寫入 BRRTSettings.setEnableRTAO()（最耗時，優先關閉）
        addInput("enableRTAO",       "開啟環境光遮蔽 (AO)",    PortType.BOOL, true);
        // SHADOWS：RT 硬陰影（primary.rgen R 通道）
        addInput("enableShadows",    "開啟光追陰影",           PortType.BOOL, true);
        // REFLECTIONS：RT 反射（primary.rgen GBA 通道）
        addInput("enableReflections","開啟光追反射",           PortType.BOOL, true);
        // SVGF_DENOISE：SVGF/NRD 時序降噪器
        addInput("enableSVGFDenoise","開啟 SVGF 降噪",         PortType.BOOL, true);
        // DAG_GI：遠景 DAG SSBO PCIe 上傳（每 N 幀上傳一次）
        addInput("enableDAGGI",      "開啟 DAG 全局光照",      PortType.BOOL, true);

        // ── 輸出端口（供串接或除錯使用） ──────────────────────────────
        addOutput("rtConfig", "RT 設定物件", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // ── 1. 讀取基本參數 ───────────────────────────────────────────
        float aoRadius  = getInput("aoRadius").getFloat();
        int   maxBounces = getInput("maxBounces").getInt();

        Object denoiserVal = getInput("denoiser").getRawValue();
        DenoiserOpt denoiserOpt = DenoiserOpt.NRD;
        if (denoiserVal instanceof DenoiserOpt d) {
            denoiserOpt = d;
        } else if (denoiserVal instanceof String s) {
            try { denoiserOpt = DenoiserOpt.valueOf(s); } catch (Exception ignored) {}
        }

        // ── 2. 讀取 RTEffect 開關 ─────────────────────────────────────
        boolean enableRTAO        = getInput("enableRTAO").getBool();
        boolean enableShadows     = getInput("enableShadows").getBool();
        boolean enableReflections = getInput("enableReflections").getBool();
        boolean enableSVGFDenoise = getInput("enableSVGFDenoise").getBool();
        boolean enableDAGGI       = getInput("enableDAGGI").getBool();

        // ── 3. 寫入 BRRTSettings（基本參數 + RTAO 同步） ─────────────
        BRRTSettings settings = BRRTSettings.getInstance();
        settings.setAoRadius(aoRadius);
        settings.setMaxBounces(maxBounces);
        // RTAO 寫入 BRRTSettings；enableEffect(RTAO) 內部亦呼叫 setEnableRTAO，避免雙重寫入
        // 此處直接走 enableRTEffect / disableRTEffect，RTAO sync 在 VkRTPipeline 中完成
        switch (denoiserOpt) {
            case NONE -> settings.setDenoiserAlgo(0);
            case SVGF -> settings.setDenoiserAlgo(1);
            case NRD  -> settings.setDenoiserAlgo(2);
        }

        // ── 4. 透過 BRRTCompositor facade 控制 RTEffect EnumSet ───────
        // BRRTCompositor 未初始化時 enableRTEffect / disableRTEffect 靜默忽略
        BRRTCompositor compositor = BRRTCompositor.getInstance();

        applyEffect(compositor, RTEffect.RTAO,         enableRTAO);
        applyEffect(compositor, RTEffect.SHADOWS,      enableShadows);
        applyEffect(compositor, RTEffect.REFLECTIONS,  enableReflections);
        applyEffect(compositor, RTEffect.SVGF_DENOISE, enableSVGFDenoise);
        applyEffect(compositor, RTEffect.DAG_GI,       enableDAGGI);

        // ── 5. 輸出（傳遞 BRRTSettings 參照供下游節點讀取） ──────────
        getOutput("rtConfig").setValue(settings);
    }

    // ── 私有輔助方法 ──────────────────────────────────────────────────

    /**
     * 根據 {@code enabled} 旗標，對指定效果呼叫 enable 或 disable。
     * 避免在 evaluate() 中重複 if/else 模式。
     */
    private static void applyEffect(BRRTCompositor compositor, RTEffect effect, boolean enabled) {
        if (enabled) {
            compositor.enableRTEffect(effect);
        } else {
            compositor.disableRTEffect(effect);
        }
    }

    @Override
    public String getTooltip() {
        return "即時調整 Vulkan 光線追蹤的算繪品質與效果預算開關";
    }

    @Override
    public String typeId() {
        return "render.pipeline.VulkanRTConfig";
    }
}
