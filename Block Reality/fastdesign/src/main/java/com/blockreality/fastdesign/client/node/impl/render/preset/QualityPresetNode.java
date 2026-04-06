package com.blockreality.fastdesign.client.node.impl.render.preset;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;

/**
 * A1-1: 總品質預設 — 設計報告 §5 A1-1
 *
 * 一鍵設定節點：選擇預設等級，自動輸出所有子系統參數。
 * Grasshopper 類比：Cluster
 */
@OnlyIn(Dist.CLIENT)
public class QualityPresetNode extends BRNode {

    public enum Preset { POTATO, LOW, MEDIUM, HIGH, ULTRA, CUSTOM }

    public QualityPresetNode() {
        super("QualityPreset", "總品質預設", "render", NodeColor.RENDER);
        addInput("preset", "品質等級", PortType.ENUM, "HIGH");

        addOutput("shadowRes", PortType.INT);
        addOutput("ssaoEnabled", PortType.BOOL);
        addOutput("ssrEnabled", PortType.BOOL);
        addOutput("taaEnabled", PortType.BOOL);
        addOutput("bloomEnabled", PortType.BOOL);
        addOutput("lodMaxDist", PortType.FLOAT);
        addOutput("volumetricEnabled", PortType.BOOL);
        addOutput("cloudEnabled", PortType.BOOL);
        addOutput("particleCount", PortType.INT);
    }

    @Override
    public void evaluate() {
        String preset = String.valueOf(getInput("preset").getRawValue());
        switch (preset.toUpperCase()) {
            case "POTATO" -> applyPreset(512, false, false, false, false, 128f, false, false, 256);
            case "LOW"    -> applyPreset(1024, true, false, false, true, 256f, false, true, 1024);
            case "MEDIUM" -> applyPreset(2048, true, false, true, true, 512f, false, true, 2048);
            case "HIGH"   -> applyPreset(2048, true, true, true, true, 768f, true, true, 4096);
            case "ULTRA"  -> applyPreset(4096, true, true, true, true, 1024f, true, true, 8192);
            default -> {} // CUSTOM: 不設值，由手動連線決定
        }
    }

    private void applyPreset(int shadow, boolean ssao, boolean ssr, boolean taa,
                              boolean bloom, float lod, boolean vol, boolean cloud, int particles) {
        getOutput("shadowRes").setValue(shadow);
        getOutput("ssaoEnabled").setValue(ssao);
        getOutput("ssrEnabled").setValue(ssr);
        getOutput("taaEnabled").setValue(taa);
        getOutput("bloomEnabled").setValue(bloom);
        getOutput("lodMaxDist").setValue(lod);
        getOutput("volumetricEnabled").setValue(vol);
        getOutput("cloudEnabled").setValue(cloud);
        getOutput("particleCount").setValue(particles);
    }

    @Override public String getTooltip() { return "一鍵品質預設：Potato/Low/Medium/High/Ultra/Custom"; }
    @Override public String typeId() { return "render.preset.QualityPreset"; }
}
