# Block Reality API + Fast Design: A GPU-Accelerated Structural Physics Engine

![Minecraft Forge 1.20.1](https://img.shields.io/badge/Minecraft%20Forge-1.20.1--47.4.13-orange)
![Java 17](https://img.shields.io/badge/Java-17-blue)
![Vulkan Compute](https://img.shields.io/badge/Vulkan-Compute%20%2B%20RT-red)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

**A GPU-accelerated structural physics simulation engine for Minecraft Forge 1.20.1 — Bridging the gap between voxel worlds and real-world engineering constraints.**

**Minecraft Forge 1.20.1 GPU 加速結構物理模擬引擎 — 弭平體素世界與現實工程限制的鴻溝。**

---

## 1. Abstract / 摘要

Block Reality is an advanced structural physics engine and design suite built for Minecraft Forge 1.20.1. It transforms standard static voxel blocks into dynamic structural elements governed by real-world engineering principles. By mapping material properties—such as compressive strength (MPa), tensile strength, shear resistance, density (kg/m³), and Young's modulus (GPa)—onto individual blocks, the system introduces realistic structural integrity constraints. Driven by a Vulkan-based GPU compute pipeline, the Potential Field Structure Failure (PFSF) engine leverages Red-Black Gauss-Seidel (RBGS) smoothing and W-Cycle multigrid methods to solve complex force equilibrium equations in real-time, resulting in dynamic, mathematically-grounded structural collapses when load limits are exceeded.

Block Reality 是一個為 Minecraft Forge 1.20.1 打造的高階結構物理引擎與設計套件。它將標準的靜態體素方塊轉化為受現實世界工程原理支配的動態結構元素。透過將材料屬性（如抗壓強度、抗拉強度、抗剪強度、密度與楊氏模量）映射至個別方塊，本系統引入了真實的結構完整性限制。藉由基於 Vulkan 的 GPU 計算管線驅動，勢場結構失效（PFSF）引擎採用紅黑高斯-賽德爾（RBGS）平滑與 W-Cycle 多重網格方法，即時求解複雜的力平衡方程式；當超出承載極限時，會產生具備數學基礎的動態結構崩塌。

## 2. Introduction / 簡介

Traditional voxel-based environments suffer from a lack of physical realism, often allowing impossible architectures (e.g., floating structures) to persist without consequence. Block Reality addresses this limitation by introducing a robust physics abstraction layer. Instead of relying on simple connectivity checks, Block Reality formulates structural integrity as a potential field diffusion problem. The engine propagates stress and load paths from ground anchors throughout the connected voxel graph. This allows for rigorous evaluation of cantilever limits, crushing scenarios, and tensile failures.

傳統基於體素的環境缺乏物理真實感，通常允許不可能的建築結構（例如懸浮結構）無條件存在。Block Reality 透過引入穩健的物理抽象層來解決此限制。本系統不依賴簡單的連通性檢查，而是將結構完整性公式化為勢場擴散問題。引擎從地面錨點將應力與載重路徑沿著相連的體素圖進行傳遞，從而能夠嚴格評估懸臂極限、壓碎情境與抗拉失效。

## 3. System Architecture / 系統架構

The framework is decoupled into five tightly integrated subsystems, enabling maintainability and cross-language interoperability:

系統架構解耦為五個緊密整合的子系統，確保可維護性與跨語言互通性：

```
Block Reality API (com.blockreality.api)             Foundation Layer / 基礎層
  ├── physics/pfsf/     PFSF v2.1 GPU physics engine — RBGS 8-color smoothing,
  │                     W-Cycle multigrid, Ambati 2015 phase-field fracture mechanics.
  ├── physics/          StructureIslandRegistry, UnionFind connectivity,
  │                     SupportPathAnalyzer, CollapseManager.
  ├── material/         BlockTypeRegistry, DefaultMaterial, RC Fusion detection.
  ├── render/           Vulkan RT pipeline, SDF Ray Marching (GI + AO),
  │                     BRMeshShaderPath (GL_NV_mesh_shader).
  └── spi/              ModuleRegistry, Extensible interfaces.

Fast Design (com.blockreality.fastdesign)            Extension Layer / 擴充層
  ├── client/           3D hologram preview, HUD overlay, LivePreviewBridge.
  ├── command/          /fd command system, undo manager.
  ├── node/             Visual programming node system (Grasshopper-style).
  └── construction/     Event handling, construction phase monitoring.

MctoNurbs Sidecar (TypeScript)                       Export Pipeline / 匯出管線
  ├── src/pipeline.ts       Dual Contouring / Greedy Mesher → NURBS → STEP.
  ├── src/dc/               QEF Solver (Jacobi rotation eigenvector generation).
  └── src/cad/              opencascade.js integration.

libpfsf (C++)                                        Standalone Compute / 獨立計算
  ├── src/core/             Vulkan context management, Island buffers.
  └── src/solver/           Standalone PFSF RBGS and Multigrid implementations.

brml (Python)                                        Machine Learning / 機器學習
  ├── brml/train/           JAX/Flax surrogate model training for physics inference.
  └── brml/ui/              Gradio UI for model validation and collapse prediction.
```

**Dependency Hierarchy / 依賴方向**: `fastdesign` → `api` → `libpfsf`. External RPC bridges to `MctoNurbs` sidecar and `brml`.

## 4. Methodology & Key Technologies / 方法論與核心技術

### 4.1 Potential Field Structure Failure (PFSF v2.1)
The core physics computation transitioned from traditional FEM to a highly optimized Particle Field Simulation Framework (PFSF). The system computes structural equilibrium on the GPU using an **RBGS (Red-Black Gauss-Seidel) 8-color in-place smoothing solver** coupled with a **W-Cycle multigrid** approach (replacing the legacy Chebyshev-accelerated Jacobi iteration). This provides 2x faster convergence for sparse block matrices. Phase-field fracture mechanics (based on Ambati 2015) are integrated to accurately model crack propagation and structural yield.

核心物理計算已從傳統 FEM 轉移至高度最佳化的粒子場模擬框架（PFSF）。系統使用 **RBGS 8 色原地平滑求解器** 搭配 **W-Cycle 多重網格**（取代舊版的 Chebyshev 加速 Jacobi 迭代），在 GPU 上求解結構平衡，為稀疏區塊矩陣提供 2 倍的收斂速度。整合了基於 Ambati 2015 的相場斷裂力學，以準確模擬裂紋擴展與結構降伏。

### 4.2 Material Mapping & RC Fusion
Materials are strictly quantified. Concrete (e.g., C30: 30 MPa compressive, 3 MPa tensile, 30 GPa modulus) and Steel (Q345: 345 MPa yield) interact dynamically. The runtime features an RC Fusion detector utilizing local chunk caching to identify adjacent concrete and rebar, dynamically resolving them into a composite material logic representation for physics evaluation.

材料被嚴格量化。混凝土與鋼材會產生動態交互作用。執行時具備 RC 融合偵測器，利用區域區塊快取來識別相鄰的混凝土與鋼筋，動態解析為複合材料邏輯以進行物理評估。

### 4.3 Rendering Pipeline (Hardware RT & SDF)
The client-side rendering bypasses traditional OpenGL pipelines when possible. It implements hardware Ray Tracing (Vulkan RT) supporting ReSTIR and DDGI. Furthermore, it constructs a 256³ R16F 3D SDF texture using Jump Flooding Algorithm (JFA) to perform Sphere Tracing for global illumination and ambient occlusion. A Mesh Shader fast path (`GL_NV_mesh_shader`) is also provided for robust occlusion culling.

客戶端渲染儘可能繞過傳統 OpenGL 管線。實作了支援 ReSTIR 與 DDGI 的硬體光線追蹤。此外，利用跳躍泛洪演算法（JFA）建構 256³ SDF 紋理，以進行全局光照與環境光遮蔽的球體追蹤。同時提供網格著色器快速路徑以優化遮蔽剔除。

### 4.4 Machine Learning Integration
The `brml` subsystem leverages JAX and Flax to train physics surrogate models. This enables rapid heuristic-based collapse prediction and structural recommendation workflows outside of the strict GPU physics constraint loop.

`brml` 子系統利用 JAX 與 Flax 訓練物理代理模型，這使得在嚴格的 GPU 物理限制迴圈外，能快速進行基於啟發式的崩塌預測與結構推薦。

## 5. Performance Evaluation / 效能評估

The system is heavily optimized to maintain smooth 20 TPS server ticks and 60+ FPS rendering under load:
- **Asynchronous Compute Pipeline:** Triple-buffered non-blocking Vulkan fences prevent the GPU physics evaluation from stalling the main server thread.
- **Sparse Voxel Tracking:** Dirty voxel updates are constrained (max 512 per tick). If the threshold is exceeded, a structured rebuild is deferred to prevent lockups.
- **Memory Management:** Vulkan buffers (`PFSFIslandBuffer`) are meticulously managed with atomic reference counting. Fast path object allocation is eliminated via static caches to prevent GC pressure during high-frequency PFSF evaluations.

本系統經高度最佳化，以在負載下維持 20 TPS 的伺服器 tick 與 60+ FPS 的渲染效能：
- **非同步計算管線：** 三重緩衝的非阻塞 Vulkan 柵欄可防止 GPU 物理評估阻擋伺服器主執行緒。
- **稀疏體素追蹤：** 限制髒體素更新量（每 tick 最多 512 個）。若超出閾值，則延遲進行結構化重建。
- **記憶體管理：** 使用原子引用計數嚴密管理 Vulkan 緩衝區。透過靜態快取消除快速路徑的物件分配，以降低高頻物理評估時的 GC 壓力。

## 6. Conclusion / 結論

Block Reality successfully establishes a comprehensive framework for real-time structural analysis and visualization within a voxel game engine. By combining state-of-the-art GPU compute paradigms (RBGS, Multigrid), advanced rendering techniques (SDF, Vulkan RT), and real engineering metrics, it provides an unparalleled sandbox for architectural design and structural testing.

Block Reality 成功地在體素遊戲引擎內建立了一個用於即時結構分析與視覺化的綜合框架。藉由結合最先進的 GPU 計算範式、高階渲染技術與真實的工程指標，它為建築設計與結構測試提供了無與倫比的沙盒環境。

## References & Documentation / 參考文獻與文檔

Detailed documentation can be found in the `docs/` hierarchy:
- [Master Index](docs/index.md)
- [Citations and References Report](docs/Citations_and_References.md) - *Detailed list of all academic papers and algorithms referenced in this project.*

## License / 授權
MIT
