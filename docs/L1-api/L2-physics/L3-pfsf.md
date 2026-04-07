# L3-pfsf — PFSF 勢場導流物理引擎

> GPU 加速的 Poisson 方程式求解器，透過 Vulkan Compute Shader 實現百萬方塊即時結構物理模擬。

**v2.1 升級**：RBGS 8-色迭代器（2× 收斂）、Ambati 2015 相場斷裂（連續裂紋蔓延）、Morton Tiled 記憶體佈局（L2 cache 命中率 +80%）、上風向傳導率、ICuringManager 水化時間效應。

## 概述

PFSF（Potential Flow Stress Field）將三維結構力學降維為純量勢場 φ，以離散 Poisson 方程式描述：

```
Σ_{j∈N(i)} σ_ij × (φ_j - φ_i) + ρ_i = 0
```

其中 φ = 累積結構荷載、σ = 材料傳導率、ρ = 自重源項、錨點 = φ=0 Dirichlet 邊界。

## 關鍵類別

| 類別 | 套件 | 說明 |
|------|------|------|
| `PFSFEngine` | `physics.pfsf` | 總入口，管理 Vulkan Pipeline，驅動 W-Cycle（v2.1：含相場演化） |
| `PFSFIslandBuffer` | `physics.pfsf` | 每島 GPU 緩衝區（phi/source/conductivity/type/fail_flags/hField/dField/hydration/blockOffsets）|
| `PFSFScheduler` | `physics.pfsf` | Chebyshev ω 排程、保守重啟、發散熔斷 |
| `PFSFConductivity` | `physics.pfsf` | σ_ij 傳導率計算（垂直=Rcomp、水平=Rtens+v2.1 上風向偏置） |
| `PFSFSourceBuilder` | `physics.pfsf` | 源項 ρ 建構（力臂 BFS + ArchFactor 雙色 BFS） |
| `PFSFDataBuilder` | `physics.pfsf` | v2.1：整合 ICuringManager 水化度 → 動態 σ/G_c；Morton 重排預計算 |
| `PFSFFailureApplicator` | `physics.pfsf` | GPU fail_flags 讀回 → CollapseManager 觸發（含相場 d>0.95 觸發） |
| `PFSFRenderBridge` | `physics.pfsf` | phi[]/dField[] 零拷貝渲染共享（Compute→Fragment） |
| `PFSFConstants` | `physics.pfsf` | 全域物理常數與 GPU 標記值（v2.1：PHASE_FIELD_L0、G_C_*、WIND_UPWIND_FACTOR） |
| `VulkanComputeContext` | `physics.pfsf` | Vulkan 初始化包裝（複用 BRVulkanDevice 或獨立建立） |
| `VramBudgetManager` | `physics.pfsf` | v3: VRAM 智慧預算管理器 — 自動偵測 GPU 顯存 + 動態分區（PFSF/Fluid/Other） |
| `ComputeRangePolicy` | `physics.pfsf` | v3: 動態計算範圍策略 — 根據 VRAM 壓力決定 island 分配精度/步數 |
| `DescriptorPoolManager` | `physics.pfsf` | v3: On-demand Descriptor Pool 重置管理器（取代固定 20-tick 間隔） |
| `IslandBufferEvictor` | `physics.pfsf` | v3: LRU Island Buffer 驅逐器 — VRAM 壓力大時驅逐閒置 island |
| `UnionFind<T>` | `physics.pfsf` | 泛型不相交集合（錨點分群用） |

## Compute Shaders

