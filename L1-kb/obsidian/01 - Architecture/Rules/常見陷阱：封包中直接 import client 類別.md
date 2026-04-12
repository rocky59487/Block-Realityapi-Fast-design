---
id: "pitfall:client-import-in-packet"
type: pitfall
tags: ["pitfall", "network", "client-only", "crash"]
fix_script: "fix_imports.py"
---

# 📄 常見陷阱：封包中直接 import client 類別

## 📖 內容
在 network/ 封包類別頂部直接 import ...client... 類別，會在專用伺服器啟動時觸發 NoClassDefFoundError。正確做法是使用完整限定名稱（FQN）字串或 Lazy 載入。修復腳本：fix_imports.py。

> [!tip] 相關資訊
> 🛠️ Fix Script: `fix_imports.py`

## 🔗 Related Notes
- [[ports]]
