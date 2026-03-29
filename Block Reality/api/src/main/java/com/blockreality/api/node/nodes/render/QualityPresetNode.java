package com.blockreality.api.node.nodes.render;

import com.blockreality.api.node.BRNode;
import com.blockreality.api.node.PortType;

/**
 * A1-1 品質預設節點 — 一鍵品質預設，扇出 20+ 個布林/整數輸出。
 *
 * TODO [API-BOUNDARY]: 此節點屬於渲染互動層（顯示、交互），不屬於 API 計算層。
 *   API 只負責提供計算引擎和資料結構（BRNode, NodeGraph, PortType 等）。
 *   渲染預設節點應移至建築師模組 (fastdesign/architect)。
 *   遷移時保留 BRNode 基類在 API，將此類移至 fastdesign.client.node.impl.render。
 *
 * 選擇預設等級後，自動設定所有渲染子系統的開關與品質參數。
 * 預設等級：Potato(0), Low(1), Medium(2), High(3), Ultra(4), Custom(5)。
 *
 * Custom 模式下不覆寫任何輸出，保留上游連線的值。
 */
public class QualityPresetNode extends BRNode {

    public static final int NODE_COLOR = 0x2196F3; // Blue

    public QualityPresetNode() {
        super("品質預設 QualityPreset", "render", NODE_COLOR);

        // Input: preset selection (0=Potato, 1=Low, 2=Medium, 3=High, 4=Ultra, 5=Custom)
        addInput("preset", PortType.INT, 3); // default: High

        // Outputs: fan out to all render subsystems
        addOutput("shadowRes", PortType.INT);
        addOutput("ssaoEnabled", PortType.BOOL);
        addOutput("ssrEnabled", PortType.BOOL);
        addOutput("ssgiEnabled", PortType.BOOL);
        addOutput("taaEnabled", PortType.BOOL);
        addOutput("bloomEnabled", PortType.BOOL);
        addOutput("dofEnabled", PortType.BOOL);
        addOutput("volumetricEnabled", PortType.BOOL);
        addOutput("contactShadowEnabled", PortType.BOOL);
        addOutput("motionBlurEnabled", PortType.BOOL);
        addOutput("cloudEnabled", PortType.BOOL);
        addOutput("weatherEnabled", PortType.BOOL);
        addOutput("waterEnabled", PortType.BOOL);
        addOutput("fogEnabled", PortType.BOOL);
        addOutput("atmosphereEnabled", PortType.BOOL);
        addOutput("sssEnabled", PortType.BOOL);
        addOutput("anisotropicEnabled", PortType.BOOL);
        addOutput("pomEnabled", PortType.BOOL);
        addOutput("vctEnabled", PortType.BOOL);
        addOutput("rtShadowEnabled", PortType.BOOL);
        addOutput("ssaoSamples", PortType.INT);
        addOutput("lodMaxDistance", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        int preset = getInt("preset");

        switch (preset) {
            case 0 -> applyPotato();
            case 1 -> applyLow();
            case 2 -> applyMedium();
            case 3 -> applyHigh();
            case 4 -> applyUltra();
            case 5 -> {
                // Custom: don't change outputs — leave wired values intact
            }
            default -> applyHigh(); // fallback to High
        }
    }

    /** Potato — 最低畫質，極限效能 */
    private void applyPotato() {
        setOutput("shadowRes", 512);
        setOutput("ssaoEnabled", false);
        setOutput("ssrEnabled", false);
        setOutput("ssgiEnabled", false);
        setOutput("taaEnabled", false);
        setOutput("bloomEnabled", false);
        setOutput("dofEnabled", false);
        setOutput("volumetricEnabled", false);
        setOutput("contactShadowEnabled", false);
        setOutput("motionBlurEnabled", false);
        setOutput("cloudEnabled", false);
        setOutput("weatherEnabled", false);
        setOutput("waterEnabled", false);
        setOutput("fogEnabled", true);
        setOutput("atmosphereEnabled", false);
        setOutput("sssEnabled", false);
        setOutput("anisotropicEnabled", false);
        setOutput("pomEnabled", false);
        setOutput("vctEnabled", false);
        setOutput("rtShadowEnabled", false);
        setOutput("ssaoSamples", 8);
        setOutput("lodMaxDistance", 128.0f);
    }

    /** Low — 基礎效果，適合低端硬體 */
    private void applyLow() {
        setOutput("shadowRes", 1024);
        setOutput("ssaoEnabled", true);
        setOutput("ssrEnabled", false);
        setOutput("ssgiEnabled", false);
        setOutput("taaEnabled", false);
        setOutput("bloomEnabled", true);
        setOutput("dofEnabled", false);
        setOutput("volumetricEnabled", false);
        setOutput("contactShadowEnabled", false);
        setOutput("motionBlurEnabled", false);
        setOutput("cloudEnabled", false);
        setOutput("weatherEnabled", true);
        setOutput("waterEnabled", true);
        setOutput("fogEnabled", true);
        setOutput("atmosphereEnabled", false);
        setOutput("sssEnabled", false);
        setOutput("anisotropicEnabled", false);
        setOutput("pomEnabled", false);
        setOutput("vctEnabled", false);
        setOutput("rtShadowEnabled", false);
        setOutput("ssaoSamples", 16);
        setOutput("lodMaxDistance", 256.0f);
    }

    /** Medium — 平衡畫質與效能 */
    private void applyMedium() {
        setOutput("shadowRes", 2048);
        setOutput("ssaoEnabled", true);
        setOutput("ssrEnabled", false);
        setOutput("ssgiEnabled", false);
        setOutput("taaEnabled", true);
        setOutput("bloomEnabled", true);
        setOutput("dofEnabled", false);
        setOutput("volumetricEnabled", false);
        setOutput("contactShadowEnabled", true);
        setOutput("motionBlurEnabled", false);
        setOutput("cloudEnabled", true);
        setOutput("weatherEnabled", true);
        setOutput("waterEnabled", true);
        setOutput("fogEnabled", true);
        setOutput("atmosphereEnabled", true);
        setOutput("sssEnabled", false);
        setOutput("anisotropicEnabled", false);
        setOutput("pomEnabled", false);
        setOutput("vctEnabled", false);
        setOutput("rtShadowEnabled", false);
        setOutput("ssaoSamples", 24);
        setOutput("lodMaxDistance", 512.0f);
    }

    /** High — 高品質，推薦設定 */
    private void applyHigh() {
        setOutput("shadowRes", 2048);
        setOutput("ssaoEnabled", true);
        setOutput("ssrEnabled", true);
        setOutput("ssgiEnabled", true);
        setOutput("taaEnabled", true);
        setOutput("bloomEnabled", true);
        setOutput("dofEnabled", false);
        setOutput("volumetricEnabled", true);
        setOutput("contactShadowEnabled", true);
        setOutput("motionBlurEnabled", false);
        setOutput("cloudEnabled", true);
        setOutput("weatherEnabled", true);
        setOutput("waterEnabled", true);
        setOutput("fogEnabled", true);
        setOutput("atmosphereEnabled", true);
        setOutput("sssEnabled", true);
        setOutput("anisotropicEnabled", true);
        setOutput("pomEnabled", true);
        setOutput("vctEnabled", true);
        setOutput("rtShadowEnabled", false);
        setOutput("ssaoSamples", 32);
        setOutput("lodMaxDistance", 768.0f);
    }

    /** Ultra — 最高品質，所有效果全開 */
    private void applyUltra() {
        setOutput("shadowRes", 4096);
        setOutput("ssaoEnabled", true);
        setOutput("ssrEnabled", true);
        setOutput("ssgiEnabled", true);
        setOutput("taaEnabled", true);
        setOutput("bloomEnabled", true);
        setOutput("dofEnabled", true);
        setOutput("volumetricEnabled", true);
        setOutput("contactShadowEnabled", true);
        setOutput("motionBlurEnabled", true);
        setOutput("cloudEnabled", true);
        setOutput("weatherEnabled", true);
        setOutput("waterEnabled", true);
        setOutput("fogEnabled", true);
        setOutput("atmosphereEnabled", true);
        setOutput("sssEnabled", true);
        setOutput("anisotropicEnabled", true);
        setOutput("pomEnabled", true);
        setOutput("vctEnabled", true);
        setOutput("rtShadowEnabled", true);
        setOutput("ssaoSamples", 64);
        setOutput("lodMaxDistance", 1024.0f);
    }
}
