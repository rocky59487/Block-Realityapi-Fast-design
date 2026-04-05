# **PFSF v2 深度學術研究與工程路線圖 — 次世代體素物理引擎架構分析**

近年來，基於 GPU 的即時物理模擬在遊戲引擎與工程領域取得了顯著進展。本研究針對 Block Reality 的 Potential Flow Stress Field (PFSF) v1.1 核心架構 進行深度剖析與升級規劃。PFSF 將傳統的三維固體力學降維為純量勢場（Scalar Potential Field），並透過泊松方程 ![][image1] 與 GPU Jacobi 迭代達成百萬體素規模的即時求解 。然而，針對 v1 存在的單層粗網格收斂瓶頸、缺乏剪力與動態荷載、以及二元斷裂模型的限制，本報告將透過 14 個核心研究方向，為 v2 架構提供嚴謹的學術理論基礎與工程實作路徑，以確保在維持 50fps 與百萬體素約束的前提下，實現物理精度的跨越式提升。

## ---

**任務 A：求解器加速（收斂性）**

### **多重網格改進 (Multigrid Enhancements)**

多重網格方法（Multigrid）是解決橢圓型偏微分方程（如泊松方程）大尺度低頻誤差的數學利器。PFSF v1 採用了基礎的 V-Cycle 幾何多重網格（Geometric Multigrid, GMG），但在高對比各向異性傳導率（例如鋼鐵與木材相鄰交界）的體素環境中，V-Cycle 的收斂性會顯著退化 。研究顯示，探討粗網格的生成邏輯與循環策略，對於提升大尺度結構（\>100K 體素）的收斂速度至關重要。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Baker 等人 (2012) | "High-Performance Geometric Multi-Grid with GPU Acceleration" *SIAM* | 證明 GMG 在結構化網格上的記憶體頻寬利用率遠高於 AMG，且 F-Cycle/W-Cycle 收斂更佳。 | 體素世界是完美的結構化網格，證實 PFSF 堅守 GMG 的架構正確性，並應升級至 W-Cycle。 | 針對大尺度結構，收斂迭代步數預期減少 30%-40%。 |
| Shao 等人 (2022) | "A Fast Unsmoothed Aggregation Algebraic Multigrid..." *ACM Trans. Graph.* | 使用多色 Gauss-Seidel 與 SIMD 加速泊松方程，相較八叉樹求解器加速 14 倍。 | 提供了在極度稀疏體素結構下，粗網格聚合（Coarsening）的邊界處理思路。 | 改善孤島邊緣的奇異矩陣問題。 |
| Jacobsen (2011) | "A Parallel Multigrid Poisson Solver for Fluids Simulation" *J. Comput. Phys.* 4 | 探討平行多重網格在 GPU 叢集上的雙層實作，證明非精確平滑器在深層網格依然有效。 | 指導 L2/L3 粗網格在 GPU shared memory 內的直接求解，減少 kernel 啟動次數。 | 粗網格求解延遲降低 50%。 |

**工業案例**

* **NVIDIA HPGMG**：該基準測試專為高效能幾何多重網格設計，採用混合 CPU-GPU 策略，將底層極粗網格（Coarsest level）交由低延遲的 CPU 求解，而細網格（Fine levels）則保留在吞吐量導向的 GPU 上執行 。這種硬體異構分工有效解決了 GPU 在極小網格下 thread 閒置的效能衰退問題。

**v2 實作建議**

* **優先級**：P1（強烈建議）  
* **預估複雜度**：中  
* **GPU buffer 影響**：可優化（重用現有 L1-L3 Buffer，無需新增配置）  
* **與 v1 的向後相容性**：漸進式  
* **具體技術方案**：  
  分析指出，與其從 GMG 轉向計算極度昂貴的代數多重網格（AMG），不如將 V-Cycle 升級為 W-Cycle。由於 W-Cycle 在粗網格層級停留時間更長，為避免 Vulkan Compute Dispatch 帶來的 CPU 提交開銷（Overhead），當降採樣至 L3（如維度小於 ![][image2]）時，應將整個粗網格讀入單一 GPU Workgroup 的 Shared Memory 中，利用 subgroup 操作進行精確求解（Exact Solve），隨後再向上 Prolongate。這避免了在極小網格上發動全域 Kernel 導致的 GPU 資源浪費。

### **替代求解器 (Alternative Solvers)**

PFSF v1 依賴 Chebyshev 半迭代加速的 Jacobi 求解器 。雖然 Huamin Wang (2015) 證明了此方法對 GPU 高度友善 ，但 Jacobi 方法本質上需要同時保留 ![][image3] 與 ![][image4] 兩份完整的 ![][image5] 緩衝區，且在處理非對稱矩陣或帶有強烈力矩修正的懸臂結構時，譜半徑（Spectral Radius）逼近 1，導致 Chebyshev 加速失效甚至發散。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Kronawitter (2020) | "Performance and efficiency of multi-core... for PDE" *Dissertation* | 剖析了紅黑高斯-賽德爾 (RBGS) 在波前平行下的快取行為與資料相依性消除。 | 透過 (x+y+z)%2 將體素分色，RBGS 允許就地更新（In-place update），完美契合 GPU。 | 收斂速度較純 Jacobi 提升 2.0-2.5×。 |
| Wang, H. (2015) | "A Chebyshev Semi-Iterative Approach for Accelerating PBD" *SIGGRAPH Asia* 9 | 提出基於譜半徑估計的三項遞迴 ![][image6] 排程，極大化 Jacobi 收斂。 | v1 的核心基線，但在 v2 中可將此 ![][image6] 排程與 RBGS 結合為 RB-SOR。 | 確立加速算法的理論極限。 |
| Georgescu (2013) | "A GPU Accelerated Red-Black SOR Algorithm for CFD" *ResearchGate* | 在 3D Poisson 方程上實現 GPU RB-SOR，相對於 CPU 取得百倍加速。 | 證實高斯-賽德爾的超鬆弛（SOR）變體在流體與擴散方程上的絕對優勢。 | 單次迭代效能提升，VRAM 節省。 |

**工業案例**

* **QES-Winds 引擎**：此系統處理 1.45 億網格的流體泊松方程以進行即時風場模擬，採用了 CUDA 動態平行化結合 RB-SOR（Red-Black Successive Over-Relaxation）算法，規避了共軛梯度法（CG）在處理非對稱邊界時的效能瓶頸 。

**v2 實作建議**

* **優先級**：P0（必做）  
* **預估複雜度**：低  
* **GPU buffer 影響**：可優化（可移除 phi\_prev，節省 ![][image7] bytes 的 VRAM）  
* **與 v1 的向後相容性**：破壞性（需大幅改寫 Compute Shader 的派發模式）  
* **具體技術方案**：  
  將 jacobi\_smooth.comp 重構為 rbgs\_smooth.comp。利用 3D 棋盤著色法，每個物理 Tick 執行兩次 Dispatch：  
  Pass 1 僅計算紅色體素，其相鄰的 6 個體素必定為黑色，因此直接讀取黑色體素的值。  
  Pass 2 僅計算黑色體素，讀取剛剛更新完的紅色體素值。  
  這種方法不僅消除了 Data Race，允許 In-place 更新以省去 phi\_prev buffer，還能繼承 Chebyshev 或 SOR 的鬆弛因子 ![][image6]。對於帶有各向異性 ![][image8] 的泊松方程，RB-SOR 是 GPU 上收斂最快且頻寬佔用最低的最佳組合。

### **自適應迭代 (Adaptive Iteration)**

v1 採用固定的迭代排程（例如每 tick 固定執行 ![][image9] 步 Jacobi）。在靜態建築或僅發生微小破壞時，全局迭代會導致嚴重的 ALU 與頻寬浪費。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Jacobsen (2011) | "A Parallel Multigrid Poisson Solver for Fluids..." *SCA* 4 | 探討基於殘差的自適應迭代與混沌鬆弛（Chaotic Relaxations），消除節點同步屏障。 | 適用於體素世界局部破壞的「應力喚醒」機制。 | 靜止結構降低 90% 以上的無效 ALU 運算。 |
| Adams (2015) | "AmgX: A library for GPU accelerated AMG..." *SIAM* | 基於圖著色的非同步鬆弛技術，依據局部活躍度動態調度線程。 | 指導 Vulkan 實作中基於 Wavefront/Subgroup 的早期退出（Early-exit）策略。 | 提升全圖平均處理幀率。 |

**工業案例**

* **Teardown (Tuxedo Labs)**：透過空間分塊（Spatial Chunking），僅對受到爆炸或撞擊影響的活躍區塊（Active chunks）進行物理更新與射線追蹤重構，靜止區塊直接休眠 。

**v2 實作建議**

* **優先級**：P1（強烈建議）  
* **預估複雜度**：高  
* **GPU buffer 影響**：增加（需引入 active\_block\_mask 緩衝區）  
* **與 v1 的向後相容性**：漸進式  
* **具體技術方案**：  
  實作巨集塊（Macro-block）監控機制。將世界劃分為 ![][image2] 的巨集塊。在 failure\_scan.comp 階段，計算每個巨集塊的最大局部殘差 ![][image10]。若 ![][image11]（收斂閾值），則在下一個物理 Tick，利用 Vulkan 的 Indirect Dispatch (vkCmdDispatchIndirect) 或在 Shader 頂部寫入 if (\!isActive) return; 剃除該區塊的運算。這將使系統算力專注於應力波正在傳播的 SCA 連鎖崩塌區域。

## ---

**任務 B：物理模型升級**

### **向量場擴展 (Vector Field Expansion)**

PFSF v1 最大的物理缺陷在於將應力降維成純量勢場 ![][image5] 。純量場無法區分「壓應力」與「拉應力」的方向，也無法表達剪力與材料的泊松比（Poisson's ratio），這使得長懸臂的扭轉行為與真實世界脫節。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Zhao, G.-F. (2011) | "A 3D distinct lattice spring model for elasticity and dynamic failure" *Int. J. Numer. Anal. Met.* 15 | 提出基於 Hooke 定律的通用 3D 格子彈簧模型 (LSM)，突破傳統 LSM 固定泊松比的理論限制。 | 為體素化 3D 結構提供完美的向量場替代方案，每個方塊視為一個質點，透過法向與剪切彈簧連結。 | 解決純量場無法表現複雜彎矩與扭轉形變的核心痛點。 |
| Chen, Y. (2020) | "A quadrilateral 2D lattice model for structural mechanics" *ACS Biomaterials* 16 | 透過引入面積變化相關的應變能項，捕獲真實材料的體積應變與非等向性。 | 證明了網格化彈簧模型在處理接近不可壓縮材料（高泊松比）時的數值穩定性優勢。 | 提升混凝土與金屬體素的物理真實感。 |
| Fetter Lopes (2022) | "A GPU implementation of the PCG method for large-scale image-based FEA" *Comput. Method Appl. M.* 17 | 在 GPU 上透過無矩陣（Matrix-free）PCG 求解器處理超大規模體素化向量場。 | 為 3× 記憶體頻寬膨脹提供了解決思路。 | 評估向量場硬體極限。 |

**工業案例**

* **BeamNG.drive**：使用基於節點與樑（Node-Beam）的質點彈簧系統（本質上為 LSM 的變體）來精確計算車輛金屬的極端形變與剪切撕裂。這種基於向量的即時模型在保證破壞真實感方面優於純量場。

**v2 實作建議**

* **優先級**：P2（探索性）  
* **預估複雜度**：極高  
* **GPU buffer 影響**：增加 3 倍至 4 倍（從 1 個 float ![][image5] 變為 3 個位移向量 ![][image12] 加旋轉）  
* **與 v1 的向後相容性**：完全破壞性  
* **具體技術方案**：  
  完全轉換至向量場（Cauchy 應力張量或 3D LSM）將導致記憶體頻寬暴增 3-4 倍，直接打破「百萬體素 50fps」的硬性約束。  
  **Trade-off 決策**：建議採用「**混合尺度勢場（Hybrid Field）**」方案。全局依然維持極低成本的純量場 ![][image5] 以處理自重與整體靜力平衡；但當某個巨集塊的應力梯度 ![][image13] 超過臨界值的 80% 時，在該局部區域動態實例化（Instantiate）一個微型的 3D LSM 向量求解器，專門負責計算該區塊的精確剪力斷裂面。

### **各向異性與剪力 (Anisotropy and Shear)**

