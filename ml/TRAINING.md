# Block Reality ML — Training Guide

## Quick Start (3 steps)

```bash
# 1. Setup (run once on GPU VM)
bash ml/scripts/a100/setup_env.sh

# 2. Smoke test (~5 min, validates full pipeline)
bash ml/scripts/a100/launch.sh smoke

# 3. Full training
bash ml/scripts/a100/launch.sh brnext
```

## GCP VM Setup

### Option A: V100 (development, ~$2.48/hr)

```bash
gcloud compute instances create br-train-v100 \
  --zone=us-central1-a \
  --machine-type=n1-standard-8 \
  --accelerator=type=nvidia-tesla-v100,count=1 \
  --image-family=pytorch-latest-gpu \
  --image-project=deeplearning-platform-release \
  --boot-disk-size=100GB \
  --maintenance-policy=TERMINATE
```

### Option B: A100 (production, ~$3.67/hr)

```bash
gcloud compute instances create br-train-a100 \
  --zone=us-central1-a \
  --machine-type=a2-highgpu-1g \
  --accelerator=type=nvidia-tesla-a100,count=1 \
  --image-family=pytorch-latest-gpu \
  --image-project=deeplearning-platform-release \
  --boot-disk-size=200GB \
  --maintenance-policy=TERMINATE
```

### After VM creation

```bash
# SSH into the VM
gcloud compute ssh br-train-v100 --zone=us-central1-a

# Clone the repo
git clone <your-repo-url>
cd Block-Realityapi-Fast-design

# Run setup
bash ml/scripts/a100/setup_env.sh
```

## Training Targets

| Target | Command | GPU | Time | Grid | Output |
|--------|---------|-----|------|------|--------|
| Smoke test | `launch.sh smoke` | Any | ~5 min | 8^3 | Validates pipeline |
| **BR-NeXT SSGO** | `launch.sh brnext` | A100 | ~3-4h | 16^3 | `bifrost_surrogate.onnx` |
| HYBR Meta | `launch.sh hybr` | A100 | ~4-6h | 16^3 | `hybr_ssgo.onnx` |
| Reborn Style | `launch.sh reborn` | A100 | ~3h | 16^3 | Style-conditioned model |
| Benchmark | `launch.sh benchmark` | Any | ~2 min | 8^3 | Performance report |

## Model Input/Output Contract

All SSGO-family models follow this contract:

```
Input:  [B, L, L, L, 7]   7 channels:
  ch0: occupancy         (1.0 = solid, 0.0 = air)
  ch1: E / 200 GPa       (Young's modulus, normalized)
  ch2: nu                (Poisson's ratio, raw)
  ch3: rho / 7850        (density, normalized to steel)
  ch4: rcomp / 250 MPa   (compression strength, normalized)
  ch5: rtens / 500 MPa   (tension strength, normalized)
  ch6: anchor            (1.0 = fixed support, 0.0 = free)

Output: [B, L, L, L, 10]  10 channels:
  ch0-5: stress tensor   (sigma_xx, yy, zz, tau_xy, yz, xz)
  ch6-8: displacement    (u_x, u_y, u_z, log-transformed)
  ch9:   phi             (PFSF-compatible potential field)
```

## Deploying Trained Models to Minecraft

After training completes, copy the ONNX file to your Minecraft instance:

```bash
# The model file will be at:
#   ml/experiments/outputs/brnext/a100_cmfd/brnext_ssgo.onnx
# Or for robust trainer:
#   ml/experiments/outputs/brnext/smoke_test/ssgo_robust_medium.onnx

# Copy to Minecraft config directory:
cp <onnx-file> <minecraft-root>/config/blockreality/models/bifrost_surrogate.onnx
```

The mod's `BIFROSTModelRegistry` will auto-detect and load the model on startup.

### Available model slots

| Filename | Purpose | Training source |
|----------|---------|----------------|
| `bifrost_surrogate.onnx` | Structural physics (stress+phi) | BR-NeXT / HYBR |
| `bifrost_fluid.onnx` | Water simulation | brml fluid trainer |
| `bifrost_lod.onnx` | LOD classification | brml LOD trainer |
| `bifrost_collapse.onnx` | Collapse prediction | brml collapse trainer |

## Advanced: Manual Training

### BR-NeXT Robust SSGO (recommended)

```bash
source ml/.venv/bin/activate
python -m brnext.train.train_robust_ssgo \
    --grid 16 \
    --steps-s1 5000 \
    --steps-s2 5000 \
    --steps-s3 10000 \
    --batch-size 8 \
    --lr 0.001 \
    --hidden 48 \
    --modes 8 \
    --output ml/experiments/outputs/brnext/custom_run
```

### HYBR Meta-Trainer

```bash
python ml/scripts/a100/train_hybr_meta.py \
    --config ml/configs/hybr_a100.yaml
```

### Training Stages (BR-NeXT)

1. **Stage 1 — LEA Warm-up**: Low-fidelity alignment using material properties as pseudo-targets. Fast, builds basic input→output mapping.

2. **Stage 2 — PFSF Distillation**: Distills phi field from CPU PFSF Jacobi solver. Teaches the model structural load flow patterns.

3. **Stage 3 — FEM Fine-tuning**: Fine-tunes against full FEM solutions (stress + displacement + phi). Highest fidelity, most expensive per sample.

## GPU Selection Guide

| GPU | VRAM | Recommended for |
|-----|------|----------------|
| Tesla T4 | 16 GB | Inference only / minimal smoke tests |
| **V100** | **16 GB** | **Development, debugging, small-scale training** |
| V100 32GB | 32 GB | Larger batch sizes |
| **A100 40GB** | **40 GB** | **Production training (best value)** |
| A100 80GB | 80 GB | Config files target this; production training |
| L4 | 24 GB | Good cost/performance for training |
| H100 | 80 GB | Fastest, if budget allows |

## Troubleshooting

### JAX not using GPU

```bash
python -c "import jax; print(jax.default_backend())"
# If output is "cpu", reinstall JAX:
pip install "jax[cuda12]" --upgrade --force-reinstall
```

### Out of memory

Reduce batch size or grid size:
```bash
python -m brnext.train.train_robust_ssgo --grid 8 --batch-size 2
```

### FEM solver slow (Stage 3)

Stage 3 is CPU-bound (FEM ground truth generation). Increase `n_fem_workers` in config, or use a VM with more CPU cores.
