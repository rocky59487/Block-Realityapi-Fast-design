---
id: "java_api:com.blockreality.api.physics.pfsf.StructuralKeystone#KeystoneResult"
type: method
tags: ["java", "api", "method"]
---

# 🔧 StructuralKeystone#KeystoneResult

> [!info] Signature
> `public record KeystoneResult(/** Blocks whose removal would disconnect the structure. */
        Set<BlockPos> bridgeBlocks, /** Blocks that are sole vertical support for blocks above. */
        Set<BlockPos> loadBearingBlocks, /** Combined: all critical blocks. */
        Set<BlockPos> allKeystones...)`

## 🔗 Related
- [[BlockPos]]
- [[KeystoneResult]]
- [[Result]]
- [[StructuralKeystone]]
- [[connect]]
- [[disconnect]]
- [[load]]
- [[record]]
- [[ring]]
