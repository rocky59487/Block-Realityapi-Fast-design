package com.blockreality.fastdesign.client.node.impl.render.preset;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import org.lwjgl.opengl.GL11;

/** A1-4: GPU 自動偵測 — 設計報告 §5 A1-4 */
@OnlyIn(Dist.CLIENT)
public class GPUDetectNode extends BRNode {
    public GPUDetectNode() {
        super("GPUDetect", "GPU 偵測", "render", NodeColor.RENDER);
        addOutput("gpuVendor", PortType.ENUM);
        addOutput("vramMB", PortType.INT);
        addOutput("glVersionMajor", PortType.INT);
        addOutput("recommendedTier", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        try {
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            String version = GL11.glGetString(GL11.GL_VERSION);

            String vendor = "OTHER";
            if (renderer != null) {
                String lower = renderer.toLowerCase();
                if (lower.contains("nvidia") || lower.contains("geforce")) vendor = "NVIDIA";
                else if (lower.contains("amd") || lower.contains("radeon")) vendor = "AMD";
                else if (lower.contains("intel")) vendor = "INTEL";
            }
            getOutput("gpuVendor").setValue(vendor);

            int glMajor = 3;
            if (version != null && !version.isEmpty()) {
                glMajor = Character.getNumericValue(version.charAt(0));
            }
            getOutput("glVersionMajor").setValue(glMajor);
            getOutput("vramMB").setValue(estimateVRAM(renderer));

            String tier = glMajor >= 4 ? "Tier2_Ultra" : glMajor >= 3 ? "Tier1_Quality" : "Tier0_Compat";
            getOutput("recommendedTier").setValue(tier);
        } catch (Exception e) {
            getOutput("gpuVendor").setValue("OTHER");
            getOutput("vramMB").setValue(512);
            getOutput("glVersionMajor").setValue(3);
            getOutput("recommendedTier").setValue("Tier0_Compat");
        }
    }

    private int estimateVRAM(String renderer) {
        if (renderer == null) return 512;
        String lower = renderer.toLowerCase();
        if (lower.contains("4090") || lower.contains("4080")) return 16384;
        if (lower.contains("3080") || lower.contains("3090")) return 10240;
        if (lower.contains("3070") || lower.contains("3060")) return 8192;
        if (lower.contains("2080") || lower.contains("2070")) return 8192;
        if (lower.contains("1080") || lower.contains("1070")) return 8192;
        return 2048;
    }

    @Override public String getTooltip() { return "自動偵測 GPU 型號與能力"; }
    @Override public String typeId() { return "render.preset.GPUDetect"; }
}
