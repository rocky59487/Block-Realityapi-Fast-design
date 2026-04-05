# Block Reality API + Fast Design

![Minecraft Forge 1.20.1](https://img.shields.io/badge/Minecraft%20Forge-1.20.1--47.4.13-orange)
![Java 17](https://img.shields.io/badge/Java-17-blue)
![Vulkan Compute](https://img.shields.io/badge/Vulkan-Compute%20%2B%20RT-red)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

**A GPU-accelerated structural physics simulation engine for Minecraft Forge 1.20.1 — if it can't stand in the real world, it won't stand here.**

**Minecraft Forge 1.20.1 GPU 加速結構物理模擬引擎 — 現實中撐不住的，這裡也撐不住。**

---

## Overview / 概述

Block Reality transforms Minecraft blocks into structural elements with real material properties. Every block has compressive strength (MPa), tensile strength, shear resistance, density (kg/m³), and Young's modulus (GPa) — all in real engineering units. The **PFSF (Potential Field Structure Failure)** engine runs entirely on the GPU via Vulkan Compute, evaluating structural integrity through a potential field diffusion model with Chebyshev-accelerated Jacobi iteration and V-Cycle multigrid. Structures that fail collapse dynamically.

Block Reality 將 Minecraft 方塊轉化為具有真實材料屬性的結構元素。每個方塊都有抗壓強度（MPa）、抗拉強度、抗剪強度、密度（kg/m³）和楊氏模量（GPa）——全部使用真實工程單位。**PFSF（勢場結構失效）** 引擎透過 Vulkan Compute 完全在 GPU 上運行，以勢場擴散模型搭配 Chebyshev 加速 Jacobi 迭代與 V-Cycle 多重網格求解結構完整性。無法承受的結構會動態崩塌。

## Architecture / 架構

Three tightly integrated components / 三個緊密整合的組件：

```
Block Reality API (com.blockreality.api)             Foundation Layer / 基礎層
  ├── physics/pfsf/     PFSF GPU physics engine — Jacobi + V-Cycle multigrid,
  │                     Chebyshev ω scheduling, triple-buffered async compute,
  │                     anisotropic conductivity, sparse scatter updates,
  │                     GPU failure detection (4 modes), stress field extraction
  ├── physics/          StructureIslandRegistry (connected component tracking),
  │                     UnionFind connectivity, BFS support path analysis,
  │                     PhysicsResult, FailureType/FailureReason
  ├── material/         BlockTypeRegistry, DefaultMaterial (10+ types),
  │                     CustomMaterial.Builder, DynamicMaterial (RC fusion 97/3)
  ├── blueprint/        Blueprint ↔ NBT serialization with version migration,
  │                     BlueprintIO with atomic writes
  ├── collapse/         CollapseManager — triggers destruction on physics failure
  ├── chisel/           10×10×10 voxel sub-block shape system
  ├── sph/              SPH stress engine — Monaghan 1992 cubic spline kernel +
  │                     Teschner 2003 spatial hash neighbor search
  ├── sidecar/          SidecarBridge — stdio JSON-RPC 2.0 IPC to TypeScript
  ├── client/render/    GreedyMesher, AnimationEngine, RenderPipeline,
  │                     Vulkan RT (ray tracing), SDF Ray Marching (GI + AO)
  ├── node/             BRNode graph system, EvaluateScheduler (topological sort)
  └── spi/              ModuleRegistry, SPI extension interfaces

Fast Design (com.blockreality.fastdesign)            Extension Layer / 擴充層
  ├── client/           3D hologram preview, HUD overlay, GUI screens,
  │                     chisel tools, node editor (90+ node implementations)
  ├── command/          /fd command system, undo manager
  ├── construction/     Construction event handling, rebar placement
  ├── network/          Packet sync (hologram state, build actions)
  └── sidecar/          NURBS/STEP/IFC export bridge

MctoNurbs-review/                                    TypeScript Sidecar
  ├── src/pipeline.ts       Dual-path NURBS export (GreedyMesh / DualContouring)
  ├── src/rpc-server.ts     JSON-RPC 2.0 server (stdio)
  ├── src/path-security.ts  Path traversal prevention
  ├── src/sdf/              SDF grid + Hermite data
  ├── src/dc/               Dual contouring surface reconstruction + QEF solver
  └── src/cad/              opencascade.js CAD kernel (Mesh→BRep→STEP)
```

**Dependency direction / 依賴方向**: `fastdesign` → `api` (never the reverse).

## Physics Engine — PFSF / 物理引擎 — PFSF

The physics engine uses **Potential Field Structure Failure (PFSF)**, a GPU-native approach where structural integrity is modeled as potential field diffusion from ground anchors through connected blocks.

物理引擎採用 **PFSF（勢場結構失效）**，一種 GPU 原生方法，將結構完整性建模為從地面錨點沿連接方塊的勢場擴散。

### GPU Compute Pipeline / GPU 計算管線

| Component | Role |
|-----------|------|
| `PFSFEngine` | Main entry — orchestrates per-tick compute dispatch |
| `VulkanComputeContext` | Vulkan device, command pool, VMA (shares BRVulkanDevice or standalone fallback) |
| `PFSFAsyncCompute` | Triple-buffered (3 frames in flight) non-blocking fence-based async |
| `PFSFIslandBuffer` | Per-island GPU buffers: phi, source, conductivity, type, fail_flags, maxPhi, rcomp, rtens |
| `PFSFScheduler` | Chebyshev ω acceleration with warmup protection (8 steps pure Jacobi), oscillation detection (3-tick history) |
| `PFSFSourceBuilder` | BFS horizontal arm distance field, source term modulation |
| `PFSFConductivity` | Anisotropic 6-directional conductivity σ with distance decay |
| `PFSFSparseUpdate` | Dirty voxel tracking (max 512/tick, full rebuild if exceeded) |
| `PFSFFailureApplicator` | GPU fail_flags → CollapseManager bridge |

### Compute Shaders (8) / 計算著色器 (8)

| Shader | Purpose |
|--------|---------|
| `jacobi_smooth.comp.glsl` | Jacobi iteration with shared memory tiling + Chebyshev omega + damping |
| `mg_restrict.comp.glsl` | V-Cycle multigrid restriction (fine → coarse) |
| `mg_prolong.comp.glsl` | V-Cycle multigrid prolongation (coarse → fine) |
| `failure_scan.comp.glsl` | 4-mode failure detection (cantilever, crushing, no_support, tension) |
| `failure_compact.comp.glsl` | GPU stream compaction of non-zero failure entries |
| `phi_reduce_max.comp.glsl` | Parallel reduction for max φ value |
| `sparse_scatter.comp.glsl` | SoA scatter updates to large arrays |
| `stress_heatmap.frag.glsl` | Client-side stress visualization |

### Per-Tick Execution / 每 Tick 執行流程

```
PFSFEngine.onServerTick()
  ├─ Phase 1: PFSFAsyncCompute.pollCompleted()       ← non-blocking fence check
  ├─ Phase 2: StructureIslandRegistry.getDirtyIslands() → acquireFrame()
  ├─ Phase 3: Sparse scatter or full source/conductivity rebuild
  ├─ Phase 4: Jacobi iterations + V-Cycle (Chebyshev ω)
  ├─ Phase 5: failure_scan → failure_compact → phi_reduce_max
  └─ Phase 6: submitAsync() → callback → CollapseManager.triggerPFSFCollapse()
```

### Connectivity & Collapse / 連通性與崩塌
- `StructureIslandRegistry` — connected component tracking with dirty epoch for incremental PFSF updates
- `UnionFind` with path compression for real-time integrity checks
- `SupportPathAnalyzer` — BFS anchor-seeded path analysis for collapse detection
- `CollapseManager` — dynamic collapse with particle effects when PFSF detects failure

## Render Pipeline / 渲染管線

### Vulkan Ray Tracing
- Hardware RT on RTX 30xx+ (Ada/Blackwell optimized paths)
- `BRVulkanDevice` shared between rendering and PFSF compute

### SDF Ray Marching
- **BRSDFVolumeManager** — 256³ R16F 3D SDF texture, JFA (Jump Flooding Algorithm) compute pipeline, dirty section tracking
- **BRSDFRayMarcher** — Sphere Tracing for GI (global illumination) + AO (ambient occlusion) + soft shadows
- Integrated into `RTRenderPass` pipeline: `SDF_UPDATE` → `SDF_GI_AO`
- Active on Blackwell and Ada render paths

## Material System / 材料系統

10+ default materials with real engineering properties:

| Material | Rcomp (MPa) | Rtens (MPa) | Density (kg/m³) | E (GPa) |
|----------|-------------|-------------|------------------|---------|
| Concrete C30 | 30 | 3 | 2400 | 30 |
| Steel Q345 | 345 | 500 | 7850 | 200 |
| Timber | 40 | 8 | 600 | 11 |
| Brick | 10 | 0.5 | 1800 | 15 |
| Glass | 50 | 0.5 | 2500 | 70 |
| RC Fusion | 97% concrete / 3% rebar composite | | | |

Custom materials via `CustomMaterial.Builder`. Dynamic materials for RC fusion (rebar + concrete adjacent → composite).

## Tech Stack / 技術棧

| Component | Technology |
|-----------|-----------|
| Game Platform | Minecraft Forge 1.20.1 (47.4.13), Official Mappings |
| Language (Mod) | Java 17 |
| GPU Compute | Vulkan Compute (PFSF physics + SDF ray marching) |
| GPU Rendering | Vulkan RT (ray tracing pipeline) |
| Build System | Gradle 8.8, daemon disabled, 3GB heap |
| Language (Sidecar) | TypeScript, Node.js 20 |
| CAD Kernel | opencascade.js |
| Testing | JUnit 5 (Java), Vitest (TypeScript) |
| IPC | Stdio JSON-RPC 2.0 |

## Quick Start / 快速開始

### Prerequisites / 前置需求
- **Java 17** JDK (Temurin recommended)
- **Node.js 20+** and npm (for the sidecar)
- **Vulkan-capable GPU** (required for PFSF physics and RT rendering)

### Build / 建置

```bash
git clone https://github.com/rocky59487/Block-Realityapi-Fast-design.git
cd Block-Realityapi-Fast-design

# Build the Forge mod (API + Fast Design)
cd "Block Reality"
./gradlew build

# Build merged JAR for mods/ folder
./gradlew mergedJar

# The sidecar is auto-built during fastdesign:processResources
```

### Run / 運行

```bash
# Run Minecraft client with Fast Design + API
./gradlew :fastdesign:runClient

# Run API client only
./gradlew :api:runClient

# Run all tests
./gradlew test

# Deploy to PrismLauncher dev instance
./gradlew :fastdesign:copyToDevInstance
```

### TypeScript Sidecar

```bash
cd MctoNurbs-review
npm install
npm run build
npm test
```

## SPI Extension Points / SPI 擴展點

All extension points are registered through `ModuleRegistry`:

| Interface | Purpose | Default |
|-----------|---------|---------|
| `IFusionDetector` | RC fusion detection | `RCFusionDetector` |
| `ICableManager` | Cable tension physics | `DefaultCableManager` |
| `ICuringManager` | Concrete curing progress | `DefaultCuringManager` |
| `ILoadPathManager` | Load path & cascade collapse | `LoadPathEngine` |
| `IMaterialRegistry` | Thread-safe material registry | Built-in |
| `ICommandProvider` | Custom Brigadier commands | — |
| `IRenderLayerProvider` | Custom client render layers | — |
| `IBlockTypeExtension` | Custom block type behaviors | — |

## Documentation / 文件

Structured API reference in `docs/` with 4-tier hierarchy:

- [docs/index.md](docs/index.md) — Master index
- [docs/L1-api/](docs/L1-api/index.md) — API foundation layer (10 L2 sections)
- [docs/L1-fastdesign/](docs/L1-fastdesign/index.md) — Fast Design extension layer
- [docs/L1-sidecar/](docs/L1-sidecar/index.md) — MctoNurbs TypeScript sidecar

## References / 參考文獻

- **Monaghan, J.J. (1992)** — "Smoothed Particle Hydrodynamics". ARAA, 30, 543-574 (SPH kernel)
- **Teschner, M. et al. (2003)** — "Optimized Spatial Hashing for Collision Detection" (spatial hash grid)
- **Rong & Tan (2006)** — "Jump Flooding in GPU with Applications to Voronoi Diagram and Distance Transform" (JFA for SDF generation)
- **Hart, J.C. (1996)** — "Sphere Tracing: A Geometric Method for the Antialiased Ray Tracing of Implicit Surfaces" (SDF ray marching)

## License / 授權

MIT
