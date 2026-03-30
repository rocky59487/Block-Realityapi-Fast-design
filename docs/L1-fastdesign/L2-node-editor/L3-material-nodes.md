# 材料節點

> 所屬：L1-fastdesign > L2-node-editor

## 概述

材料節點（Category B）涵蓋基礎材料定義、混合與方塊建立、材料操作、形狀處理及視覺化五大子分類，共 35+ 個節點。

## 基礎材料 (base/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `ConcreteMaterialNode` | material.base.Concrete | 混凝土（RC 主材） |
| `RebarMaterialNode` | material.base.Rebar | 鋼筋 |
| `SteelMaterialNode` | material.base.Steel | 鋼材 |
| `TimberMaterialNode` | material.base.Timber | 木材 |
| `BrickMaterialNode` | material.base.Brick | 磚塊 |
| `GlassMaterialNode` | material.base.Glass | 玻璃 |
| `StoneMaterialNode` | material.base.Stone | 石材 |
| `SandMaterialNode` | material.base.Sand | 砂 |
| `ObsidianMaterialNode` | material.base.Obsidian | 黑曜石 |
| `BedrockMaterialNode` | material.base.Bedrock | 基岩 |
| `PlainConcreteMaterialNode` | material.base.PlainConcrete | 素混凝土 |
| `RCNodeMaterialNode` | material.base.RCNode | RC 節點材料 |
| `MaterialConstantNode` | material.base.MaterialConstant | 材料常數查詢 |
| `CustomMaterialNode` | material.base.CustomMaterial | 自訂材料（Builder 模式） |

## 混合與方塊建立 (blending/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `BlockBlenderNode` | material.blending.BlockBlender | 方塊混合器 |
| `BlockCreatorNode` | material.blending.BlockCreator | 自訂方塊建立（輸出 BRBlockDef） |
| `BlockLibraryNode` | material.blending.BlockLibrary | 方塊庫瀏覽 |
| `BlockPreview3DNode` | material.blending.BlockPreview3D | 方塊 3D 預覽 |
| `BatchBlockFactoryNode` | material.blending.BatchBlockFactory | 批量方塊工廠 |
| `PropertyTunerNode` | material.blending.PropertyTuner | 屬性微調器 |
| `RecipeAssignerNode` | material.blending.RecipeAssigner | 配方指定 |
| `VanillaBlockPickerNode` | material.blending.VanillaBlockPicker | 原版方塊選擇器 |

## 材料操作 (operation/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `RCFusionNode` | material.operation.RCFusion | RC 融合（混凝土+鋼筋 97/3） |
| `MaterialMixerNode` | material.operation.MaterialMixer | 材料混合 |
| `MaterialScalerNode` | material.operation.MaterialScaler | 材料屬性縮放 |
| `MaterialCompareNode` | material.operation.MaterialCompare | 材料比較 |
| `MaterialLookupNode` | material.operation.MaterialLookup | 材料查詢 |
| `CuringProcessNode` | material.operation.CuringProcess | 養護過程模擬 |
| `FireResistanceNode` | material.operation.FireResistance | 耐火性計算 |
| `WeatherDegradationNode` | material.operation.WeatherDegradation | 風化降級 |

## 形狀處理 (shape/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `ShapeSelectorNode` | material.shape.ShapeSelector | 形狀選擇器 |
| `CustomShapeNode` | material.shape.CustomShape | 自訂形狀 |
| `ShapeCombineNode` | material.shape.ShapeCombine | 形狀合併 |
| `ShapeMirrorNode` | material.shape.ShapeMirror | 形狀鏡像 |
| `ShapeRotateNode` | material.shape.ShapeRotate | 形狀旋轉 |
| `ShapeToMeshNode` | material.shape.ShapeToMesh | 形狀轉網格 |

## 視覺化 (visualization/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `MaterialRadarChartNode` | material.viz.MaterialRadarChart | 材料雷達圖 |
| `StressStrainCurveNode` | material.viz.StressStrainCurve | 應力-應變曲線 |
| `MaterialPaletteNode` | material.viz.MaterialPalette | 材料調色盤 |
| `BlockPropertyTableNode` | material.viz.BlockPropertyTable | 方塊屬性表 |

## 關聯接口
- 依賴 → API 層 `DefaultMaterial`、`CustomMaterial.Builder`、`DynamicMaterial`
- 被依賴 ← [MaterialBinder](L3-binding.md)（將材料節點值推送到 runtime）
