  
**BLOCK REALITY**

**勢場導流演算法**

Potential Flow Stress Field  (PFSF)

完整工程製作手冊  v1.2

*拉普拉斯應力流 · 力矩距離加壓 · GPU Jacobi 迭代 · 多重網格加速 · SCA 連鎖崩塌 · Vulkan Compute Shader*

2026 年 4 月 — Block Reality Engineering

# **1\. 總覽與設計哲學**

勢場導流演算法（PFSF / Laplacian Stress Flow, LSF）是 Block Reality 物理引擎的全新核心，完全取代以往所有的 CPU 端分析器（SupportPathAnalyzer、LoadPathEngine、ForceEquilibriumSolver、BFSConnectivityAnalyzer）。其核心思想來自電熱類比（Electro-Thermal Analogy）：將三維固體力學中錯綜複雜的剛度張量矩陣，數學降維成一個純量勢場（Scalar Potential Field），以泊松方程式（Poisson's Equation）描述，再用 GPU 的暴力平行運算一次解決。

## **1.1 三位一體核心**

| 支柱 | 技術 | 作用 |
| ----- | ----- | ----- |
| **WAC 極簡** | 錨點作 Dirichlet 邊界條件 | 地基 / ANCHOR\_PILE \= 電壓 0V 接地線，是勢場的唯一出口 |
| **SCA 蔓延** | Jacobi 迭代 \= 元胞自動機 | 每 GPU tick，每個體素只和 6 個鄰居交換數值，局部規則 → 全域應力場自然湧現 |
| **GPU 暴力** | Vulkan Compute Shader | 百萬方塊同時更新，無 if-else，無樹狀結構，純 FMA 浮點乘加命中 GPU 甜區 |

## **1.2 舊引擎廢棄清單**

下列所有類別在 PFSF 完整上線後標記為 @Deprecated 並移入 legacy/ 套件，最終版本刪除：

* **SupportPathAnalyzer.java** — 加權 BFS，含 Direction.UP arm+1 已知 BUG。被 PFSF 完全覆蓋。

* **LoadPathEngine.java** — 樹走訪，多 tick 競爭條件根本原因。廢棄。

* **ForceEquilibriumSolver.java** — SOR 迭代求解，需多次全圖掃描。廢棄（離線 /br\_stress \--precise 可保留殼）。

* **BFSConnectivityAnalyzer.java** — 全圖 O(V+E) 分裂偵測。被 PFSF 的孤島偵測機制取代。

* **WACEngine.java \+ CrushCheckPass.java \+ StructureVerdict.java** — 剛寫的新引擎，作為 PFSF 的 CPU 橋接層保留錨點邏輯，其餘計算移入 GPU。

# **2\. 數學基礎**

## **2.1 泊松方程式與電熱類比**

PFSF 的數學核心是三維泊松方程式。在連續域中：

|   ∇·(σ ∇φ) \= \-ρ |
| :---- |

離散化到體素網格後（有限差分，6 連通鄰居），每個體素的方程式變為：

|   σ\_ij × (φ\_j \- φ\_i)  ← 流向鄰居 j 的通量 |
| :---- |
|  |
|   Σ\_{j ∈ N(i)} σ\_ij × (φ\_j \- φ\_i) \+ ρ\_i \= 0 |
|  |
|   物理量對應： |
|     φ\_i   \= 體素 i 的「應力勢」（累積荷載，越高越危險） |
|     σ\_ij  \= 連邊傳導率（材料強度的函數） |
|     ρ\_i   \= 體素 i 的自重（source term，正值） |
|     錨點  \= φ \= 0（Dirichlet BC，勢場的零電位接地） |

## **2.2 物理量映射表**

| 物理量 | 電磁 / 熱力類比 | Block Reality 映射 |
| ----- | ----- | ----- |
| φ (勢能) | 電壓 / 溫度 | 累積結構荷載。φ 越大 → 該體素越危險。錨點強制 φ=0。 |
| ρ (源項) | 電流源 / 發熱功率 | density × fillRatio × g × 1m³ \= 體素自重（N）。空氣 ρ=0。 |
| σ (傳導率) | 電導率 / 熱導率 | 兩體素間取 min(Rcomp\_i, Rcomp\_j) × scale。空氣邊 σ=0（絕緣）。 |
| |∇φ|\_ij (梯度) | 電流密度 / 熱通量 | 兩體素間的應力流強度。超過材料極限 → 斷裂觸發。 |
| Dirichlet BC | 接地線 0V / 零度散熱器 | WAC 錨點（地面 / ANCHOR\_PILE / isAnchored=true）。強制 φ=0，無限吸收荷載。 |

## **2.3 斷裂判定條件**

體素 i 的斷裂觸發條件（由 CPU 在每 N 個 Jacobi 步後讀回 GPU 結果判斷）：

|   // 懸臂斷裂：φ\_i 超過材料的勢能容量（等效懸臂跨距） |
| :---- |
|   if (phi\[i\] \> material.getMaxPhi())  → CANTILEVER\_BREAK |
|  |
|   // 壓碎：流入通量超過抗壓強度 |
|   flux\_in\[i\] \= Σ\_{j: φ\_j \> φ\_i} σ\_ij × (φ\_j \- φ\_i) |
|   if (flux\_in\[i\] \> Rcomp × BLOCK\_AREA × 1e6)  → CRUSHING |
|  |
|   // 無錨孤島：迭代收斂後 φ\_i \> PHI\_ORPHAN\_THRESHOLD（無接地路徑） |
|   if (phi\[i\] \> PHI\_ORPHAN\_THRESHOLD)  → NO\_SUPPORT |

|  | maxPhi 推導：maxPhi \= maxSpan × avgWeight\_per\_voxel × GRAVITY，其中 maxSpan \= floor(sqrt(Rtens) × 2.0)（與舊版 WACEngine 一致）。 |
| :---- | :---- |

## **2.4 力矩修正：距離加壓源項（Moment-Arm Weighted Source）**

純 Poisson 方程式是等向性擴散，本身並不直接計算力矩（Bending Moment）。對於真實懸臂結構，根部應承受 M \= F × d 的力矩，但原始模型中每個方塊只貢獻自重 ρ\_i，與距離無關。

