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

    /**
     * ★ P8-fix (2025-04): 補全 RT 效果開關。
     * 原本僅有 enableRTAO，缺少 RT Shadows / Reflections / GI 的個別開關，
     * 導致 VulkanRTConfigNode 無法透過節點編輯器獨立切換各效果。
     *
     * 這些布林值將接入 Shader Specialization Constants（由 BRVulkanRT 推送）。
     * 預設值：
     *   - enableRTShadows:      true  （RT 陰影最基礎，通常需要啟用）
     *   - enableRTReflections:  true  （螢幕空間反射的升級，開啟可見度高）
     *   - enableRTGI:           false （效能負擔最重，預設關閉待 Phase 5 穩定）
     */
    private volatile boolean enableRTShadows     = true;
    private volatile boolean enableRTReflections = true;
    private volatile boolean enableRTGI          = false;

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

    // ── P8: RT 效果個別開關 ──────────────────────────────────────────────────

    /** RT 陰影（Ray-traced shadows）開關。 */
    public boolean isEnableRTShadows() { return enableRTShadows; }

    public void setEnableRTShadows(boolean enable) {
        if (this.enableRTShadows != enable) {
            this.enableRTShadows = enable;
            LOGGER.debug("RTSettings: RT Shadows {}", enable ? "enabled" : "disabled");
        }
    }

    /** RT 反射（Ray-traced reflections）開關。 */
    public boolean isEnableRTReflections() { return enableRTReflections; }

    public void setEnableRTReflections(boolean enable) {
        if (this.enableRTReflections != enable) {
            this.enableRTReflections = enable;
            LOGGER.debug("RTSettings: RT Reflections {}", enable ? "enabled" : "disabled");
        }
    }

    /**
     * RT 全域光照（Ray-traced Global Illumination）開關。
     * 效能負擔最重，預設關閉；Phase 5 ReSTIR GI 穩定後建議預設開啟。
     */
    public boolean isEnableRTGI() { return enableRTGI; }

    public void setEnableRTGI(boolean enable) {
        if (this.enableRTGI != enable) {
            this.enableRTGI = enable;
            LOGGER.debug("RTSettings: RT GI {}", enable ? "enabled" : "disabled");
        }
    }

    public int getDenoiserAlgo() { return denoiserAlgo; }

    public void setDenoiserAlgo(int algo) {
        if (this.denoiserAlgo != algo) {
            this.denoiserAlgo = algo;
            LOGGER.debug("RTSettings: Denoiser Algorithm changed to {}", algo);
        }
    }
}
