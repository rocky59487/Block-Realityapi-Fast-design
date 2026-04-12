---
id: "native:shader-rt-primary"
type: native
tags: ["shader", "glsl", "rt", "vulkan", "raytracing"]
---

# 📄 Vulkan RT Primary Ray Generation

## 📖 內容
rt/ada/primary.rgen.glsl 與 rt/blackwell/primary.rgen.glsl：分別對應 Ada (RTX 40) 與 Blackwell (RTX 50) 世代的硬體光追 primary ray generation shader。由 BRVulkanRT 根據 GPU 架構動態選擇。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/rt/ada/primary.rgen.glsl`
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/rt/blackwell/primary.rgen.glsl`

## 🔗 Related Notes
- [[BRVulkanRT]]
- [[glsl]]
- [[primary.rgen.glsl]]