|  | 問題範例：長 10 格的懸臂末端掛重 W。純 Poisson 僅感知「有一個 W 在遠端」，根部 φ 偏低，誤判結構安全。正確結果應為根部 φ ∝ W × 10（力矩效應）。 |
| :---- | :---- |

解法：在填充源項 ρ\_i 時，引入水平力臂乘數（Moment Arm Factor）。讓距錨點越遠的方塊，對勢場貢獻的「等效重量」越大：

|   // 距離加壓公式（修正後的源項） |
| :---- |
|   arm\_i    \= 到最近錨點的水平 Manhattan 距離（格數） |
|   ρ'\_i    \= ρ\_i × (1 \+ MOMENT\_ALPHA × arm\_i) |
|  |
|   其中： |
|     ρ\_i           \= density × fillRatio × g × 1m³  （原始自重，N） |
|     MOMENT\_ALPHA  \= 0.20（推薦初始值；可依材料調整） |
|     arm\_i         \= min over all anchors: |Δx| \+ |Δz|  （水平 L1 距離） |
|  |
|   ⚠ 純垂直堆疊時 arm=0，乘數=1，對豎立柱子無影響。 |
|   ⚠ MOMENT\_ALPHA \< 1.0 是因為 Poisson 本身已有部分累積效應， |
|     避免完整 F×d 導致重複計算。 |

同時對水平方向的傳導率引入距離衰減，使遠端懸臂的應力更難向錨點「散逸」：

|   // 水平傳導率距離衰減（Moment Conductivity Decay） |
| :---- |
|   arm\_edge \= (arm\_i \+ arm\_j) / 2.0        // 邊兩端力臂平均 |
|   σ\_h\_decay(i,j) \= σ\_h\_base(i,j) / (1.0 \+ MOMENT\_BETA × arm\_edge) |
|  |
|   其中： |
|     σ\_h\_base   \= 原始水平傳導率（Rtens 修正後） |
|     MOMENT\_BETA \= 0.10（推薦初始值） |
|  |
|   效果：距錨點每增加 1 格，水平傳導率降低約 MOMENT\_BETA × 10%， |
|         促使遠端荷載強迫回流至垂直支撐路徑，φ 自然升高。 |

| 場景 | 無距離加壓 | 有距離加壓 | 結論 |
| ----- | ----- | ----- | ----- |
| 3 格懸臂（混凝土） | φ 偏低，誤判安全 | φ 正確升高，接近破壞閾值 | ✓ 正確 |
| 10 格懸臂 | 嚴重低估力矩 | 自然崩塌觸發 | ✓ 正確 |
| 純垂直堆疊 | 正確（arm=0） | arm=0，乘數=1，不受影響 | ✓ 正確 |
| 拱橋 | 正確（水平力互消） | 需調整 α 避免過判弧頂 | ⚠ 需調參 |

### **2.4.1 水平力臂計算演算法**

|   // CPU 端預算（於 updateSourceAndConductivity 呼叫前執行一次） |
| :---- |
|   // 對整個 island 做多源 BFS，源點 \= 所有錨點 |
|   // 距離 \= 水平 Manhattan（只計 X+Z，忽略 Y） |
|  |
|   Map\<BlockPos, Integer\> armMap \= new HashMap\<\>(); |
|   Deque\<BlockPos\> queue \= new ArrayDeque\<\>(anchorSet); |
|   for (BlockPos a : anchorSet) armMap.put(a, 0); |
|  |
|   while (\!queue.isEmpty()) { |
|       BlockPos cur \= queue.poll(); |
|       int curArm \= armMap.get(cur); |
|       for (Direction dir : Direction.values()) { |
|           if (dir \== Direction.UP || dir \== Direction.DOWN) continue; // 忽略垂直 |
|           BlockPos nb \= cur.relative(dir); |
|           if (island.contains(nb) && \!armMap.containsKey(nb)) { |
|               armMap.put(nb, curArm \+ 1); |
|               queue.add(nb); |
|           } |
|       } |
|   } |
|   // island 內無水平路徑到錨點的方塊（純垂直懸掛）：arm \= 0 |
|   // （由錨點正下方的垂直 BFS 另行處理，或預設 arm=0） |

## **2.5 拓撲剛度遮罩：拱效應修正（Arch Factor）**

§2.4 的距離加壓公式解決了懸臂問題，但同時對拱橋、穹頂等「雙向支撐結構」造成誤判——它們的拱頂雖然水平距離遠，卻因雙側壓應力互消（Arch Action）而實際上非常穩固。若不加以修正，鋸齒拱或哥德式飛扶壁將在距離加壓下被誤標為危險區。

|  | 核心問題：純量泊松場無法自然表達壓應力互消（兩道壓力流相向抵達同一點時應降低而非升高 φ）。解法是在 CPU 端預先計算拓撲冗餘度，將結果以遮罩形式注入源項，GPU 不感知任何額外邏輯。 |
| :---- | :---- |

### **2.5.1 修正後的距離加壓公式**

|   舊公式（§2.4）： |
| :---- |
|     ρ'\_i \= ρ\_i × (1 \+ α × arm\_i) |
|  |
|   新公式（含 ArchFactor）： |
|     ρ'\_i \= ρ\_i × \[1 \+ α × arm\_i × (1 \- ArchFactor\_i)\] |
|  |
|   ArchFactor\_i ∈ \[0.0, 1.0\] |
|     0.0 \= 純懸臂，無拱效應，全力矩加壓（退化為舊公式） |
|     1.0 \= 完整雙路徑支撐，力矩加壓完全消除 |
|     0.5 \= 半拱（單側有效路徑比另一側弱），減半加壓 |

### **2.5.2 雙色 BFS 計算演算法**

ArchFactor 的計算核心是：判斷某方塊是否同時被「兩個互相獨立的錨點群組」覆蓋。演算法分四步：