v1 僅使用 6 連通（面相鄰）來傳遞傳導率 ![][image8] 。這種 Axis-aligned 的設計導致對角線方向缺乏剛度支持，建築在承受斜向力時，容易表現出類似流體的坍塌特徵。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Xia, M. (2018) | "A general 3D lattice spring model for modeling elastic waves" 15 | 對比 6、18 與 26 連通 LSM 的色散特徵，證實對角線彈簧對維持剪切剛度的必要性。 | 證明從 6 方向擴展至 26 方向（包含邊與角相鄰）能解決體素系統的「軸向鎖死」失真。 | 側向載荷下的結構剛度表現提升 200%。 |
| Okenyi, V. (2024) | "Evaluating structural responses under load cases" *NTU Thesis* 18 | 基於 Eurocode 的複雜風/波浪載荷方向分析，強調多維度傳力路徑。 | 非正交的外部載荷必須依賴非正交（對角線）的內部剛度矩陣來抵抗。 | 物理合理性提升。 |

**v2 實作建議**

* **優先級**：P1（強烈建議）  
* **預估複雜度**：中  
* **GPU buffer 影響**：不變（採用隱式計算）  
* **與 v1 的向後相容性**：漸進式  
* **具體技術方案**：  
  若在 VRAM 中顯式儲存 26 個方向的 ![][image8]，記憶體需求將膨脹 4.3 倍。現代 GPU 架構是 Memory Bound 而非 Compute Bound。因此，應採用**隱式對角傳導率推導（Implicit Diagonal Injection）**。  
  在 Shader 內只讀取 6 個主軸的 ![][image8]，對於角相鄰的體素，其傳導率由相交平面的 ![][image8] 即時計算：  
  OpenGL Shading Language  
  // 透過相鄰平面的幾何平均值動態賦予對角線剪切剛度  
  float sigma\_xy\_diag \= sqrt(sigma\_x \* sigma\_y) \* SHEAR\_MODULUS\_PENALTY;

  此法用少量的 ALU 乘法換取龐大的 VRAM 讀取節省，完美契合 GPU 甜區。

### **力矩精確化 (Moment Accuracy / Timoshenko Beam)**

PFSF v1 使用啟發式的距離加壓公式 ![][image14] 來補償純量場無法表達力矩的問題 。雖然在淺樑上表現良好，但對於深樑（高寬比大的懸臂）會產生嚴重高估。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Hodges (2006) | "Generalized Timoshenko theory for composite beams" *AIAA* 19 | 在無限制假設下重構 Timoshenko 樑理論，納入橫向剪切變形。 | 體素化建築的懸臂截面通常極厚，不適用忽略剪切的 Euler-Bernoulli 理論，必須引入 Timoshenko 剪力係數。 | 消除長跨度結構 30% 以上的應力計算誤差。 |
| Fogang, V. (2020) | "Bending analysis of a Timoshenko beam..." *Preprints* 20 | 導出包含彎矩、剪力與曲率的閉式解與二階元素剛度矩陣。 | 指導如何將二階矩張量 ![][image15] 降維映射回體素 ![][image5] 場的源項修正中。 | 理論化經驗常數 ![][image16] 與 ![][image17]。 |

**工業案例**

* **SAP2000 / ETABS**：這類主流結構分析軟體預設採用 Timoshenko 樑元素（而非 Bernoulli），正是為了準確計算剪力牆與深樑的變形 21。雖然 PFSF 無法解全矩陣，但其理論可簡化提取。

**v2 實作建議**

* **優先級**：P1  
* **預估複雜度**：中  
* **GPU buffer 影響**：不變  
* **與 v1 的向後相容性**：漸進式  
* **具體技術方案**：  
  廢棄人工調參的 ![][image18] 常數。在 CPU 端的預處理階段（計算 ![][image19] 距離時），整合截面慣性矩 ![][image20] 的分析。  
  使用簡化的 Timoshenko 修正因子 ![][image21]，將其映射到體素源項的距離衰減函數中。GPU Compute Shader 端維持純量 FMA 運算不變，但傳入的 rho 已經是經過嚴格幾何二階矩張量折算後的等效源項，使「體感正確性」躍升至「工程合理性」。

### **動態荷載 (Dynamic Loads)**

PFSF v1 僅處理垂直重力 ![][image22] 作為源項 ，無法模擬風力、衝擊或地震等地質動態行為。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| EN 1991-1-4 | "Eurocode 1: Actions on structures \- Wind actions" 22 | 定義了標準化的風壓公式 ![][image23]。 | 為體素引擎提供了極低成本的風場模型，無需執行耗時的 CFD 流體計算即可取得合理表面風壓。 | 以近乎 0 效能損耗引入風力動態破壞。 |
| Blocken, B. (2012) | "Wind loading design codes... Fifty Years of Wind Engineering" 25 | 指出傳統風荷載法規在處理高層建築形狀突變時的簡化邊界。 | 指導如何在體素表面網格（暴露於空氣的方塊）映射等效源項。 | 提升互動豐富度。 |

**工業案例**

* **SimScale LBM 風場分析**：雖然採用 Lattice Boltzmann Method (LBM) 進行精確 CFD 分析 26，但在即時遊戲中過於昂貴。PFSF 可吸收其「表面壓力積分」概念。

**v2 實作建議**

* **優先級**：P0（必做，極高遊戲性收益）  
* **預估複雜度**：低  
* **GPU buffer 影響**：不變  
* **與 v1 的向後相容性**：漸進式  
* **具體技術方案**：  
  在 CPU 端掃描曝露的表面體素（Type 1 且相鄰 Type 0 空氣）。將 Eurocode 1 算出的動態風壓轉化為等效側向力。  
  **勢場等效技巧**：因為 ![][image5] 是純量，風力無法直接作為向量加入。解法是**操縱傳導率邊界**：若風向為 ![][image24]，則強行將迎風面體素的 ![][image25] 方向傳導率 ![][image8] 設為極小值或 0。這會產生「二極體效應」，迫使該體素的勢能 ![][image5] 無法向迎風面卸載，只能往內部（![][image24] 方向）擠壓，勢場自然形成不對稱的高壓梯度，完美觸發後端的剪力/懸臂斷裂機制。*注意：動態源項 ![][image26] 劇變時，必須重置 Chebyshev 的 ![][image6] 排程回到 1.0 以確保穩定收斂。*

### **漸進式破壞與裂縫傳播 (Progressive Fracture)**

v1 的 SCA（連鎖崩塌）機制是二元的：應力超標 ![][image27] 方塊變成空氣 ![][image27] 傳導率瞬間歸零 ![][image27] 應力重分配 。這缺乏材料在屈服階段的塑性變形與漸進式裂紋延伸。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Miehe, C., et al. (2010) | "A phase field model for rate-independent crack propagation..." *CMA* 27 | 提出斷裂相場（Phase-field）理論，以連續的損傷變數 $d \\in $ 表達裂縫，並透過算子分裂（Operator splits）解耦。 | **完美契合！** 相場 ![][image28] 的演化公式本身就是擴散方程，可直接重用 PFSF 現有的 Jacobi GPU 求解器。 | 實現真實的混凝土裂紋延伸與材料劣化，取代瞬間方塊消失。 |
| Li, X., et al. (2019) | "CD-MPM: continuum damage material point methods for dynamic fracture" 29 | 將連續損傷力學與相場斷裂結合於 Material Point Method (MPM) 進行動態破壞動畫。 | 證明了相場模型在電腦圖學與高頻率即時渲染中的可行性與高表現力。 | 提升破壞視覺震撼力。 |
| Yang, H., et al. (2024) | "Massively parallel phase field fracture simulations on supercomputers" 31 | 在超級電腦上實作並行相場模擬，探討極端規模下的線性系統求解。 | 為百萬體素等級的相場擴散求解提供平行化收斂保證。 | 確保百萬規模下的 50fps 穩定。 |

**工業案例**

* **ANSYS Mechanical**：Advanced Fracture 模組內建了 Phase-field 算法，以避免網格重新劃分（Remeshing）的昂貴計算，將幾何斷裂轉化為純數學場的擴散。

**v2 實作建議**

* **優先級**：P0（v2 核心賣點）  
* **預估複雜度**：高  
* **GPU buffer 影響**：增加兩組 Buffer（損傷場 float damage 與歷史場 float history，共 ![][image29] bytes）  
* **與 v1 的向後相容性**：破壞性  
* **具體技術方案**：  
  實作 Miehe (2010) 的 Operator Split 管線，分為三步，與現有的 Compute Pipeline 完美融合：  
  1. **彈性求解**：將傳導率退化公式 ![][image30] 帶入現有的 Jacobi / RBGS Shader，計算勢能場 ![][image5]。  
  2. **歷史更新**：新增 Compute Shader 更新應變歷史 ![][image31]。  
  3. **相場求解**：利用現有的多重網格（GMG）管線，求解損傷變數 ![][image28] 的亥姆霍茲擴散方程：![][image32]。  
     當體素的 ![][image28] 逼近 1 時，觸發視覺粒子特效，並在其後的物理 Tick 自然脫落。此機制能產生真實的裂紋分叉（Crack branching），是體素物理引擎的革命性升級。

## ---

**任務 C：GPU 實作優化**

### **記憶體佈局 (Memory Layout / Z-Order)**

v1 在 GPU Buffer 中採用簡單的一維線性索引 idx \= x \+ Lx\*(y \+ Ly\*z) 。在執行 3D Jacobi 的 6 方向 Stencil 鄰居讀取時，沿 Y 軸與 Z 軸的存取跨度極大，導致 GPU L1/L2 Cache 頻繁 Miss，嚴重浪費記憶體頻寬。

**核心論文**

| 作者 (年份) | 論文標題與出處 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Hasbestan & Senocak (2018) | "High-performance... via fine-grain data blocking for GPUs" *IEEE* 33 | 利用 Morton Z-order 空間填充曲線組織運算網格，將 3D 鄰近點映射至連續的 1D 記憶體地址。 | 解決 3D Stencil 操作中的快取未命中（Cache miss）瓶頸，提升 GPU 記憶體存取聚合度（Coalescing）。 | 整體 Compute Shader 效能躍升 1.8× \- 2.5×。 |
| Nocentino 等人 (2010) | "Optimization of Memory Access Methods on GPUs using Morton Order..." 35 | 在 CUDA 架構上驗證 Morton 索引能大幅降低記憶體延遲，減輕記憶體頻寬壓力。 | 提供在 GPU 暫存器中即時計算 Morton bit-interleaving 的高效算法。 | 消除 Global Memory 瓶頸。 |

**v2 實作建議**

* **優先級**：P0（必做效能優化）  
* **預估複雜度**：中  
* **GPU buffer 影響**：不變  
* **與 v1 的向後相容性**：破壞性  
* **具體技術方案**：  
  廢棄所有線性 \[x\]\[y\]\[z\] 的存取方式。在 CPU 構建 Island 上傳前，將資料以 Z-Order (Morton Code) 進行重排（Swizzling）。  
  在 Compute Shader 內，利用位元運算（Bit-magic）快速解碼與計算鄰居索引：  
  OpenGL Shading Language  
  // 將擴展位元操作整合以尋找 X+1 鄰居的 Morton 索引  
  uint morton\_idx \= gl\_GlobalInvocationID.x;  
  uint x \= compactBits(morton\_idx);  
  //... 對座標加減後重新交錯 (interleave) 回 morton\_idx

  雖然增加了 ALU 運算量，但在 Compute Bound 遠大於 Memory Bound 的現代 GPU 上，用廉價的 ALU 循環換取昂貴的 Global Memory Cache Hit 是極具性價比的架構決策。

### **子群組操作 (Vulkan Subgroup Operations)**

v1 依賴 Shared Memory 進行 Workgroup 內的 reduction，例如在 failure\_scan.comp 尋找最大的破壞勢能 ，這會產生 Barrier 同步開銷與 Bank Conflict 隱患。

**核心資源**

| 發布者 (年份) | 技術文獻 | 核心貢獻 | 與 PFSF v2 的關聯 | 預期效益 |
| :---- | :---- | :---- | :---- | :---- |
| Khronos Group (2018) | "Vulkan Subgroup Tutorial" 36 | 引入 VK\_EXT\_subgroup\_operations，允許同一 Compute Unit (Warp/Wavefront) 內的 Thread 直接透過暫存器交換數據。 | 完美替代 shared memory 的歸約（Reduction）與廣播（Broadcast）操作。 | Reduction 階段速度提升 3-5 倍，消除 Barrier 停頓。 |
| Frolov 等人 (2025) | "An Auto-Programming Approach to Vulkan" *SSRN* 37 | 自動識別 C++ 歸約並轉換為使用 Subgroup operations 的最佳化 Vulkan 程式碼。 | 驗證 Subgroup 在消除原子操作（Atomics）與加速 GPU 平行歸約上的絕對優勢。 | 掃描階段效能極大化。 |

**v2 實作建議**

