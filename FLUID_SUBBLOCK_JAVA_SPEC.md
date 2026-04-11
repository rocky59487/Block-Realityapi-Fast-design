# HYDRO-Subblock Java 整合規範（給外包）

> **對應模型**：`bifrost_fluid_subblock`（HYDRO-Subblock）  
> **合約版本**：v1  
> **更新日期**：2026-04-11  
> **負責人**：芷絮（Java 端對接）  

---

## 1. 核心概念

HYDRO-Subblock 是 Block Reality 下一代流體神經算子，運作在 **0.1m 子網格（sub-cell）** 層級：

- **1 個 Minecraft block = 10×10×10 sub-cells**
- 模型直接對 **sub-cell grid** 做推理，而非 block grid
- Java 端必須負責 **上採樣（block → sub-cell）** 與 **下採樣（sub-cell → block）**

---

## 2. ONNX 合約

```python
# brml/brml/export/onnx_contracts.py
FLUID_SUBBLOCK = ModelContract(
    model_id="bifrost_fluid_subblock",
    version=1,
    inputs=[
        TensorSpec("input", (1, N, N, N, 8), "float32",
                   "velocity(3) + pressure(1) + boundary(1) + position(3)"),
    ],
    outputs=[
        TensorSpec("output", (1, N, N, N, 4), "float32",
                   "velocity_next(3) + pressure_next(1)"),
    ],
    notes="N = grid_subcells. Small=80, Large=160. "
          "1 block = 10 sub-cells. Position in [0,1].",
)
```

模型檔名建議：
- 小模型驗證版：`hydro_subblock_small.onnx`（對應 `N=80`，即 8 blocks）
- 中大型商業版：`hydro_subblock_large.onnx`（對應 `N=160`，即 16 blocks）

---

## 3. 輸入張量詳細規範

輸入 shape：`[1, N, N, N, 8]`（row-major）

其中 `N` 必須與模型訓練時一致：
- `N = 80`（small model，對應 8×8×8 blocks region）
- `N = 160`（large model，對應 16×16×16 blocks region）

### 3.1 通道說明（最後一維 8 個通道）

| 通道 | 名稱 | 物理意義 | 單位 / 範圍 | 備註 |
|------|------|---------|------------|------|
| 0 | `vx` | sub-cell x 方向速度 | m/s，建議 clamp 到 `[-10, 10]` | 靜止水為 0 |
| 1 | `vy` | sub-cell y 方向速度 | m/s，建議 clamp 到 `[-10, 10]` | 重力方向通常為負 |
| 2 | `vz` | sub-cell z 方向速度 | m/s，建議 clamp 到 `[-10, 10]` | |
| 3 | `pressure` | sub-cell 壓力 | Pa | 初始可用 `ρ·g·depth` |
| 4 | `boundary` | 邊界掩碼 | `1.0`=流體，`0.0`=固體 | **必須與 sub-cell 固體牆一致** |
| 5 | `px` | 正規化 x 位置 | `[0, 1]` | `x / (N-1)` |
| 6 | `py` | 正規化 y 位置 | `[0, 1]` | `y / (N-1)` |
| 7 | `pz` | 正規化 z 位置 | `[0, 1]` | `z / (N-1)` |

### 3.2 索引順序

Java ONNX Runtime 輸入為 5D row-major，記憶體佈局：

```
index = ((x * N + y) * N + z) * 8 + channel
```

---

## 4. 輸出張量詳細規範

輸出 shape：`[1, N, N, N, 4]`（row-major）

| 通道 | 名稱 | 物理意義 | 單位 |
|------|------|---------|------|
| 0 | `vx_next` | 下一步 x 速度 | m/s |
| 1 | `vy_next` | 下一步 y 速度 | m/s |
| 2 | `vz_next` | 下一步 z 速度 | m/s |
| 3 | `pressure_next` | 下一步壓力 | Pa |

**重要**：輸出已經在模型內部被 `boundary` 掩碼處理過（固體 sub-cell 的輸出值為 0）。Java 端寫回時，**只應更新 fluid sub-cells**。

---

## 5. Java 端數據轉換流程

### 5.1 整體流程圖

```
FluidRegion (block-level)
    │
    ▼
[Upsample]  block → sub-cell (10× per axis)
    │
    ├── velocity[3]:  直接複製（每個 block 內的 10×10×10 sub-cells 速度相同）
    ├── pressure[1]:  直接複製
    ├── boundary[1]:  block 為固體 → sub-cells 全 0；block 為流體/空氣 → 需進一步判斷
    └── position[3]:  按 sub-cell index 計算 [0,1]
    │
    ▼
Build ONNX Input [1, N, N, N, 8]
    │
    ▼
ONNX Inference (OnnxFluidRuntime)
    │
    ▼
Parse ONNX Output [1, N, N, N, 4]
    │
    ▼
[Downsample]  sub-cell → block
    │
    ├── velocity:  可選（目前 Java 端 block-level 無 velocity 欄位，暫不寫回）
    └── pressure:  **邊界處平均**（見 5.3 節）
    │
    ▼
Update FluidRegion (block-level)
```

