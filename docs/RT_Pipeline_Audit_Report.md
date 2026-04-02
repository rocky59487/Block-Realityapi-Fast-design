# Block Reality - RT 渲染管線全方位檢測報告

這份報告針對 `Block Reality/api/src/main/java/com/blockreality/api/client/render/rt` 目錄下先進的光線追蹤渲染管線進行了深度審查。審查範圍涵蓋：效能 (Performance)、真實性 (Realism) 與代碼強度 (Robustness)。

---

## 1. 效能與資源管理 (Performance & Resource Management)

### ✅ 優勢：零拷貝跨 API 互操作
新的 `BRVKGLSync` 實作了 `GL_EXT_memory_object_fd` 與 `GL_EXT_semaphore_fd`，實現了 Vulkan 與 OpenGL 之間的零拷貝與無 CPU 阻塞的 GPU Semaphore 同步。這取代了 `BRVulkanInterop` 的 CPU PBO Readback 瓶頸，是提升幀率的一大突破。

### ⚠️ 隱患 A：Vulkan 記憶體與描述符洩漏 (Memory Leaks)
在 `BRVulkanDevice.java` 中發現了頻繁的 Vulkan 資源分配（例如 `allocateCommandBuffer`、`allocateDescriptorSet`、`vkAllocateMemory`），但相對應的 `free` 或銷毀調用在生命週期管理中卻不夠對稱：
- **Descriptor Sets**：雖然有 Pool，但動態綁定的 Descriptor Set 似乎沒有被顯式回收或隨 Frame 重置。
- **Command Buffers**：`beginSingleTimeCommands` 被大量使用於諸如 BVH 構建、紋理上傳等。如果在每幀渲染循環內（例如 Fallback 路徑）頻繁調用，會造成 Driver 的龐大開銷甚至 OOM (Out Of Memory)。
- **建議**：引進 `FrameGraph` 或基於環形緩衝區 (Ring Buffer) 的 Frame 級資源池，並利用 `vkResetCommandPool` 來批次清理每幀產生的暫時資源。

### ⚠️ 隱患 B：Fallback 路徑中的 CPU-GPU Stall
在 `BRVulkanRT.java`（Legacy/Fallback 路徑）中：
```java
// Submit and stall (per-frame readback — acceptable on Fallback path)
vkQueueWaitIdle(vkQueue);
```
這會在每一幀強制 CPU 等待 GPU 完成所有工作，嚴重破壞並行管線，導致 Frametime 暴增。即使是 Fallback 路徑，也應該透過雙緩衝 (Double-buffering) 和 Fences 進行非同步讀回。

---

## 2. 光線追蹤加速結構 (BVH & Acceleration Structures)

### ✅ 優勢：攤提成本的 TLAS 重建與 OMM 支援
- `BRVulkanBVH` 中使用了 `MAX_BLAS_REBUILDS_PER_FRAME` 的邏輯。當方塊被破壞或放置時，並不會在一幀內阻塞性地重建所有 `SectionBLAS`，而是將更新壓力平攤，這對 Minecraft 這樣的體素遊戲極為關鍵。
- `BROpacityMicromap` 完全支援 Ada 架構的 Phase 3 OMM 擴充，使用 2-state/4-state micro-triangles 加速透明方塊（如樹葉、玻璃）的 Any-Hit shader 遍歷。

---

## 3. 光照演算法與真實性 (Lighting & Realism)

### ✅ 優勢：前沿的 GI 與 DI 採樣技術
- **ReSTIR DI & GI**：模組內實作了基於 Reservoir 的採樣器，支援空間與時域重用 (Spatiotemporal Reuse)。`GIReservoir` 的資料結構已經緊湊化至 32 bytes/pixel，這能在不使用海量光線的情況下，給出平滑且真實的二次反彈光效。
- **Light BVH**：`BRRTEmissiveManager` 將所有發光實體（如火把）封裝成一棵 `Light BVH`，讓 Fragment Shader 能夠以 $\mathcal{O}(\log N)$ 的複雜度進行光源採樣，這對於夜間或地底場景的效能表現是決定性的。

### ⚠️ 隱患 C：DDGI Probe 漏光與更新策略
- `BRDDGIProbeSystem` 採用了 Scrolling Grid 來對齊玩家周遭的 Probe，並且使用了攤提更新（每幀預設更新 20%）。
- **真實性考量**：DDGI 最著名的問題是「漏光 (Light Leaking)」（例如牆外的光透進暗室）。程式中提到了 `visibilityAtlas` (雙重深度 Chebychev)，需要確保 `ddgi_update.comp.glsl` 中有嚴謹的深度偏移 (Depth Bias) 與法線過濾，否則在厚度僅 1 格的方塊牆壁處容易穿幫。

---

## 4. 降噪與升頻 (Denoising & Upscaling)

### ✅ 優勢：完整的後處理管線
- 整合了 FSR (FidelityFX Super Resolution) (`BRFSRManager.java`) 與未來的 DLSS 接口，確保玩家能在中階顯示卡上開啟光追並保持可玩的幀率。
- 具備針對不同硬體分級的 Fallback 機制（從最頂尖的 SER/Cluster BVH 降級至標準 RT Pipeline，再到 Compute Shader Fallback）。

---

## 總結與建議 (Actionable Advice)

1. **重構資源生命週期 (Robustness)**：強烈建議為 `BRVulkanDevice` 引入一個 `VulkanResourceManager`，專門追蹤 `VkBuffer`, `VkImage`, `VkDeviceMemory` 並且綁定幀序號 (Frame Index)。當幀渲染完成時，自動釋放 N-2 幀前未被持有的資源，以徹底根除記憶體洩漏風險。
2. **消滅 vkQueueWaitIdle (Performance)**：移除所有渲染循環內的 WaitIdle，即便是 Fallback 或 CPU Readback，也應使用 `VkFence` 非同步檢查。
3. **DDGI 漏光優化 (Realism)**：檢查 DDGI Shader，對於高頻率、薄牆體的體素世界，考慮縮小 Probe 間距或增加 Ray Backface 剔除的嚴格度，來減少光子錯誤擴散。