---
id: "trouble:node-not-registered"
type: troubleshooting
tags: ["troubleshooting", "node", "fastdesign", "serialization"]
severity: "medium"
fix_script: "fix_registry.py"
---

# 🚑 NodeGraph 載入時找不到節點類別

> [!failure] 症狀
> 開啟節點圖時某些節點變成空白或報錯 Unknown node type。原因：新增了節點子類別但忘記在 NodeRegistry 註冊。解決：執行 python fix_registry.py 自動掃描未註冊節點並補上；或手動在 NodeRegistry 中加入 register(new YourNode())。

> [!danger] Severity: `medium`

> [!tip] 相關資訊
> 🛠️ Fix Script: `fix_registry.py`
> 🚨 Severity: `medium`
> 📎 Related Files:
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/NodeRegistry.java`

## 🔗 Related Notes
- [[NodeGraph]]
- [[NodeRegistry]]
- [[register]]
- [[type]]
