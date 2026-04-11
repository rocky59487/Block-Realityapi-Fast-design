---
id: "java_api:com.blockreality.api.physics.pfsf.PFSFConductivity"
type: class
tags: ["java", "api", "pfsf"]
---

# 🧩 com.blockreality.api.physics.pfsf.PFSFConductivity

> [!info] 摘要
> PFSF 傳導率計算器。  σ_ij 決定應力如何在體素之間流動： <ul> <li>垂直邊：取兩側較弱材料的 Rcomp（荷載沿重力傳遞）</li> <li>水平邊：加上抗拉修正 + 距離衰減（力矩放大效應）</li> <li>空氣邊：σ = 0（絕緣）</li> </ul>  參考：PFSF 手冊 §5.3

## 🔗 Related
- [[PFSFConductivity]]
