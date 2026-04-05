# SPH 應力引擎

> 所屬：L1-api > L2-sph

## 概述

觸發式 SPH 應力引擎，使用 **Monaghan (1992) 立方樣條核心函數** 配合
**Teschner (2003) 空間雜湊鄰域搜索**，以真實的 SPH 粒子方法計算爆炸對結構方塊的衝擊應力。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `SPHStressEngine` | `com.blockreality.api.sph` | 爆炸應力計算引擎（靜態、執行緒安全） |
| `SPHKernel` | `com.blockreality.api.sph` | 立方樣條核心函數 W(r,h) + 梯度 ∇W |
| `SpatialHashGrid` | `com.blockreality.api.sph` | O(1) 鄰域搜索（Teschner 空間雜湊） |
| `SnapshotEntry` | 內部 record | 方塊快照資料（位置、類型、抗壓/抗拉強度） |

## SPH 演算法

### Step 1: 密度求和
```
ρᵢ = Σⱼ mⱼ W(|rᵢ - rⱼ|, h)
```
每個 Minecraft 方塊視為一個 SPH 粒子（質量 m=1）。使用 `SPHKernel.cubicSpline()` 三維正規化核心。

### Step 2: 狀態方程 + 爆炸衝量
```
Pᵢ = k × max(0, ρᵢ - ρ₀) + impulse
impulse = k × (1 - dist_to_center / radius)  (if within blast radius)
```
k = basePressure × (explosionRadius / 4)，ρ₀ = 靜止密度（預設 1.0）。

### Step 3: 壓力梯度力
```
fᵢ = -Σⱼ mⱼ (Pᵢ/ρᵢ² + Pⱼ/ρⱼ²) ∇W(rᵢⱼ, h)
```
使用 `SPHKernel.cubicSplineGradient()` 計算核心梯度。

### Step 4: 材料正規化
```
stressLevel = min(|fᵢ| × materialFactor / Rcomp, 2.0)
```
- `materialFactor` = `BlockType.getStructuralFactor()`
- `Rcomp` = 材料抗壓強度（MPa）
- 結果在 `[0.0, 2.0]`，`>= 1.0` 視為損壞

## SPHKernel — 核心函數

### 立方樣條 W(r, h)
三維正規化：σ₃ = 1/(πh³)

```
q = r/h
W(q) = σ₃ × { 1 - 1.5q² + 0.75q³   if 0 ≤ q ≤ 1
             { 0.25(2-q)³             if 1 < q ≤ 2
             { 0                       if q > 2
```

性質：正規化（∫W dV = 1）、對稱、緊支撐（r > 2h → 0）、C¹ 連續。

### 梯度 dW/dr
用於壓力梯度力計算。同樣具備緊支撐性。

## SpatialHashGrid — 鄰域搜索

使用三個大質數（73856093, 19349663, 83492791）的 XOR 雜湊，將 3D 格子座標映射到 1D 鍵值。
格子邊長 = 2h（核心支撐半徑）。查詢時搜索 3³ = 27 個相鄰格子。

- 插入：O(1) amortized
- 查詢：O(k)，k = 候選鄰居數

## 三階段非同步流程

### Phase 1：主線程快照擷取
- 在 `ExplosionEvent.Start` 事件中，於主線程擷取爆炸範圍內所有 `RBlockEntity` 的不可變快照
- 球形篩選 + `Collections.unmodifiableMap()` 確保異步安全
- 粒子上限煞車：超過 `sphMaxParticles` 時提前返回

### Phase 2：異步 SPH 計算
- 透過 `CompletableFuture.supplyAsync()` 在 `SPH_EXECUTOR` 執行緒池執行
- 建構 SpatialHashGrid → 密度求和 → 狀態方程 → 壓力力 → 正規化
- 30 秒超時，失敗時降級返回空結果

### Phase 3：主線程回寫
- 透過 `level.getServer().execute()` 回到主線程
- 呼叫 `ResultApplicator.applyStressField()` 套用結果
- 同步廣播 `StressSyncPacket` 到客戶端

## 核心方法

### `SPHStressEngine.onExplosionStart(ExplosionEvent.Start)`
- **觸發**：Forge 事件 `@SubscribeEvent`
- **條件**：爆炸半徑 > `sphAsyncTriggerRadius`

### `SPHStressEngine.computeStress(Map, Vec3, float)`
- **說明**：完整 SPH 計算管線（密度→壓力→力→應力）

### `SPHKernel.cubicSpline(double r, double h)`
- **回傳**：核心值 W(r,h) ≥ 0

### `SPHKernel.cubicSplineGradient(double r, double h)`
- **回傳**：dW/dr（通常 ≤ 0）

### `SpatialHashGrid.getNeighbors(double x, double y, double z)`
- **回傳**：候選鄰居粒子索引列表

## 關聯接口

- 依賴 → [材料系統](../../L1-api/L2-material/) — `RMaterial`、`BlockType`
- 依賴 → `ResultApplicator`、`StressField`（physics 套件）
- 被依賴 ← [崩塌管理](../../L1-api/L2-collapse/) — 應力超標時觸發崩塌

## 參考文獻

- Monaghan, J.J. (1992). "Smoothed Particle Hydrodynamics". ARAA, 30, 543-574.
- Price, D.J. (2012). "Smoothed particle hydrodynamics and magnetohydrodynamics". JCP, 231(3), 759-794.
- Teschner, M. et al. (2003). "Optimized Spatial Hashing for Collision Detection of Deformable Objects". VMV 2003.
