# 風格節點

> 所屬：L1-fastdesign > L2-node-editor

## 概述

風格節點（光影預設子分類）提供電影級後處理特效，實現獨特視覺風格，共 7 個節點。

## 風格特效 (postfx/style/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `FilmGrainNode` | render.postfx.FilmGrain | 膠卷顆粒 — 類比底片紋理 |
| `ChromaticAberrationNode` | render.postfx.ChromaticAber | 色差 — RGB 通道分離效果 |
| `VignetteNode` | render.postfx.Vignette | 暗角 — 邊緣漸暗 |
| `ColorGradingLUTNode` | render.postfx.ColorGradingLUT | 色彩分級查表 — 調色工具 |
| `OutlineNode` | render.postfx.Outline | 輪廓 — 物件邊界檢測 |
| `PixelArtNode` | render.postfx.PixelArt | 像素藝術 — 像素化效果 |
| `CRTNode` | render.postfx.CRT | CRT 掃描線 — 復古螢幕效果 |

### 輸入/輸出範例

| 節點 | 主要輸入 | 主要輸出 |
|------|--------|--------|
| FilmGrain | enabled, intensity(0~1), size(0.5~3) | grainnedTexture |
| ChromaticAber | enabled, intensity(0~0.2), direction(VEC2) | aberratedTexture |
| Vignette | enabled, intensity(0~1), smoothness(0~1) | vignettedTexture |
| ColorGradingLUT | enabled, lutTexture(TEXTURE), intensity(0~1) | gradedTexture |
| Outline | enabled, thickness(0.5~3), color(COLOR) | outlinedTexture |
| PixelArt | enabled, pixelSize(1~32), threshold(0~1) | pixelatedTexture |
| CRT | enabled, scanlineIntensity(0~1), curvature(0~1) | crtTexture |

## StylePreset 整合

這些風格節點與 [BidirectionalSync](L3-binding.md) 中的 `applyStylePreset()` 方法搭配使用，實現風格預設快速套用機制：

```java
// 使用者選擇預設 → BidirectionalSync.applyStylePreset()
// 掃描預設的 portOverrides 並批量設定對應節點埠值
// 自動推送到執行時系統
```

## 關聯接口
- 依賴 → API 層後處理特效管線（`RenderPipeline`）
- 被依賴 ← [StylePresetRegistry](../L2-command/) — 預設管理與套用
- 配合 ← [BidirectionalSync](L3-binding.md)（風格預設→節點圖推送）
- 參考 ← [L3-render-nodes.md](L3-render-nodes.md)（後處理特效父分類）
