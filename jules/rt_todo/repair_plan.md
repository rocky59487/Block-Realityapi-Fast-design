## rt 修復方案 [2026-04-08 17:56:00]

### 優先級1 (立即修復)
1. [ForgeRenderEventBridge.java:55] 失效描述: 渲染事件未觸發 RT 管線 (Phase 4-F 移除後未恢復) → 在 `onRenderStage` 函式中，加入判斷如果是 `RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS` 等適當階段，就呼叫 `BRRTCompositor.getInstance().executeRTPass()` 與 `BRRTPipelineOrdering.dispatchFrame()`。 (預期效果: 恢復每幀觸發 RT 渲染管線調度)
2. [BRRTCompositor.java:79] 失效描述: `RT_PIPELINE_REMOVED` 強制為 true，導致 `init` 方法提早返回。 → 將 `private static final boolean RT_PIPELINE_REMOVED = true;` (或相關判斷) 修改為 `false`，並在必要的地方正確呼叫 `init` (預期效果: 確保 `VkContext`, `BRVulkanRT` 及各相關元件能成功完成初始化，不再靜默失敗)

### 優先級2 (影響較小)
3. [BRVulkanRT.java:369] 失效描述: 初始化未被外部連結 -> `BRVulkanRT.init()` 的呼叫未正確執行。 → 確保 `VkRTPipeline.init` 會繼續呼叫或重新連接。 (預期效果: 初始化 Vulkan 管線及分配 SBT)

### 執行順序建議
先修 [BRRTCompositor.java:79](解鎖RT初始化) → 修 [ForgeRenderEventBridge.java:55](連接渲染迴圈) → 驗證 [BRVulkanRT.java]
