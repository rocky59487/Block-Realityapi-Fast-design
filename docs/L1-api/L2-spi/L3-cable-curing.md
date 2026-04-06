# ICableManager 與 ICuringManager

> 所屬：L1-api > L2-spi

## 概述

纜索管理與混凝土養護的 SPI 接口及其預設實作，分別處理繩索/纜索張力物理模擬與方塊養護進度追蹤。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `ICableManager` | `com.blockreality.api.spi` | 纜索管理 SPI 接口 |
| `DefaultCableManager` | `com.blockreality.api.spi` | XPBD 繩索物理預設實作 |
| `ICuringManager` | `com.blockreality.api.spi` | 混凝土養護管理 SPI 接口 |
| `DefaultCuringManager` | `com.blockreality.api.spi` | ConcurrentHashMap 養護追蹤預設實作 |

## ICableManager

### 核心方法

#### `attachCable(BlockPos from, BlockPos to, RMaterial cableMaterial)`
- **回傳**：`CableElement`
- **說明**：在兩個方塊之間建立纜索連接

#### `detachCable(BlockPos from, BlockPos to)`
- **說明**：移除纜索，不存在時為 no-op

#### `getCablesAt(BlockPos pos)`
- **回傳**：`Set<CableElement>`（快照）
- **說明**：查詢與指定位置連接的所有纜索

#### `tickCables()`
- **回傳**：`Set<CableElement>`（本 tick 斷裂的纜索）
- **說明**：推進物理一個 tick — 重算張力、檢測斷裂、觸發事件

#### `removeChunkCables(ChunkPos)`
- **回傳**：移除數量
- **說明**：區塊卸載時清除兩端都在該區塊內的纜索

### DefaultCableManager (XPBD)

基於 Extended Position Based Dynamics（Macklin & Muller, MIG'16）：

```
C(x) = |x1 - x2| - L_rest
alpha_tilde = compliance / dt^2
delta_lambda = (-C - alpha_tilde * lambda) / (w1 + w2 + alpha_tilde)
```

- Compliance: `1e-6`（鋼纜）
- 迭代次數: 5
- 速度阻尼: 0.98（2% 能量損耗/tick）
- NaN/Inf 保護 + 速度回退

## ICuringManager

### 核心方法

#### `startCuring(BlockPos pos, int totalTicks)`
- **說明**：開始追蹤方塊養護（totalTicks <= 0 會被忽略）

#### `getCuringProgress(BlockPos pos)`
- **回傳**：`float` (0.0~1.0)

#### `isCuringComplete(BlockPos pos)`
- **回傳**：`boolean`

#### `tickCuring()`
- **回傳**：`Set<BlockPos>`（本 tick 完成養護的位置）

#### `removeCuring(BlockPos pos)`
- **說明**：停止追蹤

#### `v2.1 接入點 (PFSF)`
- **說明**：可將 `this::getCuringProgress` 封裝為 `Function<BlockPos, Float>` 傳入 `PFSFEngine.setCuringLookup()` 作為 v2.1 PFSF 的動態養護參數查詢來源。

### DefaultCuringManager

- `@ThreadSafe`，使用 `ConcurrentHashMap` + `AtomicInteger`
- 每個 `CuringEntry` 記錄 `totalTicks` 與 `startTick`（皆為 final）
- 進度計算：`elapsed / totalTicks`，夾在 `[0.0, 1.0]`

## 關聯接口

- 被依賴 ← [ModuleRegistry](L3-module-registry.md) — 註冊與查詢
- 依賴 → `CableElement`、`CableNode`、`CableState`（physics 套件）
