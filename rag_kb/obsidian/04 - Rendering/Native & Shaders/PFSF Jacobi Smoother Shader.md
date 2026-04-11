---
id: "native:shader-pfsf-jacobi"
type: native
tags: ["shader", "glsl", "pfsf", "jacobi", "gpu"]
---

# 📄 PFSF Jacobi Smoother Shader

## 📖 內容
jacobi_smooth.comp.glsl：執行 Jacobi 迭代平滑 phi 場，同時更新 hField（max(old, ψ_e)）。必須使用與 pcg_matvec 完全相同的 26 連通 stencil。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/jacobi_smooth.comp.glsl`

## 🔗 Related Notes
- [[glsl]]
- [[jacobi_smooth.comp.glsl]]
- [[smooth]]
