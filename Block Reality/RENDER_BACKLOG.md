# 渲染部門 Backlog

## 審閱摘要 (2026-04-08)

### 1. [高] ReSTIR DI/GI — 已完成，可從 backlog 移除

**狀態**: 已實作完成

**已完成的工作**:
- `BRReSTIRDI.java` (493 LOC) — 完整 Reservoir 管理（雙緩衝 SSBO，16 bytes/pixel）
  - 三階段 RIS：Initial Candidate (32 samples) → Temporal Reuse (M-cap=20) → Spatial Reuse (16px radius)
  - `restir_di.comp.glsl` — Blackwell 優化 + Ada 降級支援
- `BRReSTIRGI.java` (401 LOC) — 完整 GI Reservoir（2×uvec4 = 32 bytes/pixel）
  - Blackwell: 4 rays/pixel；Ada: 2 rays/pixel
  - `restir_gi.comp.glsl` — Spatial search radius 24px
- `BRReSTIRDITest.java` (374 LOC, 35+ 單元測試) — 全覆蓋
- `BRReSTIRComputeDispatcher.java` — Vulkan dispatch 封裝
- `ReSTIRConfigNode.java` — FastDesign 節點編輯器整合
- `RTRenderPass.RESTIR_DI` / `RESTIR_GI` — 管線排序已整合
- `BRRTSettings` — enableReSTIRDI/GI、temporalMaxM、spatialSamples 全部可調

**VRAM 預算**: DI 63MB + GI 126MB = 189MB @ 1080p（雙緩衝）

---

### 2. [中] DLSS 3/4 — 框架完成，JNI 橋接待實作

**狀態**: 部分完成（Java 層 100%，JNI 0%）

**已完成**:
- `BRDLSS4Manager.java` (376 LOC) — 完整 Java 管理層
  - 5 種模式：Native AA (1.0×) / Quality (0.667×) / Balanced (0.579×) / Performance (0.50×) / UltraPerf (0.333×)
  - Blackwell MFG (1→4 幀) / Ada FG (1→2 幀) / Legacy SR-only 三路分支
  - MFG 曝光修正表（0.97× scale）
- `DLSSConfigNode.java` — 節點編輯器整合
- `RTRenderPass.DLSS_SR` / `DLSS_MFG` / `DLSS_FG` — 管線排序已整合
- `BRFSRManager.java` (519 LOC) — FSR 1.0 作為 AMD/Legacy fallback 已完整實作

**待完成**:
- [ ] NVIDIA Streamline SDK JNI 綁定（`sl.interposer.dll` 載入）
- [ ] `BRNativeLibrary.loadStreamline()` 實作
- [ ] JNI 層 evaluate() 呼叫 — 目前 `attemptDLSSInit()` 返回 false
- [ ] 4K VRAM 壓力測試（MFG 需要 4× motion vector buffer）

**預估工作量**: ~2 週（JNI + Streamline SDK 整合 + 測試）

---

### 3. [低] SSSR 手機端移植評估 — 未開始

**狀態**: 僅有 UI Node stub

**現況**:
- `SSRNode.java` (36 LOC) — 只有參數定義（maxDistance、maxSteps、binarySteps、thickness、fadeEdge）
- `evaluate()` 返回靜態 `hitRate = 0.0f` — 無 GPU 實作
- Blackwell 管線已用 ReSTIR GI 取代 SSR — 桌面端不需要

**評估結論**:
- 手機端無 RT 能力，SSSR (Hi-Z tracing) 是唯一可行的反射方案
- 建議基於 Stochastic SSR (Tomasi 2021) 實作：半解析度、temporal reprojection、8-32 march steps
- 優先級可維持 [低] — 等 HBAO Android 移植確認可行後再啟動

---

## 待辦事項（更新）

1. ~~[高] ReSTIR DI/GI~~ → **已完成** (移至「已完成」)
2. [中] DLSS 3/4 JNI 橋接
3. [低] SSSR 手機端移植評估
4. [低] HBAO Android 效能評估（來自 REQUESTS.md）

## 已完成
- ReSTIR DI/GI 整合（完整 Java + GLSL + 測試 + 節點 + 管線排序）
- FSR 1.0 整合（完整 EASU + RCAS）
- NRD 降噪整合（ReBLUR / ReLAX / SIGMA）
- P7-C Buffer 分配
- Mesh Shader 支援檢查與更新
- Occlusion Culling 改善
