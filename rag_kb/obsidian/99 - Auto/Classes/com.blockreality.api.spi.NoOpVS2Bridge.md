---
id: "java_api:com.blockreality.api.spi.NoOpVS2Bridge"
type: class
tags: ["java", "api", "spi"]
---

# 🧩 com.blockreality.api.spi.NoOpVS2Bridge

> [!info] 摘要
> No-op VS2 bridge used when Valkyrien Skies 2 is not installed.  <p>Always returns {@code false} from {@link #assembleAsShip}, causing {@link com.blockreality.api.fragment.StructureFragmentManager} to fall back to the built-in {@code StructureFragmentEntity + StructureRigidBody}.  <p>Registered automatically at mod init; replaced by {@link com.blockreality.api.vs2.VS2ShipBridge} if VS2 is detected.

> [!tip] 資訊
> 🔌 Implements: [[IVS2Bridge]]

## 🔗 Related
- [[Fragment]]
- [[IVS2Bridge]]
- [[NoOpVS2Bridge]]
- [[StructureFragment]]
- [[StructureFragmentEntity]]
- [[StructureFragmentManager]]
- [[StructureRigidBody]]
- [[VS2ShipBridge]]
- [[assembleAsShip]]
- [[com.blockreality.api.fragment.StructureFragment]]
- [[com.blockreality.api.fragment.StructureFragmentManager]]
- [[com.blockreality.api.vs2.VS2ShipBridge]]
- [[detect]]
- [[from]]
- [[init]]
- [[replace]]
