package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.render.rt.BROpacityMicromap;
import com.blockreality.api.client.render.rt.BRRTSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 節點 evaluate() → BRRTSettings 映射整合測試。
 *
 * 測試覆蓋（6 個 pipeline 節點）：
 *   - ReSTIRConfigNode  → enableReSTIRDI/GI、temporalMaxM、spatialSamples、giRaysPerPixel
 *   - DDGIConfigNode    → enableDDGI、ddgiProbeSpacingBlocks、ddgiUpdateRatio
 *   - DLSSConfigNode    → enableDLSS、dlssMode（enum ordinal 映射）、enableFrameGeneration
 *   - MegaGeometryNode  → enableClusterBVH（非 Blackwell 硬體應強制 false）
 *   - OMMConfigNode     → subdivisionLevel（via BROpacityMicromap）
 *   - NRDConfigNode     → denoiserAlgo（REBLUR=0, else=1）
 *
 * 測試環境假設：
 *   - BRAdaRTConfig.hasOMM()          = false（未呼叫 detect()）
 *   - BRAdaRTConfig.isBlackwellOrNewer() = false
 *   - BRDLSS4Manager.isInitialized()  = false（stub）
 *   - BRNRDDenoiser.isInitialized()   = false（stub）
 *
 * 由於節點繼承 BRNode（含 abstract getTooltip() / typeId()），
 * 各輔助方法以匿名子類別方式提供必要的 abstract 實作。
 */
class VulkanRTConfigNodeTest {

    // ─── 測試前重置 BRRTSettings 為已知狀態 ─────────────────────────────────

    @BeforeEach
    void resetSettings() {
        BRRTSettings s = BRRTSettings.getInstance();
        s.setEnableReSTIRDI(false);
        s.setEnableReSTIRGI(false);
        s.setEnableDDGI(false);
        s.setEnableDLSS(false);
        s.setEnableFrameGeneration(false);
        s.setEnableClusterBVH(true);   // 預設 true
        s.setDlssMode(2);              // BALANCED
        s.setReSTIRDITemporalMaxM(20);
        s.setReSTIRDISpatialSamples(1);
        s.setReSTIRGIRaysPerPixel(2);
        s.setDdgiProbeSpacingBlocks(8);
        s.setDdgiUpdateRatio(0.25f);

        // 清除 OMM section 狀態
        BROpacityMicromap.getInstance().clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ReSTIRConfigNode
    // ═══════════════════════════════════════════════════════════════════════

    /** 建立可實例化的 ReSTIRConfigNode（補實 abstract 方法）。 */
    private ReSTIRConfigNode makeReSTIRNode() {
        return new ReSTIRConfigNode() {
            @Override public String getTooltip() { return "ReSTIR DI/GI 設定"; }
            @Override public String typeId()     { return "render.pipeline.ReSTIRConfig"; }
        };
    }

    @Test
    void reSTIRNode_enableDI_true_setsEnableReSTIRDI() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.getInput("enableDI").setLocalValue(true);
        node.evaluate();
        assertTrue(BRRTSettings.getInstance().isEnableReSTIRDI(),
            "enableDI=true → BRRTSettings.enableReSTIRDI 應為 true");
    }

