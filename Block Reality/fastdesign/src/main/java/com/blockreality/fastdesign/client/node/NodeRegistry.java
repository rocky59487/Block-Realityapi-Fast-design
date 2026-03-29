package com.blockreality.fastdesign.client.node;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 節點註冊表 — 設計報告 §12.1 N1-12
 *
 * 靜態註冊所有 144 個節點型別，提供：
 *   - 工廠方法建立節點實例
 *   - 搜尋（英文/中文模糊匹配）
 *   - 按類別分組
 *
 * 註冊在 FastDesignMod 初始化時執行。
 */
public final class NodeRegistry {

    private static final Logger LOGGER = LogManager.getLogger("NodeRegistry");

    private static final Map<String, NodeEntry> REGISTRY = new LinkedHashMap<>();
    private static boolean initialized = false;

    private NodeRegistry() {}

    // ─── 註冊 ───

    /**
     * 註冊一個節點型別。
     *
     * @param typeId  型別 ID，如 "render.preset.QualityPreset"
     * @param factory 工廠函數（如 QualityPresetNode::new）
     * @param displayNameEN 英文名稱
     * @param displayNameCN 中文名稱
     * @param category 類別
     */
    public static void register(String typeId, Supplier<BRNode> factory,
                                 String displayNameEN, String displayNameCN,
                                 String category) {
        if (REGISTRY.containsKey(typeId)) {
            LOGGER.warn("重複註冊節點型別：{}", typeId);
            return;
        }
        REGISTRY.put(typeId, new NodeEntry(typeId, factory, displayNameEN, displayNameCN, category));
    }

    /**
     * 建立節點實例。
     */
    @Nullable
    public static BRNode create(String typeId) {
        NodeEntry entry = REGISTRY.get(typeId);
        if (entry == null) {
            LOGGER.warn("未註冊的節點型別：{}", typeId);
            return null;
        }
        return entry.factory.get();
    }

    // ─── 查詢 ───

    public static Set<String> allTypeIds() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    public static Collection<NodeEntry> allEntries() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static int registeredCount() {
        return REGISTRY.size();
    }

