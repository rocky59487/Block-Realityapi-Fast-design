---
id: "pitfall:missing-onlyin-annotation"
type: pitfall
tags: ["pitfall", "client-only", "annotation", "crash"]
fix_script: "fix_only_in_client.py"
---

# 📄 常見陷阱：client 類別缺少 @OnlyIn(Dist.CLIENT)

## 📖 內容
新增或重構 client/ 下的類別時，容易忘記加上 @OnlyIn(Dist.CLIENT)。這會導致 Forge 在伺服器端嘗試載入該類別。修復腳本：fix_only_in_client.py。

> [!tip] 相關資訊
> 🛠️ Fix Script: `fix_only_in_client.py`

## 🔗 Related Notes
- [[CLIENT)]]