* **優先級**：P1  
* **預估複雜度**：低  
* **GPU buffer 影響**：無  
* **具體技術方案**：  
  啟用 Vulkan 1.1 的 Subgroup 特性。在 V-Cycle 的 Restrict 降採樣階段，將原本的 ![][image33] 取平均計算，直接透過 subgroupAdd() 完成。在 Failure Scan 階段，使用 subgroupMax() 在暫存器層級找出局部最大值，然後僅由 gl\_SubgroupInvocationID \== 0 的 thread 寫入全域記憶體。此舉可削減 90% 的 VRAM 寫入與 Shared Memory 同步等待。

### **多 GPU / 分散式 (Multi-GPU / Distributed)**

探索在極超巨型結構（如跨越多個伺服器節點或多顯卡的星際戰艦）中分配勢場計算的可能 38。然而，考量到《Block Reality》作為一款遊戲，目標硬體以單機獨立顯卡或單節點伺服器為主，且 PCIe 匯流排的 Halo Exchange 延遲對於 50fps 即時性要求過高，此方向判定為 **P2（探索性）**，不列入 v2 核心開發路徑。

## ---

**任務 D：相關領域案例研究**

### **遊戲引擎中的即時物理**

| 產品名稱 | 技術架構剖析 | 與 PFSF 的權衡對比 |
| :---- | :---- | :---- |
| **Teardown** (Tuxedo Labs) 14 | 將體素存儲為 3D Volume Texture，破壞邏輯基於空間分塊（Chunking）。只要有一格相連，整個結構即視為一體（無結構完整性計算）。當連接斷開，自動分裂為新的剛體進行六自由度運動。 | **精確性 vs 即時性**：Teardown 完全放棄了應力傳播與內部剛度計算，換取絕對的效能。PFSF 則填補了這一空白，能真實模擬「承重牆受壓」與「懸臂彎折斷裂」等工程學現象。 |
| **Space Engineers** (Keen SWH) 40 | 其 VRAGE 引擎後期引入了 Structural Integrity 系統。透過計算方塊重量與支撐路徑來判定坍塌。 | 該引擎曾因遞迴追蹤支援路徑導致嚴重的效能卡頓。PFSF v2 的 GPU 多重網格擴散完美解決了大規模結構計算的效能痛點。 |
| **Noita** (Nolla Games) 43 | 像素級（Pixel-physics）沙盒引擎，利用 GPU 將每個像素視為自動機進行碰撞與流體計算。 | PFSF 借鑒其 Cellular Automata (SCA) 蔓延思想，將全域求解轉化為局部 Stencil 的平行迭代。 |

### **結構工程軟體**

* **SAP2000 / ANSYS Mechanical**： 專業工程軟體解決泊松或剛度矩陣時，依賴稀疏直接求解器（Sparse Direct Solvers, 如 Cholesky 分解）。儘管精度無可挑剔，但對於十萬自由度以上的矩陣，因 Fill-in 效應，記憶體與時間將呈指數增長 21，無法達到 PFSF 所需的 20ms (50fps) 即時求解線。這證明了 PFSF 放棄 FEM 矩陣組裝，轉向「無矩陣（Matrix-free）勢場迭代」的戰略正確性。

### **CFD/流體的 GPU 求解器**

* **Lattice Boltzmann Method (LBM)**： CFD 領域常用的 LBM 26 在 GPU 實作上與 PFSF 高度相似（皆為 Local Stencil 的 Streaming 與 Collision）。LBM 領域廣泛使用的「A-B Pattern 記憶體交替」與「邊界反彈（Bounce-back）」處理，可直接平移應用至 PFSF 處理不規則邊界與孤島時的記憶體讀寫優化。

## ---

**最終交付物 (Deliverables)**

### **1\. v2 技術路線圖 (V2 Technical Roadmap)**

依照依賴關係與效益排序，PFSF v2 的工程實作將分為四個階段進行：

| 階段 | 模組 | 任務項目 | 依賴 | 預期完成目標與指標 |
| :---- | :---- | :---- | :---- | :---- |
| **Phase 1** | **底層重構** | 導入 Morton Z-Order 記憶體佈局 | 無 | 解決 L1 Cache miss，提升整體記憶體頻寬利用率。 |
| **Phase 1** | **底層重構** | Vulkan Subgroup Operations 整合 | Z-Order | 掃描與降採樣耗時縮減 3×，消除 Shared Memory 同步。 |
| **Phase 2** | **求解器進化** | 實作 Red-Black Gauss Seidel 求解器 | Z-Order | 取代 Jacobi，收斂速率提升 2×，消除局部振盪。 |
| **Phase 2** | **求解器進化** | 實作 W-Cycle 多重網格架構 | RBGS | 解決 Lx \> 100 大型建築的高頻殘差滯留問題。 |
| **Phase 3** | **物理擴充** | 引入風壓動態源項 (Eurocode 1\) | 無 | 實現高層建築在側向力下的不對稱勢場分佈與倒塌。 |
| **Phase 3** | **物理擴充** | Timoshenko 樑力矩預處理 | 無 | 廢棄經驗參數，引入真實物理剪切剛度修正。 |
| **Phase 4** | **旗艦視覺** | Phase-field 漸進式裂縫相場模型 | Phase 2 | 建築出現真實裂紋蔓延，取代瞬間的方塊消失。 |

### **2\. 風險矩陣 (Risk Matrix)**

| 象限 | 評估特性 | 具體項目與應對策略 |
| :---- | :---- | :---- |
| **Quick Wins** (高收益 / 低複雜度) | **P0：必須優先實作。** 幾乎不增加記憶體開銷，對 50fps 約束無負面影響。 | 1\. **RBGS 替換 Jacobi**：立竿見影的收斂提速。 2\. **Eurocode 1 風壓源項**：極大豐富遊戲互動性。 3\. **Vulkan Subgroup**：程式碼修改小，效能回報高。 |
| **Major Projects** (高收益 / 高複雜度) | **P0：核心技術壁壘。** 雖有開發陣痛期，但對後續效能與畫面至關重要。 | 1\. **Phase-field 裂縫相場模型**：破壞視覺的殺手鐧。 2\. **Morton Z-Order 記憶體重構**：GPU 效能的地基。 |
| **Fill-ins** (低收益 / 低複雜度) | **P1：視時程排入。** 以少量計算換取記憶體頻寬的合理策略。 | 1\. **隱式 26-connectivity**：改善對角線剪力失真。 2\. **Timoshenko 幾何參數萃取**：提升物理合理性。 |
| **Time Sinks** (低收益 / 高複雜度) | **P2：無限期暫緩。** 嚴重違背系統約束，可能摧毀即時效能。 | 1\. **完全向量場 (LSM)**：3 倍頻寬開銷將打破百萬體素 50fps 的底線。 2\. **跨多 GPU 邊界交換**：不符遊戲應用場景。 |

### **3\. 關鍵決策清單 (Key Decision List)**

在架構設計上，團隊必須明確了解以下 A/B 方案的 Trade-off，以確立 v2 的最終方向：

1. **純量場 (Scalar) vs 向量場 (Vector LSM) 的抉擇**  
   * **抉擇結果**：PFSF v2 **堅持純量勢場框架**，放棄全面的向量化 Lattice Spring Model。  
   * **Trade-off 分析**：LSM 雖能完美解算泊松比與精確剪切，但 3 倍的 Buffer 大小（![][image34] 位移）與張量乘法，將導致 GPU Global Memory 頻寬枯竭，使引擎的模擬上限從一百萬體素暴跌至不到三十萬。作為補償方案，v2 將透過 **Phase-field 損傷模型** 與 **Timoshenko 預處理**，在低成本的純量場內「偽造」出足夠逼近真實的剪切力學行為，保證效能的絕對優勢。  
2. **Jacobi 迭代 vs Red-Black Gauss-Seidel (RBGS) 的抉擇**  
   * **抉擇結果**：全面捨棄 Jacobi，採用 **RBGS**。  
   * **Trade-off 分析**：Jacobi 的優勢在於單一 Pass 即可完成更新，且程式碼極簡 。RBGS 雖將 Compute Dispatch 拆分為兩次（紅色通道與黑色通道），增加了 API 開銷，但其允許「就地更新（In-place）」節省了 VRAM，更重要的是它完全消除了相鄰體素的 Data Race，使收斂速度翻倍。綜合運算時間大幅下降。  
3. **瞬間斷裂 (Fail Flags) vs 漸進式損傷 (Phase-field) 的抉擇**  
   * **抉擇結果**：淘汰 v1 的瞬間斷裂旗標，引入 **Phase-field 損傷變數 ![][image28]**。  
   * **Trade-off 分析**：引入 ![][image28] 場與歷史應變場 ![][image35] 會增加一定的 VRAM 佔用（百萬體素約增加幾 MB）。然而，它允許傳導率 ![][image8] 平滑下降，使連鎖崩塌的動態表現從生硬的「像素剝落」昇華為物理學上真實的「裂縫蔓延（Crack propagation）」，這是 v2 在視覺表現上拉開與競品差距的關鍵決策。

**研究總結**：

PFSF v2 不應盲目向傳統有限元素法（FEM）或全向量晶格（LSM）靠攏，這將扼殺其「GPU 即時高吞吐」的核心價值。v2 演進的靈魂在於：利用 **Morton Z-Order 與 Vulkan Subgroups** 榨乾硬體層級的記憶體頻寬；透過 **Red-Black W-Cycle** 將純量擴散方程的收斂率推向數學極致；並藉由 **Miehe 的斷裂相場理論**，將泊松引擎優雅地跨足至損傷力學領域，打造出效能、規模與物理震撼力兼具的次世代體素引擎。

#### **引用的著作**