| Shader | 路徑 | 功能 |
|--------|------|------|
| `jacobi_smooth.comp.glsl` | `shaders/compute/pfsf/` | Jacobi（粗網格平滑）+ Amor 拉壓分裂（v2.1） |
| `rbgs_smooth.comp.glsl` | `shaders/compute/pfsf/` | **v2.1 新增**：RBGS 8-色就地迭代（細網格主求解器） |
| `mg_restrict.comp.glsl` | `shaders/compute/pfsf/` | 多重網格殘差降採樣 |
| `mg_prolong.comp.glsl` | `shaders/compute/pfsf/` | 多重網格修正量三線性插值 |
| `phase_field_evolve.comp.glsl` | `shaders/compute/pfsf/` | **v2.1 新增**：Ambati 2015 混合相場演化（連續裂紋） |
| `morton_utils.glsl` | `shaders/compute/pfsf/` | **v2.1 新增**：Morton Z-Order 工具函式（GLSL include） |
| `failure_scan.comp.glsl` | `shaders/compute/pfsf/` | 斷裂偵測掃描（cantilever/crush/orphan） |
| `stress_heatmap.frag.glsl` | `shaders/compute/pfsf/` | 應力熱力圖視覺化（v2.1：新增 dField binding `(set=1, binding=1)` 及 crack overlay） |

## 核心方法

### PFSFIslandBuffer

| 方法 | 說明 |
|------|------|
| `getDamageBuf()` | 向後相容 alias：取得 dFieldBuf |
| `getHistoryBuf()` | 向後相容 alias：取得 hFieldBuf |

### PFSFEngine

| 方法 | 說明 |
|------|------|
| `init()` | 初始化 Vulkan Pipeline 和 Descriptor Pool |
| `onServerTick(level, players, epoch)` | 每 Server Tick 主迴路（v2.1：W-Cycle → RBGS → 相場演化） |
| `adaptiveConvergenceSkip()` | v2.1：新增 adaptive convergence skip（macro-block threshold） |
| `shutdown()` | 清理所有 GPU 資源 |
| `isAvailable()` | GPU 物理是否可用 |
| `setMaterialLookup(Function)` | 注入材料查詢函式 |
| `setCuringLookup(Function<BlockPos, Float>)` | **v2.1**：注入 ICuringManager 水化度查詢（每體素 H ∈ [0,1]） |
| `setWindVector(Vec3)` | **v2.1**：設定當前風向向量（null = 無風，傳導率對稱） |

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

### PFSFDataBuilder（v2.1 擴充）

| 方法 | 說明 |
|------|------|
| `updateSourceAndConductivity(buf, island, level, materialLookup, anchorLookup, fillRatioLookup, curingLookup, windVec)` | 整合水化度 + 上風向傳導率的完整資料上傳 |
| `updateSourceAndConductivity(buf, island, level, materialLookup, anchorLookup, fillRatioLookup)` | 向後相容：不含水化度/風向的舊 API |
| `buildMortonLayout(buf)` | CPU 端 Morton 重排（8×8×8 micro-block），上傳 blockOffsets |
| `build()` | v2.1 整合 Timoshenko moment factor |

### PFSFConductivity（v2.1 擴充）

| 方法 | 說明 |
|------|------|
| `sigma(mi, mj, dir, armI, armJ, windVec)` | v2.1：含上風向偏置（`WIND_UPWIND_FACTOR = 0.30`）的傳導率計算 |
| `sigma(mi, mj, dir, armI, armJ)` | 向後相容重載（windVec=null） |

## 斷裂判定

| 類型 | 條件 | GPU Flag |
|------|------|----------|
| CANTILEVER_BREAK | φ > maxPhi | 1 |
| CRUSHING | φ / (Rcomp × 1e6) > 1.0 | 2 |
| NO_SUPPORT | φ > PHI_ORPHAN_THRESHOLD | 3 |
| **PHASE_FIELD_FRACTURE** | **d_field[i] > 0.95** | **1（複用）** |

## 關聯接口

- **[RMaterial](../L2-material/)** — 材料屬性（Rcomp、Rtens、density）
- **[CollapseManager](../L2-collapse/)** — 崩塌觸發（`triggerPFSFCollapse`）
- **[ICuringManager](../L2-spi/)** — v2.1：水化度查詢（混凝土養護進度 H ∈ [0,1]）
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

### 對角線虛擬邊（Phantom Edges） / 26-connectivity
- `PFSFSourceBuilder.injectDiagonalPhantomEdges()`：CPU 端偵測邊/角連接
- 新增 edge/corner sigma 貢獻（EDGE_P=0.35, CORNER_P=0.15）
- GPU 仍跑 6 鄰域，零額外 shader 開銷

