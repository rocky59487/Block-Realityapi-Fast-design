# IMaterialRegistry、IFusionDetector、IBlockTypeExtension、ILoadPathManager

> 所屬：L1-api > L2-spi

## 概述

材料註冊、RC 融合偵測、方塊類型擴展與荷載路徑管理的 SPI 接口，為 Block Reality 物理模擬系統提供可替換的擴展點。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `IMaterialRegistry` | `com.blockreality.api.spi` | 材料中央註冊表接口 |
| `IFusionDetector` | `com.blockreality.api.spi` | RC 融合偵測器接口 |
| `IBlockTypeExtension` | `com.blockreality.api.spi` | 自訂方塊類型行為接口 |
| `ILoadPathManager` | `com.blockreality.api.spi` | 荷載路徑傳遞管理接口 |

## IMaterialRegistry

### 核心方法

#### `registerMaterial(String id, RMaterial material)`
- **說明**：註冊材料，同 ID 覆寫（記錄警告）

#### `getMaterial(String id)`
- **回傳**：`Optional<RMaterial>`

#### `canPair(RMaterial a, RMaterial b)`
- **回傳**：`boolean`
- **說明**：檢查兩材料是否可 RC 融合（至少一方 Rtens > 0）

#### `getAllMaterialIds()` / `getCount()`
- **說明**：枚舉與統計

## IFusionDetector

### 核心方法

#### `checkAndFuse(ServerLevel level, BlockPos pos)`
- **回傳**：融合數量
- **說明**：放置 REBAR/CONCRETE 時掃描 6 方向鄰居，找到互補材料則升級為 RC_NODE

#### `checkAndDowngrade(ServerLevel level, BlockPos brokenPos, BlockType brokenType)`
- **回傳**：降級數量
- **說明**：支撐材料被破壞時，檢查 RC_NODE 是否需要降級回原始類型

### RC 融合公式

```
R_RC_tens  = R_concrete_tens + R_rebar_tens * phi_tens (phi=0.8)
R_RC_shear = R_concrete_shear + R_rebar_shear * phi_shear (phi=0.6)
R_RC_comp  = R_concrete_comp * compBoost (1.1)
```

包含蜂窩效應機率模擬（施工品質不確定性）。

## IBlockTypeExtension

### 核心方法

#### `getTypeId()`
- **回傳**：唯一小寫識別符（如 `ci_reinforced_concrete`）

#### `canTransitionTo(String targetTypeId)`
- **說明**：是否允許類型轉換（升級/降級/養護）

#### `isStructural()`
- **說明**：是否參與結構荷載計算（false = 裝飾性）

#### `canBeAnchor()`
- **說明**：是否可作為錨定點（不可崩塌、支撐整體結構）

## ILoadPathManager

### 核心方法

#### `onBlockPlaced(ServerLevel, BlockPos)`
- **回傳**：`boolean`（是否找到支撐）
- **說明**：確立支撐點、傳遞自重、檢查壓碎

#### `onBlockBroken(ServerLevel, BlockPos)`
- **回傳**：崩塌方塊數
- **說明**：移除載重、收集孤兒、嘗試重新連接、級聯崩塌

#### `findBestSupport(ServerLevel, BlockPos, RBlockEntity)`
- **回傳**：`BlockPos`（最佳支撐者，null = 無支撐）
- **優先級**：正下方 > 側向（需 Rtens > 0）> 上方（懸吊）

#### `propagateLoadDown(ServerLevel, BlockPos, float delta)`
- **說明**：沿支撐樹向下傳遞載重變化

#### `traceLoadPath(ServerLevel, BlockPos)`
- **回傳**：`List<BlockPos>`（從起點到錨定點的完整支撐鏈）
- **用途**：`/br_load trace` 指令診斷

### 設計模式

支撐樹（Support Tree）— 每個方塊記住「我的重量傳給誰（Parent）」。
效能：O(H) 放置、O(K) 破壞，H = 層高、K = 受影響依賴者數。

## 關聯接口

- 被依賴 ← [ModuleRegistry](L3-module-registry.md)
- 依賴 → `RMaterial`、`BlockType`、`RBlockEntity`（核心型別）
