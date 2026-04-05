# 側向扭轉挫屈 (LTB)

> 所屬：L1-api > L2-physics

## 概述

AISC 360-22 §F2 / Eurocode EN 1993-1-1 §6.3.2 側向扭轉挫屈計算器。
當梁受彎時，壓力翼緣可能發生側向位移並伴隨截面扭轉。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `LateralTorsionalBuckling` | `com.blockreality.api.physics` | LTB 計算器（靜態、工具類別） |
| `LateralTorsionalBuckling.LTBRegion` | 內部列舉 | PLASTIC / INELASTIC / ELASTIC 三區域分類 |

## 三區域模型（AISC §F2）

### 塑性區（L_b ≤ L_p）
截面可達完全塑性彎矩 M_p，不發生 LTB。
```
L_p = 1.76 × r_y × √(E / F_y)
```

### 非彈性區（L_p < L_b ≤ L_r）
臨界彎矩在 M_p 和 0.7F_y·S_x 之間線性插值：
```
M_n = M_p − (M_p − M_r) × (L_b − L_p) / (L_r − L_p)
```

### 彈性區（L_b > L_r）
使用 Timoshenko 彈性 LTB 臨界彎矩：
```
M_cr = C_b × (π/L_b) × √(E·I_y·G·J + (π·E/L_b)²·I_y·C_w)
```

## 截面參數（1m × 1m 正方形）

| 參數 | 符號 | 值 | 說明 |
|------|------|-----|------|
| 慣性矩 | I | 1/12 m⁴ | b⁴/12 |
| 截面模數 | S_x | 1/6 m³ | I / y_max |
| 塑性截面模數 | Z_x | 0.25 m³ | b³/4 |
| 扭轉常數 | J | 0.1406 m⁴ | Saint-Venant |
| 翹曲常數 | C_w | ≈ 0 m⁶ | 實心截面可忽略 |
| 迴轉半徑 | r_y | 0.2887 m | √(I/A) |

## 核心方法

### `LateralTorsionalBuckling.elasticCriticalMoment(...)`
- **說明**：Timoshenko 彈性 LTB 臨界彎矩
- **參數**：E, G, I_y, J, C_w, L_b, C_b
- **回傳**：M_cr (N·m)

### `LateralTorsionalBuckling.blockCriticalMoment(E, G, L_b)`
- **說明**：1m×1m 方塊截面的 LTB 臨界彎矩
- **回傳**：M_cr (N·m)

### `LateralTorsionalBuckling.designMomentCapacity(E, G, F_y, L_b)`
- **說明**：AISC 三區域設計彎矩容量 M_n
- **回傳**：M_n (N·m)，不超過 M_p

### `LateralTorsionalBuckling.classifyRegion(L_b, E, F_y)`
- **說明**：判定 LTB 行為區域
- **回傳**：`LTBRegion.PLASTIC / INELASTIC / ELASTIC`

### `LateralTorsionalBuckling.blockUtilizationRatio(M, E, G, L_b)`
- **說明**：LTB 利用率 = M / M_cr
- **回傳**：> 1.0 表示 LTB 失效

## Minecraft 應用

- 正方形 1m×1m 截面抗 LTB 能力極高（I_y = I_z）
- 雕刻方塊（10×10×10 體素）可產生薄壁截面，LTB 成為控制因素
- 多格水平懸臂的等效跨距增大，降低 M_cr

## 關聯接口

- 依賴 → [柱挫屈](L3-beam-stress.md) — `ColumnBucklingCalculator.blockRadiusOfGyration()`
- 依賴 → `PhysicsConstants` — 截面參數
- 被依賴 ← [力平衡求解器](L3-force-solver.md) — 穩定性判定擴充

## 參考文獻

- AISC 360-22 §F2 — Doubly Symmetric Compact I-Shaped Members
- EN 1993-1-1:2005 §6.3.2 — Lateral-torsional buckling
- Timoshenko & Gere (1961) — Theory of Elastic Stability, Ch. 6
