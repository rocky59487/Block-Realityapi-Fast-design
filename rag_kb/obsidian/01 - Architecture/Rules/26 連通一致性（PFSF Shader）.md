---
id: "rule:26-connectivity"
type: rule
tags: ["rule", "pfsf", "shader", "gpu", "critical"]
---

# 📄 26 連通一致性（PFSF Shader）

## 📖 內容
RBGS、Jacobi、PCG matvec 必須使用完全相同的 26 連通 stencil：6 面鄰居 ×1.0，12 邊鄰居 ×0.35（SHEAR_EDGE_PENALTY），8 角鄰居 ×0.15（SHEAR_CORNER_PENALTY）。任一 shader 不一致會導致 CG 收斂到錯誤解或多網格發散。

> [!warning] 注意
> Shader stencil 不一致是最難 debug 的沈默錯誤之一

> [!tip] 相關資訊
> ⚠️ **WARNING**: Shader stencil 不一致是最難 debug 的沈默錯誤之一
