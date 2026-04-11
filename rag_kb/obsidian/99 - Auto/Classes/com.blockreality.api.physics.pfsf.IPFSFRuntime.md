---
id: "java_api:com.blockreality.api.physics.pfsf.IPFSFRuntime"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.IPFSFRuntime

> [!info] 摘要
> PFSF 物理引擎運行時抽象 — Strategy pattern。  <p>允許在不同求解後端之間切換（Java/LWJGL vs C++ native via JNI）， 而不影響上層呼叫者（{@link PFSFEngine} 靜態 facade、ServerTickHandler 等）。</p>  <p>實作： <ul> <li>{@link PFSFEngineInstance} — Java/LWJGL Vulkan 後端（現有）</li> <li>NativePFSFRuntime — C++ libpfsf via JNI（Phase 3 計畫）</li> </ul>  @since v0.3a (libpfsf Phase 0) @see PFSFEngine @see PFSFEngineInstance

## 🔗 Related
- [[IPFSFRuntime]]
- [[PFSFEngine]]
- [[PFSFEngineInstance]]
- [[ServerTickHandler]]
