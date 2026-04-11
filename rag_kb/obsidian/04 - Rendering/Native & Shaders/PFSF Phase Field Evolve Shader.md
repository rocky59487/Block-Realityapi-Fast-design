---
id: "native:shader-pfsf-phasefield"
type: native
tags: ["shader", "glsl", "pfsf", "phase-field", "gpu"]
---

# 📄 PFSF Phase Field Evolve Shader

## 📖 內容
phase_field_evolve.comp.glsl：執行相場（phase-field）演化步驟。對 hField 必須為唯讀。若從此 shader 也寫入 hField，會造成 GPU race condition。

> [!warning] 注意
> 此 shader 對 hField 唯讀，不可寫入

> [!tip] 相關資訊
> ⚠️ **WARNING**: 此 shader 對 hField 唯讀，不可寫入
> 📎 Related Files:
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/phase_field_evolve.comp.glsl`

## 🔗 Related Notes
- [[glsl]]
- [[phase_field_evolve.comp.glsl]]
