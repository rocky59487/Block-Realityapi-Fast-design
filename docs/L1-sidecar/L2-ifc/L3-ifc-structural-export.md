# IFC 結構匯出邏輯

> 所屬：L1-sidecar / L2-ifc / L3-ifc-structural-export

## 概述

將 `BlueprintBlock[]` 轉換為 IFC 4 結構模型的主邏輯。包含鄰接分析元素分類、材料資料庫對映、IFC 空間階層建立和幾何生成。

## 關鍵類別

| 類別 / 介面 | 說明 |
|------------|------|
| `Ifc4ExportOptions` | 匯出選項（outputPath, projectName, authorOrg, includeGeometry） |
| `Ifc4ExportResult` | 匯出統計（elementCount, columnCount, maxUtilization, 等） |
| `MaterialDef` | 材料定義（Rcomp MPa, Rtens MPa, E GPa, 密度 kg/m³, 預設角色） |
| `StructuralRole` | `'COLUMN' | 'BEAM' | 'WALL' | 'SLAB' | 'GENERIC'` |
| `IfcContext` | IFC 空間階層共享實體 ID（ownerHistoryId, storeyId, axisZId 等） |

## 核心函式

| 函式 | 簽名 | 說明 |
|------|------|------|
| `exportToIfc4` | `(blocks: BlueprintBlock[], options: Ifc4ExportOptions) → Promise<Ifc4ExportResult>` | 主匯出入口，寫出 .ifc 檔案 |
| `classifyBlocks` | `(blocks) → StructuralRole[]` | 基於鄰接分析的元素角色分類 |
| `buildSpatialHierarchy` | `(w, projectName) → IfcContext` | 建立 IFCPROJECT→IFCSITE→IFCBUILDING→IFCBUILDINGSTOREY 階層 |
| `buildMaterial` | `(w, matDef, ownerHistoryId) → [matId, propsId]` | 生成 IFCMATERIAL + IFCMATERIALPROPERTIES |
| `buildBlockGeometry` | `(w, x, y, z, blockSize, ...) → shapeRepId` | IFCEXTRUDEDAREASOLID 1×1×1m 方塊幾何 |
| `buildStructuralPset` | `(w, ownerHistoryId, stressLevel, isAnchored, elementId) → void` | Pset_BlockReality_Structural 屬性集 |
| `getMaterialDef` | `(materialId: string) → MaterialDef` | 材料資料庫查找，未知材料回退至 'default' |

## 元素分類規則

| 條件 | 分類 |
|------|------|
| `isAnchored === true` | COLUMN |
| 垂直鄰居 ≥ 1，水平鄰居 = 0 | COLUMN |
| 水平 X 或 Z 單軸連接，無垂直 | BEAM |
| 水平多方向，無垂直 | SLAB |
| 垂直連接 > 水平連接 | COLUMN |
| 水平連接 ≥ 2 | WALL |
| 其他 | 材料預設角色 |

## 匯出流程

1. `buildSpatialHierarchy` — 建立 IFC 空間架構（IFCPROJECT → IFCBUILDINGSTOREY）
2. 依材料分組 → 逐材料呼叫 `buildMaterial` → 生成 `IFCMATERIAL` + `IFCMATERIALPROPERTIES`
3. `classifyBlocks` — 使用 O(N) HashSet 鄰接分析分類所有方塊
4. 逐方塊生成 IFC 結構元素（IFCCOLUMN / IFCBEAM / IFCWALL / IFCSLAB）
5. 逐元素呼叫 `buildStructuralPset` — `Pset_BlockReality_Structural`
6. `IFCRELCONTAINEDINSPATIALSTRUCTURE` — 元素關聯至樓層
7. `IFCRELASSOCIATESMATERIAL` — 元素關聯至材料
8. 建立 `IFCSTRUCTURALANALYSISMODEL` — FEA 相容性
9. `w.build()` → 寫入 .ifc 檔案

## 關聯

- [L2-ifc/index.md](index.md) — 父模組
- [L3-ifc-writer.md](L3-ifc-writer.md) — 依賴的 P21 寫入器
- Java 呼叫者：`fastdesign/sidecar/IfcExporter.java`