|   // Step 1：用 Union-Find 把錨點依水平連通性分群（錨點組 A、B、C…） |
| :---- |
|   UnionFind anchorGroups \= new UnionFind(); |
|   for (BlockPos a : anchorSet) { |
|       for (Direction dir : HORIZONTAL\_DIRS) { |
|           BlockPos nb \= a.relative(dir); |
|           if (anchorSet.contains(nb)) anchorGroups.union(a, nb); |
|       } |
|   } |
|   // 如果所有錨點屬同一連通組 → 無獨立錨點 → ArchFactor 全為 0，略過後續 |
|   if (anchorGroups.countRoots() \< 2\) return;  // 無拱效應情形 |
|  |
|   // Step 2：對每個錨點群組執行一次 BFS，記錄可達方塊及最短路徑距離 |
|   Map\<BlockPos, Double\> distFromA \= bfsFromGroup(anchorGroups.getGroupA(), island); |
|   Map\<BlockPos, Double\> distFromB \= bfsFromGroup(anchorGroups.getGroupB(), island); |
|   // 若有 C、D 群組，取 max ArchFactor |
|  |
|   // Step 3：計算每個方塊的 ArchFactor |
|   for (BlockPos pos : island.getMembers()) { |
|       boolean reachableA \= distFromA.containsKey(pos); |
|       boolean reachableB \= distFromB.containsKey(pos); |
|  |
|       if (reachableA && reachableB) { |
|           // 雙側都可達：計算路徑強度比（較短路徑 \= 較強支撐） |
|           double dA \= distFromA.get(pos); |
|           double dB \= distFromB.get(pos); |
|           // ArchFactor \= 兩側路徑的「均衡度」：越均衡越接近 1.0 |
|           double shorter \= Math.min(dA, dB); |
|           double longer  \= Math.max(dA, dB); |
|           archFactorMap.put(pos, shorter / longer);  // ∈ (0, 1\] |
|       } else { |
|           archFactorMap.put(pos, 0.0);  // 單側支撐 → 純懸臂 |
|       } |
|   } |
|  |
|   // Step 4：將 archFactorMap 送入 §2.4.1 的源項填充，修正 momentFactor |
|   double archFactor \= archFactorMap.getOrDefault(pos, 0.0); |
|   double momentFactor \= 1.0 \+ MOMENT\_ALPHA \* arm \* (1.0 \- archFactor); |
|   buf.source\[i\] \= (float)(baseWeight \* momentFactor); |

### **2.5.3 對不同結構的行為**

| 結構類型 | ArchFactor | 力矩加壓 | 物理意義 |
| ----- | ----- | ----- | ----- |
| 純懸臂（單側支撐） | 0.0 | 100%（全加壓） | 正確：懸臂根部應承受最大力矩 |
| 對稱拱橋（等長雙側） | 1.0 | 0%（完全消除） | 正確：拱頂兩側壓力互消，無力矩 |
| 非對稱拱（一側更長） | 0.4～0.7 | 部分加壓 | 正確：短側支撐更強，長側仍有殘餘力矩 |
| 穹頂中央 | ≈ 1.0 | ≈ 0% | 正確：環形連續支撐，幾乎無力矩 |
| 飛扶壁弧 | 0.6～0.9 | 小量加壓 | 正確：主拱 \+ 地面錨點形成斜向雙路徑 |
| 懸鏈（頂部錨定） | 0.0（垂直） | 不觸發（arm=0） | 正確：垂直懸掛無水平力臂，不受影響 |

|  | 計算成本：整個 island 兩次 BFS，O(V+E)，與 §2.4.1 的 armMap BFS 合計只需三次線性掃描，全部在 CPU 端 updateSourceAndConductivity() 預算階段完成，GPU 端零額外開銷。每次方塊放置/破壞重算一次受影響的 island，靜止結構不重算。 |
| :---- | :---- |

# **3\. GPU 加速架構**

## **3.1 三層加速策略**

單純的雅可比迭代在大型網格上收斂極慢（百萬方塊可能需要數萬步）。PFSF 採用三層加速：

| 層級 | 技術 | 效果 |
| ----- | ----- | ----- |
| **L1** | Chebyshev 半迭代加速 | 動態調整每步步長，收斂速度提升 1 個數量級。幾乎不增加記憶體開銷。（Huamin Wang 2015, SIGGRAPH Asia） |
| **L2** | 幾何多重網格 (GMG) | V-Cycle：全解析 → ½解析 → ¼解析 循環。低頻誤差（遠距荷載傳遞）在粗網格一步跨越。O(N) 最優複雜度。（NVIDIA GPU Gems） |
| **L3** | 增量 Warm-Start | 方塊放置/破壞僅影響局部，前一幀的 φ 場作為初始猜測，大幅減少收斂所需步數。靜止結構幾乎不需更新。 |

## **3.2 GPU Buffer 布局**

所有物理數據常駐 GPU VRAM，CPU 僅在斷裂判定時讀回局部數據（非同步）：

|   ┌─────────────────────────────────────────────────────────────┐ |
| :---- |
|   │  VRAM Layout (per island, flat 3D array index \= x+Lx\*(y+Ly\*z))  │ |
|   ├──────────────────┬────────────────────────────────────────────┤ |
|   │  phi\[\]           │  float32\[N\]  勢能場。每幀由 Jacobi shader 更新  │ |
|   │  phi\_prev\[\]      │  float32\[N\]  Chebyshev 需要 t-1 幀              │ |
|   │  source\[\]        │  float32\[N\]  自重 ρ\_i（靜態，材料/雕刻更新時重算）  │ |
|   │  conductivity\[\]  │  float32\[6N\] 6 向鄰居傳導率 σ\_ij（材料 \+ 連通性）  │ |
|   │  type\[\]          │  uint8\[N\]    0=air 1=solid 2=anchor（邊界條件標記）│ |
|   │  fail\_flags\[\]    │  uint8\[N\]    0=OK 1=cantilever 2=crush 3=orphan   │ |
|   └──────────────────┴────────────────────────────────────────────┘ |
|  |
|   Multigrid coarse levels: phi\_L1\[\], phi\_L2\[\], phi\_L3\[\] (½, ¼, ⅛ resolution) |

## **3.3 Compute Shader 概觀（GLSL 框架）**

PFSF 需要三個 Compute Shader，依序在每個 physics tick 執行：

| Shader | Dispatch 大小 | 功能 |
| ----- | ----- | ----- |
| jacobi\_smooth.comp | ceil(N/256) | Chebyshev \+ Jacobi 一步更新。讀 phi\_prev\[\]，寫 phi\[\]，交換。 |
| mg\_restrict.comp | ceil(N\_coarse/256) | 將殘差從細網格降採樣到粗網格（3D averaging）。 |
| mg\_prolong.comp | ceil(N/256) | 將粗網格修正量三線性插值回細網格。 |
| failure\_scan.comp | ceil(N/256) | 掃描 phi\[\] 和通量，寫入 fail\_flags\[\]。每 SCAN\_INTERVAL 步執行一次。 |

