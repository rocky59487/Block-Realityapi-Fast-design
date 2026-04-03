# Block Reality API + Fast Design

![Minecraft Forge 1.20.1](https://img.shields.io/badge/Minecraft%20Forge-1.20.1--47.4.13-orange)
![Java 17](https://img.shields.io/badge/Java-17-blue)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

**A structural physics simulation engine for Minecraft Forge 1.20.1 — if it can't stand in the real world, it won't stand here.**

**Minecraft Forge 1.20.1 結構物理模擬引擎 — 現實中撐不住的，這裡也撐不住。**

---

## Overview / 概述

Block Reality transforms Minecraft blocks into structural elements with real material properties. Every block has compressive strength (MPa), tensile strength, shear resistance, density (kg/m³), and Young's modulus (GPa) — all in real engineering units. The physics engine evaluates whether structures can support themselves, and those that fail collapse dynamically.

Block Reality 將 Minecraft 方塊轉化為具有真實材料屬性的結構元素。每個方塊都有抗壓強度（MPa）、抗拉強度、抗剪強度、密度（kg/m³）和楊氏模量（GPa）——全部使用真實工程單位。物理引擎即時判定結構是否能自行支撐，無法承受的結構會動態崩塌。

## Architecture / 架構

Three tightly integrated components / 三個緊密整合的組件：

```
Block Reality API (com.blockreality.api)             Foundation Layer / 基礎層
  ├── physics/          Force equilibrium (SOR), beam stress (Euler-Bernoulli),
  │                     column buckling (Johnson + Euler), lateral torsional buckling,
  │                     LRFD load combinations (ASCE 7-22), 3D force vectors,
  │                     Union-Find connectivity, load path tracing, BFS analysis
  ├── material/         BlockTypeRegistry, DefaultMaterial (10+ types),
  │                     CustomMaterial.Builder, DynamicMaterial (RC fusion 97/3)
  ├── blueprint/        Blueprint ↔ NBT serialization with version migration,
  │                     BlueprintIO with atomic writes
  ├── collapse/         CollapseManager — triggers destruction on physics failure
  ├── chisel/           10×10×10 voxel sub-block shape system
  ├── sph/              SPH stress engine — Monaghan 1992 cubic spline kernel +
  │                     Teschner 2003 spatial hash neighbor search
  ├── sidecar/          SidecarBridge — stdio JSON-RPC 2.0 IPC to TypeScript
  ├── client/render/    GreedyMesher, AnimationEngine, RenderPipeline, Vulkan RT
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

## Physics Engine / 物理引擎

### Force Equilibrium Solver / 力平衡求解器
- **SOR (Successive Over-Relaxation)** iterative solver with adaptive omega
- **3D force vectors** (Fx, Fy, Fz) and moment vectors (Mx, My, Mz)
- Moment equilibrium check (ΣM = 0) for rotational stability
- Warm-start cache for incremental updates

### Beam Stress Analysis / 梁應力分析
- **Euler-Bernoulli beam elements** connecting adjacent voxels
- Axial force, bending moment (M = wL²/8), and shear calculation
- Composite stiffness via harmonic mean (Voxelyze-inspired)
- **Eurocode EN 1993-1-1 §6.2.1** linear interaction: N/N_max + M/M_max ≤ 1.0

### Column Buckling / 柱挫屈
- **Euler buckling** for long columns (λ > λ_c)
- **Johnson parabola** (CRC formula) for short/intermediate columns
- Effective length factor K = 0.7 (AISC Table C-A-7.1)
- **AISC 360-22 §E3** compliant unified formula

### Lateral Torsional Buckling / 側向扭轉挫屈
- **Timoshenko elastic critical moment** M_cr formula
- AISC §F2 three-zone classification (Plastic / Inelastic / Elastic)
- Design moment capacity M_n with L_p and L_r limit lengths
- Saint-Venant torsion constant for solid square sections

### Load Combinations / 荷載組合
- **ASCE 7-22 §2.3.1** LRFD load combinations (7 standard combinations)
- Load types: Dead, Live, Wind, Seismic, Snow, Thermal
- Scalar and 3D vector envelope search for critical combination
- Uplift/overturning checks (LC5: 0.9D + 1.0W, LC7: 0.9D + 1.0E)

### SPH Stress Engine / SPH 應力引擎
- **Monaghan (1992)** cubic spline kernel W(r,h) with 3D normalization
- **Teschner (2003)** spatial hash grid for O(1) neighbor search
- Full SPH pipeline: density summation → Tait EOS → pressure gradient force
- Async three-phase execution (snapshot → compute → apply)

### Connectivity & Collapse / 連通性與崩塌
- Union-Find with path compression for real-time integrity checks
- BFS anchor-seeded flood fill (ThreadLocal buffer reuse)
- Load path tracing from structure to ground
- Dynamic collapse with particle effects when physics fails

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
| Build System | Gradle 8.8, daemon disabled, 3GB heap |
| Language (Sidecar) | TypeScript, Node.js 20 |
| CAD Kernel | opencascade.js |
| Testing | JUnit 5 (Java), Vitest (TypeScript) |
| IPC | Stdio JSON-RPC 2.0 |

## Quick Start / 快速開始

### Prerequisites / 前置需求
- **Java 17** JDK (Temurin recommended)
- **Node.js 20+** and npm (for the sidecar)

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

The physics engine implementations reference these standards and publications:

- **ASCE/SEI 7-22** — Minimum Design Loads for Buildings (load combinations)
- **AISC 360-22** — Specification for Structural Steel Buildings (buckling, beam design)
- **EN 1993-1-1:2005** — Eurocode 3: Design of steel structures (interaction formulas, LTB)
- **EN 1990:2002** — Eurocode: Basis of structural design (LRFD philosophy)
- **Monaghan, J.J. (1992)** — "Smoothed Particle Hydrodynamics". ARAA, 30, 543-574 (SPH kernel)
- **Teschner, M. et al. (2003)** — "Optimized Spatial Hashing for Collision Detection" (spatial hash grid)
- **Timoshenko & Gere (1961)** — Theory of Elastic Stability (lateral torsional buckling)
- **Salmon, Johnson & Malhas (2009)** — Steel Structures: Design and Behavior (column buckling)

## License / 授權

MIT