### 條件化能量衰減
- `dampingActive` flag：只在振盪偵測觸發時啟用
- Push constant `damping` 傳入 Jacobi/RBGS shader
- 穩定後自動關閉（maxPhi 變化 < 1%）

### 動態 Sub-stepping
- 崩塌時步數 = max(STEPS_COLLAPSE, height × 1.5)
- 確保應力資訊在 1-2 tick 內傳遞到建築頂端

### 多人遊戲同步
- `PFSFStressSyncPacket`：每 10 tick 同步 stress ≥ 0.3 的方塊到客戶端
- `CollapseManager.triggerPFSFCollapse()`：廣播崩塌效果到 64 格範圍

### v3 VRAM 智慧管理

取代舊版硬編碼 768MB 預算：

- **自動偵測**：`VramBudgetManager.init()` 查詢 `VkPhysicalDeviceMemoryProperties`，取得 device-local heap 大小
- **比例分配**：預設使用偵測到的 VRAM 的 60%（使用者可調 30-80%，`BRConfig.vramUsagePercent`）
- **三分區架構**：PFSF 66.7% / Fluid 20.8% / Other 12.5%，防止引擎互相餓死
- **分配時檢查**：`allocateDeviceBuffer()` 先 VMA 分配，再 `tryRecord()` 預算檢查，超額時回滾
- **釋放時遞減**：`freeBuffer()` 呼叫 `recordFree()`（CRITICAL fix：舊版完全遺漏，導致計數器只增不減）
- **Per-buffer tracking**：ConcurrentHashMap 記錄每個 buffer 的 size + partition，free 時精確遞減

**VRAM 壓力驅動降級**（`ComputeRangePolicy`）：

| 壓力 | 策略 |
|------|------|
| < 50% | L0 全解析度 + 全迭代步數 |
| 50-70% | L0 全解析度 + 減少迭代步數 |
| 70-85% | L1 粗網格（半維度）+ 減少迭代步數 |
| > 85% | 拒絕新 island |

**LRU 驅逐**（`IslandBufferEvictor`）：
- 每次處理 island 更新 LRU 時戳
- 每 20 tick 檢查，VRAM 壓力 > 70% 時驅逐最久未使用的 island（每次最多 3 個）
- island 至少存活 100 tick 才會被驅逐

**Descriptor Pool On-Demand Reset**（`DescriptorPoolManager`）：
- 取代固定每 20 tick 重置
- 追蹤已分配 set 數量，達容量 75% 才重置
- 安全保障：最長 40 tick 無條件重置

### v3 Ping-Pong Parallel 批次提交

取代舊版逐個 `submitAsync()`：

- `PFSFEngineInstance.onServerTick()` 收集最多 3 個 `ComputeFrame` 到 batch
- `PFSFAsyncCompute.submitBatch()` 一次提交多個 frame
- 各 frame 使用獨立 fence + 獨立 `vkQueueSubmit()`（非合併 VkSubmitInfo）
- 好處：driver 可自由排程多個 island 的 compute work，GPU 利用率更高
- batch.size()==1 時自動退化為 `submitAsync()`

---

## v2.1 新增功能

### RBGS 8-色就地迭代（Task B）
- **理論依據**：Young (1971)，ρ(GS) = ρ(J)²，理論收斂速度 2×
- 8-color octree 染色：`color = (x&1) | (y&1)<<1 | (z&1)<<2`，確保 26-連通無資料競爭
- shader 使用 8 pass dispatch，1D `local_size_x=256`
- 細網格主求解器替換（Jacobi 保留用於 W-Cycle 粗網格 L1/L2）
- 省去 phiPrev buffer，VRAM 節省 4N bytes

### Morton Tiled 記憶體佈局（Task C）
- **理論依據**：Mellor-Crummey et al. (2001)，3D stencil L2 cache 命中率 +~80%
- Hybrid Tiled Morton：8×8×8 micro-block 內使用 9-bit Morton Z-Order
- CPU：`PFSFDataBuilder.buildMortonLayout()` 預計算 block_offsets[]
- GPU：`morton_utils.glsl` include，所有 stencil shader 可選用 mortonGlobalIndex()

