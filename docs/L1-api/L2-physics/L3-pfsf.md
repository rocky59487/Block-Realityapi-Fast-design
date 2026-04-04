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

## 取代的舊類別

以下類別在 PFSF 完整上線後標記為 `@Deprecated`：

- `SupportPathAnalyzer` — 加權 BFS
- `LoadPathEngine` — 樹走訪
- `ForceEquilibriumSolver` — SOR 迭代
- `BFSConnectivityAnalyzer` — 全圖連通性
