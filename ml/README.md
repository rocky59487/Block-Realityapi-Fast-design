# ML Stack — Organized Directory Layout

This directory contains the complete Block Reality machine-learning pipeline:
**BR-NeXT → HYBR → reborn-ml**, plus the underlying **brml** BIFROST core.

## Directory Map

```
ml/
├── brml/                     # BIFROST ML core (datasets, FEM, export)
├── BR-NeXT/                  # Production surrogate trainer (SSGO, CMFD, ONNX)
├── HYBR/                     # HyperNetwork meta-learning engine
├── reborn-ml/                # Style-conditioned generative design (A100 tier)
├── experiments/
│   ├── checkpoints/          # Saved training states
│   │   ├── brnext/
│   │   └── reborn/
│   ├── outputs/              # Training artifacts, ONNX models, reports
│   │   ├── brnext/
│   │   ├── hybr/
│   │   ├── hybr_minecraft/   # ← Demo ONNX ready for Minecraft
│   │   └── reborn/
│   └── cache/                # Shared DuckDB + Zarr cache
├── scripts/
│   ├── local/                # Windows/local CPU smoke tests & builders
│   │   ├── monument_validation.py
│   │   ├── build_hybr_for_minecraft.py
│   │   └── test_all_stages.py
│   └── a100/                 # Linux/A100 full-scale training launchers
│       ├── setup_env.sh
│       ├── launch.sh
│       ├── train_brnext_cmfd.py
│       ├── train_hybr_meta.py
│       └── train_reborn_style.py
├── configs/                  # A100 YAML hyperparameter configs
│   ├── brnext_a100.yaml
│   ├── hybr_a100.yaml
│   └── reborn_a100.yaml
├── .venv/                    # Unified Python environment (Windows)
├── setup_venv.ps1            # One-command venv builder (copies from legacy)
└── run_local.ps1             # Convenience runner for local scripts
```

## Quick Start

### Local Windows (CPU smoke test / Minecraft ONNX builder)

```powershell
cd ml
.\.venv\Scripts\python scripts\local\build_hybr_for_minecraft.py
```

Or use the convenience wrapper:
```powershell
.\run_local.ps1 -Task build_hybr
```

The resulting `hybr_for_minecraft.onnx` will be placed in:
`ml/experiments/outputs/hybr_minecraft/`

### A100 Linux (Full-scale training)

```bash
# 1. Setup environment (run once per node)
bash ml/scripts/a100/setup_env.sh

# 2. Launch training
bash ml/scripts/a100/launch.sh brnext   # or hybr / reborn
```

All outputs are written under `ml/experiments/outputs/` and `ml/experiments/checkpoints/`.

## What Was Moved

- **Packages**: `brml/`, `BR-NeXT/`, `HYBR/`, `reborn-ml/` → grouped under `ml/`
- **Checkpoints**: `brnext_checkpoints/`, `reborn_ml_checkpoints/` → `ml/experiments/checkpoints/`
- **Outputs**: `brnext_output/`, `hybr_output/`, `hybr_minecraft_output/` → `ml/experiments/outputs/`
- **Scripts**: `test_all_stages.py`, `monument_validation.py`, `build_hybr_for_minecraft.py` → `ml/scripts/local/`

No data was deleted. The legacy venv at `reborn-ml/.venv/` was preserved as a backup and its packages were copied into `ml/.venv/`.
