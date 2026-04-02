# IFC 4.x 結構匯出模組（P3-A）

> 所屬：L1-sidecar / L2-ifc

## 概述

Block Reality 獨有的 IFC 4.x 語意結構匯出管線，將 Minecraft 結構方塊轉換為符合 ISO 16739-1 標準的 IFC 4 模型。無需 OpenCASCADE，純 TypeScript P21 寫入器，並加入完整工程元資料。

## 競品差異化

✦ 唯一在 Minecraft 中輸出語意有效 IFC 4 的結構模擬模組
✦ 每個元素含材料強度（Rcomp / Rtens / 楊氏模量）→ 直接匯入 Autodesk Robot / Tekla Structures
✦ 每個元素含結構利用率（`Pset_BlockReality_Structural`）→ BIM 工作流可用
✦ 結構分析模型（`IFCSTRUCTURALANALYSISMODEL`）→ FEA 軟體相容

## 關鍵檔案

| 檔案 | 說明 |
|------|------|
| `src/ifc/ifc-writer.ts` | IFC P21 檔案寫入器（`IfcWriter` 類別） |
| `src/ifc/ifc-structural-export.ts` | 主轉換邏輯（結構角色分類、材料資料庫、IFC 實體生成） |

## IFC 輸出結構

```
IFCPROJECT
  └── IFCSITE
        └── IFCBUILDING
              └── IFCBUILDINGSTOREY
                    ├── IFCCOLUMN × N     (isAnchored=true 或垂直連接)
                    ├── IFCBEAM × N       (水平單軸連接)
                    ├── IFCWALL × N       (平面叢集，混凝土/砌體)
                    └── IFCSLAB × N       (水平平面叢集)

IFCMATERIAL × M  (每種材料)
  └── IFCMATERIALPROPERTIES
        ├── YoungModulus (Pa = GPa × 1e9)
        ├── PoissonRatio
        ├── MassDensity (kg/m³)
        ├── CompressiveStrength (Pa = MPa × 1e6)
        └── TensileStrength (Pa)

Pset_BlockReality_Structural × N  (每個元素)
  ├── StressLevel    (0.0–1.0)
  ├── UtilizationRatio (0.0–1.0)
  └── IsAnchored     (Boolean)

IFCSTRUCTURALANALYSISMODEL  (FEA 匯入用)
```

## 材料資料庫（`MATERIAL_DB`）

| 材料 ID | 顯示名稱 | Rcomp (MPa) | Rtens (MPa) | E (GPa) | ρ (kg/m³) |
|---------|---------|------------|------------|---------|-----------|
| `concrete` | Reinforced Concrete | 30 | 3 | 30 | 2400 |
| `concrete_hpc` | High-Performance Concrete | 80 | 8 | 35 | 2500 |
| `rebar` | Reinforcing Bar (Steel) | 420 | 420 | 200 | 7850 |
| `steel` | Structural Steel | 355 | 355 | 210 | 7850 |
| `steel_hss` | Hollow Structural Section | 350 | 350 | 210 | 7850 |
| `timber` | Structural Timber GL30h | 24 | 21 | 13 | 480 |
| `masonry` | Masonry / Brick | 6 | 0.2 | 4 | 1800 |
| `glass_struct` | Structural Glass | 250 | 45 | 70 | 2500 |
| `aluminum` | Structural Aluminum | 270 | 270 | 70 | 2700 |

## 元素分類演算法

使用 O(N) 鄰接查找（HashSet），每個方塊：

1. `isAnchored=true` → **COLUMN**（固定支座）
2. 上下各有鄰居（垂直連接 ≥ 2）→ **COLUMN**
3. 水平單軸連接（X 或 Z 方向）→ **BEAM**
4. 水平平面多方向連接（無垂直）→ **SLAB**
5. 混合連接垂直主導 → **COLUMN**；水平主導 → **WALL**
6. 回退至材料預設角色

## 核心函式

| 函式 | 簽名 | 說明 |
|------|------|------|
| `exportToIfc4` | `(blocks, options) → Promise<Ifc4ExportResult>` | 主匯出入口 |
| `classifyBlocks` | `(blocks) → StructuralRole[]` | 鄰接分析結構角色 |
| `buildSpatialHierarchy` | `(w, projectName) → IfcContext` | 建立 IFC 空間階層 |
| `buildMaterial` | `(w, matDef, ownerHistoryId) → [matId, propsId]` | 材料 + 屬性 |
| `buildBlockGeometry` | `(w, x, y, z, ...) → shapeRepId` | 1m³ 方塊幾何 |
| `buildStructuralPset` | `(w, ownerHistoryId, stressLevel, ...) → void` | 結構屬性集 |

## 關聯

- [L3-ifc-writer.md](L3-ifc-writer.md) — IfcWriter P21 寫入器
- [L3-ifc-structural-export.md](L3-ifc-structural-export.md) — 主轉換邏輯
- Java 呼叫者：`fastdesign/sidecar/IfcExporter.java`
- 父層：[L1-sidecar/index.md](../index.md)