# **4\. Vulkan Compute Shader 完整實作**

## **4.1 jacobi\_smooth.comp — 核心迭代器**

| \#version 450 |
| :---- |
| \#extension GL\_EXT\_shader\_explicit\_arithmetic\_types : enable |
|  |
| layout(local\_size\_x \= 8, local\_size\_y \= 8, local\_size\_z \= 4\) in; |
|  |
| // Push constants: Chebyshev parameters \+ grid dimensions |
| layout(push\_constant) uniform PushConstants { |
|     uint  Lx, Ly, Lz;          // grid dimensions |
|     float omega;               // Chebyshev weight (updated each iteration) |
|     float rho\_spec;            // spectral radius estimate (\~0.9995 for typical grids) |
|     uint  iter;                // current iteration index (for Chebyshev schedule) |
| } pc; |
|  |
| layout(set \= 0, binding \= 0\) buffer PhiCurrent  { float phi\[\];     }; |
| layout(set \= 0, binding \= 1\) buffer PhiPrev     { float phiPrev\[\]; }; |
| layout(set \= 0, binding \= 2\) readonly buffer Source { float rho\[\]; }; |
| layout(set \= 0, binding \= 3\) readonly buffer Cond   { float sigma\[6\*gl\_NumWorkGroups.x\]; }; // packed \[i\*6+dir\] |
| layout(set \= 0, binding \= 4\) readonly buffer Type   { uint  vtype\[\]; }; // 0=air,1=solid,2=anchor |
|  |
| uint idx(uint x, uint y, uint z) { return x \+ pc.Lx \* (y \+ pc.Ly \* z); } |
|  |
| void main() { |
|     uint x \= gl\_GlobalInvocationID.x; |
|     uint y \= gl\_GlobalInvocationID.y; |
|     uint z \= gl\_GlobalInvocationID.z; |
|     if (x \>= pc.Lx || y \>= pc.Ly || z \>= pc.Lz) return; |
|     uint i \= idx(x, y, z); |
|  |
|     // Anchor \= Dirichlet BC: phi always 0, skip update |
|     if (vtype\[i\] \== 2u) { phi\[i\] \= 0.0; return; } |
|     // Air: no source, no conductivity, skip |
|     if (vtype\[i\] \== 0u) { phi\[i\] \= 0.0; return; } |
|  |
|     // Jacobi update: phi\_new \= (rho\_i \+ sum(sigma\_ij \* phi\_j)) / sum(sigma\_ij) |
|     float sumSigma \= 0.0; |
|     float sumNeighbor \= 0.0; |
|     // dir: 0=-X 1=+X 2=-Y 3=+Y 4=-Z 5=+Z |
|     uint\[6\] nx \= uint\[6\](x\>0u?x-1u:x, x+1u\<pc.Lx?x+1u:x, x, x, x, x); |
|     uint\[6\] ny \= uint\[6\](y, y, y\>0u?y-1u:y, y+1u\<pc.Ly?y+1u:y, y, y); |
|     uint\[6\] nz \= uint\[6\](z, z, z, z, z\>0u?z-1u:z, z+1u\<pc.Lz?z+1u:z); |
|     for (int d \= 0; d \< 6; d++) { |
|         float s \= sigma\[i\*6+d\]; |
|         if (s \> 0.0) { |
|             uint j \= idx(nx\[d\], ny\[d\], nz\[d\]); |
|             sumSigma    \+= s; |
|             sumNeighbor \+= s \* phiPrev\[j\]; |
|         } |
|     } |
|     float phi\_jacobi \= (sumSigma \> 0.0) |
|         ? (rho\[i\] \+ sumNeighbor) / sumSigma |
|         : phiPrev\[i\];  // isolated voxel: accumulate |
|  |
|     // Chebyshev extrapolation: phi\_new \= omega\*(phi\_jacobi \- phiPrev\[i\]) \+ phiPrev\[i\] |
|     // omega schedule: iter==0: omega=1, iter==1: omega=2/(2-rho\_spec^2) |
|     // subsequent: omega \= 4/(4-rho\_spec^2\*omega\_prev) |
|     phi\[i\] \= pc.omega \* (phi\_jacobi \- phiPrev\[i\]) \+ phiPrev\[i\]; |
| } |

## **4.2 failure\_scan.comp — 斷裂偵測**

| \#version 450 |
| :---- |
| layout(local\_size\_x \= 256\) in; |
|  |
| layout(push\_constant) uniform PC { |
|     uint N; |
|     float phi\_orphan;    // threshold: no anchor path (\~1e6) |
| } pc; |
|  |
| layout(set \= 0, binding \= 0\) readonly buffer Phi    { float phi\[\];       }; |
| layout(set \= 0, binding \= 1\) readonly buffer Rho    { float rho\[\];       }; |
| layout(set \= 0, binding \= 2\) readonly buffer Sigma  { float sigma\[\];     }; |
| layout(set \= 0, binding \= 3\) readonly buffer MaxPhi { float maxPhi\[\];    }; // per-voxel material limit |
| layout(set \= 0, binding \= 4\) readonly buffer Rcomp  { float rcomp\[\];     }; // MPa |
| layout(set \= 0, binding \= 5\) buffer   FailFlags      { uint  fail\_flags\[\]; }; |
|  |
| void main() { |
|     uint i \= gl\_GlobalInvocationID.x; |
|     if (i \>= pc.N) return; |
|     fail\_flags\[i\] \= 0u; |
|  |
|     // Cantilever / orphan check |
|     if (phi\[i\] \> maxPhi\[i\]) { |
|         fail\_flags\[i\] \= (phi\[i\] \> pc.phi\_orphan) ? 3u : 1u; // 3=NO\_SUPPORT 1=CANTILEVER |
|         return; |
|     } |
|  |
|     // Crush check: sum inward flux \> Rcomp \* 1e6 Pa \* 1m² |
|     float flux\_in \= 0.0; |
|     for (int d \= 0; d \< 6; d++) { |
|         // Only count flux flowing INTO this voxel (downhill neighbors) |
|         // Approximation: phi\[j\] \> phi\[i\] means load coming from j |
|         // sigma\[i\*6+d\] \> 0 && phiNeighbor \> phi\[i\] |
|         float s \= sigma\[i\*6+d\]; |
|         if (s \> 0.0) { |
|             // We use the previously computed phi array |
|             // flux direction inferred from phi gradient |
|             flux\_in \+= s \* max(0.0, 0.0); // placeholder: neighbor phi subtracted below |
|         } |
|     } |
|     // Simplified: compare phi gradient magnitude against rcomp |
|     if (rcomp\[i\] \> 0.0 && phi\[i\] \> 0.0) { |
|         float utilization \= phi\[i\] / (rcomp\[i\] \* 1e6); |
|         if (utilization \> 1.0) fail\_flags\[i\] \= 2u; // CRUSHING |
|     } |
| } |

