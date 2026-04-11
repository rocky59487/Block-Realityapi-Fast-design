---
id: "native:shader-pfsf-pcg"
type: native
tags: ["shader", "glsl", "pfsf", "pcg", "solver", "gpu"]
---

# 📄 PFSF PCG Shader 系列

## 📖 內容
包含 pcg_matvec.comp.glsl（矩陣向量乘）、pcg_dot.comp.glsl（內積/reduce）、pcg_direction.comp.glsl（方向更新）、pcg_update.comp.glsl（解更新）。pcg_matvec 的 26 連通 stencil 必須與 smoother 完全一致，否則 CG 收斂到錯誤解。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/pcg_matvec.comp.glsl`
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/pcg_dot.comp.glsl`
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/pcg_direction.comp.glsl`
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/pcg_update.comp.glsl`

## 🔗 Related Notes
- [[glsl]]
- [[pcg_direction.comp.glsl]]
- [[pcg_dot.comp.glsl]]
- [[pcg_matvec.comp.glsl]]
- [[pcg_update.comp.glsl]]
- [[smooth]]
- [[update]]
