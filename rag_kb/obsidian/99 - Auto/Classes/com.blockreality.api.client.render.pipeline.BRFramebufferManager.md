---
id: "java_api:com.blockreality.api.client.render.pipeline.BRFramebufferManager"
type: class
tags: ["java", "api", "pipeline", "client-only"]
---

# 🧩 com.blockreality.api.client.render.pipeline.BRFramebufferManager

> [!info] 摘要
> Framebuffer 管理器 — Iris 風格 main/alt buffer swap 機制。  管理所有固化管線使用的 FBO： - Shadow FBO（深度附件，2048²） - GBuffer FBO（5 色彩附件 + 深度） - Composite FBO（雙 ping-pong buffer） - Final FBO（輸出到螢幕）  採用 Iris 的 main/alt swap 模式： composite/deferred pass 從 "main" 讀取、寫入 "alt"， 每個 pass 完成後 swap main ↔ alt。  生命週期： init() → 建立所有 FBO（在 ClientSetup 呼叫） resize(w,h) → 視窗大小改變時重建 cleanup() → 釋放所有 GL 資源

## 🔗 Related
- [[BRFramebufferManager]]
- [[ClientSetup]]
- [[cleanup]]
- [[init]]
- [[line]]
- [[main]]
- [[render]]
- [[resize]]
- [[size]]
- [[swap]]