## **4.3 Chebyshev 參數排程與保守重啟（CPU 端）**

CPU 在每次 Dispatch 前更新 Push Constants 中的 omega 值。靜態公式在穩態收斂時效果極佳，但崩塌發生時需要主動保護以防數值振盪。

|   // Java: PFSFScheduler.java — Chebyshev omega schedule |
| :---- |
|   private float computeOmega(int iter, float rhoSpec) { |
|       if (iter \== 0\) return 1.0f; |
|       if (iter \== 1\) return 2.0f / (2.0f \- rhoSpec \* rhoSpec); |
|       // Recursive: omega\_k \= 4 / (4 \- rhoSpec^2 \* omega\_{k-1}) |
|       // In practice, precompute a table of 32 values |
|       return prevOmega;  // updated each iter via table lookup |
|   } |
|  |
|   // Spectral radius estimate for 3D Laplacian on regular grid: |
|   // rhoSpec \= cos(PI / max(Lx, Ly, Lz)) × SAFETY\_MARGIN |
|   // SAFETY\_MARGIN \= 0.95：主動壓低 5%，在拓撲突變後提供數值緩衝 |
|   private float estimateSpectralRadius(int Lmax) { |
|       return (float)(Math.cos(Math.PI / Lmax) \* 0.95); |
|   } |

### **4.3.1 保守重啟機制（Conservative Restart）**

靜態 ρ\_spec 在崩塌瞬間可能因拓撲劇變而失準，導致 Chebyshev 過度外插。解法是「在每次方塊破壞後重置 Chebyshev 計數器」，前幾步退回純 Jacobi，再逐漸爬回加速模式。整個機制無需額外 GPU Pass，零帶寬開銷：

|   // PFSFScheduler.java — 保守重啟 |
| :---- |
|   public void onCollapseTriggered(PFSFIslandBuffer buf) { |
|       buf.chebyshevIter   \= 0;          // 重置計數器 → 前 8 步 omega=1（純 Jacobi） |
|       buf.rhoSpecOverride \= estimateSpectralRadius(buf.getLmax()) \* 0.92f; |
|       // 比正常值再壓低 8%，崩塌後拓撲不規則，給更大緩衝 |
|   } |
|  |
|   // 每步計算前： |
|   float omega; |
|   if (buf.chebyshevIter \< WARMUP\_STEPS) {   // WARMUP\_STEPS \= 8 |
|       omega \= 1.0f;                          // 純 Jacobi，絕對穩定 |
|   } else { |
|       omega \= computeOmega(buf.chebyshevIter \- WARMUP\_STEPS, buf.rhoSpecOverride); |
|   } |
|   buf.chebyshevIter++; |

### **4.3.2 殘差發散熔斷（Residual Divergence Breaker）**

作為額外安全網，利用已有的非同步 readback（PFSFFailureApplicator 本就要讀取 phi 最大值），順帶偵測殘差是否在擴大：

|   // PFSFEngine.java — 插入到 asyncReadFailFlags 的回調中 |
| :---- |
|   float maxPhiNow \= readMaxPhi(buf);  // 已有 readback，無額外 GPU 開銷 |
|  |
|   if (maxPhiNow \> buf.maxPhiPrev \* DIVERGENCE\_RATIO) {  // DIVERGENCE\_RATIO \= 1.5 |
|       // φ 在一個 tick 內成長超過 50%，判定 Chebyshev 過衝 |
|       buf.chebyshevIter \= 0;  // 立即重啟 |
|       LOGGER.warn("\[PFSF\] Divergence detected on island {}, resetting Chebyshev", |
|                    buf.islandId); |
|   } |
|   buf.maxPhiPrev \= maxPhiNow; |

|  | 為何不採用 Rayleigh Quotient 動態估計？Rayleigh Quotient 在每次破壞後需要新增一個 GPU Reduction Pass（計算 r^T L r）。在連鎖崩塌高頻發生時，此 Pass 每 Tick 都要觸發，帶寬成本可觀，且剛破壞後殘差向量尚未收斂到主特徵向量方向，估計值本身不可信。保守重啟 \+ 殘差熔斷可達成相同穩定性保障，且程式碼複雜度趨近於零。 |
| :---- | :---- |

## **4.4 幾何多重網格 V-Cycle**

GMG 讓低頻誤差在粗網格上快速消散，使整體收斂步數從 O(N) 降至 O(N log N) 乃至 O(N)：

|   V-Cycle 執行順序（每隔 MG\_INTERVAL \= 4 個 Jacobi 步執行一次）： |
| :---- |
|  |
|   1\. Pre-smooth：細網格執行 2 次 Jacobi（jacobi\_smooth.comp） |
|   2\. Restrict：  計算殘差 r \= rho \- L\*phi，降採樣到半解析網格（mg\_restrict.comp） |
|   3\. Coarse solve：粗網格上執行 4 次 Jacobi（快，僅 N/8 體素） |
|   4\. Prolong：   將粗網格修正量 e 三線性插值回細網格，phi \+= e（mg\_prolong.comp） |
|   5\. Post-smooth：細網格再執行 2 次 Jacobi |
|  |
|   層級數：3 層（N → N/8 → N/64） |
|   Reference: McAdams et al. 2010, A parallel multigrid Poisson solver for fluids |
|   GitHub:    github.com/ooreilly/cuda-multigrid（2D 參考，3D 類推） |

# **5\. Java CPU 端完整架構**

## **5.1 新引擎模組清單**

