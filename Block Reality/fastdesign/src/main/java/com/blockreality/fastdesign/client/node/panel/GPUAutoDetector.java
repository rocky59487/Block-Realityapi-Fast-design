package com.blockreality.fastdesign.client.node.panel;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;

/**
 * GPU 自動偵測 — 設計報告 §12.1 N5-4
 *
 * 偵測 GPU 型號、VRAM、GL 版本，推薦最佳品質預設。
 */
@OnlyIn(Dist.CLIENT)
public class GPUAutoDetector {

    public enum QualityTier { POTATO, LOW, MEDIUM, HIGH, ULTRA }

    private String gpuVendor = "Unknown";
    private String gpuRenderer = "Unknown";
    private int glMajor = 3, glMinor = 3;
    private int estimatedVramMB = 2048;
    private QualityTier recommended = QualityTier.MEDIUM;

    public void detect() {
        try {
            gpuRenderer = GL11.glGetString(GL11.GL_RENDERER);
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            String version = GL11.glGetString(GL11.GL_VERSION);

            if (vendor != null) gpuVendor = vendor;
            if (version != null && version.length() >= 3) {
                glMajor = Character.getNumericValue(version.charAt(0));
                glMinor = Character.getNumericValue(version.charAt(2));
            }

            estimatedVramMB = estimateVRAM(gpuRenderer);
            recommended = recommendTier(gpuRenderer, estimatedVramMB, glMajor);
        } catch (Exception e) {
            recommended = QualityTier.LOW;
        }
    }

    private int estimateVRAM(String renderer) {
        if (renderer == null) return 2048;
        String l = renderer.toLowerCase();
        if (l.contains("4090")) return 24576;
        if (l.contains("4080")) return 16384;
        if (l.contains("4070")) return 12288;
        if (l.contains("3090") || l.contains("3080")) return 10240;
        if (l.contains("3070") || l.contains("3060")) return 8192;
        if (l.contains("2080") || l.contains("2070")) return 8192;
        if (l.contains("1080") || l.contains("1070")) return 8192;
        if (l.contains("1060") || l.contains("2060")) return 6144;
        if (l.contains("rx 7") || l.contains("rx 6")) return 8192;
        if (l.contains("intel") && l.contains("arc")) return 8192;
        if (l.contains("intel")) return 1024;
        return 2048;
    }

    private QualityTier recommendTier(String renderer, int vram, int gl) {
        if (gl < 3) return QualityTier.POTATO;
        if (vram < 2048) return QualityTier.LOW;
        if (vram < 4096) return QualityTier.MEDIUM;
        if (vram < 8192) return QualityTier.HIGH;
        return QualityTier.ULTRA;
    }

    public String gpuVendor() { return gpuVendor; }
    public String gpuRenderer() { return gpuRenderer; }
    public int glMajor() { return glMajor; }
    public int glMinor() { return glMinor; }
    public int estimatedVramMB() { return estimatedVramMB; }
    public QualityTier recommended() { return recommended; }
}
