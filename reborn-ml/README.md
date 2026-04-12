# reborn-ml

**StyleConditionedSSGO** — 風格條件化頻譜神經算子  
A100 級生成式建築設計訓練包（純 ML，無 Minecraft 依賴）

---

## 概覽

`reborn-ml` 從 [Block Reality](../Block%20Reality/) 專案中提取純 ML 訓練管線，
包含論文核心貢獻 **StyleConditionedSSGO**，可在 A100/H100 伺服器上獨立執行。

**核心架構**：
```
輸入 [B,L,L,L,6] + style_id [B]
      │
 SpectralGeometryEncoder → z_geom    ← 零參數幾何編碼
 StyleEmbedding(style_id) → z_style  ← 可訓練
      │
 z = z_geom + α·z_style              ← α 為 softplus 門控
      │
 HyperMLP → SpectralWeightHead → CP 因子
 SpectralWeightBank(W_base + α·ΔW)
      │
 Global FNO + Focal VAG + 門控融合 + Backbone FNO
      │
 應力頭(6) + 位移頭(3) + phi頭(1) + 風格SDF頭(1)
      │
 輸出 [B,L,L,L,11]
```

**四階段訓練**：
| 階段 | 名稱 | 步數 | 損失 |
|------|------|------|------|
| 1 | 物理預訓練（LEA） | 3,000 | `freq_align + 0.05×physics_residual` |
| 2 | 風格條件化蒸餾 | 3,000 | `style_consistency + 0.5×spectral_fid` |
| 3 | 聯合微調 | 5,000 | `reborn_total_loss`（7 任務 Kendall 加權） |
| 4 | 對抗精修（可選） | 2,000 | `7 任務 + 0.1×hinge_GAN` |

預估 A100 時間：**~3 小時**（13,000 步 × ~0.8s/步）

---

## 安裝

### 從本倉庫安裝（開發模式）

```bash
# 在倉庫根目錄
pip install -e reborn-ml/

# 確認相鄰包可訪問（首次使用需執行）
python -c "import reborn_ml; print(reborn_ml.__version__)"
```

### A100 GPU 環境

```bash
# CUDA 12 JAX
pip install "jax[cuda12]>=0.4.20" \
    -f https://storage.googleapis.com/jax-releases/jax_cuda_releases.html

pip install -e reborn-ml/
```

---

## 快速開始

### 10 步冒煙測試（CPU，無 GPU）

```python
from reborn_ml.config import TrainingConfig
from reborn_ml.training.style_trainer import RebornStyleTrainer

cfg = TrainingConfig(
    grid_size=8,
    stage1_steps=10,
    stage2_steps=10,
    stage3_steps=10,
    stage4_steps=0,
)
trainer = RebornStyleTrainer(cfg)
params, model, history = trainer.run()
print("輸出通道：", 11)  # σ×6 + u×3 + φ×1 + style_sdf×1
```

### 模型前向推理

```python
import jax, jax.numpy as jnp
from reborn_ml.models.style_net import StyleConditionedSSGO

model = StyleConditionedSSGO()
rng = jax.random.PRNGKey(0)
x = jnp.zeros((1, 16, 16, 16, 6))  # [B, L, L, L, C_in]
sid = jnp.array([1])                # style_id（0=Gaudí, 1=Zaha）

variables = model.init(rng, x, sid, update_stats=False)
out = model.apply(variables, x, sid, update_stats=False)
print(out.shape)  # (1, 16, 16, 16, 11)
```

### A100 完整訓練

```bash
# 單卡 A100（~3 小時）
python -m reborn_ml.experiments.exp_006_style_training --grid 16 --steps 13000

# 或使用 CLI 入口
reborn-train --grid 16 --steps 13000 --output ./checkpoints/

# 消融研究
reborn-ablation

# 效能基準
reborn-benchmark
```

---

## 套件結構

```
reborn-ml/
├── pyproject.toml
├── requirements.txt
└── src/
    └── reborn_ml/
        ├── __init__.py            # 版本 + 依賴自動發現
        ├── config.py              # TrainingConfig, A100_TRAINING_CONFIG
        ├── models/
        │   ├── diff_sdf_ops.py   # JAX 可微分 SDF 基元
        │   ├── diff_gaudi.py     # 可微分 GaudiStyle（可學習參數）
        │   ├── diff_zaha.py      # 可微分 ZahaStyle（semi-Lagrangian 對流）
        │   └── style_net.py      # StyleConditionedSSGO + Discriminator
        ├── training/
        │   ├── losses.py         # 7 任務損失（Kendall 不確定性加權）
        │   ├── data_pipeline.py  # RebornDataPipeline（AsyncBuffer 整合）
        │   ├── evaluator.py      # RebornEvaluator（合規性比、FID、LaTeX）
        │   └── style_trainer.py  # RebornStyleTrainer（四階段協調）
        ├── stages/
        │   └── diff_topo.py      # 可微分 SIMP（jax.grad + OC bisection）
        └── experiments/
            ├── exp_006_style_training.py
            ├── exp_007_ablation.py
            └── exp_008_a100_benchmark.py
```

---

## 依賴架構

`reborn-ml` 在同一倉庫中依賴以下鄰居包（自動發現）：

| 包 | 路徑 | 用途 |
|----|------|------|
| `hybr` | `../HYBR/hybr/` | AdaptiveSSGO、HyperMLP、SpectralWeightBank |
| `brnext` | `../BR-NeXT/brnext/` | 損失函數、AsyncBuffer、VoxelGAT |
| `brml` | `../brml/brml/` | pmap 多 GPU 訓練 |

若在倉庫外部使用，需手動安裝或在 `PYTHONPATH` 中指定這些包。

---

## 損失函數

7 任務 Kendall 不確定性加權損失：

```
L_total = Σ_{i=1}^{7} L_i · exp(-2σ_i) / 2 + σ_i

其中 σ_i = exp(log_sigma[i])（可學習，Stage 3 以純 Adam 訓練）
```

| 任務 | 損失 | 來源 |
|------|------|------|
| stress | hybrid_task_loss.stress | BR-NeXT |
| disp | hybrid_task_loss.disp | BR-NeXT |
| phi | hybrid_task_loss.phi | BR-NeXT |
| consistency | hybrid_task_loss.consistency | BR-NeXT |
| physics | physics_residual_loss | BR-NeXT |
| style_con | style_consistency_loss（遮罩 L1） | reborn-ml |
| spectral | spectral_style_fid（頻域 Fréchet） | reborn-ml |

---

## 論文引用

```bibtex
@article{blockreality2025style,
  title   = {StyleConditionedSSGO: Style-Conditioned Spectral Neural Operators
             for Generative Architectural Design},
  author  = {Block Reality Research},
  journal = {arXiv preprint},
  year    = {2025},
}
```

---

## 授權

MIT License — 詳見 [LICENSE](LICENSE)
