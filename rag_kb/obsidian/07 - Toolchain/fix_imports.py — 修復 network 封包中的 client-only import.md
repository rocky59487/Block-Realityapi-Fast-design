---
id: "tool:fix_imports"
type: tool
tags: ["tool", "script", "network", "client-only", "autofix"]
command: "python fix_imports.py"
---

# 🛠️ fix_imports.py — 修復 network 封包中的 client-only import

## 📝 說明
自動掃描 fastdesign/network/ 下的封包類別，將對 client/ 類別的直接 import 改為完整限定名稱（FQN），避免專用伺服器啟動時 NoClassDefFoundError。適用場景：新增或修改網路封包後忘記檢查 import。

> [!tip] 使用資訊
> ⌨️ Command: `python fix_imports.py`
> 📎 Related Files:
>   - `fix_imports.py`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/network/`

## 🔗 Related Notes
- [[ports]]
- [[py — 修復 network 封包中的 client-only import]]
