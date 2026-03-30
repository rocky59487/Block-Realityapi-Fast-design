# 渲染管線核心

> 所屬：L1-api > L2-render

## 概述

Block Reality 固化渲染管線的核心元件，包含 Pass 排程器、Framebuffer 管理、多視口相機系統與硬體分級偵測。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BRRenderPipeline` | `client.render.pipeline` | 頂級渲染引擎，12-pass 延遲管線排程 |
| `BRFramebufferManager` | `client.render.pipeline` | FBO 管理器（Iris main/alt swap） |
| `BRViewportManager` | `client.render.pipeline` | Rhino 風格多視口相機系統 |
| `BRRenderTier` | `client.render.pipeline` | GPU 硬體分級偵測（Tier 0~3） |
| `RenderPass` | `client.render.pipeline` | 渲染 Pass 枚舉（12 個階段） |
| `RenderPassContext` | `client.render.pipeline` | 單一 Pass 的執行上下文 |

## RenderPass 管線順序

```
SHADOW → GBUFFER_TERRAIN → GBUFFER_ENTITIES → GBUFFER_BLOCK_ENTITIES
→ GBUFFER_TRANSLUCENT → DEFERRED_LIGHTING → COMPOSITE_SSAO
→ COMPOSITE_BLOOM → COMPOSITE_TONEMAP → FINAL → OVERLAY_UI → OVERLAY_EFFECT
```

## BRFramebufferManager

管理所有 FBO，採用 Iris 的 main/alt swap 模式：

| FBO | 說明 |
|-----|------|
| Shadow FBO | 深度附件，2048x2048 |
| GBuffer FBO | 5 色彩附件（position/normal/albedo/material/emission）+ 深度 |
| Composite Main/Alt | 雙 ping-pong buffer（HDR RGBA16F） |
| TAA History | 前一幀渲染結果（temporal reprojection） |

### 核心方法

- `init(w, h)` — 建立所有 FBO
- `resize(w, h)` — 視窗大小變更時重建尺寸相關 FBO
- `swapCompositeBuffers()` — composite pass 間切換 main/alt
- `copyToTaaHistory(srcTex)` — GPU-side 快速複製（glBlitFramebuffer）

## BRViewportManager

支援 SINGLE、DUAL_HORIZONTAL、DUAL_VERTICAL、QUAD 四種佈局模式。
每個視口擁有獨立 FBO、相機實例和投影矩陣（透視/正交頂視/正交前視/正交右視）。

## BRRenderTier

| Tier | 名稱 | GL 需求 | GPU 目標 |
|------|------|---------|---------|
| 0 | Compatibility | GL 3.3 | Intel HD 4000+ |
| 1 | Quality | GL 4.5 | GTX 1060+ |
| 2 | Ultra | GL 4.6 | RTX 2060+ |
| 3 | Ray Tracing | Vulkan RT | RTX 3060+ |

透過 `isFeatureEnabled("feature_name")` 查詢特定功能是否在當前 Tier 啟用。

## 關聯接口

- 依賴 → [GreedyMesher](L3-mesher.md) — 幾何優化
- 依賴 → [後製特效](L3-postfx.md) — Composite Pass 消費者
- 依賴 → [Vulkan RT](L3-vulkan-rt.md) — Tier 3 光線追蹤
