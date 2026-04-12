---
id: "rule:hfield-write"
type: rule
tags: ["rule", "pfsf", "gpu", "race-condition"]
---

# 📄 hField 寫入權規則

## 📖 內容
hField（歷史應變能場）僅由 Jacobi/RBGS smoother 寫入（max(old, ψ_e)）。phase_field_evolve.comp.glsl 對 hField 唯讀。從兩個 shader 寫入會造成 GPU race condition。

> [!warning] 注意
> GPU race condition 會造成非決定性數值錯誤

> [!tip] 相關資訊
> ⚠️ **WARNING**: GPU race condition 會造成非決定性數值錯誤

## 🔗 Related Notes
- [[glsl]]
- [[phase_field_evolve.comp.glsl]]
- [[smooth]]
