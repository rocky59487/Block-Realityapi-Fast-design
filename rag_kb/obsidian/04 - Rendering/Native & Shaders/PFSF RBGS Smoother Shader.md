---
id: "native:shader-pfsf-rbgs"
type: native
tags: ["shader", "glsl", "pfsf", "rbgs", "gpu"]
---

# 📄 PFSF RBGS Smoother Shader

## 📖 內容
rbgs_smooth.comp.glsl：紅黑 Gauss-Seidel smoother，用於 AMG V-Cycle 的 pre/post smoothing。同樣負責寫入 hField。26 連通 stencil 必須與 jacobi_smooth 一致。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/rbgs_smooth.comp.glsl`

## 🔗 Related Notes
- [[glsl]]
- [[rbgs_smooth.comp.glsl]]
- [[smooth]]
