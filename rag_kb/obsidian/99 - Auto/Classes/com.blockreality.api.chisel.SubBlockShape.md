---
id: "java_api:com.blockreality.api.chisel.SubBlockShape"
type: class
tags: ["java", "api", "chisel"]
---

# 🧩 com.blockreality.api.chisel.SubBlockShape

> [!info] 摘要
> 子方塊形狀模板 — 預定義的雕刻形狀及其工程截面屬性。  每個模板攜帶結構力學所需的截面參數： - fillRatio:       填充率（影響質量：mass = density × fillRatio） - crossSectionArea: 有效截面積 A (m²)（影響抗壓容量） - momentOfInertiaX/Y: 截面慣性矩 Ix/Iy (m⁴)（影響彎矩抗力） - sectionModulusX/Y: 截面模數 Wx/Wy (m³)（影響懸臂容量）  CUSTOM 形狀使用全塊預設值進行物理計算（保守近似）， 僅 fillRatio 反映實際填充率以正確計算質量。  工程公式參考： 正方形截面 b×h: I = bh³/12, W = bh²/6, A = bh 圓形截面 r:     I = πr⁴/4, W = πr³/4, A = πr²

## 🔗 Related
- [[CUSTOM]]
- [[SubBlockShape]]
- [[crossSectionArea]]
- [[density]]
- [[fill]]
- [[fillRatio]]
- [[momentOfInertiaX]]
- [[sectionModulusX]]
