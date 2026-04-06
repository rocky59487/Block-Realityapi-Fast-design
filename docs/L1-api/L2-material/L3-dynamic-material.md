# DynamicMaterial — 動態 RC 融合材料

> 所屬：L1-api > L2-material

## 概述

在 runtime 以公式計算的不可變 record 材料，主要用於 RC 融合節點（鋼筋+混凝土的動態強度計算）。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `DynamicMaterial` | `com.blockreality.api.material.DynamicMaterial` | record，實作 `RMaterial`，執行緒安全 |

## 核心方法

### `ofRCFusion(RMaterial concrete, RMaterial rebar, double phiTens, double phiShear, double compBoost, boolean hasHoneycomb)`
- **參數**: 混凝土材料、鋼筋材料、抗拉融合係數、抗剪融合係數、抗壓增幅、是否有蜂窩
- **回傳**: `DynamicMaterial`
- **說明**: RC 融合公式計算。

### `ofCustom(String id, double rcomp, double rtens, double rshear, double density)`
- **回傳**: `DynamicMaterial`
- **說明**: 自訂材料建構，含基本驗證。

## RC 融合公式（v3fix 手冊）

| 屬性 | 公式 |
|------|------|
| R_RC_comp | R_concrete_comp x compBoost（預設 1.1） |
| R_RC_tens | R_concrete_tens + R_rebar_tens x phi_tens（預設 0.8） |
| R_RC_shear | R_concrete_shear + R_rebar_shear x phi_shear（預設 0.6） |
| density | concrete x 97% + rebar x 3%（ACI 318 典型配筋率） |

## 蜂窩懲罰

當 `hasHoneycomb=true` 時，所有強度參數乘以 0.7（降低 30%），模擬澆灌品質不良。

## 輸入驗證

- `concrete` / `rebar` 不可為 null
- `concrete.getRcomp()` 與 `rebar.getRtens()` 不可為負
- `ofCustom()` 中密度必須 > 0，強度 clamp 到 >= 0

## 關聯接口

- 實作 → [RMaterial](index.md)
- 被依賴 ← [RCFusionDetector](../L2-physics/L3-load-path.md)（融合計算）
- 被依賴 ← [SupportPathAnalyzer](../L2-physics/L3-load-path.md)（RC 連接材料）
