---
id: "java_api:com.blockreality.api.client.render.optimization.BRPaletteCompressor"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRPaletteCompressor

> [!info] 摘要
> Lithium-style palette compression for block state storage. <p> Replaces {@code HashMap<Property, Value>} with packed integer encoding. Properties are enum-indexed, values are ordinal-packed into bitfields. A typical block needs only 16 bits. <p> Design follows the FerriteCore/Lithium approach: unique block states are mapped to compact palette indices, which are then packed into long arrays using t

## 🔗 Related
- [[BRPaletteCompressor]]
- [[Palette]]
- [[Properties]]
- [[compact]]
- [[index]]
- [[ordinal]]
- [[render]]
- [[values]]
