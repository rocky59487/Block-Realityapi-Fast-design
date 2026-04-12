---
id: "pfsf:overview"
type: pfsf
tags: ["pfsf", "physics", "gpu", "vulkan", "overview"]
---

# 📄 PFSF 引擎總覽

## 📖 內容
PFSF 是 Potential Field Structure Failure 的縮寫，是 Block Reality 的核心結構完整性評估引擎。它將每個方塊視為具有真實工程屬性（抗壓強度 MPa、抗拉強度、剪切阻力、密度 kg/m³、楊氏模量 GPa）的結構元素，並透過 GPU 上的 Vulkan Compute 每 server tick 評估應力與相場（phase-field）演化。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/`
>   - `libpfsf/src/solver/`
>   - `Block Reality/api/src/main/resources/assets/blockreality/shaders/`

## 🔗 Related Notes
- [[tick]]
