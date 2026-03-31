package com.blockreality.api.client.render.rt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 儲存 Vulkan 光線追蹤的動態全域設定。
 * 這些設定將由 FastDesign 模組中的 VulkanRTConfigNode 讀寫，
 * 並在每幀由 BRVulkanRT 與各種 Shader 與後處理讀取生效。
 */
public class BRRTSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger(BRRTSettings.class);

    // ── Singleton ───────────────────────────────────────────────────────────
    private static final BRRTSettings INSTANCE = new BRRTSettings();

    public static BRRTSettings getInstance() {
        return INSTANCE;
    }

    private BRRTSettings() {}

    // ── 內部狀態 (執行緒安全) ──────────────────────────────────────────────────
    private volatile int maxBounces = 1;
    private volatile float aoRadius = 2.5f;
    private volatile boolean enableRTAO = true;
    
    // 0 = NONE, 1 = SVGF, 2 = NRD (RELAX/REBLUR)
    private volatile int denoiserAlgo = 2;

    // ── Getter / Setter ──────────────────────────────────────────────────────

    public int getMaxBounces() { return maxBounces; }
    
    public void setMaxBounces(int bounces) {
        if (this.maxBounces != bounces) {
            this.maxBounces = bounces;
            LOGGER.debug("RTSettings: Max Bounces changed to {}", bounces);
        }
    }

    public float getAoRadius() { return aoRadius; }
    
    public void setAoRadius(float radius) {
        this.aoRadius = radius;
    }

    public boolean isEnableRTAO() { return enableRTAO; }
    
    public void setEnableRTAO(boolean enable) {
        this.enableRTAO = enable;
    }

    public int getDenoiserAlgo() { return denoiserAlgo; }
    
    public void setDenoiserAlgo(int algo) {
        if (this.denoiserAlgo != algo) {
            this.denoiserAlgo = algo;
            LOGGER.debug("RTSettings: Denoiser Algorithm changed to {}", algo);
        }
    }
}
