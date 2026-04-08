## vulkan 修復方案 [$(date +"%Y-%m-%d %H:%M:%S")]

### 優先級1 (立即修復)
1. [BRSDFRayMarcher.java:174] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 在第174行加入 `MinecraftForge.EVENT_BUS.post(new SDFRayMarchEvent(this));` 或補齊 `vkCmdDispatch(cb, groupsX, groupsY, 1);` (恢復 SDF Ray Marching 計算的排程與分派)
2. [FluidJacobiRecorder.java:65] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 取消第61-68行的註解，恢復 `vkCmdDispatch(cmdBuf, gx, gy, gz);` 與 `computeBarrier(cmdBuf);` (恢復流體 Jacobi 計算與資料同步)
3. [BRNRDNative.cpp:184] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 取消註解第184行的 `nrd::Dispatch(*inst->denoiser, &dispatch, 1);` (恢復 NVIDIA NRD SDK 降噪分派)

### 優先級2 (影響較小)
4. [jacobi_solver.cpp:58] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 在第58行加入 `vkCmdDispatch(cmdBuf, (buf.getN() + WG_RBGS - 1) / WG_RBGS, 1, 1);` 取代 TODO 註解 (修復 C++ RBGS 求解分派)
5. [phase_field.cpp:63] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 在第63行加入 `vkCmdDispatch(cmdBuf, (N + WG_SCAN - 1) / WG_SCAN, 1, 1);` 取代 TODO 註解 (修復 C++ 相位場斷裂演算法分派)
6. [BRDDGIComputeDispatcher.java:263] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 在第263行附近加入 `MinecraftForge.EVENT_BUS.post(new DDGIUpdateEvent());` 或確認外部事件綁定 (修復 DDGI 探測網格更新觸發)
7. [BRReSTIRComputeDispatcher.java:242] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 在第242行附近加入 `MinecraftForge.EVENT_BUS.post(new ReSTIRDispatchEvent());` (確保 ReSTIR DI/GI 階段被觸發)
8. [BRSDFVolumeManager.java:467] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 在 `dispatchJFA()` 第467行實作 JFA pass 的實際 `vkCmdDispatch` 與屏障邏輯 (修復 JFA SDF 生成)
9. [VkRTAO.java:538] ❌ 事件未觸發 (發現被註解的事件觸發器) -> 在第538行附近加入對應的 Forge 事件 `MinecraftForge.EVENT_BUS.post(new RTAODispatchEvent());` (確保 AO 渲染能被主循環調用)

### 執行順序建議
先修 BRSDFRayMarcher(解鎖基本渲染流程) -> 修 FluidJacobiRecorder(解鎖流動物理) -> 修 BRNRDNative(解鎖降噪優化) -> 驗證 jacobi_solver 與 phase_field(確保後端效能)
