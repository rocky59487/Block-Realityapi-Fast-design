# Block Reality: 高標準論文級程式碼審核報告 (Paper-Level Code Review Report)

## 摘要 (Abstract)

本報告對 Block Reality 專案（包含 Java 端的渲染與物理引擎，以及 TypeScript 端的 CAD Sidecar）進行了極其嚴謹的高標準程式碼審核。審核範圍涵蓋了演算法穩定度、圖形引擎架構、物理引擎數學嚴謹度、程式碼乾淨度與執行緒安全性。

本次審核**最強烈聚焦於圖形渲染部（Vulkan Engine & GPU Optimization）**。經過深度剖析，我們發現了數個導致「無法成功渲染」的**巨大缺陷 (Critical Flaws)**，特別是在硬體遮蔽剔除 (Hardware Occlusion Culling)、Mesh Shader 的 fallback 機制，以及 Vulkan 記憶體匯出 (External Memory FD) 與 OpenGL 互操作 (Interop) 之間的同步死鎖與對齊問題。

此外，我們也審核了物理力學部 (Mechanics Dept) 的 PFSF Chebyshev 加速演算法與坍方管理員，以及 API/CAD 部門中 QEF Solver 的奇異值分解 (SVD) 實作。總體而言，專案在演算法與數學設計上具備高度學術價值，但在與底層 API 互動的工程實作層面存在嚴重的生命週期與同步漏洞。

---

## 1. 圖形渲染部 (Graphics & Vulkan Engine) - 核心缺陷剖析

圖形引擎的設計企圖融合傳統 OpenGL 管線與現代 Vulkan 光線追蹤 (Ray Tracing) 管線，並引入 Mesh Shader。然而，這部分的程式碼存在嚴重的同步與記憶體管理問題，直接導致了渲染失敗與崩潰。

### 1.1 Vulkan-OpenGL 記憶體互操作 (Interop) 的致命缺陷
在 `BRVulkanRT.java` 中，使用了 `VK_KHR_external_memory_fd` 試圖將 Vulkan 算出的光追結果 (RGBA16F) 匯出給 OpenGL 使用。
* **缺陷 (Critical)**：`exportOutputMemoryFd()` 將 POSIX opaque fd 匯出後，該 fd 的所有權已經轉移給了 GL (OpenGL)。但在 `cleanupOutputImage()` 時，Vulkan 依然強制呼叫 `vkFreeMemory(device, rtOutputImageMemory, null)`。根據 Vulkan 規範，若匯出記憶體被其他 API 綁定，Vulkan 端直接 Free 會導致驅動程式崩潰或 GPU Device Lost。
* **缺陷 (Critical)**：GPU 同步機制缺失。在 `initOutputImage` 雖然建立了 `doneSemaphore` 並匯出，但在實際 `traceRays` 方法的最後，**完全沒有**將該 Semaphore 作為 Signal Semaphore 提交給 `vkQueueSubmit`。這導致 OpenGL 端若使用該 fd 進行 `glWaitSync` 會永久卡死 (Deadlock) 或直接讀到未完成的髒數據 (Tearing)。

### 1.2 硬體遮蔽查詢剔除 (Hardware Occlusion Culling) 的 OpenGL 狀態洩漏
在 `BROcclusionCuller.java` 中，引入了跨幀的遮蔽查詢 (Query) 機制來優化效能。
* **缺陷 (Critical)**：AABB 代理幾何體的繪製使用 `BRShaderEngine.getOcclusionQueryShader()`，但原始碼中並未正確設置 View-Projection 矩陣。這導致所有的遮蔽測試都在 NDC (Normalized Device Coordinates) 空間的原點進行，進而造成「所有方塊都被錯誤判定為遮蔽」或「所有方塊都被錯誤判定為可見」，使剔除系統完全失效或導致畫面全黑。
* **潛在效能漏洞 (High)**：雖然實作了 `QUERY_TIMEOUT_FRAMES` 來強制釋放卡死的 Query Slot，但被超時重置的 `queryIds` 依然在 OpenGL 驅動內部處於 `ACTIVE` 狀態。這會導致 OpenGL 驅動內部資源耗盡 (GL_OUT_OF_MEMORY)。

### 1.3 Mesh Shader 路徑的安全防護不足
在 `BRMeshShaderPath.java` 嘗試探測 `GL_NV_mesh_shader` 擴充功能。
* **缺陷 (Medium)**：在 `renderMeshlets` 方法中，未檢查 `meshletSSBO`、`vertexSSBO` 等緩衝區是否為有效綁定。一旦傳入 `0`，`glBindBufferBase` 將解綁 SSBO，隨後的 `glDrawMeshTasksNV` 會直接觸發 GPU Access Violation (越界記憶體存取)。

---

## 2. 物理力學部 (Physics, Mechanics & Algorithms)

