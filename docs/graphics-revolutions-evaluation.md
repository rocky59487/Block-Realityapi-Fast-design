# Block Reality：次世代圖學革命技術評估報告

**日期:** 2024-04-04
**目標:** 評估三大近期圖學革命技術（3D Gaussian Splatting、SDF Ray Marching、AI Neural Reconstruction）應用於 Block Reality (Minecraft Vulkan 客製化光追渲染引擎) 的可行性與架構契合度。

---

## 1. 革命一：3D Gaussian Splatting (高斯潑濺)

### 1.1 技術簡介
高斯潑濺是一種全新的 3D 場景表示法，它完全捨棄了傳統的「多邊形網格 (Triangle Mesh)」。場景由數以百萬計帶有位置、顏色、透明度、旋轉與縮放屬性的「3D 高斯橢球」組成。透過極高效率的 GPU 排序 (Sorting) 與 Splatting，可以直接渲染出具有高度照片寫實感的場景，完全不需要傳統光柵化管線中的頂點與多邊形計算。

### 1.2 Block Reality 適用性評估：低 ～ 中等
* **優勢**：如果未來有將「真實世界場景掃描」導入 Minecraft 的需求，高斯潑濺能提供無與倫比的寫實感與渲染速度。
* **劣勢與挑戰**：
  * **幾何特性衝突**：Minecraft 本質上是具有極度銳利邊緣（Hard Edges）的方塊（Voxel）世界。高斯潑濺在處理這類完美的銳利幾何結構時，往往需要堆疊極大量的高斯球來逼近邊緣，反而喪失了其效能與儲存優勢。
  * **管線重構成本**：Block Reality 目前已經具備成熟的 `SparseVoxelOctree` 與硬體光線追蹤 (Hardware RT) 管線。引入高斯潑濺意味著必須建立一套全新的基於 Compute Shader 的 Rasterization / Sorting 管線，無法輕易與現有的光影（如 ReSTIR、GI）整合。

### 1.3 結論
**目前不建議**作為 Block Reality 核心光影管線的升級方向，其特性更適合點雲掃描與平滑幾何的重建，而非方塊沙盒遊戲。

---

## 2. 革命二：SDF (符號距離場) 光線行進 (Ray Marching)

### 2.1 技術簡介
SDF（Signed Distance Field）不使用「面」來構成世界，而是用數學函數（或體積紋理）來定義空間中任意一點「距離最近表面有多遠」。當進行光線追蹤時，光線可以根據這個距離值「大步邁進 (Sphere Tracing)」，跳過大量無實體的空間，極大地加速求交測試（Intersection Testing）。Unreal Engine 5 的 Lumen 系統在軟體光追模式下正是依賴 SDF 來實現極速的全域光照計算。

### 2.2 Block Reality 適用性評估：極高 (優先推薦)
* **優勢與契合度**：
  * **完美的沙盒契合**：Block Reality 已經擁有強大的體素資料結構（`SparseVoxelOctree`）。將體素結構轉換或擴展為宏觀的 SDF 場是非常直觀的。
  * **極致的 Compute Shader 效能**：在 Vulkan Compute Shader 中，對 SDF 進行 Ray Marching 來計算全域光照（GI）、環境光遮蔽（AO）或柔和陰影（Soft Shadows），其效能往往遠勝於對龐大網格發射硬體光線（Hardware RT）。
  * **混合渲染潛力**：可以設計一套混合管線——近處高精度物件使用硬體 RT（取得精確的鏡面反射與銳利陰影），而遠處大範圍的 GI 採樣則使用 SDF Ray Marching，這將是對效能的「降維打擊」。
* **挑戰**：
  * **動態更新成本**：當玩家頻繁破壞或放置方塊時，局部 SDF 的即時更新（Update）會帶來運算壓力。需要設計非同步更新或層次化 (Hierarchical SDF) 的機制來確保穩定的 Frame Rate。

### 2.3 結論
**強烈建議導入**。SDF 光線行進是當前對 Block Reality 客製化渲染引擎最直接、最具潛力的架構升級。

---

## 3. 革命三：AI 像素幻覺 (Neural Reconstruction / DLSS 3.5)

### 3.1 技術簡介
傳統光線追蹤的瓶頸在於需要發射海量光線（高 spp）才能消除噪聲。AI Neural Reconstruction（如 NVIDIA DLSS 3.5 Ray Reconstruction）的革命在於：只發射極少量光線（例如 1 spp），得到一張充滿噪聲的畫面。接著將這張噪聲圖連同 G-Buffer (深度、法線、運動向量) 餵給 GPU 的 Tensor Cores，讓訓練有素的 AI 神經網路在毫秒內「腦補（幻覺）」出完美無瑕的高解析度光影細節。

### 3.2 Block Reality 適用性評估：高，但具備硬體與工程挑戰
* **優勢**：
  * **畫質與效能的飛躍**：Block Reality 目前依賴 ReSTIR 與 FSR 進行光影計算與升頻。若能成功整合 AI 降噪，將能徹底解決 Minecraft 全域光照在低光環境下的閃爍（Ghosting）與噪點問題，達到真正的路徑追蹤（Path Tracing）電影級畫質。
* **劣勢與挑戰**：
  * **硬體強綁定**：這類技術通常依賴特定的硬體加速（如 NVIDIA Tensor Cores 與 NGX SDK）。這會使得該功能成為特定顯示卡的專屬特性，無法做到全平台通用。
  * **管線整合難度**：在客製化 Vulkan 引擎中接入專有 SDK 需要精確準備各種 G-Buffer，並妥善處理歷史幀資料。同時，系統必須維持兩套降噪管線（通用降噪與 AI 降噪）以兼顧不同硬體的玩家。

### 3.3 結論
**高度推薦作為「旗艦級渲染選項」**。雖然具有硬體限制與實作門檻，但在技術成熟且行有餘力的情況下，引入 AI Ray Reconstruction 將能使 Block Reality 的光影表現達到業界頂尖水準。

---

## 4. 總結與未來研發建議

綜合上述分析，對 Block Reality (Rendering Dept) 的未來發展建議如下：

1. **短期至中期（SDF Ray Marching 整合）**：
   - **優先級：最高**。
   - 投入研發如何從目前的 `SparseVoxelOctree` 衍生出支援即時更新的 SDF 資料結構。
   - 嘗試在 Vulkan Compute Shader 中實作 SDF Ray Marching，用於加速大尺度環境的 GI 採樣與 AO 計算，作為硬體 BVH 追蹤的輔助與最佳化。
2. **長期（AI Neural Reconstruction 接入）**：
   - **優先級：中高**。
   - 在底層管線穩定後，評估接入 NVIDIA NGX SDK 的工程成本，嘗試以 AI 降噪取代或輔助現有的空間/時間降噪器，提供給高階顯卡使用者極致的視覺體驗。
3. **暫停評估（3D Gaussian Splatting）**：
   - **優先級：最低**。
   - 除非遊戲未來改變核心玩法，引入大量非方塊的掃描建模，否則不符合目前的體素幾何架構，不建議投入資源重構渲染管線。
