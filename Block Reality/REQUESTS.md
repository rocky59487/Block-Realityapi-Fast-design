# 新增請求

## 審閱摘要 (2026-04-08)

以下為所有現有功能請求的技術審閱結果。

---

### 1. 評估 HBAO 在 Android 端的實作效能

**狀態**: 需要新功能開發
**優先級**: 低
**預估工作量**: 中（~2-3 週）

**現況分析**:
- 專案**無 HBAO 實作**。現有 AO 系統為：
  - `VkRTAO` — Ray-Query AO（需 Vulkan RT 擴展，桌面專用）
  - `SSAO_GTAONode` — Screen-Space GTAO（Vulkan Compute，可移植性較高）
  - `sdf_gi_ao.comp.glsl` — SDF Sphere-Tracing AO（Vulkan Compute）
- Android 端**缺乏硬體 RT 支援**（Adreno/Mali/PowerVR 均無 `VK_KHR_ray_query`）
- Minecraft Forge 1.20.1 無官方 Android 移植

**建議方案**:
1. **優先評估 GTAO 移植**而非從零開發 HBAO — `SSAO_GTAONode` 已有完整參數化（kernel 4-64、radius、falloff），改寫為 GLES 3.2 Compute Shader 可行性高
2. 若堅持 HBAO，建議實作 HBAO+ (Bavoil & Sainz 2008)：4-6 方向 × 4 步進，半解析度（1/2×1/2），FP16 精度
3. 目標硬體：Snapdragon 888+ (Adreno 660)，1280×720 @ 30fps，AO pass < 2ms

**前置依賴**: 需先建立 `BRRenderTier.MOBILE` 配置層級，將 RT 效果全部關閉

---

### 2. 測試 Adaptive Resolution Scaling 與 MSAA 的效能對比

**狀態**: 兩者皆未實作
**優先級**: 低
**預估工作量**: 中（各 ~1 週）

**現況分析**:
- **Adaptive Resolution Scaling (ARS)**: 零實作。目前解析度由 DLSS/FSR 模式靜態決定，無逐幀動態調整
- **MSAA**: 零實作，且**架構上刻意省略** — NRD 時序降噪器（ReBLUR/ReLAX）與 MSAA 不相容（歷史緩衝會產生混疊失配）
- DLSS SR + FSR EASU 已提供等效的時序抗鋸齒

**建議方案**:
1. ARS 建議實作於 `BRRenderSettings`：基於前幀 GPU 時間（`vkGetQueryPoolResults`）動態調整內部解析度（0.5×-1.0×），搭配 FSR RCAS 銳化補償
2. MSAA 評估可直接跳過 — 在有 DLSS/FSR/NRD 的管線中，MSAA 增加 VRAM 消耗（2-4× framebuffer）卻無法與時序降噪協同
3. 若仍需對比數據，建議以 **ARS + FSR vs 固定解析度 + DLSS** 為更有意義的對比基準

**結論**: MSAA 與現有渲染架構不相容，建議將此請求改為「ARS + FSR 動態品質評估」

---

## 原始請求（保留）

- 評估 HBAO 在 Android 端的實作效能。
- 測試 Adaptive Resolution Scaling 與 MSAA 的效能對比。
