---
id: "pfsf:PFSFDataBuilder"
type: pfsf
tags: ["pfsf", "data", "gpu", "builder"]
---

# 📄 PFSFDataBuilder — GPU 資料上傳建構器

## 📖 內容
將方塊世界狀態（RBlockState）轉換為 GPU buffer 的建構器。核心職責包括：計算 conductivity、source、maxPhi（懸臂閾值）、rcomp（壓碎閾值）、rtens（拉斷閾值），並在上傳前統一除以 sigmaMax 做正規化。

> [!warning] 注意
> 新增任何 threshold buffer 時必須同步做 /= sigmaMax

> [!tip] 相關資訊
> ⚠️ **WARNING**: 新增任何 threshold buffer 時必須同步做 /= sigmaMax
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDataBuilder.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/PFSFSourceBuilder.java`

## 🔗 Related Notes
- [[Builder]]
- [[PFSFDataBuilder]]
- [[PFSFSourceBuilder]]
- [[RBlock]]
- [[RBlockState]]
- [[State]]
- [[rcomp]]
- [[rtens]]
- [[sigma]]
