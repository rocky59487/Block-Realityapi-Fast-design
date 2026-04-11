---
id: moc-rendering-moc
type: "moc"
tags: [moc]
---

# Rendering MOC

## Concepts

| 標題 | 檔案 |
|------|------|
| [[BRVulkanRT — Vulkan 光追主控]] | `BRVulkanRT — Vulkan 光追主控.md` |
| [[DDGI 與 ReSTIR 全局光照]] | `DDGI 與 ReSTIR 全局光照.md` |
| [[LOD 與地形優化]] | `LOD 與地形優化.md` |
| [[NRD JNI 原生橋接建置]] | `NRD JNI 原生橋接建置.md` |
| [[全息投影與 Ghost 預覽]] | `全息投影與 Ghost 預覽.md` |
| [[天氣與環境效果]] | `天氣與環境效果.md` |
| [[後處理效果管線]] | `後處理效果管線.md` |
| [[應力視覺化]] | `應力視覺化.md` |
| [[渲染系統總覽]] | `渲染系統總覽.md` |
| [[鑿子（Chisel）網格與選擇渲染]] | `鑿子（Chisel）網格與選擇渲染.md` |
| [[降噪器（ReLAX / NRD / SVGF）]] | `降噪器（ReLAX - NRD - SVGF）.md` |

## Native & Shaders

| 標題 | 檔案 |
|------|------|
| [[NRD JNI 原生橋接]] | `NRD JNI 原生橋接.md` |
| [[PFSF Failure Scan / Compact Shader]] | `PFSF Failure Scan - Compact Shader.md` |
| [[PFSF Jacobi Smoother Shader]] | `PFSF Jacobi Smoother Shader.md` |
| [[PFSF PCG Shader 系列]] | `PFSF PCG Shader 系列.md` |
| [[PFSF Phase Field Evolve Shader]] | `PFSF Phase Field Evolve Shader.md` |
| [[PFSF RBGS Smoother Shader]] | `PFSF RBGS Smoother Shader.md` |
| [[Vulkan RT Primary Ray Generation]] | `Vulkan RT Primary Ray Generation.md` |
| [[Vulkan RT ReSTIR Shader]] | `Vulkan RT ReSTIR Shader.md` |
| [[libpfsf C++ API]] | `libpfsf C++ API.md` |
| [[流體 Compute Shader]] | `流體 Compute Shader.md` |

## 🔍 Dataview 動態查詢

> [!info] 需安裝 Dataview 外掛才能看到動態結果

```dataview
TABLE type, summary
FROM "04 - Rendering/Concepts" OR "04 - Rendering/Native & Shaders"
WHERE type != "moc"
SORT file.name ASC
LIMIT 50
```

---

返回 [[Home]]