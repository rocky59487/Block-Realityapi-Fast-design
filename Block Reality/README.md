# Block Reality API

**GPU-accelerated structural physics simulation engine for Minecraft Forge 1.20.1**

Block Reality API brings real-world structural engineering to Minecraft. Every block has material properties — compressive strength, tensile strength, shear resistance, density, and Young's modulus — and the **PFSF (Potential Field Structure Failure)** engine evaluates structural integrity entirely on the GPU via Vulkan Compute.

> *If it wouldn't survive in the real world, it won't survive in Block Reality.*

## Features

**PFSF Physics Engine** — GPU-native potential field diffusion with RBGS 8-color in-place smoothing (2× convergence) with W-Cycle multigrid and Ambati 2015 phase-field fracture (continuous crack vs. instant deletion), triple-buffered async compute, anisotropic conductivity, and 4-mode failure detection (cantilever, crushing, no_support, tension). Structures that lose support will collapse.

### What's New in v2.1
- **RBGS 8-color solver**: ρ(GS)=ρ(J)², 2× convergence, 8-color octree coloring eliminating 26-connectivity data race (125K voxels: <1.1ms; 1M voxels: <8ms).
- **Ambati 2015 phase-field fracture**: d ∈ [0,1] continuous damage field replacing binary fracture, no Newton-Raphson (+~0.3ms for 125K voxels; +~2.2ms for 1M voxels).
- **Morton Tiled layout**: 8×8×8 micro-block Z-order, GPU L2 cache hit rate +~80%.
- **ICuringManager hydration scaling**: σ(t)=σ×H^0.5, Gc(t)=Gc×H^1.5 (Bažant 1989 MPS).
- **Upwind conductivity**: WIND_UPWIND_FACTOR=0.30, replacing hard cut-off WIND_CONDUCTIVITY_DECAY.

**Material System** — 10+ default materials (concrete, steel, timber, brick, glass, bedrock...) with real engineering values. Custom materials via `CustomMaterial.Builder`. Dynamic materials with RC fusion (97/3 concrete-rebar composite).

**Blueprint System** — Save, load, and share structural designs. Multi-block placement with rotation, mirroring, and offset support.

**Fast Design (Extension)** — CAD-style building module with 3D hologram preview, construction HUD overlay, rebar placement, NURBS/STEP/IFC export, node editor (90+ nodes), and a full GUI editor.

**Render Pipeline** — Vulkan RT (hardware ray tracing) + SDF Ray Marching (JFA-generated 256^3 SDF volume for GI, AO, and soft shadows).

**Collapse Simulation** — When PFSF detects structural failure, blocks break and fall with SPH-based particle effects.

## Physics Engine — PFSF

### GPU Compute Pipeline

| Component | Description |
|-----------|-------------|
| `PFSFDataBuilder` | Timoshenko source + ICuringManager hydration scaling + Morton layout |
| `PFSFVCycleRecorder` | W-Cycle orchestration: RBGS fine-grid, Jacobi coarse-grid |

### PFSFIslandBuffer

The GPU buffer layout for each structural island includes the following properties per voxel:
- `phi[]`, `source[]`, `conductivity[]`, `type[]`, `fail_flags[]`
- `hField[]`: Maximum strain energy history field (irreversible)
- `dField[]`: Damage phase field $d \in [0,1]$
- `hydrationBuf[]`: Hydration degree for curing effects
- `blockOffsetsBuf[]`: Morton micro-block start offsets

## Architecture

```
Block Reality API (blockreality)     <- Foundation layer
  ├── physics/pfsf/   PFSF GPU physics (10 classes, 6 compute shaders)
  ├── physics/         Island registry, UnionFind, BFS support analysis
  ├── material/        Material properties & registry
  ├── blueprint/       Structure serialization
  ├── collapse/        Failure & destruction logic
  ├── chisel/          10x10x10 voxel sub-block shapes
  ├── sph/             SPH particle-based visual effects
  ├── sidecar/         TypeScript IPC bridge
  ├── client/render/   Vulkan RT + SDF Ray Marching pipeline
  ├── node/            BRNode graph system (topological sort)
  └── spi/             ModuleRegistry, SPI extension interfaces

Fast Design (fastdesign)             <- Extension layer
  ├── client/          3D preview, HUD, node editor (90+ nodes)
  ├── command/         /fd command system, undo manager
  ├── construction/    Build tools & rebar system
  ├── network/         Packet sync (hologram, actions)
  └── sidecar/         NURBS/STEP/IFC export bridge
```

## Tech Stack

- **Minecraft Forge** 1.20.1 (47.4.13), Official Mappings
- **Java 17**, Gradle 8.8
- **Vulkan Compute** (PFSF physics + SDF ray marching)
- **Vulkan RT** (hardware ray tracing pipeline)

## Quick Start

```bash
git clone https://github.com/rocky59487/Block-Realityapi-Fast-design.git
cd Block-Realityapi-Fast-design/"Block Reality"

# Build
./gradlew build

# Run Minecraft with the mod
./gradlew :fastdesign:runClient
```

## License

MIT

---

# Block Reality API

**Minecraft Forge 1.20.1 GPU 加速結構物理模擬引擎**