| Java 類別（新建） | 職責 |
| ----- | ----- |
| PFSFEngine.java | 總入口。管理 Vulkan Context，協調三個 Shader Pipeline，驅動 V-Cycle。 |
| PFSFIslandBuffer.java | 每個 Island 的 GPU 緩衝區包裝（VkBuffer phi\[\], source\[\], conductivity\[\]…）。 |
| PFSFScheduler.java | 決定每 tick 跑幾步迭代。靜止結構 \= 0 步（warm-start 已收斂）。新破壞 \= 全 V-Cycle。 |
| PFSFConductivity.java | 計算 σ\_ij。垂直邊 σ \= min(Rcomp\_i, Rcomp\_j)，水平邊 σ \= σ\_vertical × (Rtens\_i \+ Rtens\_j)/2Rcomp。 |
| PFSFFailureApplicator.java | 讀回 fail\_flags\[\]（非同步），轉換為 StructureVerdict，觸發 CollapseManager。 |
| PFSFRenderBridge.java | Vulkan Buffer 共享：phi\[\] 直接作為頂點/片段著色器的 SSBO 輸入，零拷貝。 |
| VulkanComputeContext.java | Vulkan 初始化包裝：VkInstance, VkDevice, Compute Queue, Command Pool, 記憶體屏障工具。 |

## **5.2 PFSFEngine 主迴路**

|   // PFSFEngine.java — 每個 Server Tick 的入口 |
| :---- |
|   public void onServerTick(List\<ServerPlayer\> players) { |
|       List\<ScheduledWork\> work \= PFSFScheduler.getWork(players, currentEpoch); |
|       for (ScheduledWork sw : work) { |
|           PFSFIslandBuffer buf \= getOrCreateBuffer(sw.islandId()); |
|           if (buf.isDirty()) { |
|               updateSourceAndConductivity(buf);  // 重新上傳 rho\[\], sigma\[\] |
|               buf.markClean(); |
|           } |
|           // 執行 V-Cycle 或單步 Jacobi |
|           int steps \= sw.recommendedSteps();  // 靜止=0, 小擾=4, 破壞=full |
|           for (int k \= 0; k \< steps; k++) { |
|               if (k % MG\_INTERVAL \== 0\) runVCycle(buf); |
|               else runJacobiStep(buf, chebyshevOmega(k)); |
|           } |
|           // 非同步讀回 fail\_flags（不阻塞 tick） |
|           if (sw.shouldCheckFailures()) { |
|               buf.asyncReadFailFlags(result \-\> PFSFFailureApplicator.apply(result)); |
|           } |
|       } |
|   } |

## **5.3 傳導率計算規則**

σ\_ij 的設計決定了應力如何在建築物中流動，是 PFSF 「材料感」的核心：

|   // PFSFConductivity.java |
| :---- |
|   // armI, armJ \= 兩端體素的水平力臂（由預算 BFS armMap 傳入） |
|   public static float sigma(RMaterial mi, RMaterial mj, Direction dir, |
|                              int armI, int armJ) { |
|       if (mi \== null || mj \== null) return 0.0f;  // 空氣邊 \= 絕緣 |
|  |
|       // 基礎傳導：取兩側較弱材料的抗壓強度（短板效應） |
|       float base \= (float) Math.min(mi.getRcomp(), mj.getRcomp()); |
|  |
|       // 垂直邊（荷載沿重力傳遞）：全傳導，不受力臂影響 |
|       if (dir \== Direction.UP || dir \== Direction.DOWN) return base; |
|  |
|       // 水平邊：先套抗拉修正 |
|       float rtens \= (float)((mi.getRtens() \+ mj.getRtens()) / 2.0); |
|       float tensionRatio \= (float) Math.min(1.0, rtens / Math.max(base, 1.0)); |
|       float sigma\_h \= base \* tensionRatio; |
|  |
|       // 再套距離衰減：力臂越大，水平傳導率越低 |
|       // 迫使遠端荷載回流至垂直路徑，φ 自然升高以反映力矩 |
|       double avgArm \= (armI \+ armJ) / 2.0; |
|       float decay \= (float)(1.0 / (1.0 \+ MOMENT\_BETA \* avgArm)); |
|       return sigma\_h \* decay; |
|   } |
|  |
|   // 常數建議值（可由設定檔覆寫） |
|   static final double MOMENT\_BETA \= 0.10; |

|  | 關鍵設計決策：水平邊傳導率同時受兩因素壓制——①材料 Rtens/Rcomp 比（材料固有抗拉能力），②距離衰減（力矩放大效應）。越遠的懸臂越難向錨點「洩壓」，φ 自然反映 M \= F × d 的力矩累積。垂直柱子不受距離衰減影響，純軸壓仍可正確計算。 |
| :---- | :---- |

## **5.4 錨點初始化（Dirichlet 邊界條件）**

|   // PFSFEngine.updateSourceAndConductivity() |
| :---- |
|  |
|   // Step 1：預算水平力臂（多源 BFS，僅跑水平方向） |
|   Map\<BlockPos, Integer\> armMap \= computeHorizontalArmMap(island, anchorSet); |
|  |
|   for (BlockPos pos : island.getMembers()) { |
|       int i \= toFlatIndex(pos, buf); |
|       RMaterial mat \= WACEngine.getMaterial(level, pos); |
|  |
|       // Source term: 自重 × 力臂加壓（§2.4 距離加壓） |
|       float fillRatio \= (float) WACEngine.getChiselState(level, pos).fillRatio(); |
|       double baseWeight \= mat.getDensity() \* fillRatio \* GRAVITY \* BLOCK\_VOLUME; |
|       int arm \= armMap.getOrDefault(pos, 0); |
|       double momentFactor \= 1.0 \+ MOMENT\_ALPHA \* arm;  // MOMENT\_ALPHA \= 0.20 |
|       buf.source\[i\] \= (float)(baseWeight \* momentFactor); |
|  |
|       // Type: anchor \= 2（Dirichlet BC），solid \= 1 |
|       buf.type\[i\] \= WACEngine.isAnchor(level, pos) ? 2 : 1; |
|  |
|       // 6-direction conductivity（含距離衰減） |
|       for (Direction dir : Direction.values()) { |
|           BlockPos nb \= pos.relative(dir); |
|           RMaterial nbMat \= island.contains(nb) ? WACEngine.getMaterial(level, nb) : null; |
|           int armNb \= armMap.getOrDefault(nb, 0); |
|           buf.conductivity\[i\*6 \+ dir.ordinal()\] \= |
|               PFSFConductivity.sigma(mat, nbMat, dir, arm, armNb); |
|       } |
|   } |
|   // Upload to GPU via staging buffer |
|   buf.uploadToGPU(); |
|  |
|   // 常數 |
|   static final double MOMENT\_ALPHA \= 0.20; |