### 5.2 Block → Sub-cell 上採樣細節

假設 `FluidRegion` 管理的是 `Bx × By × Bz` blocks，且 `N = Bx * 10`：

```java
// 簡化的上採樣邏輯（以 pressure 為例）
for (int bx = 0; bx < Bx; bx++) {
    for (int by = 0; by < By; by++) {
        for (int bz = 0; bz < Bz; bz++) {
            int bIdx = bx + by * Bx + bz * Bx * By;
            float pBlock = region.getPressure()[bIdx];
            FluidType tBlock = FluidType.fromId(region.getType()[bIdx] & 0xFF);
            
            // 複製到 10×10×10 sub-cells
            for (int dx = 0; dx < 10; dx++) {
                for (int dy = 0; dy < 10; dy++) {
                    for (int dz = 0; dz < 10; dz++) {
                        int sx = bx * 10 + dx;
                        int sy = by * 10 + dy;
                        int sz = bz * 10 + dz;
                        int sIdx = sx + sy * N + sz * N * N;
                        
                        // pressure
                        input[sIdx * 8 + 3] = pBlock;
                        
                        // boundary: 固體牆 = 0，流體 = 1，空氣 = 0
                        float boundary = (tBlock == FluidType.SOLID_WALL) ? 0.0f :
                                         (tBlock == FluidType.AIR) ? 0.0f : 1.0f;
                        input[sIdx * 8 + 4] = boundary;
                        
                        // position
                        input[sIdx * 8 + 5] = sx / (float)(N - 1);
                        input[sIdx * 8 + 6] = sy / (float)(N - 1);
                        input[sIdx * 8 + 7] = sz / (float)(N - 1);
                    }
                }
            }
        }
    }
}
```

**Velocity 初始值**：
- 若 `FluidRegion` 目前無 sub-cell velocity 資料，初始化為 `0`
- 若已有前一步 ONNX 輸出的 sub-cell velocity，直接沿用

**建議**：在 `FluidRegion` 內部新增 `float[] subVx, subVy, subVz` 緩衝，或者將整個 sub-cell 狀態外包給 `FluidSubRegionBuffer` 管理。

### 5.3 Sub-cell → Block 下採樣（邊界壓力平均）

這是 **PFSF 結構引擎耦合的關鍵**。PFSF 只需要 **block-level 的邊界壓力**，因此：

1. 對於每個 block，收集其周圍（或內部）流體 sub-cells 的壓力
2. 若該 block 與固體相鄰，取該 block **最外層 sub-cells** 的壓力平均值
3. 將平均後的壓力寫回 `FluidRegion.getPressure()[bIdx]`

```java
/**
 * 將 sub-cell 壓力下採樣回 block-level，專注於邊界耦合。
 * 策略：對於每個 block，取其表面一層 sub-cells 的壓力平均。
 */
float[] downsampleBoundaryPressure(float[] subPressure, int Bx, int By, int Bz, int N) {
    float[] blockPressure = new float[Bx * By * Bz];
    
    for (int bx = 0; bx < Bx; bx++) {
        for (int by = 0; by < By; by++) {
            for (int bz = 0; bz < Bz; bz++) {
                double sum = 0.0;
                int count = 0;
                
                // 只採樣該 block 的最外層 sub-cells（表面層）
                for (int dx = 0; dx < 10; dx++) {
                    for (int dy = 0; dy < 10; dy++) {
                        for (int dz = 0; dz < 10; dz++) {
                            boolean isSurface = (dx == 0 || dx == 9 || dy == 0 || dy == 9 || dz == 0 || dz == 9);
                            if (!isSurface) continue;
                            
                            int sx = bx * 10 + dx;
                            int sy = by * 10 + dy;
                            int sz = bz * 10 + dz;
                            int sIdx = sx + sy * N + sz * N * N;
                            
                            sum += subPressure[sIdx];
                            count++;
                        }
                    }
                }
                
                int bIdx = bx + by * Bx + bz * Bx * Bz;
                blockPressure[bIdx] = count > 0 ? (float)(sum / count) : 0.0f;
            }
        }
    }
    return blockPressure;
}
```

**進階策略（可選）**：
- 若 block 完全被流體填充且遠離固體，可用 **體積加權平均** 代替表面平均
- 若 block 為空氣，壓力設為 `0`

---

## 6. OnnxFluidRuntime 接口建議

雖然 Java 端由外包負責，但以下是 Python 端預期的調用接口，供參考：

