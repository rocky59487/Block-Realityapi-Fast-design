---
id: "tool:fix_registry"
type: tool
tags: ["tool", "script", "node", "registry", "autofix"]
command: "python fix_registry.py"
---

# 🛠️ fix_registry.py — 自動將未註冊節點加入 NodeRegistry

## 📝 說明
掃描 fastdesign/client/node/impl/ 下的所有 BRNode 子類別，檢查是否已在 NodeRegistry 中註冊，若缺少則自動產生註冊程式碼建議或直接補上。適用場景：新增多個節點後忘記手動註冊。

> [!tip] 使用資訊
> ⌨️ Command: `python fix_registry.py`
> 📎 Related Files:
>   - `fix_registry.py`
>   - `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/NodeRegistry.java`

## 🔗 Related Notes
- [[BRNode]]
- [[NodeRegistry]]
- [[py — 自動將未註冊節點加入 NodeRegistry]]