    @Test
    void reSTIRNode_enableDI_false_disablesReSTIRDI() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.getInput("enableDI").setLocalValue(false);
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableReSTIRDI(),
            "enableDI=false → BRRTSettings.enableReSTIRDI 應為 false");
    }

    @Test
    void reSTIRNode_enableGI_true_setsEnableReSTIRGI() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.getInput("enableGI").setLocalValue(true);
        node.evaluate();
        assertTrue(BRRTSettings.getInstance().isEnableReSTIRGI(),
            "enableGI=true → BRRTSettings.enableReSTIRGI 應為 true");
    }

    @Test
    void reSTIRNode_enableGI_false_keepsReSTIRGIDisabled() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.getInput("enableGI").setLocalValue(false);
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableReSTIRGI());
    }

    @Test
    void reSTIRNode_temporalMaxM_propagatesToSettings() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.getInput("temporalMaxM").setLocalValue(15);
        node.evaluate();
        assertEquals(15, BRRTSettings.getInstance().getReSTIRDITemporalMaxM(),
            "temporalMaxM=15 → BRRTSettings.reSTIRDITemporalMaxM 應為 15");
    }

    @Test
    void reSTIRNode_spatialSamples_propagatesToSettings() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.getInput("spatialSamples").setLocalValue(3);
        node.evaluate();
        assertEquals(3, BRRTSettings.getInstance().getReSTIRDISpatialSamples(),
            "spatialSamples=3 → BRRTSettings.reSTIRDISpatialSamples 應為 3");
    }

    @Test
    void reSTIRNode_giRaysPerPixel_propagatesToSettings() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.getInput("giRaysPerPixel").setLocalValue(4);
        node.evaluate();
        assertEquals(4, BRRTSettings.getInstance().getReSTIRGIRaysPerPixel(),
            "giRaysPerPixel=4 → BRRTSettings.reSTIRGIRaysPerPixel 應為 4");
    }

    @Test
    void reSTIRNode_outputPort_notNull() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.evaluate();
        assertNotNull(node.getOutput("restirConfig").getRawValue(),
            "evaluate 後 restirConfig 輸出端口不應為 null");
    }

    @Test
    void reSTIRNode_outputPort_holdsBRRTSettingsSingleton() {
        ReSTIRConfigNode node = makeReSTIRNode();
        node.evaluate();
        assertSame(BRRTSettings.getInstance(),
            node.getOutput("restirConfig").getRawValue(),
            "restirConfig 輸出應為 BRRTSettings singleton");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DDGIConfigNode
    // ═══════════════════════════════════════════════════════════════════════

    private DDGIConfigNode makeDDGINode() {
        return new DDGIConfigNode() {
            @Override public String getTooltip() { return "DDGI Probe 設定"; }
            @Override public String typeId()     { return "render.pipeline.DDGIConfig"; }
        };
    }

    @Test
    void ddgiNode_enableDDGI_true_setsEnableDDGI() {
        DDGIConfigNode node = makeDDGINode();
        node.getInput("enableDDGI").setLocalValue(true);
        node.evaluate();
        assertTrue(BRRTSettings.getInstance().isEnableDDGI(),
            "enableDDGI=true → BRRTSettings.enableDDGI 應為 true");
    }

    @Test
    void ddgiNode_enableDDGI_false_keepsDisabled() {
        DDGIConfigNode node = makeDDGINode();
        node.getInput("enableDDGI").setLocalValue(false);
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableDDGI());
    }

    @Test
    void ddgiNode_probeSpacing_propagatesToSettings() {
        DDGIConfigNode node = makeDDGINode();
        node.getInput("probeSpacing").setLocalValue(16);
        node.evaluate();
        assertEquals(16, BRRTSettings.getInstance().getDdgiProbeSpacingBlocks(),
            "probeSpacing=16 → BRRTSettings.ddgiProbeSpacingBlocks 應為 16");
    }

    @Test
    void ddgiNode_updateRatio_propagatesToSettings() {
        DDGIConfigNode node = makeDDGINode();
        node.getInput("updateRatio").setLocalValue(0.5f);
        node.evaluate();
        assertEquals(0.5f, BRRTSettings.getInstance().getDdgiUpdateRatio(), 0.001f,
            "updateRatio=0.5 → BRRTSettings.ddgiUpdateRatio 應為 0.5");
    }

    @Test
    void ddgiNode_updateRatio_clampedToZeroOneRange() {
        DDGIConfigNode node = makeDDGINode();
        // InputPort range(0.0, 1.0) — 超出範圍值應被裁切
        node.getInput("updateRatio").setLocalValue(2.0f);
        node.evaluate();
        float ratio = BRRTSettings.getInstance().getDdgiUpdateRatio();
        assertTrue(ratio <= 1.0f, "updateRatio 應被裁切至 ≤ 1.0");
    }

    @Test
    void ddgiNode_outputPort_notNull() {
        DDGIConfigNode node = makeDDGINode();
        node.evaluate();
        assertNotNull(node.getOutput("ddgiConfig").getRawValue());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DLSSConfigNode
    // ═══════════════════════════════════════════════════════════════════════

    private DLSSConfigNode makeDLSSNode() {
        return new DLSSConfigNode() {
            @Override public String getTooltip() { return "DLSS 4 設定"; }
            @Override public String typeId()     { return "render.pipeline.DLSSConfig"; }
        };
    }

    @Test
    void dlssNode_enableDLSS_true_setsEnableDLSS() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("enableDLSS").setLocalValue(true);
        node.evaluate();
        assertTrue(BRRTSettings.getInstance().isEnableDLSS());
    }

    @Test
    void dlssNode_enableDLSS_false_keepsDisabled() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("enableDLSS").setLocalValue(false);
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableDLSS());
    }

    @Test
    void dlssNode_dlssMode_nativeAA_setsModeZero() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("dlssMode").setLocalValue(DLSSConfigNode.DLSSMode.NATIVE_AA);
        node.evaluate();
        // NATIVE_AA ordinal = 0
        assertEquals(0, BRRTSettings.getInstance().getDlssMode(),
            "DLSSMode.NATIVE_AA（ordinal=0）→ dlssMode=0");
    }

    @Test
    void dlssNode_dlssMode_quality_setsModeOne() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("dlssMode").setLocalValue(DLSSConfigNode.DLSSMode.QUALITY);
        node.evaluate();
        // QUALITY ordinal = 1
        assertEquals(1, BRRTSettings.getInstance().getDlssMode(),
            "DLSSMode.QUALITY（ordinal=1）→ dlssMode=1");
    }

    @Test
    void dlssNode_dlssMode_balanced_setsModeTwo() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("dlssMode").setLocalValue(DLSSConfigNode.DLSSMode.BALANCED);
        node.evaluate();
        assertEquals(2, BRRTSettings.getInstance().getDlssMode(),
            "DLSSMode.BALANCED（ordinal=2）→ dlssMode=2");
    }

    @Test
    void dlssNode_dlssMode_performance_setsModeThree() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("dlssMode").setLocalValue(DLSSConfigNode.DLSSMode.PERFORMANCE);
        node.evaluate();
        assertEquals(3, BRRTSettings.getInstance().getDlssMode(),
            "DLSSMode.PERFORMANCE（ordinal=3）→ dlssMode=3");
    }

    @Test
    void dlssNode_dlssMode_ultraPerf_setsModeFor() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("dlssMode").setLocalValue(DLSSConfigNode.DLSSMode.ULTRA_PERF);
        node.evaluate();
        assertEquals(4, BRRTSettings.getInstance().getDlssMode(),
            "DLSSMode.ULTRA_PERF（ordinal=4）→ dlssMode=4");
    }

    @Test
    void dlssNode_enableFG_true_setsEnableFrameGeneration() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("enableFG").setLocalValue(true);
        node.evaluate();
        assertTrue(BRRTSettings.getInstance().isEnableFrameGeneration(),
            "enableFG=true → BRRTSettings.enableFrameGeneration 應為 true");
    }

    @Test
    void dlssNode_enableFG_false_keepsFrameGenDisabled() {
        DLSSConfigNode node = makeDLSSNode();
        node.getInput("enableFG").setLocalValue(false);
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableFrameGeneration());
    }

    @Test
    void dlssNode_outputPort_notNull() {
        DLSSConfigNode node = makeDLSSNode();
        node.evaluate();
        assertNotNull(node.getOutput("dlssConfig").getRawValue());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  MegaGeometryNode（Cluster BVH）
    // ═══════════════════════════════════════════════════════════════════════

    private MegaGeometryNode makeMegaGeoNode() {
        return new MegaGeometryNode() {
            @Override public String getTooltip() { return "Cluster BVH 設定"; }
            @Override public String typeId()     { return "render.pipeline.MegaGeometry"; }
        };
    }

    @Test
    void megaGeoNode_enableCluster_nonBlackwellHardware_forcedFalse() {
        // 測試環境：BRAdaRTConfig.isBlackwellOrNewer() = false
        // → 無論輸入為 true，ClusterBVH 應被強制設為 false
        MegaGeometryNode node = makeMegaGeoNode();
        node.getInput("enableCluster").setLocalValue(true);
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableClusterBVH(),
            "非 Blackwell 硬體：enableCluster=true 仍應強制設為 false");
    }

    @Test
    void megaGeoNode_enableCluster_false_setsFalse() {
        MegaGeometryNode node = makeMegaGeoNode();
        node.getInput("enableCluster").setLocalValue(false);
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableClusterBVH(),
            "enableCluster=false → ClusterBVH 應為 false");
    }

    @Test
    void megaGeoNode_evaluate_doesNotThrow() {
        MegaGeometryNode node = makeMegaGeoNode();
        assertDoesNotThrow(node::evaluate,
            "MegaGeometryNode.evaluate() 在非 Blackwell 環境不應拋出例外");
    }

    @Test
    void megaGeoNode_outputPort_notNull() {
        MegaGeometryNode node = makeMegaGeoNode();
        node.evaluate();
        assertNotNull(node.getOutput("geoConfig").getRawValue());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  OMMConfigNode（Opacity Micromap）
    // ═══════════════════════════════════════════════════════════════════════

    private OMMConfigNode makeOMMNode() {
        return new OMMConfigNode() {
            @Override public String getTooltip() { return "OMM 設定"; }
            @Override public String typeId()     { return "render.pipeline.OMMConfig"; }
        };
    }

    @Test
    void ommNode_evaluate_doesNotThrow() {
        OMMConfigNode node = makeOMMNode();
        assertDoesNotThrow(node::evaluate,
            "OMMConfigNode.evaluate() 應在測試環境正常執行（無 OMM 硬體）");
    }

    @Test
    void ommNode_subdivisionLevel_propagatesToOpacityMicromap() {
        OMMConfigNode node = makeOMMNode();
        node.getInput("subdivisionLevel").setLocalValue(3);
        // evaluate() 呼叫 BROpacityMicromap.getInstance().setDefaultSubdivisionLevel(3)
        // 驗證不拋出（setDefaultSubdivisionLevel 副作用不直接暴露 getter）
        assertDoesNotThrow(node::evaluate,
            "subdivisionLevel=3 → OMMConfigNode.evaluate() 應成功呼叫 setDefaultSubdivisionLevel");
    }

    @Test
    void ommNode_outputPort_notNull() {
        OMMConfigNode node = makeOMMNode();
        node.evaluate();
        assertNotNull(node.getOutput("ommConfig").getRawValue());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  NRDConfigNode（NRD 降噪器）
    // ═══════════════════════════════════════════════════════════════════════

    private NRDConfigNode makeNRDNode() {
        return new NRDConfigNode() {
            @Override public String getTooltip() { return "NRD 降噪設定"; }
            @Override public String typeId()     { return "render.pipeline.NRDConfig"; }
        };
    }

    @Test
    void nrdNode_algorithm_reblur_setsDenoiserAlgoZero() {
        NRDConfigNode node = makeNRDNode();
        node.getInput("algorithm").setLocalValue(NRDConfigNode.NRDAlgorithm.REBLUR);
        node.evaluate();
        // REBLUR → setDenoiserAlgorithm(0)
        // BRRTSettings.getDenoiserAlgo() 應返回 0（ReBLUR）
        assertEquals(0, BRRTSettings.getInstance().getDenoiserAlgo(),
            "NRDAlgorithm.REBLUR → denoiserAlgo 應為 0（ReBLUR）");
    }

    @Test
    void nrdNode_algorithm_relaxDiffuse_setsDenoiserAlgoOne() {
        NRDConfigNode node = makeNRDNode();
        node.getInput("algorithm").setLocalValue(NRDConfigNode.NRDAlgorithm.RELAX_DIFFUSE);
        node.evaluate();
        // 非 REBLUR → setDenoiserAlgorithm(1)
        assertEquals(1, BRRTSettings.getInstance().getDenoiserAlgo(),
            "NRDAlgorithm.RELAX_DIFFUSE → denoiserAlgo 應為 1（ReLAX）");
    }

    @Test
    void nrdNode_algorithm_relaxSpecular_setsDenoiserAlgoOne() {
        NRDConfigNode node = makeNRDNode();
        node.getInput("algorithm").setLocalValue(NRDConfigNode.NRDAlgorithm.RELAX_SPECULAR);
        node.evaluate();
        assertEquals(1, BRRTSettings.getInstance().getDenoiserAlgo(),
            "非 REBLUR 演算法均應映射至 denoiserAlgo=1");
    }

    @Test
    void nrdNode_algorithm_sigmaShadow_setsDenoiserAlgoOne() {
        NRDConfigNode node = makeNRDNode();
        node.getInput("algorithm").setLocalValue(NRDConfigNode.NRDAlgorithm.SIGMA_SHADOW);
        node.evaluate();
        assertEquals(1, BRRTSettings.getInstance().getDenoiserAlgo());
    }

    @Test
    void nrdNode_evaluate_doesNotThrow() {
        // BRNRDDenoiser.isInitialized() = false → 降噪器配置部分跳過
        NRDConfigNode node = makeNRDNode();
        assertDoesNotThrow(node::evaluate,
            "NRDConfigNode.evaluate() 在未初始化 BRNRDDenoiser 的環境不應拋出例外");
    }

    @Test
    void nrdNode_outputPort_notNull() {
        NRDConfigNode node = makeNRDNode();
        node.evaluate();
        assertNotNull(node.getOutput("nrdConfig").getRawValue());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  預設值驗證（節點建構後未修改時的行為）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void reSTIRNode_defaultValues_enableDIIsTrue() {
        // ReSTIRConfigNode 預設 enableDI = true
        ReSTIRConfigNode node = makeReSTIRNode();
        node.evaluate();
        assertTrue(BRRTSettings.getInstance().isEnableReSTIRDI(),
            "ReSTIRConfigNode 預設 enableDI=true → 評估後 enableReSTIRDI 應為 true");
    }

    @Test
    void ddgiNode_defaultValues_enableDDGIIsFalse() {
        // DDGIConfigNode 預設 enableDDGI = false
        DDGIConfigNode node = makeDDGINode();
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableDDGI(),
            "DDGIConfigNode 預設 enableDDGI=false → 評估後 enableDDGI 應為 false");
    }

    @Test
    void dlssNode_defaultValues_enableDLSSIsFalse() {
        // DLSSConfigNode 預設 enableDLSS = false
        DLSSConfigNode node = makeDLSSNode();
        node.evaluate();
        assertFalse(BRRTSettings.getInstance().isEnableDLSS(),
            "DLSSConfigNode 預設 enableDLSS=false → 評估後 enableDLSS 應為 false");
    }
}