```java
public class OnnxFluidRuntime {
    // 載入模型（建議放在 BIFROSTModelRegistry 中統一管理）
    public boolean loadModel(String modelPath);
    
    // 檢查是否可用
    public boolean isAvailable();
    
    // 取得模型訓練時的網格尺寸（80 或 160）
    public int getGridSubcells();
    
    // 對單一 FluidRegion 執行單步推理
    public boolean infer(FluidRegion region);
    
    // 釋放資源
    public void shutdown();
}
```

`infer(FluidRegion)` 的內部邏輯：
1. 檢查 `region` 的 blocks 尺寸是否匹配（如 small model 需 `Bx=By=Bz=8`）
2. 上採樣構建 `[1, N, N, N, 8]` 輸入
3. 呼叫 `OrtSession.run()`
4. 解析 `[1, N, N, N, 4]` 輸出
5. 將 sub-cell pressure 下採樣回 block-level
6. （可選）保存 sub-cell velocity 供下一步使用
7. 標記 region dirty（若需要同步渲染）

---

## 7. 與現有 FluidGPUEngine 的整合建議

不要完全取代現有的 Vulkan/CPU 求解器，建議採用 **三級後端路由**：

```java
FluidGPUEngine.tick()
├── HYDRO-ONNX   → 動態、中等複雜度、sub-cell 精度
├── PFSF-GPU     → 大區域、靜態水池、Vulkan 可用時
└── PFSF-CPU     → 回退路徑
```

**路由規則草稿**：

| 條件 | 後端 |
|------|------|
| Region 尺寸 ≤ 8 blocks（small）或 ≤ 16 blocks（large），ONNX 可用 | **HYDRO-ONNX** |
| Region 尺寸 > 16 blocks | **PFSF-GPU** |
| 劇烈拓撲變化後（結構崩塌 5 tick 內） | **PFSF-GPU**（傳統求解器更穩定） |
| Vulkan 不可用 | **PFSF-CPU** |

---

## 8. 壓力單位與物理常數

| 常數 | 值 | 說明 |
|------|-----|------|
| 水密度 `ρ` | `1000.0 kg/m³` | 初始化靜水壓用 |
| 重力 `g` | `9.81 m/s²` | y 方向向下為負 |
| 時間步長 `dt` | `0.02 s`（對應教師資料）或 `0.05 s`（1 tick）| 需與訓練資料一致 |
| 運動黏度 `ν` | `1e-6 m²/s` | 水的典型值 |

**靜水壓初始化公式**（供參考）：
```java
float depth = surfaceY - subY;  // 水面高度 - 當前 sub-cell y
float pressure = 1000.0f * 9.81f * depth;
```

---

## 9. 常見問題（FAQ）

### Q1: 為什麼輸入沒有重力通道？
A: 重力方向固定為 `-y`，模型已經在訓練中學習了這一先驗。不需要額外輸入重力向量。

### Q2: 如果 region 不是立方體（例如 Bx≠By）怎麼辦？
A: **目前模型只支援立方體網格**（N×N×N）。若 region 為長方體，建議：
- 方案 A：將長方體 **padding 到最小立方體**（不足處補 solid boundary=0）
- 方案 B：直接路由到 PFSF-GPU/CPU（不適用 ONNX）

### Q3: 輸出 velocity 需要寫回 Java 端的 block-level 嗎？
A: **現階段不需要**。`FluidRegion` 目前的 SoA 欄位（`phi, pressure, volume, type`）沒有 block-level velocity。但為了讓 ONNX 下一步推理連貫，建議在 Java 端 **暫存 sub-cell velocity**（可放在 `FluidSubRegionBuffer` 或 `OnnxFluidRuntime` 內部緩衝）。

### Q4: 模型輸出是「下一步」還是「多步」？
A: **單步**（one-step ahead）。即輸入 `state(t)`，輸出 `state(t+Δt)`。Java 端每 tick 呼叫一次即可。長期 rollout 由多次單步串接完成。

### Q5: 需要處理壓力的正規化/反正规化嗎？
A: **不需要**。Python 端訓練時使用的壓力為物理尺度（Pa），輸出也是物理尺度。Java 端直接寫回即可。但若發現數值範圍過大導致 ONNX 精度問題，後續可協商增加正規化合約（v2）。

---

## 10. 聯繫與迭代

若 Java 端在對接過程中發現以下情況，請立即回饋給模型組（Python 端）：
1. ONNX 導出後在 Java Runtime 上加載失敗
2. 輸入/輸出 shape 與本文件不符
3. 長期 rollout 出現明顯發散（drift）
4. 需要額外的輸入通道（如溫度、不同流體類型、孔隙率等）

**本文件將隨著模型迭代（small → large → v2）持續更新。**
