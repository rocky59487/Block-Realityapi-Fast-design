# Block Reality — API + Fast Design

![Minecraft Forge](https://img.shields.io/badge/Forge-1.20.1--47.4.13-orange)
![Java 17](https://img.shields.io/badge/Java-17-blue)
![Vulkan](https://img.shields.io/badge/Vulkan-Compute%20%2B%20RT-red)
![ONNX Runtime](https://img.shields.io/badge/ONNX%20Runtime-1.17.3-purple)
![JAX](https://img.shields.io/badge/JAX%2FFlax-ML%20Training-yellow)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

**GPU-accelerated structural physics simulation engine for Minecraft Forge 1.20.1.**
**Minecraft Forge 1.20.1 GPU 加速結構物理模擬引擎。**

> *If it wouldn't stand in the real world, it won't stand here.*
> *現實中撐不住的，這裡也撐不住。*

---

## Overview / 概述

Block Reality transforms every Minecraft block into a structural element with real engineering properties — compressive strength (MPa), tensile strength, shear resistance, density (kg/m³), and Young's modulus (GPa). Structural integrity is evaluated every server tick by the **PFSF (Potential Field Structure Failure)** engine, which runs entirely on the GPU via Vulkan Compute. Structures that lose support collapse dynamically.

Block Reality 將每個 Minecraft 方塊轉化為具有真實工程屬性的結構元素——抗壓強度（MPa）、抗拉強度、剪切阻力、密度（kg/m³）與楊氏模量（GPa）。每個 server tick，**PFSF（勢場結構失效）** 引擎透過 Vulkan Compute 完全在 GPU 上評估結構完整性。失去支撐的結構會動態崩塌。

The system integrates three major layers:

1. **PFSF GPU Solver** — Potential field diffusion with adaptive RBGS→PCG switching, Chebyshev semi-iteration, V-Cycle multigrid, Ambati 2015 phase-field fracture, and Morton-tiled GPU memory layout.
2. **BIFROST ML Subsystem** — FNO3D surrogate solver (ONNX Runtime) that replaces PFSF for irregular island geometries, trained with a hybrid FEM + PFSF multi-teacher pipeline using PCGrad gradient surgery and uncertainty-weighted multi-task loss.
3. **Vulkan Rendering** — Hardware ray tracing pipeline (RTX 30xx+) with ReLAX denoiser, SDF ray marching (256³ JFA volume for GI/AO/soft shadows), and volumetric lighting.

---

## Module Structure / 模組結構

```
Block-Realityapi-Fast-design/
├── Block Reality/                      Gradle multi-project root
│   ├── api/                            com.blockreality.api  (Foundation Layer)
│   └── fastdesign/                     com.blockreality.fastdesign  (Extension Layer)
├── brml/                               Python ML training pipeline (JAX/Flax + ONNX)
└── .github/                            Automated Claude↔Jules workflow
```

**Dependency direction:** `fastdesign` → `api` (never the reverse). `api` packages under `client/` are `@OnlyIn(Dist.CLIENT)` and must never be referenced server-side.

---

## Architecture / 架構

```
api/  (com.blockreality.api)
  physics/pfsf/       PFSF Engine — GPU potential-field solver
    ├─ PFSFEngine             Main entry, per-tick orchestration
    ├─ PFSFDispatcher         Adaptive RBGS→PCG switching + V-Cycle scheduling
    ├─ PFSFScheduler          Chebyshev ω table, spectral radius estimation,
    │                         residual-driven adaptive steps, divergence detection
    ├─ PFSFDataBuilder        Source/conductivity assembly, sigmaMax normalization,
    │                         Morton tiled layout, ICuringManager hydration scaling
    ├─ PFSFIslandBuffer       Per-island GPU buffer layout (VMA single allocation):
    │                         phi(×2), source, conductivity[6N], type, fail_flags,
    │                         maxPhi, rcomp, rtens, blockOffsets, macroResidual,
    │                         hField, dField, hydrationBuf
    ├─ PFSFAsyncCompute       Triple-buffered fence-based async compute
    ├─ PFSFFailureRecorder    failure_scan → failure_compact → CollapseManager
    ├─ PFSFPhaseFieldRecorder Ambati 2015 phase-field evolution (hField write lock)
    ├─ PFSFPCGRecorder        Conjugate gradient solver (Jacobi preconditioned)
    ├─ AMGPreconditioner      Smoothed Aggregation AMG setup (CPU, GPU integration pending)
    ├─ HybridPhysicsRouter    ShapeClassifier → route island to PFSF or FNO backend
    ├─ OnnxPFSFRuntime        ONNX Runtime inference (CUDA→CPU fallback)
    └─ BIFROSTIntegrationTest ML surrogate validation suite

  physics/fluid/      PFSF-Fluid Engine (potential diffusion + structure coupling)
    ├─ FluidGPUEngine         GPU path orchestrator (Phase 2, currently disabled)
    ├─ FluidJacobiRecorder    Jacobi diffusion dispatch (GPU_PATH_ENABLED = false)
    ├─ FluidCPUSolver         CPU fallback (Jacobi + Ghost Cell Neumann BC)
    ├─ FluidStructureCoupler  Fluid pressure → PFSF coupling (1-tick delay)
    ├─ FluidAsyncCompute      Async compute queue integration
    └─ FluidRegionRegistry    Connected fluid region tracking

  physics/
    ├─ StructureIslandRegistry  Connected component tracking + dirty epoch
    ├─ UnionFind                Path-compressed union-find for real-time checks
    ├─ SupportPathAnalyzer      BFS anchor-seeded support path analysis
    ├─ LoadType                 ASCE 7-22 load combination types (stub: LRFDLoadCombiner)
    ├─ FailureType              11 failure modes (see §Failure Types)
    └─ EmEngine                 Electromagnetic diffusion (∇²φ = ρ_charge / ε)

  material/
    ├─ BlockTypeRegistry        Central thread-safe material registry
    ├─ DefaultMaterial          12 built-in materials with Eurocode safety factors
    ├─ CustomMaterial.Builder   Fluent builder for custom materials
    └─ DynamicMaterial          RC fusion composite (97% concrete / 3% rebar)

  collapse/
    ├─ CollapseManager          Physics failure → block destruction + particle effects
    └─ CollapseJournal          Causal chain tracking + reversible rollback

  blueprint/
    ├─ Blueprint                Structure ↔ NBT serialization (versioned migration)
    └─ BlueprintIO              File I/O with atomic writes + LitematicImporter

  sph/                SPH stress engine
    ├─ Monaghan 1992 cubic spline kernel
    └─ Teschner 2003 spatial hash neighbor search

  chisel/             10×10×10 voxel sub-block shape system

  client/rendering/   (CLIENT ONLY)
    ├─ vulkan/        BRVulkanDevice, VkMemoryAllocator (VMA), BRVulkanBVH
    ├─ render/rt/     BRVulkanBVH, BRSDFRayMarcher, BRVolumetricLighting, BRReLAXDenoiser
    └─ render/        GreedyMesher, AnimationEngine, RenderPipeline

  node/               BRNode graph system, EvaluateScheduler (topological sort)
  spi/                ModuleRegistry + 9 SPI extension interfaces

fastdesign/  (com.blockreality.fastdesign)
  client/             3D hologram preview, HUD overlay, GUI screens, chisel tools
  client/node/        Node editor — 90+ node implementations:
                        Material / Physics / Render / Tool / Output nodes
  command/            /fd command system, undo manager
  construction/       Construction event handling, rebar placement
  network/            Packet sync (hologram state, build actions)

brml/                 Python ML training pipeline
  models/             FNO3DMultiField, FNO3D, SpectralConv3D, CollapsePredictor,
                      FNOFluid, LODClassifier, NodeRecommender
  fem/                FEM ground-truth solver (hex8 elements, CG solver)
  pipeline/           auto_train.py (hybrid FEM+PFSF pipeline with PCGrad + uncertainty weighting)
  export/             ONNX export (jax2onnx → bifrost_surrogate.onnx)
  train/              Per-model training scripts
  ui/                 TUI + Web UI (Gradio)
```

---

## PFSF Physics Engine / PFSF 物理引擎

### Solver Architecture / 求解器架構

PFSF models structural integrity as steady-state potential field diffusion:

```
A · φ = b
```

where `A` is the 26-connected weighted Laplacian (conductivity-weighted), `φ` is the structural potential field, and `b` is the source term assembled from Timoshenko beam theory. Failure occurs when outward flux `|∇φ|` exceeds material thresholds.

PFSF 將結構完整性建模為穩態勢場擴散 `A·φ = b`，其中 `A` 為 26 連通加權拉普拉斯算子，`φ` 為結構勢場，`b` 為根據 Timoshenko 梁理論組裝的源項。當外向通量 `|∇φ|` 超過材料閾值時觸發失效。

### Per-Tick Execution Pipeline / 每 Tick 執行管線

```
PFSFEngine.onServerTick()
  │
  ├─ PFSFAsyncCompute.pollCompleted()          ← non-blocking fence check (triple-buffered)
  ├─ StructureIslandRegistry.getDirtyIslands() ← incremental dirty epoch tracking
  │
  ├─ PFSFDataBuilder (per dirty island)
  │    ├─ Timoshenko source assembly
  │    ├─ sigmaMax normalization:
  │    │     conductivity[i] /= sigmaMax
  │    │     source[i]       /= sigmaMax
  │    │     maxPhi[i]       /= sigmaMax
  │    │     rcomp[i]        /= sigmaMax
  │    │     rtens[i]        /= sigmaMax
  │    ├─ ICuringManager hydration scaling: σ(t) = σ × H^0.5, Gc(t) = Gc × H^1.5
  │    └─ Morton tiled layout (8×8×8 micro-blocks, Z-order)
  │
  ├─ PFSFDispatcher (adaptive solver selection)
  │    ├─ Phase 1: RBGS 8-color smoother (high-frequency elimination)
  │    │    ├─ 26-connected Laplacian stencil:
  │    │    │     6 faces   × σ_face × 1.0
  │    │    │    12 edges   × σ_edge × SHEAR_EDGE_PENALTY   (0.35)
  │    │    │     8 corners × σ_corn × SHEAR_CORNER_PENALTY (0.15)
  │    │    ├─ Chebyshev semi-iteration (WARMUP_STEPS=2 pure Jacobi first)
  │    │    │     ω₀ = 1.0
  │    │    │     ω₁ = 2 / (2 − ρ²)
  │    │    │     ωₖ = 4 / (4 − ρ² × ωₖ₋₁)    (k ≥ 2)
  │    │    │     ρ_spec = cos(π / L_max) × SAFETY_MARGIN (0.95)
  │    │    └─ V-Cycle every MG_INTERVAL=4 steps
  │    │         mg_restrict (conductivity-weighted) → Jacobi coarse → mg_prolong
  │    │
  │    ├─ Phase 2: PCG Jacobi-preconditioned (low-frequency convergence)
  │    │    ├─ matvec: 26-connected (identical stencil to RBGS — CG correctness)
  │    │    ├─ preconditioner: z = r / diag(A₂₆)  (computed on-the-fly)
  │    │    ├─ inner product: r·z  (not r·r)
  │    │    └─ Stagnation < 5% residual drop → early switch
  │    │
  │    └─ Convergence skip (v3):
  │         stableTickCount ≥ 3 → skip STABLE_TICK_SKIP_COUNT ticks
  │         macroResidual < CONVERGENCE_SKIP_THRESHOLD → LOD_DORMANT
  │
  ├─ PFSFFailureRecorder
  │    ├─ failure_scan.comp.glsl
  │    ├─ failure_compact.comp.glsl  (packed: flatIndex<<4 | failType)
  │    └─ phi_reduce_max.comp.glsl   (two-pass parallel max)
  │
  ├─ PFSFPhaseFieldRecorder (Ambati 2015 AT2)
  │    ├─ H_i = max(H_i, ψ_e_i)   ← hField monotone; smoother writes, phase_field READ-ONLY
  │    ├─ d_new = (H_i + l₀²∇²d) / (H_i + Gc/(2l₀))
  │    │         l₀=1.5 blocks, relax=0.3, fracture threshold=0.95
  │    └─ Gc(t) = Gc_base × hydration^1.5  (Bažant 1989 MPS)
  │
  └─ submitAsync() → fence → CollapseManager.triggerPFSFCollapse()
```

### Compute Shaders / 計算著色器

**Critical invariant:** RBGS, Jacobi, and PCG matvec **must** use the identical 26-connected stencil with SHEAR_EDGE_PENALTY=0.35 and SHEAR_CORNER_PENALTY=0.15. Any deviation causes CG convergence to an incorrect solution or multigrid divergence.

| Shader | WG | Purpose |
|--------|----|---------|
| `rbgs_smooth.comp.glsl` | 256 | RBGS 8-color in-place; color=(x%2)\|(y%2)<<1\|(z%2)<<2 |
| `jacobi_smooth.comp.glsl` | 8×8×4 | Jacobi + Chebyshev ω; shared mem tile 10×10×6 |
| `mg_restrict.comp.glsl` | — | Conductivity-weighted restriction fine→coarse |
| `mg_prolong.comp.glsl` | — | Trilinear prolongation coarse→fine |
| `pcg_matvec.comp.glsl` | — | 26-connected Ap; identical stencil to RBGS |
| `pcg_update.comp.glsl` | — | Dual-mode: init(r,z,p) or iterate(α, residual); z=r/diag(A) |
| `pcg_direction.comp.glsl` | — | p = z + β·p; β = rᵀz_new / rᵀz_old |
| `pcg_dot.comp.glsl` | — | Two-pass dot product; subgroup ops |
| `failure_scan.comp.glsl` | 256 | 5-mode failure; macro-block residual output |
| `failure_compact.comp.glsl` | — | Stream compaction; 44-byte update record |
| `phi_reduce_max.comp.glsl` | — | Two-pass max reduction N→ceil(N/512)→1 |
| `phase_field_evolve.comp.glsl` | — | Ambati 2015; hField READ-ONLY; ∇²d with NaN guard |
| `sparse_scatter.comp.glsl` | — | SoA scatter; update record 44 bytes |
| `morton_utils.glsl` | — | (include) 8×8×8 Z-order; expandBits magic bits |

Fluid shaders (`compute/fluid/`):

| Shader | Purpose |
|--------|---------|
| `fluid_jacobi.comp.glsl` | H(i)=φ+ρgh; Ghost Cell Neumann BC; diffusionRate ∈ [0, 0.45] |
| `fluid_pressure.comp.glsl` | P=ρ·g·h_fluid after Jacobi convergence |
| `fluid_boundary.comp.glsl` | Solid wall detection → boundaryPressure[] for PFSF coupling |

### Key Constants / 關鍵常數

```
// 26-Connectivity stencil
SHEAR_EDGE_PENALTY   = 0.35f     // 12 edge neighbors
SHEAR_CORNER_PENALTY = 0.15f     // 8 corner neighbors

// Chebyshev
WARMUP_STEPS = 2                 // Pure Jacobi before Chebyshev
MAX_OMEGA    = 1.98f
SAFETY_MARGIN = 0.95f            // ρ_spec = cos(π/L_max) × 0.95

// V-Cycle
MG_INTERVAL = 4                  // Insert V-Cycle every N Jacobi steps

// Convergence
CONVERGENCE_SKIP_THRESHOLD = 0.01f
EARLY_TERM_TIGHT           = 0.001f
EARLY_TERM_LOOSE           = 0.01f
STABLE_TICK_SKIP_COUNT     = 3
DIVERGENCE_RATIO           = 1.5f

// Iteration budgets
STEPS_MINOR=4  STEPS_MAJOR=16  STEPS_COLLAPSE=32  (capped at 128)

// Phase-field (Ambati 2015)
PHASE_FIELD_L0              = 1.5f   // regularisation length (blocks)
PHASE_FIELD_RELAX           = 0.3f
PHASE_FIELD_FRACTURE_THRESHOLD = 0.95f
G_C_CONCRETE=100  G_C_STEEL=50000  G_C_WOOD=300  // J/m²

// Failure
MAX_FAILURE_PER_TICK = 2000
PHI_ORPHAN_THRESHOLD = 1e6f

// Workgroups
WG_X=8 WG_Y=8 WG_Z=4 (Jacobi)   WG_RBGS=WG_SCAN=256

// Morton
MORTON_BLOCK_SIZE = 8            // 8×8×8 micro-blocks

// LOD
LOD_FULL=0  LOD_STANDARD=1  LOD_COARSE=2  LOD_DORMANT=3  LOD_WAKE_TICKS=5

// Voxel types
VOXEL_AIR=0  VOXEL_SOLID=1  VOXEL_ANCHOR=2

// Failure flags
FAIL_OK=0  FAIL_CANTILEVER=1  FAIL_CRUSHING=2  FAIL_NO_SUPPORT=3  FAIL_TENSION=4
```

### sigmaMax Normalisation Contract / sigmaMax 正規化約定

**Every buffer carrying a threshold must be normalised before GPU upload.** `phi` is NOT divided — since `A·φ = b`, dividing both sides by `sigmaMax` cancels, so `failure_scan` comparisons (`flux > rcomp[i]`) remain correct in normalised space.

```
conductivity[i] /= sigmaMax   → domain [0, 1]
source[i]       /= sigmaMax   → proportional
maxPhi[i]       /= sigmaMax   → cantilever threshold
rcomp[i]        /= sigmaMax   → crushing threshold
rtens[i]        /= sigmaMax   → tension threshold
phi field        unchanged    → normalisation cancels in A·φ=b
```

FNO output phi (channel 9) is in physical scale and **must be divided by sigmaMax** before entering `failure_scan`. This is applied in `OnnxPFSFRuntime.infer()`.

### LOD System / LOD 系統

```
LOD_FULL (0)     Full RBGS+PCG each tick; phase-field enabled
LOD_STANDARD (1) RBGS only; phase-field every 2 ticks
LOD_COARSE (2)   Coarse Jacobi only; phase-field skipped
LOD_DORMANT (3)  No dispatch; wakes on dirty voxel (LOD_WAKE_TICKS=5)
```

### Hybrid Physics Router / 混合物理路由

`HybridPhysicsRouter` routes each island to PFSF GPU or BIFROST FNO:

```
!fnoAvailable            → PFSF (fast path)
cache hit (same epoch)   → cached decision
ShapeClassifier score ≥ 0.45 → FNO
ShapeClassifier score <  0.45 → PFSF
```

Decisions are epoch-cached per island via `ConcurrentHashMap<Integer, CachedDecision>`. Thread-safe counters track PFSF/FNO dispatch ratios for diagnostics.

---

## BIFROST ML Subsystem / BIFROST 機器學習子系統

BIFROST is the machine-learning surrogate layer that replaces PFSF for geometrically irregular islands where the iterative solver is slow to converge. The primary model is `bifrost_surrogate.onnx` — a Fourier Neural Operator trained with a hybrid FEM + PFSF multi-teacher pipeline.

### Model Architecture / 模型架構

**FNO3DMultiField** (`brml/models/pfsf_surrogate.py`):

```
Input:  [B, Lx, Ly, Lz, 5]   — occupancy, E(norm), ν, ρ(norm), Rcomp(norm)
Output: [B, Lx, Ly, Lz, 10]  — σxx,σyy,σzz,σxy,σxz,σyz (stress), ux,uy,uz (displacement), φ (potential)

Architecture:
  Lifting layer    → Dense(hidden_channels)
  × N_layers SpectralConv3D:
      rFFT3D(input) → keep lowest `modes` frequencies per dim
                    → complex weight multiply [C_in, C_out, mx, my, mz]
                    → iRFFT3D → residual add
  Multi-head projection (per output field):
      head_width = max(hidden_channels, 32)
      Dense(head_w) → GELU → Dense(field_dim)
```

**Normalisation constants (shared Python↔Java):**

```
E_SCALE   = 200e9    Pa       (Young's modulus)
RHO_SCALE = 7850.0   kg/m³    (density)
RC_SCALE  = 250.0    MPa      (compression strength)
RT_SCALE  = 500.0    MPa      (tension strength, steel reference)
```

**Modes:** `modes = min(grid_size // 2, 8)` — auto-scaled to island size, avoids aliasing.

### Training Pipeline / 訓練管線

`brml/pipeline/auto_train.py` implements a hybrid FEM+PFSF multi-teacher pipeline:

```
Per sample:
  1. Generate random voxel structure (Minecraft-like, anchored)
  2. FEM solve (hex8, CG)     → stress σ (6 components) + displacement u (3)
  3. PFSF CPU solve           → potential φ (via scipy CSR sparse direct, O(N^1.5))
  4. Feed both to FNO3DMultiField

Multi-teacher loss (4 heads):
  L_stress = MSE(σ_pred, σ_FEM)
  L_disp   = MSE(u_pred, u_FEM)
  L_phi    = MSE(φ_pred, φ_PFSF)
  L_cons   = MSE(vonMises(σ_pred), φ_PFSF)   (consistency)
```

### PCGrad Gradient Surgery / PCGrad 梯度手術

Replaces single-pass loss gradient with per-task gradient surgery (Yu et al. 2020, NeurIPS):

```python
# 4 separate value_and_grad calls (JIT-compiled together)
l_stress, g_stress = jax.value_and_grad(_stress)(mp)
l_disp,   g_disp   = jax.value_and_grad(_disp)(mp)
l_phi,    g_phi     = jax.value_and_grad(_phi)(mp)
l_cons,   g_cons    = jax.value_and_grad(_cons)(mp)

# Per-pair gradient surgery: project gᵢ onto orthogonal complement of conflicting gⱼ
for i in range(4):
    for j in range(4):
        if i == j: continue
        dot_ij = Σ(gᵢ · gⱼ)
        if dot_ij < 0:
            gᵢ -= (dot_ij / (‖gⱼ‖² + ε)) × gⱼ   # remove conflicting component

# Uses ORIGINAL gradients for surgery (order-invariant)
```

### Uncertainty-Weighted Multi-Task Loss / 不確定性加權多任務損失

Kendall et al. 2018 (NeurIPS) learnable homoscedastic uncertainty per task:

```
L_total = Σᵢ [ Lᵢ · exp(−2·log_σᵢ) / 2  +  log_σᵢ ]

Analytical gradient (no nested jax.grad):
  ∂L/∂log_σᵢ = 1 − Lᵢ · exp(−2·log_σᵢ)

4 learnable parameters: log_σ_stress, log_σ_disp, log_σ_phi, log_σ_cons
Stored in optimizer state; updated with same Adam step as model weights.
```

### PFSF Phi Solver (CPU Training) / CPU 訓練 φ 求解器

Replaces iterative Jacobi with scipy sparse direct solve for training data generation:

```python
# Assemble CSR Laplacian (26-connected, same stencil as GPU shaders)
# SuperLU spsolve: O(N^1.5) vs O(N × n_iters) iterative
# ~100–300× faster for L ≤ 32; fallback to Jacobi with early-exit (tol=1e-5) for larger

Early-exit convergence check every n_iters//30 steps:
  if ‖r‖ / ‖r₀‖ < tol: break
```

### Producer-Consumer Training Architecture / 生產者-消費者訓練架構

```
CPU workers (ProcessPoolExecutor, N = cpu_count − 1)
  └─ Each worker: generate structure → FEM solve → PFSF phi solve → enqueue sample

Bounded queue (multiprocessing.Queue, maxsize=256)
  └─ Back-pressure prevents OOM when GPU trainer is slower than CPU generation

JAX GPU trainer (main process)
  └─ Dequeues batches → forward pass → PCGrad → uncertainty weighting → Adam update
  └─ Warm-up: _calibrate_pipeline_scales() normalises feature scales before training
```

### Java Runtime Inference / Java 執行推論

`OnnxPFSFRuntime.java` handles ONNX Runtime inference in-game:

```java
// Model loading: CUDA(0) first → CPU fallback
loadModel(String modelPath)   // extracts gridSize from input shape [1,L,L,L,5]

// Inference contract
// Input:  [1, L, L, L, 5]  (occ, E, ν, ρ, Rcomp — all normalised)
// Output: [1, L, L, L, 10] (stress×6, disp×3, phi×1)
// φ post-processing: phiArray[i] /= sigmaMax  (physical→normalised space)

// Size guard: island bounding box > gridSize → route back to PFSF
if (size > gridSize) return HybridPhysicsRouter.Backend.PFSF;
```

### BIFROST Model Inventory / BIFROST 模型清單

| Model file | Status | Purpose |
|------------|--------|---------|
| `bifrost_surrogate.onnx` | Active | FNO3DMultiField — PFSF phi + stress + displacement |
| `bifrost_fluid.onnx` | Planned | FNO surrogate for fluid solver |
| `bifrost_lod.onnx` | Planned | LOD classifier (LOD_FULL / LOD_COARSE / LOD_DORMANT) |
| `bifrost_collapse.onnx` | Planned | Collapse sequence predictor |

### AMG Preconditioner / AMG 預條件子

`AMGPreconditioner.java` implements Smoothed Aggregation AMG (Vaněk et al. 1996) for CPU training and future GPU V-Cycle:

```
build(float[] conductivity, int[] vtype, int Lx, int Ly, int Lz)
  1. Strength graph: |σᵢⱼ| / max(|σᵢᵢ|, |σⱼⱼ|) > STRENGTH_THRESHOLD (0.25)
  2. MIS aggregation: greedy maximal independent set → coarse nodes
  3. Tentative prolongator P_tent (binary: fine→aggregate mapping)
  4. Smoothed P: P = (I − ω/D·A) · P_tent,  ω = SMOOTH_OMEGA (4/7)
  5. Column normalisation (partition of unity)

Outputs: aggregation[] (fine→coarse index), pWeights[] (smoothed P weights)

GPU integration: pending (amg_scatter_restrict.comp.glsl, amg_gather_prolong.comp.glsl)
```

---

## Material System / 材料系統

### Built-in Materials / 內建材料

All values in real engineering units. Safety factors follow Eurocode.

| Material | Rcomp (MPa) | Rtens (MPa) | Rshear (MPa) | Density (kg/m³) | E (GPa) | ν | Safety factor |
|----------|-------------|-------------|--------------|-----------------|---------|---|---------------|
| `plain_concrete` | 25 | 2.5 | 3.5 | 2400 | 25 | 0.18 | 1.5 (EN 1992) |
| `concrete` | 30 | 3.0 | 4.0 | 2350 | 30 | 0.20 | 1.5 |
| `rc_node` | 33 | 5.9 | 5.0 | 2500 | 32 | 0.20 | 1.5 |
| `rebar` | 250 | 400 | 150 | 7850 | 200 | 0.29 | 1.15 (EN 1993) |
| `steel` | 350 | 500 | 200 | 7850 | 200 | 0.29 | 1.15 |
| `brick` | 10 | 0.5 | 1.5 | 1800 | 5 | 0.15 | 2.5 (EN 1996) |
| `timber` | 5 | 8.0 | 2.0 | 600 | 11 | 0.35 | 1.3 (EN 1995) |
| `stone` | 30 | 3.0 | 4.0 | 2400 | 50 | 0.25 | 1.5 |
| `glass` | 100 | 30.0 | 1.0 | 2500 | 70 | 0.22 | 1.6 (prEN 16612) |
| `sand` | 0.1 | 0 | 0.05 | 1600 | 0.01 | 0.30 | 1.4 (EN 1997) |
| `obsidian` | 200 | 5.0 | 20 | 2600 | 70 | 0.20 | 1.5 |
| `bedrock` | 1e9 | 1e9 | 1e9 | 3000 | 1e6 | 0.10 | 1.0 (indestructible) |

### RC Fusion / 鋼筋混凝土融合

When rebar and concrete blocks are adjacent, `RCFusionDetector` (implements `IFusionDetector`) promotes them to `DynamicMaterial` with a 97/3 composite:

```
σ_RC = 0.97 × σ_concrete + 0.03 × σ_rebar
```

This ratio is fixed (not configurable). The composite is registered dynamically in `IMaterialRegistry`.

### Custom Materials / 自訂材料

```java
RMaterial custom = CustomMaterial.builder("my_material")
    .compressiveStrength(45.0)   // MPa
    .tensileStrength(4.5)        // MPa
    .shearStrength(6.0)          // MPa
    .density(2600.0)             // kg/m³
    .youngsModulus(35.0)         // GPa
    .poissonRatio(0.20)
    .yieldStrength(45.0)         // MPa
    .build();
ModuleRegistry.getMaterialRegistry().register(custom);
```

### Failure Types / 失效類型

```java
enum FailureType {
    CANTILEVER_BREAK,      // Bending moment > Rtens at root; BFS arm-distance field
    CRUSHING,              // Compressive flux > Rcomp
    NO_SUPPORT,            // Orphan island — no anchor-connected path (phi > PHI_ORPHAN_THRESHOLD)
    TENSION_BREAK,         // Outward flux > Rtens (anisotropic, 6-directional)
    HYDROSTATIC_PRESSURE,  // Fluid pressure > capacity (PFSF-Fluid coupling)
    THERMAL_STRESS,        // Thermal expansion stress > Fy (PFSF-Thermal)
    THERMAL_SPALLING,      // Surface temperature gradient > spalling threshold
    WIND_OVERTURNING,      // Wind overturning moment (Eurocode 1; upwind factor 0.30)
    LIGHTNING_STRIKE,      // EM potential > MIN_LIGHTNING_POTENTIAL
    TORSION_BREAK,         // Asymmetric load torsion (PFSFVectorSolver — pending)
    FATIGUE_CRACK          // Cumulative stress cycles (long-term damage)
}
```

---

## Render Pipeline / 渲染管線

Block Reality adds an independent Vulkan rendering layer alongside Minecraft's native OpenGL. The two co-exist via `KHR_external_memory` resource sharing and `BRVKGLSync` synchronisation. No synchronous Vulkan calls are made on the GL main thread — all GPU compute runs on an async compute queue.

### Vulkan Device Initialisation / Vulkan 裝置初始化

`BRVulkanDevice` is shared between the rendering and PFSF compute subsystems:

- Extension detection at startup: KHR Ray Tracing / AS / Ray Query / Buffer Device Address / External Memory (stable KHR extensions, always required)
- Optional Ada/Blackwell extensions detected at runtime: SER (Shader Execution Reordering), OMM (Opacity Micromaps), Cluster AS, Cooperative Vectors, Mesh Shaders
- LWJGL version guard: Minecraft bundles LWJGL 3.3.1; Block Reality requires 3.3.5 — classpath ordering enforced at launch
- Memory allocation: VMA (`VkMemoryAllocator`) with `allocatorReady` flag; all allocate calls check flag before proceeding

### Vulkan Ray Tracing Pipeline / 光線追蹤管線

Hardware RT on RTX 30xx+ (Ampere), with Ada and Blackwell optimised code paths:

| Component | Role |
|-----------|------|
| `BRVulkanBVH` | BLAS/TLAS acceleration structure management (Phase 3 — opaque geometry build pending) |
| `VkRTPipeline` | RT pipeline state, shader binding table, weather integration hook (Phase 5) |
| `BRVolumetricLighting` | Volumetric lighting via ray marching in light cone |
| `BRReLAXDenoiser` | NRD ReLAX spatiotemporal denoiser for RT output |
| `BRSDFRayMarcher` | Sphere tracing for GI, AO, and soft shadows (SDF volume) |

**BVH strategy:**
- Per-island BLAS; TLAS rebuilt per-frame for dynamic collapse
- `VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR` for incremental TLAS updates
- Scratch buffer: 64 MB fixed allocation (Phase 3: per-cluster dynamic sizing pending)
- Blackwell Cluster AS (4×4 spatial sections): detected, implementation Phase 4+

### SDF Ray Marching / SDF 光線步進

`BRSDFVolumeManager` maintains a **256³ R16F** 3D SDF texture:

- JFA (Jump Flooding Algorithm) compute pipeline for SDF generation from voxel geometry
- Dirty section tracking → incremental SDF updates (avoids full recompute each tick)
- `BRSDFRayMarcher` sphere-traces the SDF for:
  - **Global Illumination** (GI) — indirect diffuse lighting
  - **Ambient Occlusion** (AO) — contact shadows in concave geometry
  - **Soft Shadows** — penumbra via cone-march to light sources
- Active on Ada and Blackwell render paths; integrated into `RTRenderPass` as `SDF_UPDATE → SDF_GI_AO` stages

### Hardware Occlusion Culling / 硬體遮蔽剔除

`BROcclusionCuller` uses GPU occlusion queries with two-frame latency readback:

- Query state array (per section); adaptive 10-frame timeout reset
- Two-frame latency pattern: submit query frame N → read result frame N+2
- Upper layers responsible for setting up `viewProj` matrix and AABB transforms

---

## Fluid Physics / 流體物理

PFSF-Fluid models fluid behaviour as potential diffusion coupled to the structural solver. The GPU path is currently **disabled** (`GPU_PATH_ENABLED = false`) — `FluidCPUSolver` handles computation on the CPU as a fallback, blocking the game thread.

### Fluid Solver Architecture / 流體求解器架構

```
FluidGPUEngine (Phase 2 — disabled)
  ├─ FluidJacobiRecorder    Jacobi diffusion dispatch
  │    H(i) = phi(i) + ρ·g·h_i
  │    φ_new(i) = φ_old(i) + α·d·(avgH_neighbor − H(i))
  │    diffusionRate ∈ [0, 0.45]   (stability limit for explicit Jacobi)
  │    Ghost Cell Neumann BC: H_ghost = H_current
  │
  ├─ FluidPressureCoupler   Hydrostatic P = ρ·g·h_fluid (post-Jacobi)
  ├─ FluidStructureCoupler  Fluid pressure → PFSF rcomp coupling (1-tick delay — by design)
  ├─ FluidAsyncCompute      Async compute queue integration (semaphore with PFSF queue)
  └─ FluidRegionRegistry    Connected fluid region tracking (separate from island registry)
```

**PFSF-Fluid coupling:** `FluidBoundaryExtractor` reads `boundaryPressure[]` from `fluid_boundary.comp.glsl` and injects it into adjacent island's `rcomp[]` buffer before the next PFSF tick. The 1-tick delay is intentional — it avoids circular dependency between the two solvers.

**Fluid types** (`FluidType`): `WATER`, `LAVA`, `AIR`, `SOLID_WALL`. `FluidState` is an immutable record: `(FluidType type, float volume, float pressure, float potential)`.

---

## SPH Stress Engine / SPH 應力引擎

`sph/` implements a Smoothed Particle Hydrodynamics stress engine for visual collapse effects:

- **Kernel**: Monaghan (1992) cubic spline kernel W(r, h)
- **Neighbour search**: Teschner et al. (2003) spatial hash grid
- Activated by `CollapseManager` when PFSF detects structural failure
- Particles carry material colour, density, and velocity for realistic rubble physics

---

## Collapse & Connectivity / 崩塌與連通性

| Class | Role |
|-------|------|
| `StructureIslandRegistry` | Connected component tracking; dirty epoch for incremental PFSF updates |
| `UnionFind` | Path-compressed union-find for real-time structural integrity queries |
| `SupportPathAnalyzer` | BFS anchor-seeded path analysis; identifies load-path-critical blocks |
| `CollapseManager` | Translates PFSF `fail_flags` into block destruction + SPH particle spawn |
| `CollapseJournal` | Causal chain logging; reversible rollback for creative-mode debugging |

---

## SPI Extension Points / SPI 擴展點

All extension points are registered and queried through `ModuleRegistry`:

| Interface | Purpose | Default |
|-----------|---------|---------|
| `IFusionDetector` | RC fusion detection (rebar+concrete→composite) | `RCFusionDetector` |
| `ICableManager` | Cable tension physics management | `DefaultCableManager` |
| `ICuringManager` | Concrete hydration progress (σ(t)=σ×H^0.5) | `DefaultCuringManager` |
| `ILoadPathManager` | Load path transmission + cascade collapse | `LoadPathEngine` |
| `IMaterialRegistry` | Thread-safe central material registry | Built-in |
| `IFluidManager` | Fluid simulation (init/tick/query) | `FluidGPUEngine` |
| `ICommandProvider` | Custom Brigadier command registration | — |
| `IRenderLayerProvider` | Custom client render layers | — |
| `IBlockTypeExtension` | Custom block type behaviours | — |
| `IBinder<T>` | Node graph port ↔ runtime object binding | `MaterialBinder`, `PhysicsBinder`, `RenderConfigBinder`, `FluidBinder` |

```java
// Registration
ModuleRegistry.registerCommandProvider(myProvider);
ModuleRegistry.setCableManager(myCustomCableManager);

// Query
ICableManager cables = ModuleRegistry.getCableManager();
IMaterialRegistry materials = ModuleRegistry.getMaterialRegistry();
```

---

## Fast Design Extension / Fast Design 擴充模組

`fastdesign` depends on `api` (never the reverse). All classes in `fastdesign/client/` are `@OnlyIn(Dist.CLIENT)`.

### Features / 功能

- **3D Hologram Preview** — real-time transparent structure preview before placement
- **Construction HUD** — structural integrity overlay during building
- **Chisel Tool** — 10×10×10 voxel sub-block shape carving
- **Rebar Placement** — guided RC rebar layout with fusion detection feedback
- **Node Editor** — 90+ node implementations across 5 categories:
  - **Material nodes** (`impl/material/`) — base material, mix, transform, visualise
  - **Physics nodes** (`impl/physics/`) — collapse trigger, load, result, solver
  - **Render nodes** (`impl/render/`) — lighting, LOD, pipeline, post-process, water, weather
  - **Tool nodes** (`impl/tool/`) — input, placement, selection, UI
  - **Output nodes** (`impl/output/`) — monitoring, export
- **Blueprint Editor** — save/load/share structural designs with rotation, mirror, offset
- **NURBS/STEP/IFC Export** — CAD-format export via TypeScript sidecar (MctoNurbs)
- **`/fd` Command System** — with full undo/redo manager

### Adding a Node / 新增節點

1. Extend `BRNode`, define input/output `Port`s
2. Implement `evaluate()` (called by `EvaluateScheduler` in topological order)
3. Register in `NodeRegistry`
4. If runtime binding needed, implement `IBinder<T>`

```java
// Example: IBinder connects node graph to runtime
IBinder<MutableRenderConfig> binder = new RenderConfigBinder();
binder.bind(nodeGraph);      // scan nodes → build mapping
binder.apply(renderConfig);  // push node values → runtime
binder.pull(renderConfig);   // pull runtime values → nodes
```

---

## Build System / 建置系統

All Gradle commands run from `Block Reality/`:

```bash
cd "Block Reality"

# Full build (both modules)
./gradlew build

# Merged JAR → project root (drop into mods/)
./gradlew mergedJar

# Individual modules
./gradlew :api:jar
./gradlew :fastdesign:jar

# Run Minecraft
./gradlew :fastdesign:runClient      # Fast Design + API (recommended)
./gradlew :api:runClient             # API only
./gradlew :api:runServer             # Dedicated server

# Deploy to PrismLauncher dev instance
./gradlew :api:copyToDevInstance
./gradlew :fastdesign:copyToDevInstance

# Tests (JUnit 5)
./gradlew test                       # All tests
./gradlew :api:test                  # API tests only
./gradlew :api:test --tests "com.blockreality.api.physics.ForceEquilibriumSolverTest"
```

**Gradle configuration:**
- Gradle 8.8 wrapper; daemon **disabled** (`org.gradle.daemon=false`)
- Heap: `-Xmx3G` (stable on large PFSF island generation during tests)
- Forge 1.20.1 (47.4.13) + Official Mappings
- Access Transformer: `api/src/main/resources/META-INF/accesstransformer.cfg` — requires `:api:jar` rebuild after changes
- Mod metadata: `api/src/main/resources/META-INF/mods.toml`; merged version in `Block Reality/merged-resources/`

### ML Training Pipeline / ML 訓練管線

```bash
cd brml

# Install dependencies
pip install jax[cuda] flax optax onnx onnxruntime scipy

# Run auto-training (hybrid FEM+PFSF, PCGrad, uncertainty weighting)
python -m brml.pipeline.auto_train

# Export trained model to ONNX
python -m brml.export.onnx_export

# Web UI (Gradio)
python -m brml.ui.app

# TUI
python -m brml.ui.tui
```

---

## Automated Claude↔Jules Workflow / 自動化 Claude↔Jules 工作流程

The repository includes a fully automated GitHub Actions pipeline where Claude plans, Jules implements, and Claude reviews — driven entirely by issue labels.

### State Machine / 狀態機

```
User creates Issue (template: jules-task.yml)
  │  auto-label: needs-plan
  ▼
[01-claude-plan.yml]  triggered on label: needs-plan
  │  Claude API reads Issue + CLAUDE.md → generates [CLAUDE_PLAN] comment
  │  label: needs-plan → plan-ready
  ▼
[02-jules-dispatch.yml]  triggered on label: plan-ready
  │  Finds [CLAUDE_PLAN] comment → formats @jules task comment
  │  label: plan-ready → jules-working
  ▼
Jules implements, opens PR  (branch: jules/issue-N-...)
  │  PR body: Closes #N
  ▼
[03-claude-review.yml]  triggered on PR: opened/synchronize/reopened
  │  Detects Jules PR (branch prefix, author, or linked issue label)
  │  Fetches PR diff + [CLAUDE_PLAN] from linked issue
  │  Claude API reviews → submits GitHub PR Review (APPROVE or REQUEST_CHANGES)
  │  APPROVE  → label: claude-approved   (human merges)
  │  REQUEST_CHANGES → label: needs-revision → Jules revises → re-review
  ▼
Human merges PR
```

### Workflow Files / 工作流程檔案

| File | Trigger | Script |
|------|---------|--------|
| `00-setup-labels.yml` | push to main (`.github/workflows/*.yml`), manual | Creates 6 state labels |
| `01-claude-plan.yml` | `issues: labeled` (needs-plan) | `claude_plan.py` |
| `02-jules-dispatch.yml` | `issues: labeled` (plan-ready) | `jules_dispatch.py` |
| `03-claude-review.yml` | `pull_request: opened/synchronize/reopened` | `claude_review.py` |

### Labels / 標籤

| Label | Colour | Meaning |
|-------|--------|---------|
| `needs-plan` | `#0075ca` | Awaiting Claude plan |
| `plan-ready` | `#cfd3d7` | Plan complete, awaiting Jules dispatch |
| `jules-working` | `#e4e669` | Jules is implementing |
| `claude-reviewing` | `#d876e3` | Claude is reviewing PR |
| `claude-approved` | `#0e8a16` | Approved — ready to merge |
| `needs-revision` | `#e11d48` | Jules must revise before re-review |

All workflows include self-healing label creation (`gh label create --force`) — labels are created if missing before any `gh issue edit` or `gh pr edit` call.

### Secrets Required / 必要 Secrets

| Secret | Purpose |
|--------|---------|
| `ANTHROPIC_API_KEY` | Claude Opus API for plan generation and PR review |
| `GITHUB_TOKEN` | Auto-provided by Actions — issue/PR read-write |

**Note:** Workflow files must be on the **default branch (main)** — GitHub Actions reads issue-triggered workflows from main only.

---

## Common Pitfalls / 常見陷阱

1. **sigmaMax normalisation** — Every new `PFSFIslandBuffer` field carrying a threshold **must** be divided by `sigmaMax` in `PFSFDataBuilder`. Forgetting this causes `failure_scan` threshold comparisons to be off by orders of magnitude.

2. **26-connected stencil consistency** — RBGS, Jacobi, and PCG matvec **must** use the same SHEAR_EDGE_PENALTY=0.35 and SHEAR_CORNER_PENALTY=0.15. Any asymmetry causes CG to converge to an incorrect solution or multigrid to diverge.

3. **hField write ownership** — `hField` (history strain energy field) is written **exclusively** by the Jacobi/RBGS smoother (`max(old, ψ_e)`). `phase_field_evolve.comp.glsl` is read-only on `hField`. Writing from two shaders causes GPU race conditions.

4. **FNO phi normalisation** — `OnnxPFSFRuntime.infer()` output phi (channel 9) is in physical scale. It **must be divided by sigmaMax** before entering `failure_scan`. The PFSF solver's phi does not require this (normalisation cancels in `A·φ=b`).

5. **Physics units** — All material values use MPa (strength), GPa (Young's modulus), kg/m³ (density). Never mix Pa or N/mm².

6. **Forge event priority** — `@SubscribeEvent` with `EventPriority.HIGH` for physics events. Lower-priority handlers must not assume structural state is already updated.

7. **Access Transformer** — Modifying `accesstransformer.cfg` requires `./gradlew :api:jar` rebuild to take effect.

8. **RC fusion ratio** — Fixed at 97% concrete / 3% rebar. Not configurable.

9. **Client/server separation** — Classes under `client/` use `@OnlyIn(Dist.CLIENT)`. Referencing them server-side causes a crash.

10. **Fluid system default** — `BRConfig.isFluidEnabled()` defaults to `false`. Must be explicitly enabled. The 1-tick fluid-structure coupling delay is intentional.

11. **Node port type matching** — `NodeGraphIO` serialises port types. Type mismatch between connected ports causes silent failure — the connection appears valid but carries no value.

12. **Gradle daemon** — Daemon is disabled. Builds are slower but more stable for large code-gen tasks. Do not re-enable without testing.

---

## Tech Stack / 技術棧

| Component | Technology |
|-----------|-----------|
| Game platform | Minecraft Forge 1.20.1 (47.4.13), Official Mappings |
| Mod language | Java 17 |
| Build system | Gradle 8.8, daemon disabled, 3 GB heap |
| GPU compute | Vulkan Compute (PFSF physics, SDF ray marching, Fluid — disabled) |
| GPU rendering | Vulkan RT (hardware ray tracing, ReLAX denoiser) |
| ML inference | ONNX Runtime 1.17.3 (CUDA → CPU fallback) |
| ML training | Python 3.11, JAX/Flax, Optax, scipy (FEM), jax2onnx |
| FEM solver | Hex8 elements, conjugate gradient |
| Automation | GitHub Actions (Claude Opus API + Jules AI) |
| Testing | JUnit 5 (Java) |

---

## References / 參考文獻

**Physics:**
- Monaghan, J.J. (1992). *Smoothed Particle Hydrodynamics*. Annual Review of Astronomy and Astrophysics, 30, 543–574.
- Teschner, M. et al. (2003). *Optimized Spatial Hashing for Collision Detection of Deformable Objects*. VMV 2003.
- Ambati, M. et al. (2015). *A review on phase-field models of brittle fracture and a new fast hybrid formulation*. Computational Mechanics, 55(2), 383–405.
- Bažant, Z.P. & Baweja, S. (1989). *Creep and Shrinkage Prediction Model for Analysis and Design of Concrete Structures (Model B3)*. Materials and Structures.
- Timoshenko, S.P. & Goodier, J.N. (1951). *Theory of Elasticity*. McGraw-Hill.

**Machine Learning:**
- Li, Z. et al. (2021). *Fourier Neural Operator for Parametric Partial Differential Equations*. ICLR 2021.
- Yu, T. et al. (2020). *Gradient Surgery for Multi-Task Learning*. NeurIPS 2020.
- Kendall, A. et al. (2018). *Multi-Task Learning Using Uncertainty to Weigh Losses for Scene Geometry and Semantics*. CVPR 2018.
- Vaněk, P. et al. (1996). *Algebraic Multigrid by Smoothed Aggregation for Second and Fourth Order Elliptic Problems*. Computing, 56(3), 179–196.

**Rendering:**
- Rong, G. & Tan, T.S. (2006). *Jump Flooding in GPU with Applications to Voronoi Diagram and Distance Transform*. I3D 2006.
- Hart, J.C. (1996). *Sphere Tracing: A Geometric Method for the Antialiased Ray Tracing of Implicit Surfaces*. The Visual Computer, 12(10), 527–545.

**Design Codes:**
- EN 1992-1-1 (Eurocode 2) — Concrete structures
- EN 1993-1-1 (Eurocode 3) — Steel structures
- EN 1995-1-1 (Eurocode 5) — Timber structures
- EN 1996-1-1 (Eurocode 6) — Masonry structures
- EN 1997-1 (Eurocode 7) — Geotechnical design
- ASCE 7-22 — Minimum Design Loads and Associated Criteria for Buildings

---

## License / 授權

MIT
