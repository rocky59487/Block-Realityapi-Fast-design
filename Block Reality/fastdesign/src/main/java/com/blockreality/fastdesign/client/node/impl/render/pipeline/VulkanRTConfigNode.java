package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.*;

/**
 * Vulkan 光線追蹤配置節點。
 * 允許使用者透過 Node 節點介面調整 API 層 Vulkan 光追的即時設定 (如 Bounces, AO 半徑)。
 */
public class VulkanRTConfigNode extends BRNode {
    
    // 定義內部 Enum 作為 Node 端口選項
    public enum DenoiserOpt { NONE, SVGF, NRD }

    public VulkanRTConfigNode() {
        super("VulkanRTConfig", "光追設定", "render", NodeColor.RENDER);
        
        // 輸入端口
        addInput("enableRTAO", "開啟環境光遮蔽", PortType.BOOL, true);
        addInput("aoRadius", "AO 取樣半徑", PortType.FLOAT, 2.5f).range(0.5f, 5.0f).step(0.1f);
        addInput("maxBounces", "最大彈射次數", PortType.INT, 1).range(0, 3);
        addInput("denoiser", "降噪演算法", PortType.ENUM, DenoiserOpt.NRD);
        
        // 輸出端口（供串接或除錯使用）
        addOutput("rtConfig", "RT 設定物件", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // 1. 取得 Node 上的值
        boolean enableRTAO = getInput("enableRTAO").getBool();
        float aoRadius = getInput("aoRadius").getFloat();
        int maxBounces = getInput("maxBounces").getInt();
        
        Object denoiserVal = getInput("denoiser").getRawValue();
        DenoiserOpt denoiserOpt = DenoiserOpt.NRD;
        if (denoiserVal instanceof DenoiserOpt) {
            denoiserOpt = (DenoiserOpt) denoiserVal;
        } else if (denoiserVal instanceof String) {
            try {
                denoiserOpt = DenoiserOpt.valueOf((String) denoiserVal);
            } catch (Exception ignored) {}
        }

        // 2. 對齊 API 設定（映射到 BRRTSettings）
        BRRTSettings settings = BRRTSettings.getInstance();
        settings.setEnableRTAO(enableRTAO);
        settings.setAoRadius(aoRadius);
        settings.setMaxBounces(maxBounces);
        
        switch (denoiserOpt) {
            case NONE -> settings.setDenoiserAlgo(0);
            case SVGF -> settings.setDenoiserAlgo(1);
            case NRD  -> settings.setDenoiserAlgo(2);
        }
        
        // 3. 輸出
        getOutput("rtConfig").setValue(settings); // 傳遞參照
    }

    @Override
    public String getTooltip() {
        return "即時調整 Vulkan 光線追蹤的算繪品質與參數";
    }

    @Override
    public String typeId() {
        return "render.pipeline.VulkanRTConfig";
    }
}