### Ambati 2015 相場斷裂（Task A）
- **理論依據**：Ambati, Gerasimov & De Lorenzis (2015)，混合公式線性化相場 PDE
- 相場演化從 Miehe 2010 升級為 Ambati 2015（Amor split k_comp）
- 連續裂縫蔓延取代瞬間方塊刪除，d ∈ [0,1] 即時演化
- 離散公式：`d_new = (H + l0²·∇²d) / (H + Gc/(2·l0))`
- 每 `SCAN_INTERVAL` 步執行一次（~2.2ms/1M voxels，RTX 4070）
- 斷裂觸發：d > `PHASE_FIELD_FRACTURE_THRESHOLD (0.95)` → fail_flags → CollapseManager

### Amor 拉壓分裂（Task A.3）
- **理論依據**：Amor, Marigo & Maurini (2009)，標量化張壓分離驅動力
- 啟發式近似：flux_in > 3×flux_out 判定純壓縮狀態
- k_comp = 0.01（純壓縮）防止垂直承重柱誤判為相場損傷驅動
- 實作於 Jacobi + RBGS shader 的 hField 更新段

### 固化時間效應（Task E.2）
- **理論依據**：Bažant (1989) MPS 水化動力學
- σ(t) = σ_final × H^0.5、G_c(t) = G_c_final × H^1.5（H = 水化度 ∈ [0,1]）
- CPU 端透過 `ICuringManager` 取得每體素水化度，零 GPU 額外開銷
- 未養護混凝土的 G_c 大幅降低，模擬早齡期脆性崩塌

### 上風向傳導率（Task D.2）
- 替換 `WIND_CONDUCTIVITY_DECAY = 0.05` 硬截斷
- 上風向：σ' = σ × (1 + 0.30)；下風向：σ' = σ / (1 + 0.30)
- 透過 `PFSFEngine.setWindVector()` 每 tick 動態更新

### v2.1 新增 GPU Buffers（PFSFIslandBuffer）

| 緩衝區 | 類型 | 大小 | 用途 |
|--------|------|------|------|
| `hFieldBuf` | `float[N]` | 4N bytes | history energy buffer（最大應變能歷史） |
| `dFieldBuf` | `float[N]` | 4N bytes | phase-field damage parameter φ（損傷相場 d ∈ [0,1]） |
| `hydrationBuf` | `float[N]` | 4N bytes | 水化度（ICuringManager 讀取） |
| `blockOffsetsBuf` | `uint[num_blocks]` | ≤ N/512 × 4 bytes | Morton micro-block 起始偏移 |

### v2.1 新增常數（PFSFConstants）

| 常數 | 值 | 說明 |
|------|----|------|
| `PHASE_FIELD_L0` | 1.5f | 正則化長度尺度（blocks） |
| `G_C_CONCRETE` | 100.0f J/m² | 混凝土臨界能量釋放率 |
| `G_C_STEEL` | 50000.0f J/m² | 鋼材臨界能量釋放率 |
| `G_C_WOOD` | 300.0f J/m² | 木材臨界能量釋放率 |
| `PHASE_FIELD_RELAX` | 0.3f | 相場鬆弛因子（防過衝） |
| `PHASE_FIELD_FRACTURE_THRESHOLD` | 0.95f | 斷裂觸發閾值 |
| `WIND_UPWIND_FACTOR` | 0.30f | 上風向傳導率偏置係數 |
| `WG_RBGS` | 256 | RBGS workgroup 大小 |
| `RBGS_COLORS` | 8 | 8-color octree 著色數 |
| `MORTON_BLOCK_SIZE` | 8 | Morton micro-block 邊長 |

---

## 取代的舊類別

以下類別在 PFSF 完整上線後標記為 `@Deprecated`：

- `SupportPathAnalyzer` — 加權 BFS
- `LoadPathEngine` — 樹走訪
- `ForceEquilibriumSolver` — SOR 迭代
- `BFSConnectivityAnalyzer` — 全圖連通性
