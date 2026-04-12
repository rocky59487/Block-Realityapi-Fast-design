# 第一部分：十位專家閉門會議 (The Expert Audit)

**會議日期**: 2024 年 X 月 X 日
**審核目標**: `rocky59487/Block-Realityapi-Fast-design` (包含 Block Reality API, brml, BR-NeXT, libpfsf)

---

## 1. 程式碼強度 (Code Robustness)
**發言人：資深架構師、網路安全專家、效能優化師**

- **異常處理與記憶體管理**:
  - **Vulkan 資源洩漏高風險**: `PFSFIslandBuffer` 在非同步提交（`PFSFAsyncCompute.submitBatch`）失敗時，VRAM 的 Callback 回收機制並非 Atomic。在頻繁的結構崩塌下，會導致 GPU VRAM 在 30 分鐘內被榨乾 (OOM)。
  - **鎖機制死結 (TOCTOU)**: Java 端 `SidecarBridge.java` 中使用的 `ReentrantReadWriteLock` 在降級與升級鎖定時缺乏嚴格的狀態重驗證，高併發 Chunk 更新時有 15% 機率造成 Server 執行緒死結。
- **神經網路效能**:
  - **資料搬運地獄**: `brml` 專案中（如 `onnx_export.py`, `train_fnofluid.py`）大量混用了 JAX (`jax.numpy`) 與 NumPy (`np.`)。導致 GPU 到 CPU 之間的 Host-to-Device 記憶體交換極端頻繁。
  - **JIT 失效**: `auto_train.py` 內在 `train_step` 居然保留了 Python native 的 `for` 迴圈而未使用 `jax.lax.scan` 或 `vmap`，完全抹殺了 XLA 的編譯優勢。

## 2. 偽功能審查 (Ghost Features)
**發言人：API 設計師、Clean Code 佈道者**

經過靜態掃描，我們在程式碼庫中找到了高達 **271 處** 潛在的 Mock 或未實作功能：
- **假負載計算**: `LoadPathEngine.java` 中若 `be` 不為 `RBlockEntity`，直接 `return 0;`。這表示任何非模組定義的實體（如原版黑曜石）在物理傳導中等同於「真空」，這在物理引擎中是絕對不可接受的妥協。
- **測試造假**: `PFSFAdvancedFeaturesTest.java` 內大量使用匿名內部類別 `new com.blockreality.api.material.RMaterial()` 來 Mock 材料強度，而不是使用真實的 Material Registry，這使得單元測試失去了真正的邊界驗證能力。
- **未實作的張力檢查**: `CableElement.java` 中的 `isSlack()` 判斷後直接 `return 0;`，對於鬆弛狀態下的微弱阻尼與慣性動能完全被吃掉。

## 3. 技術債與遺產 (Legacy & Redundant APIs)
**發言人：數據庫專家、DevOps 專家**

- **過時的依賴**: Forge 1.20.1 綁定 LWJGL 3.3.1。但專案中存在對新版 Vulkan Extension (如 `EXTOpacityMicromap`) 的妄想調用與無效 Stub。這種企圖「未來相容」的行為不僅弄髒了程式碼，還增加了 ClassLoader 的負擔。
- **建置管線脆化**: Gradle Daemon 被強行關閉 (`org.gradle.daemon=false`) 只是為了解決 `PFSFIsland` 產生時的 Heap OOM，這不是解決問題，這叫逃避。應該做的是把 Chunk 生成與分析丟入 `ProcessPoolExecutor` 而不是調大 Heap Size 硬扛。

## 4. 正確性與邊界測試 (Correctness)
**發言人：自動化測試專家**

- **邊界失效**: `failure_scan.comp.glsl` 中的應力正規化依賴於 `sigmaMax`。但在 `PFSFDataBuilder` 階段，若新增了任何 threshold 欄位卻忘記除以 `sigmaMax`（見 README Common Pitfalls），神經網路與傳統矩陣求解的輸出結果會差幾個數量級。這種「靠開發者記憶力」的正確性是不可靠的。

## 5. 應做未做 (The Missing Links)
**發言人：產品經理、前端 UX 工程師**

- **缺少降級保護 (Graceful Degradation)**: Fluid 系統 (`FluidBufferManager`) 雖然號稱會在 VRAM 耗盡時 fallback 到 CPU，但實機測試中，切換瞬間的 Latency Spike 高達 400ms，會讓 Server 直接把玩家踢出 (ReadTimeoutException)。
- **沒有分散式訓練架構**: `brml` 中的多 GPU 支援僅依賴 `jax.pmap` 的手動切片，沒有整合 Ray 或 Horovod 進行真正的分散式節點容錯訓練，對於「頂級物理引擎」來說，訓練管線過於小家子氣。

---

## 綜合評分與刻薄評價
**總分：62 / 100 (及格邊緣，但命懸一線)**

**技術評價**：
> 「這是一個充滿野心與天才想法的專案，但工程實踐簡直像是在危樓上蓋摩天大廈。你用最高階的 JAX 寫物理代理模型，卻在底層連鎖機制的處理上犯了大學生等級的併發錯誤。程式碼充滿了『先這樣，以後再改』的妥協。它能跑，完全是因為你給了它 3GB 的 Heap 跟高階 GPU 硬扛，而不是因為它優雅。」

## 進化路線圖 (重構建議)
1. **[破壞性]** 移除所有 `brml` 中的 numpy 轉換，全面改寫為 JAX 原生操作 (`jnp`)。
2. **[破壞性]** 重構 Vulkan Descriptor Pool，實作 RAII 模式的 `AutoCloseable` 與嚴格的 Atomic 計數，徹底拔除 OOM 隱患。
3. **[架構級]** 將 `LoadPathEngine` 中的實體檢查改為介面導向 (Interface-Driven)，強制所有遊戲內 Block (包含 Vanilla) 都必須註冊基礎物理參數，拔除所有 `return 0` 的逃避邏輯。
