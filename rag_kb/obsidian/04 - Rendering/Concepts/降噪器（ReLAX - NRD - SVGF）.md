---
id: "render:denoiser"
type: render
tags: ["render", "denoiser", "vulkan", "rt", "client-only"]
---

# 📄 降噪器（ReLAX / NRD / SVGF）

## 📖 內容
支援多種降噪器：BRReLAXDenoiser（NVIDIA ReLAX）、BRNRDNative（NRD JNI 橋接）、BRSVGFDenoiser（SVGF）。NRD JNI 橋接位於 api/src/main/native/，需獨立 cmake 建置。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/rt/BRReLAXDenoiser.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/rendering/vulkan/BRNRDDenoiser.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/render/rt/BRSVGFDenoiser.java`
>   - `Block Reality/api/src/main/native/`

## 🔗 Related Notes
- [[BRNRDDenoiser]]
- [[BRNRDNative]]
- [[BRReLAXDenoiser]]
- [[BRSVGFDenoiser]]
- [[main]]