    /**
     * 按類別分組。
     */
    public static Map<String, List<NodeEntry>> byCategory() {
        return REGISTRY.values().stream()
                .collect(Collectors.groupingBy(NodeEntry::category, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 模糊搜尋（支援英文/中文名稱）。
     */
    public static List<NodeEntry> search(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(REGISTRY.values());
        }

        String lower = query.toLowerCase(Locale.ROOT).trim();

        // 類別前綴搜尋（如 "render:bloom"）
        String categoryFilter = null;
        String nameFilter = lower;
        int colonIdx = lower.indexOf(':');
        if (colonIdx > 0) {
            categoryFilter = lower.substring(0, colonIdx);
            nameFilter = lower.substring(colonIdx + 1);
        }

        List<NodeEntry> results = new ArrayList<>();
        String finalCategoryFilter = categoryFilter;
        String finalNameFilter = nameFilter;

        for (NodeEntry entry : REGISTRY.values()) {
            // 類別過濾
            if (finalCategoryFilter != null &&
                    !entry.category.toLowerCase(Locale.ROOT).contains(finalCategoryFilter)) {
                continue;
            }

            // 名稱匹配（英文 + 中文）
            if (finalNameFilter.isEmpty()
                    || entry.displayNameEN.toLowerCase(Locale.ROOT).contains(finalNameFilter)
                    || entry.displayNameCN.contains(finalNameFilter)
                    || entry.typeId.toLowerCase(Locale.ROOT).contains(finalNameFilter)) {
                results.add(entry);
            }
        }

        return results;
    }

    // ─── 批量註冊 ───

    /**
     * ★ review-fix ICReM-8: 實作批量註冊全部 149 個節點。
     * 由 FastDesignMod 在初始化時呼叫。
     *
     * 同時向 API 層的 NodeGraphIO 註冊節點工廠（用於 JSON 反序列化）。
     */
    public static void registerAll() {
        if (initialized) return;
        initialized = true;

        // ═══ Material: Base (14) ═══
        reg("material.base.Concrete",       com.blockreality.fastdesign.client.node.impl.material.base.ConcreteMaterialNode::new,       "Concrete",          "混凝土",     "material");
        reg("material.base.PlainConcrete",  com.blockreality.fastdesign.client.node.impl.material.base.PlainConcreteMaterialNode::new,  "Plain Concrete",    "素混凝土",   "material");
        reg("material.base.Rebar",          com.blockreality.fastdesign.client.node.impl.material.base.RebarMaterialNode::new,          "Rebar",             "鋼筋",       "material");
        reg("material.base.Steel",          com.blockreality.fastdesign.client.node.impl.material.base.SteelMaterialNode::new,          "Steel",             "鋼材",       "material");
        reg("material.base.Timber",         com.blockreality.fastdesign.client.node.impl.material.base.TimberMaterialNode::new,         "Timber",            "木材",       "material");
        reg("material.base.Stone",          com.blockreality.fastdesign.client.node.impl.material.base.StoneMaterialNode::new,          "Stone",             "石材",       "material");
        reg("material.base.Brick",          com.blockreality.fastdesign.client.node.impl.material.base.BrickMaterialNode::new,          "Brick",             "磚塊",       "material");
        reg("material.base.Glass",          com.blockreality.fastdesign.client.node.impl.material.base.GlassMaterialNode::new,          "Glass",             "玻璃",       "material");
        reg("material.base.Sand",           com.blockreality.fastdesign.client.node.impl.material.base.SandMaterialNode::new,           "Sand",              "沙",         "material");
        reg("material.base.Obsidian",       com.blockreality.fastdesign.client.node.impl.material.base.ObsidianMaterialNode::new,       "Obsidian",          "黑曜石",     "material");
        reg("material.base.Bedrock",        com.blockreality.fastdesign.client.node.impl.material.base.BedrockMaterialNode::new,        "Bedrock",           "基岩",       "material");
        reg("material.base.RCNode",         com.blockreality.fastdesign.client.node.impl.material.base.RCNodeMaterialNode::new,         "RC Node",           "RC節點",     "material");
        reg("material.base.Custom",         com.blockreality.fastdesign.client.node.impl.material.base.CustomMaterialNode::new,         "Custom Material",   "自訂材料",   "material");
        reg("material.base.Constant",       com.blockreality.fastdesign.client.node.impl.material.base.MaterialConstantNode::new,       "Material Constant", "材料常數",   "material");

        // ═══ Material: Blending (8) ═══
        reg("material.blend.BlockCreator",   com.blockreality.fastdesign.client.node.impl.material.blending.BlockCreatorNode::new,      "Block Creator",      "方塊建立",   "material");
        reg("material.blend.BlockBlender",   com.blockreality.fastdesign.client.node.impl.material.blending.BlockBlenderNode::new,      "Block Blender",      "方塊混合",   "material");
        reg("material.blend.BlockLibrary",   com.blockreality.fastdesign.client.node.impl.material.blending.BlockLibraryNode::new,      "Block Library",      "方塊庫",     "material");
        reg("material.blend.VanillaPicker",  com.blockreality.fastdesign.client.node.impl.material.blending.VanillaBlockPickerNode::new,"Vanilla Block",      "原版方塊",   "material");
        reg("material.blend.PropertyTuner",  com.blockreality.fastdesign.client.node.impl.material.blending.PropertyTunerNode::new,     "Property Tuner",     "屬性調節",   "material");
        reg("material.blend.RecipeAssigner", com.blockreality.fastdesign.client.node.impl.material.blending.RecipeAssignerNode::new,    "Recipe Assigner",    "配方指定",   "material");
        reg("material.blend.BatchFactory",   com.blockreality.fastdesign.client.node.impl.material.blending.BatchBlockFactoryNode::new, "Batch Factory",      "批量工廠",   "material");
        reg("material.blend.Preview3D",      com.blockreality.fastdesign.client.node.impl.material.blending.BlockPreview3DNode::new,    "3D Preview",         "3D預覽",     "material");

        // ═══ Material: Operation (8) ═══
        reg("material.op.RCFusion",          com.blockreality.fastdesign.client.node.impl.material.operation.RCFusionNode::new,         "RC Fusion",          "RC融合",     "material");
        reg("material.op.MaterialMixer",     com.blockreality.fastdesign.client.node.impl.material.operation.MaterialMixerNode::new,    "Material Mixer",     "材料混合",   "material");
        reg("material.op.MaterialScaler",    com.blockreality.fastdesign.client.node.impl.material.operation.MaterialScalerNode::new,   "Material Scaler",    "材料縮放",   "material");
        reg("material.op.MaterialCompare",   com.blockreality.fastdesign.client.node.impl.material.operation.MaterialCompareNode::new,  "Material Compare",   "材料比較",   "material");
        reg("material.op.MaterialLookup",    com.blockreality.fastdesign.client.node.impl.material.operation.MaterialLookupNode::new,   "Material Lookup",    "材料查詢",   "material");
        reg("material.op.CuringProcess",     com.blockreality.fastdesign.client.node.impl.material.operation.CuringProcessNode::new,    "Curing Process",     "養護過程",   "material");
        reg("material.op.FireResistance",    com.blockreality.fastdesign.client.node.impl.material.operation.FireResistanceNode::new,   "Fire Resistance",    "耐火性",     "material");
        reg("material.op.WeatherDegradation",com.blockreality.fastdesign.client.node.impl.material.operation.WeatherDegradationNode::new,"Weather Degrade",   "風化劣化",   "material");

        // ═══ Material: Shape (6) ═══
        reg("material.shape.Selector",       com.blockreality.fastdesign.client.node.impl.material.shape.ShapeSelectorNode::new,        "Shape Selector",     "形狀選擇",   "material");
        reg("material.shape.Custom",         com.blockreality.fastdesign.client.node.impl.material.shape.CustomShapeNode::new,          "Custom Shape",       "自訂形狀",   "material");
        reg("material.shape.Combine",        com.blockreality.fastdesign.client.node.impl.material.shape.ShapeCombineNode::new,         "Shape Combine",      "形狀組合",   "material");
        reg("material.shape.Mirror",         com.blockreality.fastdesign.client.node.impl.material.shape.ShapeMirrorNode::new,          "Shape Mirror",       "形狀鏡像",   "material");
        reg("material.shape.Rotate",         com.blockreality.fastdesign.client.node.impl.material.shape.ShapeRotateNode::new,          "Shape Rotate",       "形狀旋轉",   "material");
        reg("material.shape.ToMesh",         com.blockreality.fastdesign.client.node.impl.material.shape.ShapeToMeshNode::new,          "Shape to Mesh",      "形狀轉網格", "material");

        // ═══ Material: Visualization (4) ═══
        reg("material.viz.PropertyTable",    com.blockreality.fastdesign.client.node.impl.material.visualization.BlockPropertyTableNode::new, "Property Table",  "屬性表",     "material");
        reg("material.viz.RadarChart",       com.blockreality.fastdesign.client.node.impl.material.visualization.MaterialRadarChartNode::new, "Radar Chart",     "雷達圖",     "material");
        reg("material.viz.Palette",          com.blockreality.fastdesign.client.node.impl.material.visualization.MaterialPaletteNode::new,    "Material Palette","色板",       "material");
        reg("material.viz.StressStrain",     com.blockreality.fastdesign.client.node.impl.material.visualization.StressStrainCurveNode::new,  "Stress-Strain",   "應力應變",   "material");

        // ═══ Physics: Load (7) ═══
        reg("physics.load.Gravity",          com.blockreality.fastdesign.client.node.impl.physics.load.GravityNode::new,                "Gravity",            "重力",       "physics");
        reg("physics.load.Concentrated",     com.blockreality.fastdesign.client.node.impl.physics.load.ConcentratedLoadNode::new,       "Concentrated Load",  "集中荷載",   "physics");
        reg("physics.load.Distributed",      com.blockreality.fastdesign.client.node.impl.physics.load.DistributedLoadNode::new,        "Distributed Load",   "均佈荷載",   "physics");
        reg("physics.load.Wind",             com.blockreality.fastdesign.client.node.impl.physics.load.WindLoadNode::new,               "Wind Load",          "風荷載",     "physics");
        reg("physics.load.Seismic",          com.blockreality.fastdesign.client.node.impl.physics.load.SeismicLoadNode::new,            "Seismic Load",       "地震荷載",   "physics");
        reg("physics.load.Thermal",          com.blockreality.fastdesign.client.node.impl.physics.load.ThermalLoadNode::new,            "Thermal Load",       "溫度荷載",   "physics");
        reg("physics.load.Moment",           com.blockreality.fastdesign.client.node.impl.physics.load.MomentCalculatorNode::new,       "Moment Calculator",  "力矩計算",   "physics");

        // ═══ Physics: Solver (6) ═══
        reg("physics.solver.ForceEq",        com.blockreality.fastdesign.client.node.impl.physics.solver.ForceEquilibriumNode::new,     "Force Equilibrium",  "力平衡",     "physics");
        reg("physics.solver.SupportPath",    com.blockreality.fastdesign.client.node.impl.physics.solver.SupportPathNode::new,          "Support Path",       "支撐路徑",   "physics");
        reg("physics.solver.BeamAnalysis",   com.blockreality.fastdesign.client.node.impl.physics.solver.BeamAnalysisNode::new,         "Beam Analysis",      "梁分析",     "physics");
        reg("physics.solver.CoarseFEM",      com.blockreality.fastdesign.client.node.impl.physics.solver.CoarseFEMNode::new,            "Coarse FEM",         "粗略FEM",    "physics");
        reg("physics.solver.PhysicsLOD",     com.blockreality.fastdesign.client.node.impl.physics.solver.PhysicsLODNode::new,           "Physics LOD",        "物理LOD",    "physics");
        reg("physics.solver.SpatialPart",    com.blockreality.fastdesign.client.node.impl.physics.solver.SpatialPartitionNode::new,     "Spatial Partition",  "空間分割",   "physics");

        // ═══ Physics: Collapse (4) ═══
        reg("physics.collapse.Config",       com.blockreality.fastdesign.client.node.impl.physics.collapse.CollapseConfigNode::new,     "Collapse Config",    "崩塌設定",   "physics");
        reg("physics.collapse.FailureMode",  com.blockreality.fastdesign.client.node.impl.physics.collapse.FailureModeNode::new,        "Failure Mode",       "破壞模式",   "physics");
        reg("physics.collapse.BreakPattern", com.blockreality.fastdesign.client.node.impl.physics.collapse.BreakPatternNode::new,       "Break Pattern",      "斷裂模式",   "physics");
        reg("physics.collapse.Cable",        com.blockreality.fastdesign.client.node.impl.physics.collapse.CableConstraintNode::new,    "Cable Constraint",   "纜索約束",   "physics");

        // ═══ Physics: Result (5) ═══
        reg("physics.result.StressViz",      com.blockreality.fastdesign.client.node.impl.physics.result.StressVisualizerNode::new,     "Stress Visualizer",  "應力視覺化", "physics");
        reg("physics.result.LoadPath",       com.blockreality.fastdesign.client.node.impl.physics.result.LoadPathVisualizerNode::new,   "Load Path Viz",      "載重路徑",   "physics");
        reg("physics.result.Deflection",     com.blockreality.fastdesign.client.node.impl.physics.result.DeflectionMapNode::new,        "Deflection Map",     "撓度圖",     "physics");
        reg("physics.result.Score",          com.blockreality.fastdesign.client.node.impl.physics.result.StructuralScoreNode::new,      "Structural Score",   "結構評分",   "physics");
        reg("physics.result.Utilization",    com.blockreality.fastdesign.client.node.impl.physics.result.UtilizationReportNode::new,    "Utilization Report", "利用率報告", "physics");

        // ═══ Render: Preset (5) ═══
        reg("render.preset.Quality",         com.blockreality.fastdesign.client.node.impl.render.preset.QualityPresetNode::new,         "Quality Preset",     "品質預設",   "render");
        reg("render.preset.TierSelector",    com.blockreality.fastdesign.client.node.impl.render.preset.TierSelectorNode::new,         "Tier Selector",      "級別選擇",   "render");
        reg("render.preset.GPUDetect",       com.blockreality.fastdesign.client.node.impl.render.preset.GPUDetectNode::new,            "GPU Detect",         "GPU偵測",    "render");
        reg("render.preset.PerfTarget",      com.blockreality.fastdesign.client.node.impl.render.preset.PerformanceTargetNode::new,    "Performance Target", "效能目標",   "render");
        reg("render.preset.ABCompare",       com.blockreality.fastdesign.client.node.impl.render.preset.ABCompareNode::new,            "A/B Compare",        "A/B比較",    "render");

        // ═══ Render: PostFX (18) ═══
        reg("render.postfx.SSAO",            com.blockreality.fastdesign.client.node.impl.render.postfx.SSAO_GTAONode::new,            "SSAO (GTAO)",        "環境遮蔽",   "render");
        reg("render.postfx.SSR",             com.blockreality.fastdesign.client.node.impl.render.postfx.SSRNode::new,                  "SSR",                "螢幕反射",   "render");
        reg("render.postfx.SSGI",            com.blockreality.fastdesign.client.node.impl.render.postfx.SSGINode::new,                 "SSGI",               "全局照明",   "render");
        reg("render.postfx.TAA",             com.blockreality.fastdesign.client.node.impl.render.postfx.TAANode::new,                  "TAA",                "時間抗鋸齒", "render");
        reg("render.postfx.Bloom",           com.blockreality.fastdesign.client.node.impl.render.postfx.BloomNode::new,                "Bloom",              "泛光",       "render");
        reg("render.postfx.DOF",             com.blockreality.fastdesign.client.node.impl.render.postfx.DOFNode::new,                  "Depth of Field",     "景深",       "render");
        reg("render.postfx.Volumetric",      com.blockreality.fastdesign.client.node.impl.render.postfx.VolumetricLightNode::new,      "Volumetric Light",   "體積光",     "render");
        reg("render.postfx.ContactShadow",   com.blockreality.fastdesign.client.node.impl.render.postfx.ContactShadowNode::new,       "Contact Shadow",     "接觸陰影",   "render");
        reg("render.postfx.MotionBlur",      com.blockreality.fastdesign.client.node.impl.render.postfx.MotionBlurNode::new,          "Motion Blur",        "動態模糊",   "render");
        reg("render.postfx.VCT",             com.blockreality.fastdesign.client.node.impl.render.postfx.VCT_GINode::new,              "VCT GI",             "體素追蹤",   "render");
        reg("render.postfx.SSS",             com.blockreality.fastdesign.client.node.impl.render.postfx.SSSNode::new,                 "SSS",                "次表面散射", "render");
        reg("render.postfx.Anisotropic",     com.blockreality.fastdesign.client.node.impl.render.postfx.AnisotropicNode::new,         "Anisotropic",        "各向異性",   "render");
        reg("render.postfx.POM",             com.blockreality.fastdesign.client.node.impl.render.postfx.POMNode::new,                 "POM",                "視差遮蔽",   "render");
        reg("render.postfx.Tonemap",         com.blockreality.fastdesign.client.node.impl.render.postfx.TonemapNode::new,             "Tonemap",            "色調映射",   "render");
        reg("render.postfx.ColorGrading",    com.blockreality.fastdesign.client.node.impl.render.postfx.ColorGradingNode::new,        "Color Grading",      "色彩分級",   "render");
        reg("render.postfx.LensFlare",       com.blockreality.fastdesign.client.node.impl.render.postfx.LensFlareNode::new,           "Lens Flare",         "鏡頭光暈",   "render");
        reg("render.postfx.Cinematic",       com.blockreality.fastdesign.client.node.impl.render.postfx.CinematicNode::new,           "Cinematic",          "電影效果",   "render");
        reg("render.postfx.WetPBR",          com.blockreality.fastdesign.client.node.impl.render.postfx.WetPBRNode::new,              "Wet PBR",            "濕潤PBR",    "render");

        // ═══ Render: Lighting (7) ═══
        reg("render.light.Sun",              com.blockreality.fastdesign.client.node.impl.render.lighting.SunLightNode::new,           "Sun Light",          "太陽光",     "render");
        reg("render.light.Point",            com.blockreality.fastdesign.client.node.impl.render.lighting.PointLightNode::new,         "Point Light",        "點光源",     "render");
        reg("render.light.Area",             com.blockreality.fastdesign.client.node.impl.render.lighting.AreaLightNode::new,          "Area Light",         "面光源",     "render");
        reg("render.light.Ambient",          com.blockreality.fastdesign.client.node.impl.render.lighting.AmbientLightNode::new,       "Ambient Light",      "環境光",     "render");
        reg("render.light.Emissive",         com.blockreality.fastdesign.client.node.impl.render.lighting.EmissiveBlockNode::new,      "Emissive Block",     "發光方塊",   "render");
        reg("render.light.Probe",            com.blockreality.fastdesign.client.node.impl.render.lighting.LightProbeNode::new,         "Light Probe",        "光探針",     "render");
        reg("render.light.CSM",              com.blockreality.fastdesign.client.node.impl.render.lighting.CSM_CascadeNode::new,        "CSM Cascade",        "級聯陰影",   "render");

        // ═══ Render: LOD (9) ═══
        reg("render.lod.Config",             com.blockreality.fastdesign.client.node.impl.render.lod.LODConfigNode::new,               "LOD Config",         "LOD設定",    "render");
        reg("render.lod.Level",              com.blockreality.fastdesign.client.node.impl.render.lod.LODLevelNode::new,                "LOD Level",          "LOD層級",    "render");
        reg("render.lod.GreedyMesh",         com.blockreality.fastdesign.client.node.impl.render.lod.GreedyMeshNode::new,             "Greedy Mesh",        "貪婪網格",   "render");
        reg("render.lod.FrustumCull",        com.blockreality.fastdesign.client.node.impl.render.lod.FrustumCullerNode::new,          "Frustum Culler",     "視錐剔除",   "render");
        reg("render.lod.OcclusionCull",      com.blockreality.fastdesign.client.node.impl.render.lod.OcclusionCullerNode::new,        "Occlusion Culler",   "遮擋剔除",   "render");
        reg("render.lod.MeshCache",          com.blockreality.fastdesign.client.node.impl.render.lod.MeshCacheNode::new,              "Mesh Cache",         "網格快取",   "render");
        reg("render.lod.BatchRender",        com.blockreality.fastdesign.client.node.impl.render.lod.BatchRenderNode::new,            "Batch Render",       "批次渲染",   "render");
        reg("render.lod.IndirectDraw",       com.blockreality.fastdesign.client.node.impl.render.lod.IndirectDrawNode::new,           "Indirect Draw",      "間接繪製",   "render");
        reg("render.lod.HiZ",               com.blockreality.fastdesign.client.node.impl.render.lod.HiZConfigNode::new,              "Hi-Z Config",        "Hi-Z設定",   "render");

        // ═══ Render: Pipeline (8) ═══
        reg("render.pipe.Shadow",            com.blockreality.fastdesign.client.node.impl.render.pipeline.ShadowConfigNode::new,       "Shadow Config",      "陰影設定",   "render");
        reg("render.pipe.GBuffer",           com.blockreality.fastdesign.client.node.impl.render.pipeline.GBufferConfigNode::new,      "GBuffer Config",     "GBuffer設定","render");
        reg("render.pipe.Framebuffer",       com.blockreality.fastdesign.client.node.impl.render.pipeline.FramebufferChainNode::new,   "Framebuffer Chain",  "幀緩衝鏈",   "render");
        reg("render.pipe.PipelineOrder",     com.blockreality.fastdesign.client.node.impl.render.pipeline.PipelineOrderNode::new,      "Pipeline Order",     "管線順序",   "render");
        reg("render.pipe.RenderScale",       com.blockreality.fastdesign.client.node.impl.render.pipeline.RenderScaleNode::new,        "Render Scale",       "渲染縮放",   "render");
        reg("render.pipe.VertexFormat",      com.blockreality.fastdesign.client.node.impl.render.pipeline.VertexFormatNode::new,       "Vertex Format",      "頂點格式",   "render");
        reg("render.pipe.VRAM",              com.blockreality.fastdesign.client.node.impl.render.pipeline.VRAMBudgetNode::new,         "VRAM Budget",        "顯存預算",   "render");
        reg("render.pipe.Viewport",          com.blockreality.fastdesign.client.node.impl.render.pipeline.ViewportLayoutNode::new,     "Viewport Layout",    "視口佈局",   "render");

        // ═══ Render: Water (4) ═══
        reg("render.water.Surface",          com.blockreality.fastdesign.client.node.impl.render.water.WaterSurfaceNode::new,          "Water Surface",      "水面",       "render");
        reg("render.water.Caustics",         com.blockreality.fastdesign.client.node.impl.render.water.WaterCausticsNode::new,         "Water Caustics",     "水焦散",     "render");
        reg("render.water.Foam",             com.blockreality.fastdesign.client.node.impl.render.water.WaterFoamNode::new,             "Water Foam",         "水泡沫",     "render");
        reg("render.water.Underwater",       com.blockreality.fastdesign.client.node.impl.render.water.UnderwaterNode::new,            "Underwater",         "水下",       "render");

        // ═══ Render: Weather (6) ═══
        reg("render.weather.Cloud",          com.blockreality.fastdesign.client.node.impl.render.weather.CloudNode::new,               "Cloud",              "雲",         "render");
        reg("render.weather.Fog",            com.blockreality.fastdesign.client.node.impl.render.weather.FogNode::new,                 "Fog",                "霧",         "render");
        reg("render.weather.Rain",           com.blockreality.fastdesign.client.node.impl.render.weather.RainNode::new,                "Rain",               "雨",         "render");
        reg("render.weather.Snow",           com.blockreality.fastdesign.client.node.impl.render.weather.SnowNode::new,                "Snow",               "雪",         "render");
        reg("render.weather.Atmosphere",     com.blockreality.fastdesign.client.node.impl.render.weather.AtmosphereNode::new,          "Atmosphere",         "大氣",       "render");
        reg("render.weather.Aurora",         com.blockreality.fastdesign.client.node.impl.render.weather.AuroraNode::new,              "Aurora",             "極光",       "render");

        // ═══ Tool: Input (4) ═══
        reg("tool.input.Mouse",              com.blockreality.fastdesign.client.node.impl.tool.input.MouseConfigNode::new,             "Mouse Config",       "滑鼠設定",   "tool");
        reg("tool.input.Keyboard",           com.blockreality.fastdesign.client.node.impl.tool.input.KeyBindingsNode::new,             "Key Bindings",       "按鍵綁定",   "tool");
        reg("tool.input.Gamepad",            com.blockreality.fastdesign.client.node.impl.tool.input.GamepadConfigNode::new,           "Gamepad Config",     "手把設定",   "tool");
        reg("tool.input.Gesture",            com.blockreality.fastdesign.client.node.impl.tool.input.GestureConfigNode::new,           "Gesture Config",     "手勢設定",   "tool");

        // ═══ Tool: Placement (5) ═══
        reg("tool.place.BuildMode",          com.blockreality.fastdesign.client.node.impl.tool.placement.BuildModeNode::new,           "Build Mode",         "建造模式",   "tool");
        reg("tool.place.GhostBlock",         com.blockreality.fastdesign.client.node.impl.tool.placement.GhostBlockNode::new,          "Ghost Block",        "幽靈方塊",   "tool");
        reg("tool.place.BatchOp",            com.blockreality.fastdesign.client.node.impl.tool.placement.BatchOpNode::new,             "Batch Operation",    "批量操作",   "tool");
        reg("tool.place.Blueprint",          com.blockreality.fastdesign.client.node.impl.tool.placement.BlueprintPlaceNode::new,      "Blueprint Place",    "藍圖放置",   "tool");
        reg("tool.place.QuickPlacer",        com.blockreality.fastdesign.client.node.impl.tool.placement.QuickPlacerNode::new,         "Quick Placer",       "快速放置",   "tool");

        // ═══ Tool: Selection (7) ═══
        reg("tool.select.Config",            com.blockreality.fastdesign.client.node.impl.tool.selection.SelectionConfigNode::new,     "Selection Config",   "選取設定",   "tool");
        reg("tool.select.Filter",            com.blockreality.fastdesign.client.node.impl.tool.selection.SelectionFilterNode::new,     "Selection Filter",   "選取過濾",   "tool");
        reg("tool.select.Viz",               com.blockreality.fastdesign.client.node.impl.tool.selection.SelectionVizNode::new,        "Selection Viz",      "選取視覺化", "tool");
        reg("tool.select.Export",            com.blockreality.fastdesign.client.node.impl.tool.selection.SelectionExportNode::new,     "Selection Export",    "選取匯出",   "tool");
        reg("tool.select.Brush",             com.blockreality.fastdesign.client.node.impl.tool.selection.BrushConfigNode::new,         "Brush Config",       "筆刷設定",   "tool");
        reg("tool.select.Predicate",         com.blockreality.fastdesign.client.node.impl.tool.selection.CompoundPredicateNode::new,   "Compound Predicate", "複合條件",   "tool");
        reg("tool.select.ToolMask",          com.blockreality.fastdesign.client.node.impl.tool.selection.ToolMaskNode::new,            "Tool Mask",          "工具遮罩",   "tool");

        // ═══ Tool: UI (6) ═══
        reg("tool.ui.HUD",                  com.blockreality.fastdesign.client.node.impl.tool.ui.HUDLayoutNode::new,                  "HUD Layout",         "HUD佈局",    "tool");
        reg("tool.ui.Crosshair",            com.blockreality.fastdesign.client.node.impl.tool.ui.CrosshairNode::new,                  "Crosshair",          "準心",       "tool");
        reg("tool.ui.RadialMenu",           com.blockreality.fastdesign.client.node.impl.tool.ui.RadialMenuNode::new,                 "Radial Menu",        "輻射選單",   "tool");
        reg("tool.ui.Theme",                com.blockreality.fastdesign.client.node.impl.tool.ui.ThemeColorNode::new,                  "Theme Color",        "主題色",     "tool");
        reg("tool.ui.Font",                 com.blockreality.fastdesign.client.node.impl.tool.ui.FontConfigNode::new,                  "Font Config",        "字體設定",   "tool");
        reg("tool.ui.Hologram",             com.blockreality.fastdesign.client.node.impl.tool.ui.HologramStyleNode::new,               "Hologram Style",     "全息風格",   "tool");

        // ═══ Output: Export (3) ═══
        reg("output.export.Config",          com.blockreality.fastdesign.client.node.impl.output.export.ConfigExportNode::new,         "Config Export",      "設定匯出",   "output");
        reg("output.export.NodeGraph",       com.blockreality.fastdesign.client.node.impl.output.export.NodeGraphExportNode::new,      "Graph Export",       "圖譜匯出",   "output");
        reg("output.export.Preset",          com.blockreality.fastdesign.client.node.impl.output.export.PresetExportNode::new,         "Preset Export",      "預設匯出",   "output");

        // ═══ Output: Monitor (5) ═══
        reg("output.monitor.GPU",            com.blockreality.fastdesign.client.node.impl.output.monitor.GPUProfilerNode::new,         "GPU Profiler",       "GPU分析器",  "output");
        reg("output.monitor.Memory",         com.blockreality.fastdesign.client.node.impl.output.monitor.MemoryProfilerNode::new,      "Memory Profiler",    "記憶體分析", "output");
        reg("output.monitor.Network",        com.blockreality.fastdesign.client.node.impl.output.monitor.NetworkProfilerNode::new,     "Network Profiler",   "網路分析",   "output");
        reg("output.monitor.Pass",           com.blockreality.fastdesign.client.node.impl.output.monitor.PassProfilerNode::new,        "Pass Profiler",      "Pass分析",   "output");
        reg("output.monitor.Physics",        com.blockreality.fastdesign.client.node.impl.output.monitor.PhysicsProfilerNode::new,     "Physics Profiler",   "物理分析",   "output");

        // ─── 同步註冊到 API 層 NodeGraphIO ───
        syncToApiNodeGraphIO();

        LOGGER.info("[NodeRegistry] 節點註冊完成，共 {} 種型別", REGISTRY.size());
    }

    /** 縮寫輔助方法 */
    private static void reg(String id, Supplier<BRNode> factory, String en, String cn, String cat) {
        register(id, factory, en, cn, cat);
    }

    /**
     * ★ ICReM-8: 將所有已註冊的節點同步到 API 層 NodeGraphIO，
     * 使 JSON 反序列化能找到對應的工廠函數。
     */
    private static void syncToApiNodeGraphIO() {
        for (NodeEntry entry : REGISTRY.values()) {
            com.blockreality.api.node.NodeGraphIO.registerNodeType(
                entry.typeId(), () -> {
                    // 建立 fastdesign BRNode 後包裝為 api BRNode 的代理
                    // 由於兩個 BRNode 類不同（api vs fastdesign），這裡直接用 typeId 做工廠映射
                    // 實際的反序列化在 fastdesign 的 NodeGraphIO 中處理
                    return null; // API 層反序列化由 fastdesign NodeGraphIO 接管
                }
            );
        }
        LOGGER.info("[NodeRegistry] 已同步 {} 個節點型別到 API NodeGraphIO", REGISTRY.size());
    }

    // ─── 資料結構 ───

    public static final class NodeEntry {
        private final String typeId;
        private final Supplier<BRNode> factory;
        private final String displayNameEN;
        private final String displayNameCN;
        private final String category;

        NodeEntry(String typeId, Supplier<BRNode> factory,
                  String displayNameEN, String displayNameCN, String category) {
            this.typeId = typeId;
            this.factory = factory;
            this.displayNameEN = displayNameEN;
            this.displayNameCN = displayNameCN;
            this.category = category;
        }

        public String typeId()        { return typeId; }
        public String displayNameEN() { return displayNameEN; }
        public String displayNameCN() { return displayNameCN; }
        public String category()      { return category; }

        public BRNode createInstance() { return factory.get(); }
    }
}
