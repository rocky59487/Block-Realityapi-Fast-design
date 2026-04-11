---
id: "shader:Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/morton_utils.glsl"
type: shader
tags: ["shader", "glsl", "glsl"]
---

# 🎨 morton_utils.glsl

> [!info] 摘要
> ═══════════════════════════════════════════════════════════════ PFSF Morton Z-Order Utilities  v2.1 新增：Hybrid Tiled Morton 記憶體佈局工具函式。  理論基礎： Mellor-Crummey et al. (2001)：Morton 空間填充曲線在 3D stencil 運算中的 L2 Cache 命中率優勢（比線性佈局提升 ~1.8×）。 Raman & Wise (2008)：Magic bits 位元擴展的快速實作。 Pascucci & Frank (2001)：實時

## 🧩 Functions
`mortonExpandBits`, `mortonCompactBits`, `morton3D`, `mortonEncode`, `mortonDecodeX`, `mortonDecodeY`, `mortonDecodeZ`, `mortonGlobalIndex`, `linearIndex`

## 🔗 Related
- [[glsl]]
