# SPHStressEngine

> 所屬：L1-api > L2-sph

## 概述

觸發式 SPH 應力引擎，使用距離衰減壓力模型模擬爆炸對結構方塊的衝擊應力。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `SPHStressEngine` | `com.blockreality.api.sph` | 爆炸應力計算引擎（靜態、執行緒安全） |
| `SnapshotEntry` | 內部 record | 方塊快照資料（位置、類型、抗壓/抗拉強度） |

## 應力計算公式

```
stressLevel = (BASE_PRESSURE / distance^2) * materialFactor / Rcomp
```

- `BASE_PRESSURE` = 10.0（爆炸基礎壓力常數）
- `distance` = 方塊與爆炸中心的距離（最小夾 1.0 格）
- `materialFactor` = 從 `BlockType.getStructuralFactor()` 取得
- `Rcomp` = 材料抗壓強度（MPa）
- 結果夾在 `[0.0, 2.0]`，`>= 1.0` 視為損壞

## 三階段非同步流程

### Phase 1：主線程快照擷取
- 在 `ExplosionEvent.Start` 事件中，於主線程擷取爆炸範圍內所有 `RBlockEntity` 的不可變快照
- 球形篩選 + `Collections.unmodifiableMap()` 確保異步安全
- 粒子上限煞車：超過 `sphMaxParticles` 時提前返回

### Phase 2：異步計算
- 透過 `CompletableFuture.supplyAsync()` 在 `SPH_EXECUTOR` 執行緒池計算
- 執行緒池配置：核心 1、最大 2、佇列 4、`DiscardOldestPolicy`
- 30 秒超時，失敗時降級返回空結果

### Phase 3：主線程回寫
- 透過 `level.getServer().execute()` 回到主線程
- 將 `stressLevel >= 1.0` 的方塊加入損壞集合
- 呼叫 `ResultApplicator.applyStressField()` 套用結果

## 核心方法

### `onExplosionStart(ExplosionEvent.Start)`
- **觸發**：Forge 事件 `@SubscribeEvent`
- **條件**：爆炸半徑 > `sphAsyncTriggerRadius`
- **說明**：整個三階段流程的入口

### `captureSnapshot(ServerLevel, Vec3, int)`
- **參數**：世界、爆炸中心、搜索半徑
- **回傳**：`Map<BlockPos, SnapshotEntry>`（不可變）
- **說明**：主線程擷取方塊材料快照

### `computeStress(Map, Vec3, float)`
- **參數**：快照、爆炸中心、爆炸半徑
- **回傳**：`Map<BlockPos, Float>`（應力等級）
- **說明**：純計算，異步線程執行

### `shutdown()`
- **說明**：優雅關閉執行緒池，應在 `ServerStoppingEvent` 中呼叫

## 關聯接口

- 依賴 → [材料系統](../../L1-api/L2-material/) — `RMaterial`、`BlockType`
- 依賴 → `ResultApplicator`、`StressField`（physics 套件）
- 被依賴 ← [崩塌管理](../../L1-api/L2-collapse/) — 應力超標時觸發崩塌
