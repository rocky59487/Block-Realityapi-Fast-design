# 後製特效系統

> 所屬：L1-api > L2-render

## 概述

Block Reality 的後製特效系統提供多項螢幕空間渲染技術，包含全域光照、動態模糊、次表面散射、自動曝光、色彩分級與各向異性反射等，均作為 Composite Pass 在延遲管線中執行。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BRGlobalIllumination` | `client.render.postfx` | SSGI 螢幕空間全域光照 |
| `BRMotionBlurEngine` | `client.render.postfx` | Per-Pixel Velocity Buffer 動態模糊 |
| `BRSubsurfaceScattering` | `client.render.postfx` | 螢幕空間次表面散射 |
| `BRAutoExposure` | `client.render.postfx` | GPU Compute 自動曝光（64-bin 亮度直方圖） |
| `BRColorGrading` | `client.render.postfx` | 色彩分級（程序化 3D LUT） |
| `BRAnisotropicReflection` | `client.render.postfx` | 各向異性反射（金屬拉絲紋理） |
| `BRParallaxOcclusionMap` | `client.render.postfx` | 視差遮蔽映射 |
| `BRDebugOverlay` | `client.render.postfx` | 除錯覆蓋層 |

## BRGlobalIllumination (SSGI)

- 半解析度運算（效能友好）
- 從 GBuffer albedo + normal + depth 取樣
- 射線 march 半球方向取樣間接光
- Temporal accumulation 減少噪聲
- 獨立 FBO 儲存 GI 結果（RGBA16F）

## BRMotionBlurEngine

- 獨立 FBO 存放 per-pixel 運動向量（RG16F）
- 使用 current vs previous frame viewProj 計算 screen-space velocity
- 支援相機運動 + 物件運動

## BRSubsurfaceScattering

- 可分離高斯模糊近似（Separable SSS）
- Burley diffusion profile 近似
- 雙 pass（水平 + 垂直）
- 深度感知核心，避免跨物體邊界出血
- 適用方塊：樹葉、冰塊、蜂蜜塊、黏液塊、蠟燭

## BRAutoExposure

- GPU Compute Shader 兩階段：
  1. 建立 64-bin 亮度直方圖
  2. 排除高低百分位後計算平均亮度
- 結果透過 SSBO 回傳 CPU，供 Tone Mapping 使用
- 亮度範圍：log2(1/16) ~ log2(65536)

## BRColorGrading

- 程序化 32x32x32 3D LUT 紋理
- Lift / Gamma / Gain 三區色彩校正
- 色溫（Kelvin）+ 色調（Tint）
- 飽和度 + 對比度
- 時段自適應（日出偏暖金、正午中性、黃昏偏紫粉、夜晚偏冷藍）
- Lazy update — 參數變化時才重新生成

## BRAnisotropicReflection

- GGX Anisotropic BRDF（Burley 2012 / Heitz 2014）
- GBuffer tangent 向量
- 各向異性比由 material ID 查表
- 適用方塊：鑽石塊、鐵塊、金塊、銅塊、紫水晶簇

## 關聯接口

- 依賴 → [BRFramebufferManager](L3-pipeline.md) — FBO 讀寫
- 被依賴 ← [RenderPipeline](L3-pipeline.md) — Composite Pass 排程
