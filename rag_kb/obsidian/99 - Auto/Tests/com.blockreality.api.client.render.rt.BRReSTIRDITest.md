---
id: "java_test:com.blockreality.api.client.render.rt.BRReSTIRDITest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.client.render.rt.BRReSTIRDITest

> [!info] 摘要
> Unit tests for BRReSTIRDI reservoir math: - reservoirUpdate()   : RIS stream update（wSum 累積、樣本接受機率） - computeRISWeight()  : 無偏 W = (1/p_hat(y)) × (wSum/M) - reservoirMerge()    : 時域 reservoir 合併（M-cap、wSum 貢獻）  所有測試均為純 CPU 數學，不依賴 Vulkan / Forge 運行時。

## 🔗 Related
- [[BRReSTIRDI]]
- [[BRReSTIRDITest]]
- [[com.blockreality.api.client.render.rt.BRReSTIRDI]]
- [[compute]]
- [[computeRISWeight]]
- [[render]]
- [[reservoirMerge]]
- [[reservoirUpdate]]
- [[test]]
- [[update]]
