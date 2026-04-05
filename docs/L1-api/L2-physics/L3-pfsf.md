# L3-pfsf — PFSF 勢場導流物理引擎

> GPU 加速的 Poisson 方程式求解器，透過 Vulkan Compute Shader 實現百萬方塊即時結構物理模擬。

## 概述

PFSF（Potential Flow Stress Field）將三維結構力學降維為純量勢場 φ，以離散 Poisson 方程式描述：

```
Σ_{j∈N(i)} σ_ij × (φ_j - φ_i) + ρ_i = 0
```

其中 φ = 累積結構荷載、σ = 材料傳導率、ρ = 自重源項、錨點 = φ=0 Dirichlet 邊界。

## 關鍵類別

| 類別 | 套件 | 說明 |
|------|------|------|
| `PFSFEngine` | `physics.pfsf` | 總入口，管理 Vulkan Pipeline，驅動 V-Cycle |
| `PFSFIslandBuffer` | `physics.pfsf` | 每島 GPU 緩衝區（phi/source/conductivity/type/fail_flags） |
| `PFSFScheduler` | `physics.pfsf` | Chebyshev ω 排程、保守重啟、發散熔斷 |
| `PFSFConductivity` | `physics.pfsf` | σ_ij 傳導率計算（垂直=Rcomp、水平=Rtens修正+距離衰減） |
| `PFSFSourceBuilder` | `physics.pfsf` | 源項 ρ 建構（力臂 BFS + ArchFactor 雙色 BFS） |
| `PFSFFailureApplicator` | `physics.pfsf` | GPU fail_flags 讀回 → CollapseManager 觸發 |
| `PFSFRenderBridge` | `physics.pfsf` | phi[] 零拷貝渲染共享（Compute→Fragment） |
| `PFSFConstants` | `physics.pfsf` | 全域物理常數與 GPU 標記值 |
| `VulkanComputeContext` | `physics.pfsf` | Vulkan 初始化包裝（複用 BRVulkanDevice 或獨立建立） |
| `UnionFind<T>` | `physics.pfsf` | 泛型不相交集合（錨點分群用） |

## Compute Shaders

| Shader | 路徑 | 功能 |
|--------|------|------|
| `jacobi_smooth.comp.glsl` | `shaders/compute/pfsf/` | Jacobi + Chebyshev 核心迭代 |
| `mg_restrict.comp.glsl` | `shaders/compute/pfsf/` | 多重網格殘差降採樣 |
| `mg_prolong.comp.glsl` | `shaders/compute/pfsf/` | 多重網格修正量三線性插值 |
| `failure_scan.comp.glsl` | `shaders/compute/pfsf/` | 斷裂偵測掃描（cantilever/crush/orphan） |
| `stress_heatmap.frag.glsl` | `shaders/compute/pfsf/` | 應力熱力圖視覺化 |

## 核心方法

### PFSFEngine

| 方法 | 說明 |
|------|------|
| `init()` | 初始化 Vulkan Pipeline 和 Descriptor Pool |
| `onServerTick(level, players, epoch)` | 每 Server Tick 主迴路 |
| `shutdown()` | 清理所有 GPU 資源 |
| `isAvailable()` | GPU 物理是否可用 |
| `setMaterialLookup(Function)` | 注入材料查詢函式 |

### PFSFScheduler

| 方法 | 說明 |
|------|------|
| `computeOmega(iter, rhoSpec)` | Chebyshev ω 值計算 |
| `estimateSpectralRadius(Lmax)` | 3D Laplacian 頻譜半徑估計 |
| `onCollapseTriggered(buf)` | 保守重啟 Chebyshev |
| `checkDivergence(buf, maxPhiNow)` | 殘差發散熔斷 |

### PFSFSourceBuilder

| 方法 | 說明 |
|------|------|
| `computeHorizontalArmMap(members, anchors)` | 多源 BFS 水平力臂計算 |
| `computeArchFactorMap(members, anchors)` | 雙色 BFS 拱效應因子計算 |
| `computeSource(mat, fillRatio, arm, archFactor)` | 單體素源項計算 |
| `computeMaxPhi(mat)` | 材料勢能容量 |

## 斷裂判定

| 類型 | 條件 | GPU Flag |
|------|------|----------|
| CANTILEVER_BREAK | φ > maxPhi | 1 |
| CRUSHING | φ / (Rcomp × 1e6) > 1.0 | 2 |
| NO_SUPPORT | φ > PHI_ORPHAN_THRESHOLD | 3 |

## 關聯接口

- **[RMaterial](../L2-material/)** — 材料屬性（Rcomp、Rtens、density）
- **[CollapseManager](../L2-collapse/)** — 崩塌觸發（`triggerPFSFCollapse`）
- **[PhysicsScheduler](L3-force-solver.md)** — 排程整合
- **[StructureIslandRegistry](L3-connectivity.md)** — Island 追蹤
- **[BRVulkanDevice](../L2-render/)** — Vulkan 裝置共享

## 進階功能

### 非同步 Triple-Buffered GPU 管線
- `PFSFAsyncCompute`：3 個 ComputeFrame（fence-based），CPU 永不等待 GPU
- Pre-allocated readback staging，零 runtime VMA 分配
- 2-tick 延遲（100ms），人眼不可見

### 稀疏增量更新
- `PFSFSparseUpdate`：追蹤 dirty voxels（通常 1-20/tick）
- `sparse_scatter.comp`：GPU 端將 44-byte records 散布到大陣列
- 1 方塊變更 = ~200 bytes（非 37MB 全量上傳）

### 各向異性 Capacity（壓/拉分離）
- `rtens[]` buffer：per-voxel 抗拉強度
- `FAIL_TENSION = 4`：outward flux > Rtens × 1e6 觸發拉力斷裂
- 混凝土懸臂：頂面拉裂、底面壓碎

### 對角線虛擬邊（Phantom Edges）
- `PFSFSourceBuilder.injectDiagonalPhantomEdges()`：CPU 端偵測邊/角連接
- 注入虛擬 σ（面 σ 的 30%）到空面方向
- GPU 仍跑 6 鄰域，零額外 shader 開銷

### 條件化能量衰減
- `dampingActive` flag：只在振盪偵測觸發時啟用
- Push constant `damping` 傳入 Jacobi shader
- 穩定後自動關閉（maxPhi 變化 < 1%）

### 動態 Sub-stepping
- 崩塌時步數 = max(STEPS_COLLAPSE, height × 1.5)
- 確保應力資訊在 1-2 tick 內傳遞到建築頂端

### 多人遊戲同步
- `PFSFStressSyncPacket`：每 10 tick 同步 stress ≥ 0.3 的方塊到客戶端
- `CollapseManager.triggerPFSFCollapse()`：廣播崩塌效果到 64 格範圍

### VRAM 預算防護
- `VulkanComputeContext.VRAM_BUDGET = 512MB`
- 超額分配拋出例外

## 取代的舊類別

以下類別在 PFSF 完整上線後標記為 `@Deprecated`：

- `SupportPathAnalyzer` — 加權 BFS
- `LoadPathEngine` — 樹走訪
- `ForceEquilibriumSolver` — SOR 迭代
- `BFSConnectivityAnalyzer` — 全圖連通性
