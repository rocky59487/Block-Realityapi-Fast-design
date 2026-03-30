# CableElement / CableNode / CableState — 纜索物理系統

> 所屬：L1-api > L2-physics

## 概述

僅承受拉力的繩索/纜索結構元素，使用 XPBD（Extended Position Based Dynamics）進行動態模擬。纜索在壓縮時鬆弛（零剛度），僅在拉伸時產生張力。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `CableElement` | `physics.CableElement` | 不可變 record，纜索定義（端點、材料、靜止長度） |
| `CableNode` | `physics.CableNode` | 可變模擬節點，XPBD 位置/速度狀態，`@NotThreadSafe` |
| `CableState` | `physics.CableState` | 一條纜索的完整 XPBD 模擬狀態（節點鏈 + lambda） |

## 核心方法

### `CableElement.create(BlockPos a, BlockPos b, RMaterial)`
- **參數**: 兩端點、纜索材料
- **回傳**: `CableElement`
- **說明**: 建立纜索元素，靜止長度 = 歐氏距離。預設截面積 0.01 m²。

### `CableElement.tension()`
- **回傳**: `double`（張力 N，>= 0）
- **說明**: Hooke 定律 T = E x A x strain，壓縮時回傳 0。

### `CableNode.applyGravity(double dt)`
- **說明**: 對自由節點施加重力加速度。固定節點不受影響。

### `CableState.calculateTension()`
- **回傳**: `double`（張力 N）
- **說明**: 所有 segment 的平均應變，計算整條纜索張力。

### `CableState.isBroken()`
- **回傳**: `boolean`
- **說明**: `cachedTension > maxTension()` 時判定斷裂。

## XPBD 模擬細節

- **節點佈局**: `[fixed(A)] - [free] - ... - [fixed(B)]`
- **節點間距**: 0.5m，最多 64 節點
- **Lambda 存儲**: 每個 segment 一個 Lagrange 乘數（XPBD 要求）
- **速度阻尼**: 防止能量累積
- **NaN 防護**: `validatePosition()` 偵測並重設無效位置
- **執行緒安全**: `cachedTension` 標記 `volatile`，渲染執行緒使用 `getPositionSnapshot()`

## 關聯接口

- 依賴 → [RMaterial](../L2-material/L3-default-material.md)（材料張力強度）
- 依賴 → [PhysicsConstants](L3-beam-stress.md)（重力常數）
- 被依賴 ← [ICableManager / DefaultCableManager](../L2-spi/L3-cable-curing.md)
