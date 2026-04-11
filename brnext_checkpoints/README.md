# BR-NeXT Robust SSGO Training Checkpoints

> Organized archive of training iterations for the BR-NeXT Sparse Spectral-Geometry Operator (SSGO).

## Iteration History

### `iter_1/`
- **Config**: S1=2000 / S2=2000 / S3=3000, batch=4, grid=8
- **Status**: S3 NaN crash
- **Root Cause**: `E=0` division bug in `physics_residual_loss` (unmasked air voxels)

### `iter_2/`
- **Config**: Same as iter_1
- **Status**: Completed, but phi wildly off for ID styles
- **Key Issue**: PFSF phi million-scale explosion due to singular Laplacian on floating voxels

### `iter_4a/` (Medium validation run)
- **Config**: S1=500 / S2=1000 / S3=1500
- **Status**: Completed
- **Key Fixes Applied**:
  - Removed Mixup & adversarial training
  - Gradient clipping tightened to 0.5
  - PFSF floating-voxel filter added (`scipy.ndimage.label`)
  - Simplified S2/S3 to core Huber losses
- **Result**: Loss stable, but OOD disp MAE still poor due to FEM outlier contamination of global disp scale

### `iter_4b/` (Full-scale, pre-scale-fix)
- **Config**: S1=1000 / S2=2000 / S3=3000
- **Status**: Completed
- **Result**: ID phi MAE = 24.7, OOD phi MAE = 20.0
- **Critical Issue**: `fast_fem_worker` outlier threshold (`1e6`) too loose → disp_scale exploded to ~26, crushing normal displacement targets to near-zero

### `iter_4c/` (Full-scale, tightened outlier filter)
- **Config**: S1=1000 / S2=2000 / S3=3000
- **Status**: Completed
- **Key Fix**: Outlier threshold tightened to `|disp| < grid_size * 0.5`
- **Result**: ID phi MAE = 3.38, OOD phi MAE = 2.08, FailAcc = 100%
- **Remaining Issue**: arch/tower disp still poor due to 10⁵x cross-sample scale variation (1e-6 m vs 1e-1 m)

### `iter_3/` (legacy backup)
- Backup of an intermediate milestone from earlier milestone-1 work.

---

## Archive Folder
- `archive/`: Contains earlier milestone-1 outputs (`m1`, `m1_fast`, `m1_final`, `m1_fixed`, `baseline`)

---

## Current Code State (Ready for Iteration 4d)

The following academic/engineering improvements have been implemented in the codebase and are ready for the next training run:

1. **Log-scale Displacement Supervision (P0)**
   - `DISP_REF = 1e-6` (1 micrometer)
   - `disp_to_log()` / `log_to_disp()` signed log transform
   - Eliminates 10⁵x cross-sample scale shock

2. **Anchor Mask as 7th Input Channel (P2)**
   - `build_input()` now outputs `[L,L,L,7]`
   - Provides Dirichlet boundary condition information for displacement prediction

3. **Stage 1 Disp Masking (P1)**
   - Base warm-up now supervises only `stress` and `phi`
   - Prevents teaching the physically wrong `disp ≈ occupancy` prior

4. **Progressive Consistency Schedule (P3)**
   - S3 consistency loss (`vm ≈ phi`) ramps from `0.0 → 0.1`
   - Warmup: first 1/3 steps off, middle 1/3 linear ramp, final 1/3 hold at 0.1

5. **Two-tier FEM Tolerance (P4)**
   - Tier 1: `cg_tol=1e-5, maxiter=1000`
   - Tier 2 fallback: `cg_tol=1e-3, maxiter=500` for difficult geometries
   - Boosts effective sample rate for tower/cantilever

6. **Evaluation Metrics Enrichment**
   - `ood_benchmark.py` now reports `failure_accuracy` and `safety_gap`

## How to Resume
```bash
python -m brnext.train.train_robust_ssgo \
  --grid 8 --steps-s1 1000 --steps-s2 2000 --steps-s3 3000 \
  --batch-size 4 --output brnext_output_iter4d
```

Backup command (auto-runs after training):
```powershell
New-Item -ItemType Directory -Force -Path brnext_checkpoints\iter_4d | Out-Null
Copy-Item brnext_output_iter4d\ssgo_robust_medium.msgpack brnext_checkpoints\iter_4d\
Copy-Item brnext_output_iter4d\history.json brnext_checkpoints\iter_4d\
Copy-Item brnext_output_iter4d\ood_benchmark.json brnext_checkpoints\iter_4d\
```