這部分的程式碼品質與數學嚴謹度極高，特別是 Chebyshev Semi-Iterative Method 的實作，展現了論文級的演算法水準。

### 2.1 PFSF Scheduler (Chebyshev 半迭代加速)
* **優點**：在 `PFSFScheduler.java` 中，完整實作了譜半徑估計 (Spectral Radius Estimation) 與動態 $\omega$ 權重調整。發散熔斷機制 (Divergence Breaker) 也考慮到了連續 3 tick 的振盪模式，並優雅地降級回 Jacobi 迭代，數學上非常嚴謹。
* **改善建議 (Low)**：目前的 $\rho_{spec}$ 估計 `(cos(PI / Lmax) * 0.95)` 假設了網格是一個完美的立方體。對於長條形或中空結構，這個頻譜半徑估計過於樂觀，可能會提早觸發發散熔斷。建議引入基於 Power Iteration 的即時頻譜半徑測量。

### 2.2 坍方管理器 (Collapse Manager)
* **優點**：使用了 `ConcurrentLinkedDeque` 保證跨線程安全性，這在 Forge 這種混合事件線程與 Tick 線程的環境中至關重要。
* **改善建議 (Medium)**：在 `enqueueCollapse` 遇到佇列滿載時，直接同步觸發 `triggerCollapseAt` (14 號修正)。如果在 Server Tick 結束前有大量方塊觸發崩塌，會導致瞬間生成成千上萬個 `FallingBlockEntity`，直接造成伺服器 TPS (Ticks Per Second) 崩潰，形成阻斷服務 (DoS)。

---

## 3. API、CAD 工具與演算法 (API, CAD & Sidecar Systems)

TypeScript Sidecar 用於將 Voxel 轉換為 NURBS/STEP 格式，其中 Dual Contouring 使用了二次誤差函數 (QEF) 求解器。

### 3.1 QEF 求解器 (QEF Solver) 的程式碼一致性問題
在 `qef-solver.ts` 中，出現了嚴重的註解與實作不一致現象。
* **缺陷 (High)**：程式碼中的註解聲稱：「Replaces the previous Jacobi iterative solver with a closed-form solution using Cardano's formula... requires only ~30 FLOPs vs ~300 FLOPs」。然而，下方的 `jacobiEigen3x3` 函數實作**依然是傳統的 Jacobi 旋轉迭代 (Jacobi Rotation Iteration)**（使用了 `maxIterations = 50` 的迴圈）。
* **影響**：由於這段程式會在每個 Voxel Cell 中執行，迭代算法將導致網格生成效能比預期慢上近 10 倍，這對即時 Voxel-to-Mesh 轉換是致命的效能瓶頸。

---

## 4. 程式碼乾淨度與架構安全性 (Code Cleanliness & Security)

1. **資源洩漏 (Resource Leaks)**：專案中大量使用 LWJGL 的 `MemoryStack` 和 `MemoryUtil`，大部分都有正確的 `try-with-resources` 包覆。然而，在全域資源（如 VkImage, DescriptorSets）的生命週期管理上，建構與解構的職責散落在多個類別中，違反單一職責原則 (SRP)。
2. **多執行緒競態 (Concurrency)**：物理引擎的部分狀態在 Forge Event 中被觸發，儘管有 `ConcurrentLinkedDeque` 的保護，但在 `BRNetwork.CHANNEL.send` 廣播崩塌特效時，直接在非主線程抓取 `level.getBlockEntity(pos)`，這在 Minecraft 1.20.1 中是不安全的，可能導致 `ConcurrentModificationException`。
3. **錯誤處理 (Error Handling)**：Vulkan RT 的錯誤處理非常優雅，使用了 Fallback 路徑。但降噪器 (Denoiser) 的切換邏輯過於耦合，建議引入 Strategy Pattern 進行重構。

---

## 結論 (Conclusion)

Block Reality 專案在物理與數學演算法上的實力毋庸置疑，達到了頂尖學術標準。然而，在**圖形渲染引擎**方面存在嚴重的工程缺陷。Vulkan 與 OpenGL 的記憶體互操作死鎖、以及 Hardware Occlusion Culling 坐標系錯誤，是**導致無法成功渲染的根本原因 (Root Cause)**。

**修復優先級：**
1. **[P0]** 修復 `BRVulkanRT.java` 中的 Semaphore 提交邏輯，確保 OpenGL 等待 Vulkan 渲染完成，解除 Deadlock。
2. **[P0]** 修正 `BROcclusionCuller.java` 中 AABB 的 MVP 矩陣傳遞，確保遮蔽測試在正確的世界/視圖空間進行。
3. **[P1]** 重寫 `qef-solver.ts` 的 `jacobiEigen3x3`，落實註解中承諾的 Cardano 公式解析解，以解除效能瓶頸。
4. **[P2]** 改善物理引擎坍方管理器的防護機制，防止 `FallingBlockEntity` 癱瘓伺服器。