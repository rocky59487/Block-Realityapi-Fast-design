# BR-NeXT 穩健化訓練指南（Robust SSGO）

> 本文件說明如何訓練一個對「未見過形體」與「遊玩隨機性」具有強健性的中階 SSGO 模型。

---

## 1. 快速開始

### 1.1 安裝依賴
確保已安裝 JAX、Flax、Optax：
```bash
pip install jax flax optax numpy scipy
```

### 1.2 一鍵訓練
```bash
cd BR-NeXT
python -m brnext.train.train_robust_ssgo \
  --grid 8 \
  --steps-s1 500 \
  --steps-s2 500 \
  --steps-s3 800 \
  --batch-size 4 \
  --dropout 0.05 \
  --output brnext_output_m1
```

輸出檔案：
- `brnext_output_m1/ssgo_robust_medium.msgpack` — 訓練後參數
- `brnext_output_m1/ood_benchmark.json` — OOD 泛化測試報告
- `brnext_output_m1/history.json` — 三階段 loss 曲線

---

## 2. 核心改動

### 2.1 資料增強（Data Augmentation）
檔案：`brnext/pipeline/structure_gen.py`

- **幾何擾動**：隨機旋轉（90°）、鏡像翻轉、腐蝕/膨脹（erosion/dilation）、挖洞（cutout）
- **材料擾動**：對 `E_field`、`density_field` 加乘性雜訊（±10%），對 `nu_field` 加高斯雜訊
- **Mixup**：在 batch 內隨機 pair-wise 線性混合 occupancy 與 target fields

### 2.2 課程學習（Curriculum Learning）
檔案：`brnext/pipeline/async_data_loader.py` 中的 `CurriculumSampler`

- 計算每種形體的「複雜度分數」：`complexity = 0.4*surface_ratio + 0.4*exposure + 0.2*irregularity`
- 訓練前 30% 只抽樣 complexity < 0.4 的形體（tower, bridge）
- 中間 40% 擴展到 complexity < 0.7
- 最後 30% 全開（tree, cave, overhang）

### 2.3 模型正規化
檔案：`brnext/models/ssgo.py`、`brnext/models/moe_head.py`

- **Dropout**：在 `FNOBlock`、`SparseVoxelGraphConv`、`MoESpectralHead` 中加入 rate=0.05~0.1 的 dropout
- **對抗性擾動**：在 Stage 3 的輸入上加微小高斯雜訊（ε=0.01），並對乾淨輸入與雜訊輸入取平均梯度
- **Uncertainty Weighting**：沿用 CMFD 的 `log_sigma` 多任務不確定度加權

### 2.4 修復的關鍵 Bug
檔案：`brnext/models/losses.py`

- `physics_residual_loss` 原先對 `mask`、`E_field`、`nu_field` 錯誤地加了 `[..., None]`，導致 `batch_size>1` 時廣播失敗。已統一移除多餘維度。
- `hybrid_task_loss` 中 `jnp.sqrt(vm_sq)` 在 stress 預測接近 0 時梯度爆炸為 Inf，已加入 `jnp.maximum(vm_sq, 1e-8)` 保護。

---

## 3. 驗證工具

### 3.1 OOD Benchmark
```bash
python -m brnext.evaluation.ood_benchmark
```

比較 **In-Distribution**（tower, bridge, cantilever, arch）與 **OOD**（spiral, tree, cave, overhang）的 MAE。目標：OOD MAE(φ) 相對基線降低 ≥ 30%。

### 3.2 Gameplay Stress Test
```bash
python -m brnext.evaluation.gameplay_stress_test
```

模擬真實遊玩場景：
1. 隨機建造（含不合理懸空結構）
2. 隨機破壞 5~30% 方塊
3. 連續 10 步動態加減方塊（檢查預測連續性）
4. 材料錯配（混入極軟/極硬島）

Pass 標準：
- `nan_rate == 0%`
- `mae_phi < 0.15`
- `dynamic_jump_rate < 30%`
- `extreme_error_rate < 2%`

---

## 4. 實驗結果（待填入）

### 4.1 基線 vs. 穩健模型

| 指標 | Random Init 基線 | 穩健模型 M1 | 改善幅度 |
|------|------------------|------------|---------|
| OOD MAE(φ) | TBD | TBD | TBD |
| Stress Test Pass Rate | 0% | TBD | TBD |
| nan_rate | 0% | TBD | TBD |
| mae_phi | 170,454 | TBD | TBD |

### 4.2 消融實驗（Ablation）

| 配置 | OOD MAE(φ) | Stress Pass Rate |
|------|-----------|------------------|
| Baseline (no aug) | TBD | TBD |
| + Augmentation | TBD | TBD |
| + Aug + Curriculum | TBD | TBD |
| + Aug + Curriculum + Dropout | TBD | TBD |
| + All + Adv | TBD | TBD |

---

## 5. 遊戲端整合範例

```python
import jax.numpy as jnp
from flax import serialization
from brnext.models.ssgo import SSGO

model = SSGO(hidden=48, modes=8, ...)
with open("brnext_output_m1/ssgo_robust_medium.msgpack", "rb") as f:
    params = serialization.from_bytes(None, f.read())

x = build_input(voxel_structure)  # [L, L, L, 6]
pred = model.apply({"params": params}, x[None, ...], train=False)
stress = pred[..., :6]
disp = pred[..., 6:9]
phi = pred[..., 9]
```

---

## 6. 已知限制與後續工作

- **ONNX 匯出**：目前需手動安裝 `onnx` 套件（`pip install onnx`）。若缺少該套件，只會輸出 `.msgpack`。
- **網格尺寸**：目前主要驗證於 `grid_size=8`。`grid_size=12` 或更大時，建議增加 `hidden` 與 `modes`。
- **Ensemble / TTA**：本訓練腳本產生單一模型。若要進一步提升穩健性，可訓練 3 個不同 seed 的模型並取 median（見 `brnext/inference/ensemble.py` 預留接口）。
