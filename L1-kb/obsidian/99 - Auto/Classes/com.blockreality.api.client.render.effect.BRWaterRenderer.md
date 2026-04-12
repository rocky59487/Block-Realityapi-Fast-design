---
id: "java_api:com.blockreality.api.client.render.effect.BRWaterRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRWaterRenderer

> [!info] 摘要
> 進階水體渲染器 — PBR 水面效果。  特性： - 平面反射（Planar Reflection via 剪裁平面 + 反射 FBO） - 螢幕空間折射（Screen-Space Refraction） - Gerstner 波浪動畫（多頻疊加） - 水下焦散（Caustics — 程序化紋理投射） - 菲涅爾反射率（Schlick 近似） - 深度吸收著色（Beer-Lambert — 越深越暗越綠） - 泡沫邊緣（岸邊 / 物體交接處白色泡沫） - 法線貼圖擾動（多層 UV 滾動）  水面幾何由 Minecraft 原生水方塊提供， 本引擎僅替換 fragment shader 效果。  @author Block Reality Team @version 1.0 @deprecated Since 2.0, superseded by Vulkan RT + Voxy LOD p

## 🔗 Related
- [[BRWaterRenderer]]
- [[author]]
- [[render]]
