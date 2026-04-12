---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFRenderBridge"
type: class
tags: ["java", "api", "pfsf", "client-only"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFRenderBridge

> [!info] 摘要
> PFSF 渲染橋接 — phi[] Buffer 零拷貝共享（Compute → Graphics）。  <p>核心概念：phi[] VkBuffer 同時作為 Compute Shader 的 SSBO 和 Fragment Shader 的 readonly SSBO，透過 Pipeline Memory Barrier 確保 寫入完畢再讀取。無需 CPU 中轉，消除記憶體頻寬瓶頸。</p>  <p>CPU Fallback：當 Vulkan 不可用時，讀回 phi[] 產生 {@link StressField} 供 StressHeatmapRenderer 使用。</p>  參考：PFSF 手冊 §7

## 🔗 Related
- [[Fragment]]
- [[PFSFRenderBridge]]
- [[StressField]]
- [[StressHeatmapRenderer]]
- [[line]]
- [[read]]