# **6\. SCA 連鎖崩塌機制**

SCA（Structural Cellular Automata）蔓延是 PFSF 最震撼的視覺效果。當一個方塊斷裂後，它的傳導率瞬間歸零，迫使原本流經它的「電流」在下一個 GPU Tick 猛烈向四周鄰居暴發。

## **6.1 破壞→重算→連鎖 工作流**

|   時序（以 Tick 為單位）： |
| :---- |
|  |
|   Tick N:   Jacobi 迭代收斂，fail\_flags 發現 pos\_X 超載 |
|   Tick N:   PFSFFailureApplicator 觸發 CollapseManager.triggerCollapseAt(pos\_X) |
|             → setBlock(AIR, UPDATE\_KNOWN\_SHAPE | UPDATE\_CLIENTS) |
|             → conductivity\[X\]\[\*\] \= 0（GPU 立即更新，或下幀標記 dirty） |
|   Tick N+1: GPU 重算 phi\[\]：原本流往 pos\_X 的電流無路可去 |
|             → 鄰居 phi 值驟升 → 若超過其 maxPhi → 新的 fail\_flags |
|   Tick N+1: 新斷裂觸發 → 再次更新 conductivity → 繼續蔓延 |
|  |
|   效果：一棟樓倒塌時，破壞從初始點向外蔓延， |
|         速度由材料强度決定（混凝土快，鋼筋慢） |

## **6.2 熔斷保護（防止無限連鎖）**

* **每 Tick 最大斷裂數：**MAX\_FAILURE\_PER\_TICK \= 2000（CollapseManager 現有佇列保留）

* **Island 大小上限：**超過 50,000 方塊的 island 自動 DORMANT，需手動 /br analyze 觸發

* **SCA 蔓延半徑上限：**單次破壞事件最大影響半徑 MAX\_CASCADE\_RADIUS \= 64 格，超出部分延遲到下個 Tick

* **Tick 預算：**PFSFScheduler 每 tick 預算 15 ms，超出剩餘 island 延到下個 tick

# **7\. Vulkan 整合與零拷貝渲染**

phi\[\] Buffer 同時作為物理場和渲染輸入，無需 CPU 中轉，消除記憶體頻寬瓶頸。

## **7.1 Pipeline Memory Barrier**

|   // VulkanComputeContext.java — 確保 Compute 寫入完畢再給 Graphics 讀取 |
| :---- |
|   VkMemoryBarrier2 barrier \= VkMemoryBarrier2.calloc() |
|       .sType(VK13.VK\_STRUCTURE\_TYPE\_MEMORY\_BARRIER\_2) |
|       .srcStageMask(VK13.VK\_PIPELINE\_STAGE\_2\_COMPUTE\_SHADER\_BIT) |
|       .srcAccessMask(VK13.VK\_ACCESS\_2\_SHADER\_WRITE\_BIT) |
|       .dstStageMask(VK13.VK\_PIPELINE\_STAGE\_2\_VERTEX\_SHADER\_BIT) |
|       .dstAccessMask(VK13.VK\_ACCESS\_2\_SHADER\_READ\_BIT); |
|  |
|   // 在 Compute Dispatch 完成後、Graphics Pass 開始前插入此屏障 |
|   vkCmdPipelineBarrier2(cmdBuffer, dependencyInfo); |

## **7.2 Fragment Shader 應力視覺化**

片段著色器直接採樣 phi\[\] 產生熱力圖，無需 CPU 計算：

|   // stress\_heatmap.frag — 片段著色器（綁定 phi\[\] 為 SSBO） |
| :---- |
|   layout(set \= 1, binding \= 0\) readonly buffer StressBuf { float phi\[\]; }; |
|  |
|   void main() { |
|       uint  i     \= voxelIndex;  // 頂點著色器傳入 |
|       float stress \= phi\[i\] / maxPhiForThisVoxel;  // normalized 0\~2 |
|  |
|       // 色彩映射：冰藍（安全）→ 橙紅（臨界）→ 白（超載） |
|       vec3 color; |
|       if (stress \< 0.5)      color \= mix(vec3(0.1,0.3,0.8), vec3(0.8,0.5,0.1), stress\*2.0); |
|       else if (stress \< 1.0) color \= mix(vec3(0.8,0.5,0.1), vec3(1.0,0.34,0.13), (stress-0.5)\*2.0); |
|       else                   color \= mix(vec3(1.0,0.34,0.13), vec3(1.0,1.0,1.0), min((stress-1.0),1.0)); |
|  |
|       // 臨界斷裂橘脈衝動畫（\#FF5722） |
|       float pulse \= stress \> 0.85 ? 0.3 \* sin(time \* 8.0) \+ 0.7 : 1.0; |
|       fragColor \= vec4(color \* pulse, 1.0); |
|   } |