Block Reality API 將真實世界的結構工程帶入 Minecraft。每個方塊都擁有材料屬性——抗壓強度、抗拉強度、抗剪強度、密度與楊氏模量——**PFSF（勢場結構失效）** 引擎透過 Vulkan Compute 完全在 GPU 上評估結構完整性。

> *現實中撐不住的，Block Reality 裡也撐不住。*

## 功能特色

**PFSF 物理引擎** — GPU 原生勢場擴散，搭配 RBGS 8 色就地平滑（收斂速度 2×）、W-Cycle 多重網格、Ambati 2015 相場斷裂（連續裂紋取代瞬間刪除）、三重緩衝非同步計算、各向異性傳導率、4 種失效偵測模式（懸臂斷裂、壓碎、無支撐、拉斷）。失去支撐的結構會崩塌。

### v2.1 升級亮點
- **RBGS 8-色求解器**：ρ(GS)=ρ(J)²，2× 收斂，8 色 octree 著色消除 26-connectivity data race（125K voxels：<1.1ms；1M voxels：<8ms）。
- **Ambati 2015 相場斷裂**：d ∈ [0,1] 連續損傷場，取代二元斷裂，無需 Newton-Raphson（+~0.3ms 於 125K voxels；+~2.2ms 於 1M voxels）。
- **Morton Tiled 佈局**：8×8×8 micro-block Z-order，GPU L2 cache 命中率 +~80%。
- **ICuringManager 固化效應**：σ(t)=σ×H^0.5, Gc(t)=Gc×H^1.5（Bažant 1989 MPS）。
- **上風向傳導率**：WIND_UPWIND_FACTOR=0.30，取代硬截斷 WIND_CONDUCTIVITY_DECAY。

**材料系統** — 10+ 種預設材料（混凝土、鋼材、木材、磚塊、玻璃、基岩...）使用真實工程數值。透過 `CustomMaterial.Builder` 自訂材料。動態材料支援 RC 融合（97/3 混凝土-鋼筋複合）。

**藍圖系統** — 儲存、載入、分享結構設計。支援多方塊放置，含旋轉、鏡像、偏移。

**Fast Design（擴充模組）** — CAD 風格建築模組，3D 全息投影預覽、施工 HUD、鋼筋佈置、NURBS/STEP/IFC 匯出、節點編輯器（90+ 節點）、完整 GUI 編輯器。

**渲染管線** — Vulkan RT（硬體光線追蹤）+ SDF 光線步進（JFA 生成 256^3 SDF 體積，用於全域照明、環境光遮蔽、軟陰影）。

**崩塌模擬** — 當 PFSF 偵測到結構失效，方塊會斷裂並以 SPH 粒子效果墜落。

## Physics Engine — PFSF

### GPU Compute Pipeline 表格

| 元件 | 說明 |
|------|------|
| `PFSFDataBuilder` | Timoshenko source + ICuringManager hydration scaling + Morton layout |
| `PFSFVCycleRecorder` | W-Cycle orchestration: RBGS fine-grid, Jacobi coarse-grid |

### PFSFIslandBuffer

每個結構島嶼的 GPU 緩衝區佈局包含以下體素屬性：
- `phi[]`、`source[]`、`conductivity[]`、`type[]`、`fail_flags[]`
- `hField[]`：最大應變能歷史場（不可逆遞增）
- `dField[]`：損傷相場 $d \in [0,1]$
- `hydrationBuf[]`：水化度（用於固化效應）
- `blockOffsetsBuf[]`：Morton micro-block 起始偏移

## 架構

```
Block Reality API (blockreality)     <- 基礎層
  ├── physics/pfsf/   PFSF GPU 物理（10 類別、6 計算著色器）
  ├── physics/         島嶼註冊、UnionFind、BFS 支撐分析
  ├── material/        材料屬性與註冊
  ├── blueprint/       結構序列化
  ├── collapse/        破壞與崩塌邏輯
  ├── chisel/          10x10x10 體素子方塊造型
  ├── sph/             SPH 粒子視覺效果
  ├── sidecar/         TypeScript IPC 橋接
  ├── client/render/   Vulkan RT + SDF 光線步進管線
  ├── node/            BRNode 節點圖系統（拓撲排序）
  └── spi/             ModuleRegistry、SPI 擴展介面

Fast Design (fastdesign)             <- 擴充層
  ├── client/          3D 預覽、HUD、節點編輯器（90+ 節點）
  ├── command/         /fd 指令系統、撤銷管理
  ├── construction/    建造工具與鋼筋系統
  ├── network/         封包同步（全息投影、動作）
  └── sidecar/         NURBS/STEP/IFC 匯出橋接
```

## 技術棧

- **Minecraft Forge** 1.20.1 (47.4.13)，官方映射
- **Java 17**，Gradle 8.8
- **Vulkan Compute**（PFSF 物理 + SDF 光線步進）
- **Vulkan RT**（硬體光線追蹤管線）

## 快速開始

```bash
git clone https://github.com/rocky59487/Block-Realityapi-Fast-design.git
cd Block-Realityapi-Fast-design/"Block Reality"

# 建置
./gradlew build

# 啟動帶 mod 的 Minecraft
./gradlew :fastdesign:runClient
```

## 授權

MIT
