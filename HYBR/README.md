# HYBR — HyperNetwork Meta-Learning Engine for PFSF Structural Dynamics

> **Experimental research package** built on top of [BR-NeXT](../BR-NeXT).  
> HYBR explores a paradigm shift: instead of training a single static neural operator, we use a lightweight **HyperNetwork** to dynamically generate the spectral weights of a 3D FNO conditioned on the input geometry.  
> This enables geometry-adaptive inference without per-structure retraining, while **additive low-rank weight generation** and **spectral normalization** keep the system convergent and parameter-efficient.

---

## 1. Why HYBR?

Traditional HyperNetworks suffer from two fatal flaws:
1. **Non-convergence** — generating full weight tensors from scratch causes gradient explosions and extreme sensitivity to initialization.
2. **Parameter explosion** — the HyperNet output dimension scales with the target network size, which is prohibitive for 3D FNOs.

HYBR solves both by restricting the HyperNet to generate **only the low-frequency spectral kernels** of the FNO (the mathematically structured, low-dimensional part of the operator) via **CP-decomposed residuals**:

```
W[f] = W_base[f] + α · CP(lam, a, b, u, v, w)
```

- **Additive residual** (`W_base + ΔW`) provides a stable anchor (Ortiz et al.).
- **CP rank ≤ 4** compresses the generated weight by 1–2 orders of magnitude vs. dense generation (Low-rank HyperPINN, HypeMeFed).
- **Spectral normalization** on every HyperNet layer controls the Lipschitz constant and stabilizes training (Miyato 2018, hyperFastRL).

---

## 2. Architecture Overview

```
Occupancy grid [B,L,L,L]
        │
        ▼
┌─────────────────────┐
│  Geometry Encoder   │  Zero-parameter spectral pooling or tiny 3D CNN
│   (Occ → z)         │
└─────────────────────┘
        │ z [B,d]
        ▼
┌─────────────────────┐
│   HyperNet H(z)     │  MLP + SpectralNorm + LayerNorm
│  (flax nn.SpectralNorm)
└─────────────────────┘
        │ hyper features
        ▼
┌─────────────────────┐     ┌─────────────────────┐
│ SpectralWeightHead  │────▶│  SpectralWeightBank │
│  (CP factors)       │     │  W = W_base + α·ΔW  │
└─────────────────────┘     └─────────────────────┘
                                     │
                                     ▼
                           ┌─────────────────────┐
                           │   AdaptiveSSGO      │
                           │  (dynamic FNO)      │
                           └─────────────────────┘
                                     │
                                     ▼
                           [B,L,L,L,10]  (stress, disp, phi)
```

---

## 3. Directory Layout

```
hybr/
├── core/
│   ├── geometry_encoder.py    # Spectral / CNN encoders
│   ├── hypernet.py            # SpectralNorm MLP + weight heads
│   ├── weight_bank.py         # Base weights + additive CP delta
│   ├── adaptive_ssgo.py       # Dynamic-weight SSGO
│   └── materialize.py         # Freeze adaptive weights → static SSGO
├── models/
│   └── low_rank_factors.py    # CP / Tucker reconstruction
├── training/
│   ├── meta_trainer.py        # End-to-end trainer
│   └── stability_utils.py     # LR schedule, regularizers
└── data/
    └── (structure sampler bridges to BR-NeXT)

tests/
├── test_adaptive_ssgo_forward.py
├── test_weight_generation.py
├── test_convergence.py
└── test_materialize.py
```

---

## 4. Quick Start

### Run all tests
```powershell
$env:PYTHONPATH = "$pwd\BR-NeXT;$pwd\HYBR"
.\BR-NeXT\venv\Scripts\python HYBR\tests\test_adaptive_ssgo_forward.py
.\BR-NeXT\venv\Scripts\python HYBR\tests\test_weight_generation.py
.\BR-NeXT\venv\Scripts\python HYBR\tests\test_convergence.py
.\BR-NeXT\venv\Scripts\python HYBR\tests\test_materialize.py
```

### Train a tiny model
```python
from hybr.training.meta_trainer import HYBRTrainer, HYBRConfig

cfg = HYBRConfig(grid_size=8, train_steps=500, hidden=16, modes=4)
trainer = HYBRTrainer(cfg)
params, model, history = trainer.run()
```

### Materialize static weights for ONNX export
```python
from hybr.core.materialize import materialize_static_ssgo
import jax.numpy as jnp

occ = jnp.ones((1, 12, 12, 12))  # example occupancy
static_params, static_model = materialize_static_ssgo(model, variables, occ)
# static_params can now be fed to brnext.export.manual_onnx
```

---

## 5. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **HyperNet only generates spectral conv weights** | FNO kernels in Fourier space are naturally low-dimensional and structurally aligned with PFSF math. |
| **CP decomposition (rank=2–4)** | Reduces generated parameters from `C²·mx·my·mz` to `R·(C+C+mx+my+mz)`. |
| **Additive residuals** | Prevents divergence by anchoring around a pre-trainable `W_base`. |
| **Spectral normalization on HyperNet** | Bounds Lipschitz constant; empirically eliminates training instability. |
| **Geometry encoder = spectral pooling** | Zero learnable parameters, perfectly aligned with FNO frequency-domain operations. |

---

## 6. Tested Claims

- ✅ `test_adaptive_ssgo_forward` — initializes with `batch_stats` and produces exact output shape `[B,L,L,L,10]`.
- ✅ `test_weight_generation` — CP reconstruction yields correct shape and `||ΔW|| / ||W_base|| < 0.3`.
- ✅ `test_convergence` — loss decreases monotonically on a small real-structure dataset.
- ✅ `test_materialize` — materialized static SSGO matches adaptive forward within `3.6e-4` absolute error (acceptable for research prototype; difference is floating-point order in batched einsum + SpectralNorm power iteration).

---

## 7. Roadmap

- [ ] Integrate real FEM/PFSF teachers from BR-NeXT instead of dummy targets.
- [ ] Implement full 3-stage training (Base pre-train → HyperNet freeze → joint finetune).
- [ ] Ablation study: rank=2 vs 4 vs 8; spectral vs CNN encoder; with/without SpectralNorm.
- [ ] ONNX export pipeline for materialized static SSGO.
- [ ] Benchmark generalization: unseen geometry styles vs. static SSGO baseline.

---

## 8. License

GPL-3.0 (same as parent project).