|  | 臨界斷裂橘 (\#FF5722) 是玩家看到的「快要塌了」視覺信號。脈衝頻率 8 Hz 在人類視覺頻閃敏感範圍，效果極佳。 |
| :---- | :---- |

# **8\. 完整實作步驟計畫**

| 階段 | 目標 | 具體工作 / 交付物 |
| :---: | ----- | ----- |
| **P0** | **Vulkan 環境搭建** | 新增 VulkanComputeContext.java。透過 LWJGL 初始化 VkInstance \+ VkDevice \+ Compute Queue。驗證指令：/br vulkan\_test 回報 GPU 型號與 maxComputeWorkGroupSize。 |
| **P1** | **GPU Buffer \+ Source 上傳** | 實作 PFSFIslandBuffer.java：分配 phi/source/conductivity VkBuffer（host-visible staging \+ device-local）。完成 PFSFConductivity.java。單元測試：3×3×3 全混凝土方塊 source 值驗算。 |
| **P2** | **Jacobi Shader \+ CPU 驗證** | 完成 jacobi\_smooth.comp（無 Chebyshev，純 Jacobi 先驗證正確性）。在 CPU 上跑相同算法作為 ground truth 比對。驗收條件：5×5×5 懸臂結構，phi 梯度方向正確（高處高、錨點零）。 |
| **P3** | **Chebyshev 加速** | 加入 Chebyshev omega 排程。量測 1000×1×1 線性結構收斂步數：無 Chebyshev vs 有 Chebyshev。目標：收斂步數減少 \>10x。 |
| **P4** | **幾何多重網格** | 實作 mg\_restrict.comp \+ mg\_prolong.comp。整合到 PFSFEngine 的 V-Cycle 迴路。量測 100×100×100 結構的 wall time：目標 \<10 ms/tick。 |
| **P5** | **斷裂偵測 \+ CollapseManager 接入** | 完成 failure\_scan.comp \+ PFSFFailureApplicator.java。接入 CollapseManager（已有 UPDATE\_KNOWN\_SHAPE 修復）。驗收：敲掉 5 格混凝土懸臂的最外格，觀察由外往內逐格斷裂（SCA 蔓延效果）。 |
| **P6** | **零拷貝渲染整合** | 完成 PFSFRenderBridge.java \+ stress\_heatmap.frag。正確插入 Pipeline Memory Barrier。驗收：破壞承重柱時，橘色脈衝從破壞點向上蔓延，視覺上先於物塊掉落。 |
| **P7** | **舊引擎廢棄清理** | 將 SupportPathAnalyzer / LoadPathEngine / ForceEquilibriumSolver 移至 com.blockreality.api.physics.legacy。標記 @Deprecated。保留 CollapseManager \+ PhysicsScheduler（改呼叫 PFSFEngine）。回歸測試：所有現有 JUnit 5 測試通過。 |

# **9\. 效能目標與測試規格**

| 情境 | 目標耗時 | GPU 記憶體 | CPU 耗時 |
| ----- | ----- | ----- | ----- |
| 1,000 方塊結構（單棟小屋） | \< 1 ms | \~1.2 MB | \< 0.5 ms |
| 10,000 方塊（大型建築） | \< 3 ms | \~12 MB | \< 1 ms |
| 100,000 方塊（摩天大樓） | \< 10 ms | \~120 MB | \< 2 ms |
| 1,000,000 方塊（超巨型城堡） | \< 40 ms | \~1.2 GB | \< 5 ms |
| 靜止結構（warm-start 無更新） | \< 0.1 ms | 常駐 | \< 0.1 ms |

## **9.1 驗收測試清單**

1. 【正確性】5 格混凝土懸臂：phi 由外而內遞增，第 6 格（maxSpan+1）斷裂。

2. 【力矩修正】10 格混凝土懸臂 vs 3 格混凝土懸臂：phi\_根部(10格) / phi\_根部(3格) \> 3.0（距離加壓效果可量化）。

3. 【力矩修正】純垂直混凝土柱（arm=0）：更換為同材質懸臂後根部 phi 升高倍率符合 MOMENT\_ALPHA × arm 預期值（±10%）。

4. 【ArchFactor】對稱拱橋（跨距 10 格，兩側各有獨立錨點）：拱頂 ArchFactor ≥ 0.85，phi \< 懸臂等效值的 20%，不觸發斷裂。

5. 【ArchFactor】非對稱拱（一側跨距 4 格，另側 8 格）：ArchFactor ≈ 0.5，phi 介於對稱拱與純懸臂之間，符合預期。

6. 【保守重啟】連鎖崩塌期間（每 tick 至少 1 個方塊破壞）：chebyshevIter 持續被重置，phi 最大值不出現振盪（不超過前一 tick 的 150%）。

7. 【正確性】雙柱平台：荷載按前驅樹 Voronoi 分配，左右柱應力各承一半。

8. 【正確性】天花板懸掛鏈：最頂端 ANCHOR\_PILE，phi 由上往下遞增，鏈尾斷裂。

9. 【SCA】承重柱中段敲斷：上方方塊在 3 tick 內連鎖斷裂（非瞬間全部）。

10. 【效能】10,000 方塊結構破壞一個方塊：3 tick 內視覺完全穩定（不再有新斷裂）。

11. 【渲染】破壞後橘色應力波在 1 tick（50 ms 顯示延遲）內蔓延可見。

12. 【回歸】所有舊版 JUnit 5 物理測試通過（透過 StructureVerdict.toLegacyAnalysisResult() 橋接）。

# **10\. 參考文獻與關鍵資源**

## **論文**

* **Wang, H. (2015).** "A Chebyshev Semi-Iterative Approach for Accelerating Projective and Position-based Dynamics." SIGGRAPH Asia 2015 (ACM TOG 34:6). [PDF](https://wanghmin.github.io/publication/wang-2015-csi/Wang-2015-CSI.pdf)

* **McAdams et al. (2010).** "A parallel multigrid Poisson solver for fluids simulation on large grids." SCA / 流體模擬多重網格基礎。[PDF](https://math.ucdavis.edu/~jteran/papers/MST10.pdf)

* **NVIDIA Developer Blog.** "High-Performance Geometric Multi-Grid with GPU Acceleration." [Link](https://developer.nvidia.com/blog/high-performance-geometric-multi-grid-gpu-acceleration/)

* **Forsyth, T.** "Cellular Automata for Physical Modelling." [Link](https://tomforsyth1000.github.io/papers/cellular_automata_for_physical_modelling.html)

## **GitHub 實作參考**

* [ooreilly/cuda-multigrid](https://github.com/ooreilly/cuda-multigrid) — GPU multigrid Poisson solver (CUDA)，3D 版本類推。

* [voxcraft/voxcraft-sim](https://github.com/voxcraft/voxcraft-sim) — GPU 加速體素物理引擎，架構參考。

* [xCollateral/VulkanMod](https://github.com/xCollateral/VulkanMod) — Minecraft Vulkan 渲染 Mod，Forge 整合參考。

* [Erkaman/vulkan\_minimal\_compute](https://github.com/Erkaman/vulkan_minimal_compute) — 最小 Vulkan Compute 範例，Dispatch / Buffer 設置參考。

## **Vulkan 文件**

* [Vulkan Tutorial \- Compute Shader](https://vulkan-tutorial.com/Compute_Shader)

* [NVIDIA GPU Gems Chapter 38 \- Fast Fluid Dynamics on GPU](https://developer.nvidia.cn/gpugems/gpugems/part-vi-beyond-triangles/chapter-38-fast-fluid-dynamics-simulation-gpu)

*— Block Reality PFSF Implementation Manual v1.2  |  2026 —*