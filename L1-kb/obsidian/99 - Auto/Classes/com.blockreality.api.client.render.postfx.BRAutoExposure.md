---
id: "java_api:com.blockreality.api.client.render.postfx.BRAutoExposure"
type: class
tags: ["java", "api", "postfx", "client-only"]
---

# 🧩 com.blockreality.api.client.render.postfx.BRAutoExposure

> [!info] 摘要
> GPU luminance histogram compute shader for auto-exposure. <p> Replaces the simplified CPU implementation with a two-pass compute shader approach: <ol> <li>Build a 64-bin luminance histogram from the HDR scene texture</li> <li>Reduce the histogram to a single average luminance, excluding low/high percentiles</li> </ol> The result is read back to the CPU via an SSBO for use by the tone-mapping pass.

## 🔗 Related
- [[BRAutoExposure]]
- [[compute]]
- [[from]]
- [[luminance]]
- [[read]]
- [[render]]
