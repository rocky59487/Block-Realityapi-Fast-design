---
id: "tool:fix_only_in_client"
type: tool
tags: ["tool", "script", "client-only", "annotation", "autofix"]
command: "python fix_only_in_client.py"
---

# 🛠️ fix_only_in_client.py — 自動補上 @OnlyIn(Dist.CLIENT)

## 📝 說明
掃描 fastdesign/client/ 與 api/client/ 下的所有 Java 類別，自動為缺少 @OnlyIn(Dist.CLIENT) 的類別加上註解。適用場景：新增大量 client-only 渲染器或 UI 類別後的批量檢查。

> [!tip] 使用資訊
> ⌨️ Command: `python fix_only_in_client.py`
> 📎 Related Files:
>   - `fix_only_in_client.py`

## 🔗 Related Notes
- [[CLIENT)]]