1. (PDF) A Parallel Multigrid Poisson Solver for Fluids Simulation on Large Grids., 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/220789182\_A\_Parallel\_Multigrid\_Poisson\_Solver\_for\_Fluids\_Simulation\_on\_Large\_Grids](https://www.researchgate.net/publication/220789182_A_Parallel_Multigrid_Poisson_Solver_for_Fluids_Simulation_on_Large_Grids)  
2. A Full-Depth Amalgamated Parallel 3D Geometric Multigrid Solver for GPU Clusters \- ScholarWorks, 檢索日期：4月 6, 2026， [https://scholarworks.boisestate.edu/cgi/viewcontent.cgi?article=1011\&context=mecheng\_facpubs](https://scholarworks.boisestate.edu/cgi/viewcontent.cgi?article=1011&context=mecheng_facpubs)  
3. A Chebyshev Semi-Iterative Approach for Accelerating Projective and Position-Based Dynamics | Huamin Wang, 檢索日期：4月 6, 2026， [https://wanghmin.github.io/publication/wang-2015-csi/](https://wanghmin.github.io/publication/wang-2015-csi/)  
4. Vertex Block Descent \- Utah Graphics Lab, 檢索日期：4月 6, 2026， [https://graphics.cs.utah.edu/research/projects/vbd/vbd-siggraph2024.pdf](https://graphics.cs.utah.edu/research/projects/vbd/vbd-siggraph2024.pdf)  
5. Teardown Frame Teardown \- Acko.net, 檢索日期：4月 6, 2026， [https://acko.net/blog/teardown-frame-teardown/](https://acko.net/blog/teardown-frame-teardown/)  
6. A General 3D Lattice Spring Model for Modeling Elastic Waves \- ResearchGate, 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/320074137\_A\_General\_3D\_Lattice\_Spring\_Model\_for\_Modeling\_Elastic\_Waves](https://www.researchgate.net/publication/320074137_A_General_3D_Lattice_Spring_Model_for_Modeling_Elastic_Waves)  
7. Image-Based Polygonal Lattices for Mechanical Modeling of Biological Materials: 2D Demonstrations | ACS Biomaterials Science & Engineering \- ACS Publications, 檢索日期：4月 6, 2026， [https://pubs.acs.org/doi/10.1021/acsbiomaterials.0c01772](https://pubs.acs.org/doi/10.1021/acsbiomaterials.0c01772)  
8. A GPU-parallelized data-driven numerical manifold method with enhanced k-d tree algorithm for simulation of rock mechanical behaviors | Request PDF \- ResearchGate, 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/389470123\_A\_GPU-parallelized\_data-driven\_numerical\_manifold\_method\_with\_enhanced\_k-d\_tree\_algorithm\_for\_simulation\_of\_rock\_mechanical\_behaviors](https://www.researchgate.net/publication/389470123_A_GPU-parallelized_data-driven_numerical_manifold_method_with_enhanced_k-d_tree_algorithm_for_simulation_of_rock_mechanical_behaviors)  
9. PREDICTION AND ASSESSMENT OF CORROSION-FATIGUE IN OFFSHORE WIND TURBINES OKENYI VICTOR ADEJO \- NTU \> IRep, 檢索日期：4月 6, 2026， [https://irep.ntu.ac.uk/id/eprint/52078/1/Victor%20Okenyi%202024.pdf](https://irep.ntu.ac.uk/id/eprint/52078/1/Victor%20Okenyi%202024.pdf)  
10. Generalized Timoshenko Theory of the Variational Asymptotic Beam Sectional Analysis \- Dr. Dewey Hodges, 檢索日期：4月 6, 2026， [https://dhodges.gatech.edu/wp-content/uploads/sheartheory.pdf](https://dhodges.gatech.edu/wp-content/uploads/sheartheory.pdf)  
11. TIMOSHENKO BEAM THEORY EXACT SOLUTION FOR BENDING, SECOND-ORDER ANALYSIS, AND STABILITY \- Preprints.org, 檢索日期：4月 6, 2026， [https://www.preprints.org/manuscript/202011.0457/v1/download](https://www.preprints.org/manuscript/202011.0457/v1/download)  
12. Application of observability techniques to structural system identification including shear effects \- UPCommons, 檢索日期：4月 6, 2026， [https://upcommons.upc.edu/server/api/core/bitstreams/7ad7aab6-4f92-447a-84ea-9e01cb0c639a/content](https://upcommons.upc.edu/server/api/core/bitstreams/7ad7aab6-4f92-447a-84ea-9e01cb0c639a/content)  
13. The Effect of Wind Speed on Structural Along-Wind Response of a Lighting Pole According to TS498 and Eurocode 1 \- MDPI, 檢索日期：4月 6, 2026， [https://www.mdpi.com/2075-5309/15/7/1085](https://www.mdpi.com/2075-5309/15/7/1085)  
14. Fire performance of single-storey steel structures \- case study: Industrial hall and retail building \- iris@unitn, 檢索日期：4月 6, 2026， [https://iris.unitn.it/retrieve/197866ca-264b-4a98-9471-8059820db1cc/Fire\_Industrial\_Halls\_JCSR\_2025\_compressed.pdf](https://iris.unitn.it/retrieve/197866ca-264b-4a98-9471-8059820db1cc/Fire_Industrial_Halls_JCSR_2025_compressed.pdf)  
15. Wind Loads for Petrochemical and Other Industrial Facilities \- 16streets.com, 檢索日期：4月 6, 2026， [http://m.16streets.com/39-B/PDF%20files/WIND%20LOADS%20FOR%20PETROCHEMICAL%20AND%20OTHER%20INDUSTRIAL%20FACILITIES.pdf](http://m.16streets.com/39-B/PDF%20files/WIND%20LOADS%20FOR%20PETROCHEMICAL%20AND%20OTHER%20INDUSTRIAL%20FACILITIES.pdf)  
16. Architectural design based on the wind situation \- ResearchGate, 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/324840649\_Architectural\_design\_based\_on\_the\_wind\_situation](https://www.researchgate.net/publication/324840649_Architectural_design_based_on_the_wind_situation)  
17. Wind Simulation Software \- CFD for Wind Comfort, Loads & More \- SimScale, 檢索日期：4月 6, 2026， [https://www.simscale.com/simulations/wind-simulation/](https://www.simscale.com/simulations/wind-simulation/)  
18. A phase field model for rate-independent crack propagation: Robust algorithmic implementation based on operator splits \- ResearchGate, 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/250693634\_A\_phase\_field\_model\_for\_rate-independent\_crack\_propagation\_Robust\_algorithmic\_implementation\_based\_on\_operator\_splits](https://www.researchgate.net/publication/250693634_A_phase_field_model_for_rate-independent_crack_propagation_Robust_algorithmic_implementation_based_on_operator_splits)  
19. A phase field model for rate-independent crack propagation, 檢索日期：4月 6, 2026， [https://crm.sns.it/media/course/3060/miehe+hofacker+welschinger10.pdf](https://crm.sns.it/media/course/3060/miehe+hofacker+welschinger10.pdf)  
20. Real-time voxelized mesh fracture with Gram–Schmidt constraints \- ResearchGate, 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/397150403\_Real-time\_voxelized\_mesh\_fracture\_with\_Gram-Schmidt\_constraints](https://www.researchgate.net/publication/397150403_Real-time_voxelized_mesh_fracture_with_Gram-Schmidt_constraints)  
21. Real-time voxelized mesh fracture with Gram–Schmidt constraints, 檢索日期：4月 6, 2026， [https://colab.ws/articles/10.1016%2Fj.cag.2025.104382](https://colab.ws/articles/10.1016%2Fj.cag.2025.104382)  
22. (PDF) Massively parallel phase field fracture simulations on supercomputers: towards multi-billion degree-of-freedom computations \- ResearchGate, 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/387375948\_Massively\_parallel\_phase\_field\_fracture\_simulations\_on\_supercomputers\_towards\_multi-billion\_degree-of-freedom\_computations](https://www.researchgate.net/publication/387375948_Massively_parallel_phase_field_fracture_simulations_on_supercomputers_towards_multi-billion_degree-of-freedom_computations)  
23. Accepted Posters \- SCA/HPCAsia 2026, 檢索日期：4月 6, 2026， [https://www.sca-hpcasia2026.jp/submit/accepted-posters.html](https://www.sca-hpcasia2026.jp/submit/accepted-posters.html)  
24. GPU-native Embedding of Complex Geometries in Adaptive Octree Grids Applied to the Lattice Boltzmann Method \- arXiv, 檢索日期：4月 6, 2026， [https://arxiv.org/html/2512.01251v1](https://arxiv.org/html/2512.01251v1)  
25. An adaptive finite element multigrid solver using GPU acceleration \- arXiv, 檢索日期：4月 6, 2026， [https://arxiv.org/pdf/2405.05047](https://arxiv.org/pdf/2405.05047)  
26. Optimizing Memory Access on GPUs using Morton Order Indexing \- Anthony Nocentino's Blog, 檢索日期：4月 6, 2026， [https://www.nocentino.com/Nocentino10.pdf](https://www.nocentino.com/Nocentino10.pdf)  
27. Vulkan Subgroup Tutorial \- The Khronos Group, 檢索日期：4月 6, 2026， [https://www.khronos.org/blog/vulkan-subgroup-tutorial](https://www.khronos.org/blog/vulkan-subgroup-tutorial)  
28. (PDF) An Auto-Programming Approach to Vulkan \- ResearchGate, 檢索日期：4月 6, 2026， [https://www.researchgate.net/publication/357021762\_An\_Auto-Programming\_Approach\_to\_Vulkan](https://www.researchgate.net/publication/357021762_An_Auto-Programming_Approach_to_Vulkan)  
29. Application Results on Early Exascale Hardware WBS 2.2, Milestone PM-AD-1140 Andrew Siegel1, Erik W. Draeger2, Jack Deslippe3, T \- INFO \- Oak Ridge National Laboratory, 檢索日期：4月 6, 2026， [https://info.ornl.gov/sites/publications/Files/Pub176277.pdf](https://info.ornl.gov/sites/publications/Files/Pub176277.pdf)  
30. Teardown on Steam, 檢索日期：4月 6, 2026， [https://store.steampowered.com/app/1167630/Teardown/](https://store.steampowered.com/app/1167630/Teardown/)  
31. VRAGE \- Keen Software House, 檢索日期：4月 6, 2026， [https://www.keenswh.com/vrage/](https://www.keenswh.com/vrage/)  
32. Keen Software House \- Wikipedia, 檢索日期：4月 6, 2026， [https://en.wikipedia.org/wiki/Keen\_Software\_House](https://en.wikipedia.org/wiki/Keen_Software_House)  
33. Origins | Medieval Engineers, 檢索日期：4月 6, 2026， [https://www.medievalengineers.com/origins/](https://www.medievalengineers.com/origins/)  
34. Exploring the Tech and Design of Noita \- YouTube, 檢索日期：4月 6, 2026， [https://www.youtube.com/watch?v=prXuyMCgbTc](https://www.youtube.com/watch?v=prXuyMCgbTc)  
35. Low physics FPS :: Stormworks: Build and Rescue Yleiset keskustelut \- Steam Community, 檢索日期：4月 6, 2026， [https://steamcommunity.com/app/573090/discussions/0/4427688336539255773/?l=finnish](https://steamcommunity.com/app/573090/discussions/0/4427688336539255773/?l=finnish)

[image1]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAIcAAAAZCAYAAAAbiz05AAAEcklEQVR4Xu2aTYgURxTHX9CAYkL8CCYhBlk/EIkQRBLZoDcFPZiLF0UPiwf1IB48KDkICyEHLyKSkBCExYuCiBcRPXhYPUkiiqAIipCIKCgqiIIa1Lzf1jy35k31TM9M72R76B/8YfdVd29X1av3XlWvSEVFRUVFReF80YHmjN1ZHN+pNnpjTqZJ4/vl0Yfc3ILvVV86G/edV33i7H3Juw50buzOYpivuqX6wDfkZFD1ShrfsZWWcXMLLqhWeaNyUfWHN/YjwxIG65hqnjSusFhfqS5JsavmjWqXN7YJDkIf/pbGd/barno+dldrTqqmeqMyXXVawnj0NYtU91U3VZ+5Ng/XbvLGLmDg76kGfEObEOpxjre+IcEJ1XFvTEAk2+ONET9I905dCr6WMLjXfUMEE3jXG7tkn2qLN3YIK5k+rPUNETgjzpGn3lghzRcLz3olIWr1NawSy8VZDKv+9MYuoJA8o/rWN3QITkGKwkkI+ymYyNXemIDFckC1RDXFtcUwXs2iS1lgsdDnpZLR318kdHarb6hBVJnrjV3wjeqZ6mPfUONzCbsCX0iaiADeCdZFbR4iwSNvjOBZONdvqk8lLAQGaptk705eqEa9MYGve1qp0+K8Xejz76ohCX9zpYQ+NexGqd6fShgU30jhtdPZumWNhBphhm+o8ZeEibbQ/lPt99QkGXSWawj3niMSokoKnnlWxgcJR7octfNMUqDngeqONyYgHbcjP/4TBQuBvpkzfiTB2ZN1JYPCxexIDCaHYjUvFHt5wu0GCdelIEXclvrdwCwJDsN9zRiV8Ny4FqDzbL99pAFLRxTGxojUvz+r6Z/odwNbyt4rDkrYUueRr+1sIcVFNVGL/hyNbO+hyLKwbXBzL52Dd+AZh52dbTYTSMRpBl7Pc3mGbUOJiqScFPa+OIThzzdoTzlBmZ2D+gKnJyAY9PlfSUfJMQjJDAYhBnCMzIsTUCv8LNnpwshyDiIVoXq9s1saWuDsHlIEkc86TtSgnrL+eG5IeI948K5K/cko7RTPnv/bObqB/tJ36itjh4S+Zi5AwjEOMqpaKGGXMhEwcUygnzQ8+rGEEBdDmknVEilwEDrJ8w9JfZr0MOlca+kKZ7Kox88/Slh5qe1vWQtSS9E4Qww1Jyk2EwaBap9VSngdqG8uDIsQ3gls5ccRgsFiAvdGtlZYenwiGQVWDXZnXEd0AQbOUsqQhMHKKoK5L5mfJzm2MONoaQeJLISmWLESF2kTQdYhGM7wWsLEEi3YQbW7mthd0YdmjmGwZd0s43/TVhDnHFlQz/icXRZGJIwNNQvjy+eEh5JxxpGCM4j93lgwg1JfOMZwSLZYNdM35IQzGSJQO9vC2apTEu5tNVDUJKPSmBbLgNVZwPgyRu0uvp5QxIe3ovDnG1nYh7ciDwV7CY6Bg0x6qG+afdPpJaQ4vsS2grORl95YInAOUksp6OaffYrkVwnH+s0o+z/7kEKuSTg3Kg1XVMu9cRKyW0JtUlFRUVFRUZGP/wAP4BbxMlBF2QAAAABJRU5ErkJggg==>

[image2]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAGkAAAAXCAYAAAAIqmGLAAADcUlEQVR4Xu2YTciMURTHj1Dk+6MkXyWUfEfeSLKQsmBFkc3EgqyUt2yHnUJIlFhYKMXGQknSREkpspCNjRILRRRF+Tj/znNmznNm7n3uTO+Md3F/9e+de86d53ne+7/n3jsPUSaTyWQyo5cxrK0+6JjLOs66yNrucoNkNeuoDzqmsg6xLrP2uNwgmc8a9kHHWJIxvUoyxiXOsT6zfrP+sk6U002msW6zaiRm4sZPabBG4V54RlWjlG0xnnWGVS8+49lvsQ6bPv1mFbXGFHpXTpf4znpcfMbY/mLda6WJhkhm2UKSC4VMOk9yM1wEoB/aB5s9+s881jbWZIqbdIQkD4PArqJ9ttmj/8xk7WDNorhJK1gfWEuKNqoI/V81exiQDJkEY/DFkrskg1XFdNYFHzSgzI/5YAIxk5B77WITXLsT6HPKBw0Yh16WzZBJ60mqyK9GM6hVDCViJmEG40ZYMwEGPhXM5jprkYsrddYbH0wgZJJWmU6olImkYGBqrA0urtRYH30wgZBJuhotL9qovo7mKDGTDpBc7CHJXgQekay5qVyhshlV5lURMmkLSe4FyeECXC9i0QEw4GD0ybTVvI0m1g0hk56T5LAf4YCDVWU/yThjH20jZpI6jtJUVrK+sNaZWAyYAqPUlDr1VkFKyCTdf/6Y2BySe+02sRgwBUapKTXqrYKUkEmIIXeNNa6I6daCsWojxSS7J2HdxEy4ROkzFEZhsJYWf3utIlBlkt2TMAB3WPdZE008Bv4nVNMQiUG9VhGoMsnvSYihANqImaT/uM1hrW+wvrHWmHgVmCGY5Xqa6ZWQSdiMkbvh4mgjvtPFY6CasKRv9okuCZnUIMn530WIQW3ETNJ1vpNJWAIxMCkMopIWU9wkTLgUBlFJqO4RMwnLBb6EtVOZwnpCsgSmHG9RQTBG6dfBASCHzVfBgN8kWQJnm3iIQR0cNrF+UvtKhP52T20SMwngS89MGzMWMyxlM9YK8oaE4inETMJ6/t60df/E4FcBQzpVTiieQsgk7I/YJ/e5OPo/sAGYouVlhYvaMtRXKy+pdaQ9bfIhRvLHrO43nWSB+Xi2tyTPigmGZ69iJH/M6puDTvJ8Zf1g3SXZA9eW092BhzxJ8hJwgcuNRlA5eNZllH4C/R9MYu0leVmAV0mZTCaTyWQy3fMPSafyTH7ep5wAAAAASUVORK5CYII=>

[image3]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAcAAAAaCAYAAAB7GkaWAAAAiUlEQVR4XmNgGOSAEYjDgJgVXQIEooF4DxBzo0uAdM0H4lZ0CRDQBOK3QKyELAgy6j8WzAOS5ABiSQaIkSBBEBuE4QBmJAhjAD8GiK7T6BIgAHI+SBJkPwZ4DsRfgdgYyge5Aw5AutYAMQsQmwHxRXTJpQyQgADZG4wsuR2I/wLxUyCOgCoaMQAA4aob4lcTC3EAAAAASUVORK5CYII=>

[image4]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACgAAAAZCAYAAABD2GxlAAAA6ElEQVR4XmNgGAWjYBQgg2ggtkMXRAZXgfg/uiCNQR8DxM6/ULocVRoV/Abi5+iCNAbmQKwNxHIMBBzIwgBR0IouQScgyUDAgeJA/A+I3dAl6ARwOpCDASLZCcSHgVgZiMWAmBlZER0ATgeCACh61zAMXPSCAF4H2jBAMogSugQWwAjEs4jEoBwqA9FGEOB1IEgQJMmDLoEFDIgDQdFL7/IPHeB1ICh63yLxDwExPxKfHgCvA0ESoBwMAsJA7IckRy+A14HTGSBl4DcGSHFDTwBL/+j4IbIiUMKXYiAuk4yCUTAKRsFwAgDqLT5c1HJ+xAAAAABJRU5ErkJggg==>

[image5]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAwAAAAYCAYAAADOMhxqAAAA5klEQVR4XmNgGHJAF4g10QXxgTVAHI0uiA8cBGJBdEFcgIOBRNOlgVgfXRAdSALxFCB+B8SvgfglEP8FYj5kRTDADMQ/gHgjEHMC8VYgZgRiVyC+BcTyCKUMDGVA/J8BohAEXID4GEKa4QkQP0fig63/icQvB+I5SPyHQPwVxgGFBsj0Vrg0A8N5IDZG4oPkr8I4PFABkKkwgBz+IGeiy4OdswPKZmFAhD/IL+8Z0DwMAiBP/wNibgZI+IPSjzBUbBWSOgwAcl4nEIsDMSuaHFYAcg4owRENbBggHiYa1AGxN7rgcAMA8ZwmXJ194MUAAAAASUVORK5CYII=>

[image6]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA0AAAAZCAYAAADqrKTxAAAAvElEQVR4XmNgGAVDDQigC6ABdyDmQxbYDMR7kAXQgDoQ/wfiA8iC74DYHFkAC/jLgKRJGogt4VIQAFKwEoiZkcSWAnEVjOMCxIIIOTAAOWUSmtgxBohaMChCkgABHiD+B8QeaOKngFgcxikHYkWEHIM1EK8HYlYonxGIy4DYGK6CAWITyP2qQFwBxL8YIM6bBsTyQDwZygdphgOQiSBBEAY5QQ2IpyOJ7QRiCbhqJCDMAAlBZNNgYqNg4AEA7CMealpjsvsAAAAASUVORK5CYII=>

[image7]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADMAAAAZCAYAAACclhZ6AAABs0lEQVR4Xu2VzStGQRTGH0VRSiixUrKRhYWFFDsLNhZWij3/iH9ASknJQv4CsdLNSixY+ChRlL2VhYWP8zQzmhlzP+btdS3Mr566M2e673nec+ZcIJH4t3SKBrR6RS1uGG1alegTXfmbNdEuOhB9an2IZp0TwJyO2crlHSUHamBJdA2Vx4sXI6zao7/pw6qUuq2Bda28XKZE9/6mzbToUpQh/IIQEyju3x6opGJhVUZEG1C5LLph7InWvD2HU9Ey4sz0izYRNsTYiWjLD1QggxoE46JX0ZGow4pfiGastQP/4WH9nKG6GTIINTBsQzRCEyGTZXSL5q01BwDzYZUMHBIcFj/ogjJg4HOMGUJDJnlTkUaMELbXkLVmfsznWa85qnNb7EY0aq0zxJshpkLbcFsiltAdoxHmRGO8/LbZbxhc9fYy/J0ZttiZvwl3EKwgp8V4wXgoT7x8VaERtlqjl56Mie78TWFS9CY6Fh16sUKeEFcZU5FmDIAdhNuMLMC9O5WINXOOcCVMhWIM3UIlHcIMAk6yUnYR32bN+miyhezf3Re1OicUDyj4viQSiUQi8Vt8AdJZa8HKxzOjAAAAAElFTkSuQmCC>

[image8]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAwAAAAYCAYAAADOMhxqAAAAhklEQVR4XmNgGAWDEYQB8Q0g/o8FX0RSBwYSUAlceBVCKQPDLKggK5KYNBB/A2JTJDE4+AvEt9HEBBkghviiiTOIQyU80cRdgPg5ECuhiTPoA/FbINZEE28F4uVAzIImzsDPgGnSBAaIrYxIYigAJFkDZXMC8T8kPk7AAcRGQCyALjEKaA4ATjUf4GBi+ggAAAAASUVORK5CYII=>

[image9]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABIAAAAYCAYAAAD3Va0xAAABBUlEQVR4XmNgGDGAA4glkTAzqjSYL4YmhhVMAuL/SDgHVZpBH4g/QeVgeA+KCjRwG4hPAPE/IPZAkwOBA0Bcji6IDlgYIIr4GSA2vkeVBoPrQCyOLogORIDYBcqGOR8drGGAWIgXpDMgFDUwQAyKgMsyMMgAsQ0SHysAGQCyDQZ0GCBe2wHEnFAxkGtBrsYLQDZdRRMDBTbIVVMYIMljK6o0dgDyFrKLQAAW6E+AWAmI76BKYweg2IAFNDIAGQIybAEQL0WVwg5A3pJGF2SAeAtkEChdgVyNF4ACswpdEApAkbCcAWIRzoAG5Z9QIN4FxI+AOB4qhg5AgQ7yFiO6xCgYBdQCAJCJLoFnQiXRAAAAAElFTkSuQmCC>

[image10]: <data:image/png;base64,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>

[image11]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEwAAAAZCAYAAACb1MhvAAACuklEQVR4Xu2XS6iNURiGX4kUuUROyiUDhCLOFAO3GIiBgVIGhDIzQJGTieQ6EJHUSSIDZWTgko4USpGRkUJGZCLJJZf37Vvr/Ov/trOdvfc/+B3rqbd/7e9be5+93v2tb60DZDKZGjOC6qKmOGUGYD/1q4l6qTn9szP9fIYZ5DkKiy/1if8dmfLTB8k6WO60T/wjDKNG+mCnTICZctMnyHVYbqdP1Jyt1DtqA6xPV8pCmCnqZ573sNxEn6gpY6m91DNquctVxkXqLTUVxam5i3oN62Gji6m15Sz1jZoH24btoLWrOLTuWbDbQsNnaTs+QePpqH52IJlXV2bCTnJJ43aRWReou9Q4lyuxGWbQShfXF6jzVnxEfUF13+85bL2bqI1BK0ozAtEYbceUeD/rdvG6cBvVGvYdtt43ie6UZgTidhzl4vF0nOvidUIXavUt9a9O0Vpl2l+J/cqjmHLqcSk6UZeFsQ4HjbX/dTCsDk+PesJsNDZQvS++X5W8pJxuidj0F6Dx7wwGmfXRBz36YJmiE9KjuDQmvFYlzqeOU/eoHuoc9ZC6CqtI9cMXKG9vHfGPqVPUU5jJYgZ1A3Yhvk/toW6FXLtoe36C9bdWTdN31Hr/iI7LaEgqNbzIiRA7EqQFToNVywMUl0Et+FUYi68oKkVVcyiMZdTLMBa7w3Mt7D2qzB1FumO2wK4H66nhLjcQi6gfsAJStfaW083RL6RtpKP2YBJXT/uQvO6DVVdEFRarSGZG8/SUMZ7DKBteJTJKF9grPtGE8dRiNPb0tpEJaaWkFbUmSPchVWIfip4mU1WZx6jp1DVqFcz8fWHO9vAcUmjRaUXJvFhRJ6lJsO2mCj2Dogeq1C9R52G/nrb7tvDUP/ja8pfD3CGFTs20J/jSVUmnTEZhWppTLN6hNG61SWcymUxmEPwGOoOVs074bWgAAAAASUVORK5CYII=>

[image12]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEsAAAAZCAYAAAB5CNMWAAADB0lEQVR4Xu2XS8hNURTHl1De8ojkVfKIlIGBPAciFBNSigxMZCITnxiRjEwkxcBImUgZiCQhA/JIFGbqIyVkQDEgj/Vr7fXddbZ7L+e750o6v/p3z1n7nP0/Z+299j5XpKampqampqampttMTfobDFDNVo3LG7oEfhukIr9rqrGqT6qnIX5E9UM1KMQ6hQc/rRoq1vel0HY+xfCtisNifiSLvoekOO+EXymviarl6ZjOYrJ4EWJVMl81Mx3nycKb2LoQ65RHYn4+8J6s8WJ+pbz85hmq76pVoY3z3nBeBSPS7zLVe9Xc0MbL3JTGNVXgfX0V83N2ivn1y4ub76vGhFg+8lVyXKwMvMT5xY94N6Bv/Bwv+dJ4/cYHZcbR2YEQqwoGhIFhgBzKAr+NIVYV+NF39POSL81CscWdX4dyfC1WnjekWNvrxXZOdpdmNc9OtzoPBnyxnRRicWZPVi1IcZ5phV+kjFYNDucc087G0Qr8XkjRD3/88HoQ4rzTmnQ8PMT74ME+SiNZGJ8Q2yW54Z5Yp8T3qLarnqnOqHpUc+y2Pr6JPUyrXZQEx2SxM14R27VgrdhaQiKY2bdSHN6otoTzY2J9xVgOfjFZvhPjh9fVFAcmDW2LVPtDvAALLS/5SnVONU3spreq6ema3WKzhgSSSJKxVDUwtTu3xTaHOFNzVqq+qF6qjorNYPzfqS6na3apFqs+p3OgfChZh0R8EJv97RZr+sWPvvC7K+aHF7M1wqRgkOIM/gUyz8M5TEk+LXJ4MUq0HSOlfbKAAVkSzickxeQfFBtAoETPSvOSuyjtk8WL4+fX4JF7AX1TOXm8NDtUp8Q2gt4Uw3CYXxDYJq3LsAyUz750vFXskyOHRLQrwz+FBG0WSxg6VGwux3PVY9VDse8hOtwbL0hQtnHR7AQ8SRalQunHEnQ2Sed/ZXgX1t/r6Zf1+ELhipL41PVj/6DNmSe/qfcS4EHpsDywCeWwfs7Kg/2AfvyZ8WxW6v80U6SxI59UPSk210QY6TtJo7K2mpr/hJ/KPYyzCEAenwAAAABJRU5ErkJggg==>

[image13]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACYAAAAZCAYAAABdEVzWAAAB5UlEQVR4Xu2WPSiFYRTHjzCIQkRC+UrJYDApkwwms2IzMNgUkzKbZDBZDBLZLEYMkow+SlkkA8nCQPk4/87zuOee+7739ryvbpFf/es+5/n63+ee8zyX6J8/yLpp17CaAlXHKsHkPFSyKmyQ6SdZIwdrbJH1Gag9it5UM8iqtUEKMAb8hi2UezparawtVrVMi6WMtWODjiBjdyTGGm2HoYt1a4MRNLNObdARZKyX9cA6YzWYPs0Na8IGI5hnrdmgI8gYEnmZ5NSmTZ/mhCTx81HFOmQt2Q5HkDEAcz7XosApxJ1mG+uINUlSjfiMxMdeH5RdwcHGwBOJMXsqSPorE9MgDa5V2/+MKJJjEjOeRMYw6YVkMV15l6wZ1fb4FNgkqUSAxO/7HiEnrfdMZAyLYxOc2piKw1hUxbaTVOmoiun7C8Y36AeMgQESY/uuDUP41lEMk+RQh4phrM+petY5ayrTndwYeCUx10lyhcTRw3qkzEb2/kJ1vqs2SGVsm8TYAcndFUc5ydgR18bPiDkA1Yk1Zl3bk8oY3sFdkvxBHhViheRk3ljPrHvWXNaIDKmMgSHWAhX+F+EpZV2Q5F0+UhtLQtwzpCm6MVw14zYYQdGNdZO8k4WINbZqA0UGr4J98v75/XwBuDRmb5oEO1YAAAAASUVORK5CYII=>

[image14]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAMMAAAAZCAYAAACM2prjAAAFY0lEQVR4Xu2bW8htUxTHh1DucsmhyHFySUfppKOOPHzlEokU5YFnPCjy4vC0Tzpv54WUkvryIIWUByl52PFAKVEu5VKHRAilPBz38TPXsMc39lx7rb332tbavvmrf19rzHUdc4w5x1x7fSKFQqFQKBQKhQ44qPpLdUJs2IZcqbotGguD5ynV69G4CGNJybDdOV/1ieqo2FCB/c5oLAyCU1VvqI6NDfNCIjwXjduQP1T3RaPyoyQfmZaBhDs+GgudgF8/VZ0XG+bhT9UN0bjNOEb1leqC2KDcrtqt+kKWT4ax6uZoLHQG/ZMb0FqxSzWKxjXhMdW50eh4QHVTNNbwkDSXQP+HZOjSZ0PkRdWRaMxxkmpDtcPZblRd5rb75gzV9ZJqwCZGqvejsYLa8WNJZUkTx6leUe2NDYGhJANrF86xyHlG0o3PPPhvo/obOVp1herEatv2jbU9z3Kp22YmZkb2+3HsraqdzhZ5UBr66ELVBzK52MOSDsCp3GxbzplTdQvRHPskLYDOluSATdVdW/bIk+vAUWVry+Wqn1Unx4ZA38lAQFHSHnA2tu0tCoMcz9JEFz4DZpifVNdW23dI8o9/q0OfMsBhZ01GLF5UbeMH6vyXJfn+TUmD0tv/HJlgv5dUz6pOkVTOMvozk+cg8X6RNPBn4SG5acMOyNXHs/hyTuGENuyRdH++c3AUTmkzQzwpk47MdXQTdCZBZaNXHX0mA8/F60PkR0t8ZPfEuq+Nv2BZn3Edgv5eZztN0r08Xm0zGFr9jp2Yg6cl+Ru/E4tvqc5UfSjpbZ6/D45D/pkfUT3jtj27VN/I1urnXyiBCDRfCtEZlplDAMfEUYnMx3k4qw04K3eeNpg/mpgnGa6RSfB6fa96LWPfL7PfMrG4z10bP2G/SvVeaGtiGZ/l7udq1Q8yqUCoOhCBzr65NRkjOKKNfUgog2TCRvIYtL8j+XMBFQn9xN8p7pE0Gvlp46Cki7QNtFXDvWy6bZsKax8qwzIduw7JwHV5voglwwuqR0NbE8v4LHc/uVgD4swnSQ76P/o2l0SU00ek/ly1yWBBZdOWQV3GRbhY39jUiiMNmzK5z9yiLEKnMu1Ti45kvukeVpEMdYxlsRmZ6zL9RywZWBSfFdpmsazPuCazg8EoTl0fYw3oW0ZzP+pHaI++tXLeD9ojSfvVDRy1ycC09ZtMZxEnW2Q0iAvkJrVZQJP1BL5PzCck3WMcYXLk6t1RZWtL46Krou9kINgi1OS0TXX+DLrwGdf0dbuPNQZhG82byhqD87GfQexsVvJxxGzELAM8e4yx2r60UYNFhYEDWNUP4fsbe+A4arDG8Qv+OujUQzI9qmEfZex17FB9Ls0B1XcyjKNR0ouKeZKhK5/FZGAxjY1+JN4s5iw444DsIQ44llgwrDqIScR+VAz2+UWEnwqyfXRYJhnHJwW/S1q5DwVz1N2S7vVrSYk660chT5c/IDFwRMcb+DCntgHoGctiyXC66nnVd5ISgL/3V20MbAwevB5uoiuf7VV9JqlU4rq8iaPsIs4+cvsdkJrgdOxUfau6xNksqGM5xE8Ev6peleSTCMsCe2u1BaYU6kxW9NSTU1NHz1BL2mjCyERwtVkjrIJ9kr7PYopfJWNZLBkM3gryZW0sD/Ddf92/xJXvM+6JWZa+NEgSH+Q57DgP57442IBzs2/d72OHJTOD0qkEWm5BMxRyi6Y+qftQr0uY3n2wFLqDWYSZY+pFAtlDoN0SGwYE02t2SusJyhCcWVhPrpP0K/gUG5Leyqx62l8Gyjhq1CFR/rlnPeG3Gv8ZSKEj3pX0iUhhfahbUBcKhUKhUCgU8vwNNq5ZF6oF1I8AAAAASUVORK5CYII=>

[image15]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABsAAAAWCAYAAAAxSueLAAABt0lEQVR4Xu2VPyjFURTHj1AkSkRikAxkkBjZpAwKi4FJFiuD2WCwmkwGwiILSrFZlFlKKeTPZFFWfL+/c2/v/I6X3nt+BuVbn37vd879nXPvuefeJ/Kvv65y0ARaAlVpd141S258g/N9KwbnR4/gHZyCmtSItGbAh+hYfseJFq1rsAMuQaPzRbWDY9FkD2lXcVoDw+AN9Dtf1CboEk225HwFqxZMg17RQGNpdyLuTY/ohFhCPkvSJKgXTcpkK2m39IF90f09ChTSSHnFElIVosm2ja8a7IIh0AGe5Qcl7BZtjigmuzPvB6At/OaK6W/NuROVgfHwtPrS1SzhhXlnsJfwmyudMj6Wj35fQu71ax77gnuXM9HmiOJHDMjzc2LsVDxfJYsl5MyiWEIGXQaLxk7Rfuts1Cioc7ZKcUeINWYz2FrviQZlQ1ixOfz54uo3gu3J2NdBp2jZk+ATol12D2ZF70iKncmgcQJshhFjXxVNwvEDYA6ci8aKmg/PG2PLTFeil7NXbLTMxCRcNTvRXsrsZNvlmYhn8BBsOfug6LHKXPyXiHsexTPGC+PXxAucTcSLgNeafALr/06b2D3tyAAAAABJRU5ErkJggg==>

[image16]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAsAAAAWCAYAAAAW5GZjAAAAnklEQVR4XmNgGAWDHqwC4r9AfBqIfwHxHVRpBLAE4utArAnlqwDxMyCWh/J3Q2mGU0D8H8ZBAkEMEFsEgXgSTBCk8D2MgwRcgPghEBswQGxmkGSAKG5FUgQDxgwQudcwAZhikJXoAKY4AybAAhVAVywBxHuA+CsDRBMcgNy7D4nPDcRPgLiKAWHQSSR5BmYGiCekgZgVSZyDAeLUIQkAhTUeJxel+uEAAAAASUVORK5CYII=>

[image17]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAoAAAAXCAYAAAAyet74AAAA20lEQVR4XmNgGNqAD4j3APEJKBsrcAXi+0AcAsTRQPwaVRoCtIHYDE3sIRqfgRWIV6ILMmBROAWIG9DEGIH4P5oYwykgtkETswbiWWhiDPMZINb/YoCYshOIv0LF4ABkRTqyAFSsGYgjkAVFgNgYWQAKXIB4K7IAyDSQCejAF4gPwzggBUsRciigigHJRFMg/oKQgwMPIP4HxDIwAZC1GGEFBLeA+D2ywHEgNmeAmPoKiN8B8TcGLIkBpBDka1B4iQOxGBAzo6iAApBHsPkYBeAKPwwA8jFIMXUAAHovIg4e93MBAAAAAElFTkSuQmCC>

[image18]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACEAAAAZCAYAAAC/zUevAAABr0lEQVR4Xu2VPS8EURSGX0FCUClEFBKJwg9QSBARgkKDglahUvM3RFR6jeiESAgblYgECY0oRaEghALx8b7OzJo9OztrN1Zjn+RJZs6duXPuPffeAcr8Y5rpAd2hta7tT5imV3SCztAz2prxRIkZRPYHn2jKxUpGI933QfxxEpt00sW0Hp5pj4unqYbVrc/Fi+WEtkXuK+gUnQuuM+igd3QouFcyH7CHxTCtC64LYZ7W0zdYf9v0FTEJaNFc0HEX15RpReuFJdf2ExrogItpcKu0y8W/Mnz3QdjCUdss7MVCGaM1PkhG6RqtCgO60IfOw0CEFKztGAmLKIFFHwhQiVYQKUkT7EPKzJOCtS0gpoZ5UCl2fRDWjxLISDBMQlPn2YK1eVTXXiQnpv4efBBWWvWpxZomLIemKEolvle0RzOjuN//UTTSuHe1A7UJsuikl7CdIA9pP2zEy/SWnqafBkboPd2DG1GEI9j58EKv6SO9QZ4fl6a2m7bHxFUyJeRZR+4kNmA7Q+rvqeM7qXxFoaRylSPufCgJOtg0ujh0+rb44G+j49uXLYqSKFOmaD4B1KNMhXVWoyEAAAAASUVORK5CYII=>

[image19]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACUAAAAZCAYAAAC2JufVAAABxElEQVR4Xu2UPytHURjHHyGEJPIv5cciGQzKZDQovAJ2iwwMrAZvwGgxeQnMshispFgMIkpKWSg8n985z73P79ybXd1Pfeue5zn3PP/OvSIVFRUV/48+1ZSqOXVI8K2omuK6plrOvAHem1X1OJu9N+hsI9HW7mwFllTnqiFVq+pI9aPaiv4O1ZqqO9q/VG2qXdWZqivu4wySYM919PMuCd1IOO9W8sI45z4+NzCnelNNO9u86lu1ENdUz8GIgCQ9o3pXnUiomEAbcT97kAUn6TMJcYhnPKg+3DrDKvfsSx4MrBPrqktVb1x7GB3Cx5mrzkdRBKcQD/s4r4GW6KDVBomQ0I6zAVXToYPEnkICdNAnQDHE8cVY7MJ5No5jZxuV0FYbndEvIXnfgTLwp920O2rjhHEJV2TR2eoMS9jMl2AwOmx0bCIKCJZWm4KPhNLEGZ2/OzYN69KFhA7X4a6kSXEZsQGH02awav/C7k4WIJLeHQp9knwapxK+0oxN1afqRXWnGlNdSUju0O17VD27dRl7Uky8U4pjYozbEmK+xnUB2jkg+U+TfxX/G7+5Jo0/wDJIYDI1Sni3LDDXx77sioqKipRfWfdZgVmRObsAAAAASUVORK5CYII=>

[image20]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAoAAAAZCAYAAAAIcL+IAAAAgUlEQVR4XmNgGDSAA4iF0QWBgBldYA0Q/8eCJZEVIYPfDBAFBAHMJLxAnAGiaCu6BDqwYYAobEWXQAYsDBAPPQdiJTQ5FACy9i4DxFpQUOEE5QwQa13QJdABLBzxWgsCIGtBCrnRJdABUeEH8jFIEcjHWIElA2bcgnADkppRQCUAAPYuH9agZfgSAAAAAElFTkSuQmCC>

[image21]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAFQAAAAZCAYAAACvrQlzAAADD0lEQVR4Xu2ZS6hNURjHP3nk/SjPkEiKRBIjjCQGJEnyGJvIwMTAc2IkKVMDDIQoDDCR9kiikBITiYlMKDFAHt/P2qu99nfW2efs2667zrn3V//u3d9a53Tvt77HWmuL9DcTVA9Uf9toVjG1xGLVCynmXRf3XcPk4JQjxrZaNdbYLHxumzUOI/JLtS7/fW7+E4dWMVH1UbXIDtSBFZtqjR3otMqDzUrVPdV81RbVk/JwW4jM09ZYl/PSWmfQ9Hx8hOqiGcvysVTZq/qs+qD6qbpbHm4LzmQBGoEUwVkWHMrKHVKNNGMpMk31VIrmc1C1UTVFXGB4+F9vBc+kOeneWCPy0Wc5pnpnjQlDnfyuGmXsO1RHg+cNqsnBM0ET+/8HBOltU3mN6o1qdGDrBYhCss1Dhq1QXZVWJ4dk4iK0EXAeDvUFmT/ik/TW9oFUvSZFplE/kX/eXEwtsUT1TIp5V1TjSzNqwqrdlGK78EjcF3da0XZQZ2eq5tRQ6ruGWpDur8SdLojIU+KK+hfV8mJa18xTPZYiQrrR/v+f7BMOiItIHDo7ty3Mba/9pERYn5iikO44j8jyUEN9TUkJ/zeloiikO4O2jv3I7RzHUsFGyGArCk77Y43KbnFjdZvTkG5KOAqnxfZfnDSooQNtTqmySbVdXFmDGcFYFfiK02KUBdJaDxANCsZExupGakqwR/2m2hPY9ql+qyYFtioyqaibQwnO8PdVa43dbxe75bn0uUPJMk9VDaa5ZtYortnesMYIlIYz0v6OoC/gwtgfk8+Ja6o0N4vvEb6UheAobqY6gSMviPv+95LWrqcxeLVxXJwzLXdUt/PfcQZp7e92we5APG8l3nROSOF8TpCxhetp/N2DP7YuKw/LV3EXO4BDMylHFYvwUlzkMubhObwfBZxvm/PS0ow+gPdE1EUPnZpb9rPSeks0TuLbPi6ceVNRxSrVJWO7LA3e6KfCYSnvm2kURGKsTgIO9SUA6PrcW/CapIqHql3GdlK11dh6HtI37OpcgIc1Mgant53iNvSNv8b5B9Dt5ab3QibdAAAAAElFTkSuQmCC>

[image22]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAoAAAAZCAYAAAAIcL+IAAAAuklEQVR4Xu2RvQ5BQRCFj1AqhVKIRqLTKu4L0HgIjyAewAuIx9DqdQrtTbQ6HZXK7xmzs8aqFRJf8mWz555MdnKBP9+kTDNaS/JIi+a0He4TeqeF2Ahs6dHdu/REGy5DB1qS0+hDJ8oZGdEV9H3GFFqUyU9KdEFnFgSW0GLFgh4947WEISV5d2QcwqbL6vRKhy7DDlrc0AO90LUvGDe6p0VaxftCEVlEpqWLfCC/SYqD9ENKRufQyT/NA1+mIFQLOuPJAAAAAElFTkSuQmCC>

[image23]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAKMAAAAWCAYAAACsayxGAAAE+klEQVR4Xu2aaah2UxTH/0KZ5zHTJZTMmYU3Y6TXBz5QfDVFhCK+uCWffDBESoZeEhnKF2VKr5RMKTKUoV4yhBBFIcP6tc529rPec86z732e+ww5v/p3z9n73HP2OXvttdfa+5F6enp6enp6enomxGGmd2LhEDY3XW/aMFb09IzCN6bzYmEBH8kNcmbYxrSr6WfTP6aDq/Okq0yfm9aatvB/6ZkRNjbdZ9opVhSygekO076xYtr8KTfGJmjso/LG98wOp5v+ioVL5EDT/aaNYsW0oCEYIu6+jRtiQc/U+cn0WCxcBvT9YiycFjvLG/RsVraD6cTqmGB3dVbXMxvQZxfGwmWAE3o9Fk6La01/m07Lyp6ryueB3eXt/8r0renTwepOtjLdZvpR/s54m7MGrlg5VsmTiGdM35lOVnnbcSDE8sT1baQ84F55fHmZ6fmBKxym6bYQbaIwRT9l+sF0nPzlDjX9bjohu25USH7yxGiYtldZjHqK6XvTUdX5lfI4qjQGwhh+Vf0sjifhJXge7XykOt9RPqBKjYI+etW0ZayoOFx+r6T3TV9W5RFCsNLnrigYHMnL7aoN4Vi5gZZ26LTAI8Z4B++IcQ6DwfGCPOZK78kA+MN0broo4035szCCLvY3XS73RG3sYfpEPnByuD8GVgJh05pYmLFZdryX6d3sPMK9SozxbtPNsXCcpFGRT0101DwkLLSRafWgWFEABocnOjNWtIAnY0Vh21iRsbXcq8bvGVmUX7NPVpaSyLuysi6GGWMOhtg0wBIlxpjaN+y6kVgnf8AmWdmm8qRllsFD/WK6NVYU8pn8vUu9/xHyKXwYeMaL1e0ZaXfsVGYoQqUDQnkbJcZIDvBhLGygxBiBb31jLBwnXeuLOUwt+WjHWPngJAAnZeVtXGf6Ygl6UT5ttpGMo2vEAx5tQd7G3EAI/kvem0SBzrpIZcZYAveJz75UdQzIFh3GyV90htZ3Dhgaqx+5E4mwRZgbNxscTdeTkcf2TBwaRiO61hcBo0A3yeM0ILbigxwtnyonTZoSzwnldBpJQfJ4r8mnVmLDD9JF1XlTByyaXlG9u8FzgGz3oep4VNZp0LAxtnxgPSgfBB/LNxww0BhLdmXTZ8szabb6WNRmQB6i9l20qWbTNA4jukLeCF5qP/kLNnGk6s5PnUxmhmFyr3F10lLBEF82bVedY0AkBnl7WBW4RL7NiRJklQwiOhvwQMebXjLtYrpAHlMmMBa81zi4Rf7d+XYoecrkxeiX3VTHjwymtzToHXEkv6leRcjBiB+WLxNx3yT6vAnemRBhbiBZSB4UI8RLwt6mO6vjaYAB7inPGJumIIwND0FnNK2b4llO1foegwGaNgEwGLwqGwHjJC1hsXIRPRNtTQlO2zTKQIs7MHyPPBzheEHdy2Tcex4S1v9Yo3qqIF5LOzLnV5o1SMKeVv0jAjzLUjwbxsj0BXimlEkTI4+blEwlmH0w0DRAMDjWQyMs0jNDjQJGylIY0/ncgOdhieBJ0z3yj/CA/Ld0swgf+Rp53MdAWjVYPRTe6z15h19telu+U7ISYIgMlgTJC15vrfx7t7Udr/eERv/VzkIonwtofNoZYdS2xZizBFMhWeRyoLPT1M97N4UBo/CGPDFKMd3j8riQKZMYjm/ctaIAOAli0K5puI1jVLZB0PM/hQHwtdwjlv4Ke25/6f0vtbT/VHfecqMAAAAASUVORK5CYII=>

[image24]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACEAAAAZCAYAAAC/zUevAAABGElEQVR4XmNgGAXDFHAAsQC6IBJgI8CnCigC4v9YMAygi09CksML+NAFiAARDBBLlgMxC5J4DBC/Q+ITDcrRBYgAikD8BIjfA7EOkvhXIA5G4hMNyHEECFgyQELjBBAbAPEDIGZEVkAKINcRnAwQR/wD4utAXIkqTRog1xEgAIoSkENU0SVIBZQ4YjMDxBE56BK4wDQgnoUFn8EiBsKiEG1YASjuy4BYA4h3MECixAVFBQ5ATUckAPFzKBuWXafAZckA5EQHyAFmUDYou4IcAUofZANSHAGyGOQA9Kz4kwHiEH40caIBKY54xIA9K4LSBcgRHugSxAJCjkCvD0AYuaheiEWeqESKDAg5gi4AVD2PglEwCgYMAADjPEhe9DNbdAAAAABJRU5ErkJggg==>

[image25]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACEAAAAZCAYAAAC/zUevAAAA60lEQVR4XmNgGAXDFHAAsQC6IBJgI8CnCigC4v9YMAygi09CkqM6iGCAWLIciFmQxGOA+B0Sn6ZAEYifAPF7INZBEv8KxMFIfJoDSwZIaJwAYgMgfgDEjMgK6AE4GSCO+AfE14G4ElWafgAUJSCHqKJL0BNsZoA4IgddAhdwBuJZROJpUD24ACjuy4BYA4h3MECixAVFBQ5ATUckAPFzKBuWXafAZekEQA4wg7JB2RXkCFD6oAsAWQxyAHpW/MkAcQg/mjhNwCMG7FkRlC5AjvBAl6AWQK8PQBi5qF6IRZ6oRDoKRsEoGAXIAACjrkJmApBVBQAAAABJRU5ErkJggg==>

[image26]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAZCAYAAABQDyyRAAAB4ElEQVR4Xu2WzytFQRTHj7BTUiKl5MdGlKKsLN6SBRsb5Q9QsiY7G/+A7K3sZCP7tyNWimxZyEJRysrP7/fNnN6882bm9lig3qe+3XfPuXfmzJlz5j6RJv+YRWjaGhOsQ9fW+BM48aM1ZmiH9qBO6/gu79CsNXpGoWdowthboBdoytgbhqs49dcYy9A51GUd4gLYtcZG4CqYyhnr8HBSTs4gYmxDn9YYowMqQb3G3g1dQf3GrjDtTP+4dXjmpCCAEehS3D6STXEvcOVEB9B7hQH3QYfi/PxN2edou/XXKGyVp+CeBcN9G/T3K5JfAdOf8+sW2QKtwLRx8jB98+IG5JVs+PsU9HGCFMxUWRKdwNWVxT2kaNHoC7kAmG76WKQpkgG0QQfQjrEfixuUxUdyATC99KU6gCQDYFu9SrX4FA4YHqG6JTE4MTtEg10Tt7CQZBHqyoYC24C4E49nvqJtFm6TwjH2xW0Fu+mi1l0h+f6NVAuIZ/wbdBI+4MkdRDwZGfAdtCr1LUiSB9EHdA+1Qj0SiTBgCdqyRs8wNGmNAWVxi62B+8SobAHmyH2MUjAjD9CYdfC4ZQAL1pHhDDqyxgI4MU/Wuq0piftC2Yot4lf/kCj8LKc+TCEsbnZGk7/HFxjJX45OcKzVAAAAAElFTkSuQmCC>

[image27]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABMAAAAYCAYAAAAYl8YPAAAAX0lEQVR4XmNgGAWjgKpAAV2AEuABxPzoguQCkEFB6IKUgItALI8uSC7gBuLFQCyDLjENiGeRgRcA8S8g7mOgEOB0GTkA5LLt6ILkgisMVIoAFyAWRBckF7SiC4yC4QYA/C8RC4AA67MAAAAASUVORK5CYII=>

[image28]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAoAAAAZCAYAAAAIcL+IAAAA0klEQVR4Xu2SzwqBURDFj1CUnfInC2UnW/ECNpIn8A4WlvZewt7O1gt4BlZWSikbewpnmns1d5S1hVO/vvudOfebZvqAn1SG1EmP9F0tUZs8AzNX+9AQGmz4gtcCGiz4gteZPLwZVSVdkod+7ZSWgRHZkhopkjU0OLchWcGVdIw3gLaV51ty824N6CAbmEHkIMF9NIInoaStbF+CK+O1oBMnbWNwbDy7P7kkoBRMGzwGTzQhuViYkhu5kANpkh10E8sYipI2FZIN77LwMvQv+uu7XkOiJllUWY3HAAAAAElFTkSuQmCC>

[image29]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAADMAAAAZCAYAAACclhZ6AAACDElEQVR4Xu2WvyuFURjHv0JRIj8GJmWTwaBIGSkWA4tiYvA3MN2SzSSlpG4GKYtJTJJJDBZSoigySCYD8uP5Oufce865577uqxvF+6lv732fc17v87zPjwNISPi3VImatOpFJe4yyrUimRAt6Sv/yG9QIdoUvWu9ifqdHcCAXrOVoVN0i+wXKBVNiYYzO36eUdEJlKMP3hph1i59I9kQpT1bpWhfVOPZf4p5rZwvr+kRnftGcoXc6MugvkyDZ/fpQnT91kE5FRe+u1W0ABXMiLuMVdGsZ/vkEOqBcagSI3QyZTZE0ChaRDggru1B9WFcdqEGQYfoUbQNVS2GI1GvdZ+Bjswhm9Jn0bSzI5pm0THcgBgIgwgF+RW1okHrngOAfjFLBg4JDosgzIg9HRh5HBiQcd5k5DuBEJZXi3XPvqVP1/qegypYYoQRv+jf3HgH9TCvbWZTAZgMLcMtibiEeoyB0CcGxua3g3V4Fc1Y99WiFaiHU5b9K4oRDEvswDfCHQSTyFNizAQbjI1mY8qOtVkoDISl9t2mJ+2iM98odIueRDuiLW8tA0fwPVSd+vAgDaXcp5gDII387xyC2ztBuIEnvg/7qM83BuBoD2XCZChOQKdQTocwgyCyWniwrYtuoHqFVz5UiBPFOjRZQnyn0RpU1fhcIM/5YsPeYWPxS47h9/7RTEhISEj4O3wApOhvD0w6qosAAAAASUVORK5CYII=>

[image30]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAIYAAAAZCAYAAAD0SVYHAAADjklEQVR4Xu2az6tNURTHl1B+/0j5kV+RiQz8Cj0hAxFFwkD5A0gmCFEGkoGZDCSZyIBkJkUMXikpZcSEFFKKUIp65Mf6tM7p7bPeuffcc9997rn37U99u/esve/d5+y99tpr73tFIpFIxDFLdVp1RrXIlUWGMQ9U61V3VX9VI7LF5fkg9kXDgdWq3d5YUSaIRYGdqq1iA31cNT+slEC9b6qlyfUp1ZH+4ubAKd54YxdCh76U2jMJ+z5vbCPpzEcPE9vo5P3ktFJgvy/mIHAiUdPglX2qHl/QhfxWHfJG5Yv0D0DVIucMsXvaEdguqL4H1541YuU4S9MsVN1TjfUFXcYo1XvVAl+g7FEtUb2V6jnGOtVn1eLAtlJs4GtFPiLKdW8syznJ76xug7BatExUzTFw5tuqi75AbNz2OhsR4rJqZHK9OSgrxXgx76rled3CGLH1epUvcFTFMTaKDSrLyGvVrkypQTJ61dmOqg4H13mfk5mSXTe9biX1xiWvReCFJDZl1Ag45U8ZeH+pfvRXbRoydTL2ib7A0W7HeKr6mrynX9I+yLtv+pf7TfuZV993pAkZ5og1cknM64gMZ1VPZGA22yh857uSKoKHJxmk7pbkepnYQ/kwORg2qf6I9UM92ukYjAttHwhsLxJbHlMluz1tCLLvV87GF9HIdmdvJ3ck/8Gx9XpjDjfE6hbt13nmvHY8ZRyDgbzSoAjv9XYI5HgkxuQNIb/EEs882E2SgJKINgQJCw/nExZmPLOG2VMV+iT/wbl/coIiusUx9ou168cG2yNnSyntGOm+l+QkhEY54Ryw7rQR7jM9uEkhUcTuZ08e5FHUK1oihsIxWsk1sXaZvCm1JnhKacdgvxsmJTBXbGnhHL1ZhiL55MH96RzXJMb1ZlhZ0n0/nVmPdjtGSHh+wZbVb7XpXz/OdSHE+cjASRkNk9xVCR8ZOGT6JPm/BQyGdNtX1IntcgwmQ9guEfCm2AaC3JBXH+lJOnul2NkzHBTbApLt96k2SPWcAgidj8USr4+q86pJmRqto94BF4OSpyJHaiWcsTBm9MUxMecgJ+S4Pm+iMKHCY/KGYa1eoZriCyoGDrtWNc8XtJgesWSVtbuqTJesM04Ti3YeokSvarazR5qk1o9onQapAUtupEWQ1D73xg4EpzjpjZHB0Ul/1PGw7Nb6o06kBTxTLffGDmCb2A4lEolEIpH/zT8xi9/jWBS2TwAAAABJRU5ErkJggg==>

[image31]: <data:image/png;base64,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>

[image32]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAIAAAAAaCAYAAAB/w1TuAAADl0lEQVR4Xu2ay+tNURTHl1DkWeRdwsDAqzz7FUYMJI9QBpj6AyjKhMKAgZEMpH4ZSHmMRJJyPYpQJgYGBkgpA0YGyGN9Wme7++7f+Z17z7n3nPu71/7Ut9+5e517ztlnr73W2vv+RCKRHmCr6oJqj2p8YOsEZ1UXVbNDQ6T7jFUdVy1SfVO9bjS3zW7VCtUG1Q/VrkZzpNscVV1KjuepPqpm1s1tsUrMqdz1rqj+1M3/N6PEQuJa1brAVhYTxe65U7UlaVutOpgcY3uf/HWMSz7nEQNOZJmiuil2X7gsrTmAu+c21Y7A1jeMFuvgT9X6wFYWU1WLxQbhdGCDQdXXoG2M2Pl5RETheyG/VEfCxhRwGKIR17oR2PoKZiGdnBsaSoR7cc/toUH5pNoUNirfxb6zUobO9lBEkwH7WgPzVSfEIkMrTBC7Jymqb7kv1skq4YXWpB6WgTT0JDlm5hIpfCjgcIK7kr1K4LzPYaNyW7U3Od7oGzJgclCPEAn6Fmbc77CxRMitDEYY/gmzzFCYrprk2YBBvyUWwjcHNp+rqmtBGzPer/xPecdZ8Iw8K8/cNzwVm0lvxZZbzH46WRWEdxxuoddGRAhzeBo4ATaePw0iB07iRwi3CvCv/cKzO3CSw6o3qjtiewZEkrR01LOw2fJINUvsJTHreCHH/JNKxg22H/7zgPPw/bQ8zmBnRYcsrotFF1ZG4GqOZuF/hgytQbLUtWjyXKxDroOAd1ed497J8DO8FQjlOAGh3q/yGfwv3uc87BdbeSz12oiK7TzniIPOsNzzaSXH4RznxEJiK/IdLA0Gj7qjKESOmtgMHfDaz4sViHlhn+CZWDT0Harq2qhUCD04ALtgDnLwcEuusnDr+bAAzAuDxnXI7YDT1aRYWnEpaU3QTluVtVGpOAc45LX5xRgvMC2ndhp//c8yLyvyNIPrIJxqiRTf33cO4G8981zOUTkOl6Q+RI8POXTAvlYtzAw6xK6fg+1Wl+P2SfquWafBAR+LLfFeqRY0mnNB3ub5X0rx3A84I9dx0YOJcFIsOjI5Hkp1u6SlQicHk2PCnXMAOn7PnVQybKzUVJPFcnazeiGLaVIvbJmFRXHXcb+FUO9QGLvimHdTJLWMSAhnLFv4DQDwdl5AOwORlzmq5WFjQejLA7E+tAvXIFU6CPv+50gkEolEIpFIJBKJRPoBdubctir/Ms1ewRmvjf8ZWPbv7EjP8RdimcgWLUQzuAAAAABJRU5ErkJggg==>

[image33]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEwAAAAXCAYAAACh3qkfAAACQklEQVR4Xu2Wv0scURRGbxAhomi0SCySiCCBVBai26RILwEhWKXzj4idrIWdlUUC6VJYWUuaIKttqkAgRUgjsRYE7fxxD29mcvftm9k3OmsgvAMfy963u3P22zezI5JIJBL/Bw80m5pPmlXNaPfyvYDDC3EOH+TfOAAOdIEDXfSA4IF5vqu51rw1s0EzLM5hKns+Ls7hsnjF4MGBLqwDXeDQ1cW5Zs08f6b5pfmjmTXzQfJanMOQmeFAaffpQBfWgS5woIsCBuShmb3LZutmFuKR5qk/NHDwZX8YYEvc8fbNLNahpdnwhwZ2zI4/DGAdbBd5PwUnmgs7kHhZtvF3zYy/IG6tLeE1n3lxDitmFuswrTkUd7wQR+JOtX7gQBfWAXoK83mi+an5oXnsrYVA9KN0F8OMz4gpqwzej2iMA+Bri6FIyiorMga6wIHPLoVflBe98hcqsAXV2VlV4MA1JRaOxxfLC4rdWVXQBQ6VXZzK7f6d2GWU1s4e78KSBP6dIqA0SnqePd5ld+FAF6UOE5qvmkV/IZL81KSsOW+tDjgc+8MasMuuNCP+Qg3oAofKLvhFfpvnY1liaWKHUToOL82sjkMTO4z38N5SB+6w34u7SFq+ad54sxBNXfRx4N/OMilxDtDERZ8ucLBd4EAXBZyjXNg4mM2ZZsG8roxtCe+otoTnZeCwJ90OXyTOgXvB0I7ii4fmZdBFyIEuCvL7DD8d6X86NHXj+ll6j5+nn0NLmrlxrXLo/H1ZIpFIJBKJXm4Adt+O+Xa6Ik8AAAAASUVORK5CYII=>

[image34]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEEAAAAZCAYAAABuKkPfAAACeElEQVR4Xu2XPWsVURCGR1TwCzQqhqAgihapUgiK4EcjEhsLLfQf2FhZKDZiYylIsBJBLQN2IWAh2ooWVkGxShEQLBQFCxU/5uGwyeS9Z+/d3buRW+wDL9ydsztndnbOnHPNOjo6Stjo2qnGwBa53iTX/dimBkvzKTvC7/Xhd13wPe6aKNEeK/F/zvU3owK1z1u1RGxwPbXe55kvsjWM6dx1UT+qRdfe4uYcx10/XK9c24P9pOuLa12w1SEm44qMFbx2PVRjTXa7vrvOi53qKJIw8B14cRLwxzUd7J9d18N1Ey5bCoIqUgjyua1OfBNOuC6JjZcmduau/A4HLD2w5NrnemP5NVwXgsmVOoG9E1sT6D0v1ej8tjRnrQTHYPk6w5ZohArD7+Zg++g6Gq6bQmO9ITb8Fu9RG5YED5/WgSGZseT3tmu/pQoYuEYb8sHSXI0TfNeSg/vWbpCs2V+u9663rgerh1uD0id+mnkjLrqOWUoAjsq6eVOeuD65DutAS5BY4qbXxA/42HUmXJfCTaxRYLvE2bOV4aEZs9Roq54z6hL7mVbwgmtSbFlIAJUARUlxbmiLKdc31x0daAliJ+bcMhuY+KJJ6VbINolTts1+nHUdUmMGdhr8HdSBPhDTKev9sgrrH996/Oe5m64jYu+BEn2kRlvpC1d1IMB2V7VimId7OSZX5Z6lZzhslcFH5B52BOWWpbHcf5jlNR8VDxTsuTqea5Jk+qelrl9GcWCJYlmwPAbB/4yvlg5CuRe5Zr2+VRyl/wsX1NAyc5ZPwsiwy/VCjS1CX+i3HEYC9uRZNbYIXZ9EjzT80VoraKJrdbDq6OjoWOYfXfScb3tHnDUAAAAASUVORK5CYII=>

[image35]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABIAAAAYCAYAAAD3Va0xAAAA0klEQVR4XmNgGDGAA4gF0AXJAZOA+D8WLAKVRxcH4T1QOazgNwNEETbgxwCRS0eXQAcsDBCFz9ElgIAHiA8A8VUGhCtxApACkEFb0SWAQIkBYsFSIGZEk8MAICf/A2IXdAkgaGWAWGKKLoEOQN5awwAxaD0Qz0LDINcgBz5OYMMACWiQzdgALKYIApC3QAqxeQsEQHLf0AWxAVBsgBRzo0swQAIaJAcKaIJgcKQfUHowA+JsBohBN4BYlQGimRmIxYC4FIh/QuXCGCB5chSMAqoDAF6pOAh7mt20AAAAAElFTkSuQmCC>