---
id: "java_api:com.blockreality.api.client.render.rt.BRNRDNative"
type: class
tags: ["java", "api", "rt", "client-only"]
---

# 🧩 com.blockreality.api.client.render.rt.BRNRDNative

> [!info] 摘要
> JNI wrapper for NVIDIA Real-Time Denoisers (NRD) SDK.  Provides native bindings to blockreality_nrd.dll, allowing us to utilize ReBLUR/ReLAX for temporal and spatial denoising of RT shadows and reflections. If the library fails to load, the system degrades gracefully and delegates denoising back to our fallback BRSVGFDenoiser.

## 🔗 Related
- [[BRNRDNative]]
- [[BRSVGFDenoiser]]
- [[allow]]
- [[bind]]
- [[fail]]
- [[full]]
- [[load]]
- [[render]]
- [[shadow]]
