---
id: "java_api:com.blockreality.api.material.BlockTypeRegistry"
type: class
tags: ["java", "api", "material"]
---

# 🧩 com.blockreality.api.material.BlockTypeRegistry

> [!info] 摘要
> 動態方塊類型註冊表 — v3fix §M5  允許 Construction Intern (CI) 模組在不修改 BlockType 枚舉的前提下 註冊新的方塊類型（如 PRESTRESSED, COMPOSITE, GEOPOLYMER 等）。  設計原則： 1. 核心 5 種 BlockType（PLAIN, REBAR, CONCRETE, RC_NODE, ANCHOR_PILE） 仍使用 enum，確保 switch 語句和序列化穩定。 2. 第三方/CI 模組透過此 Registry 註冊擴展類型。 3. 查詢優先檢查 enum，再查 registry。 4. ConcurrentHashMap 保證執行緒安全。  使用方式： // 模組初始化時 BlockTypeRegistry.register("prestressed", 0.6f);  // 查詢時 BlockTyp

## 🔗 Related
- [[ANCHOR_PILE]]
- [[BlockType]]
- [[BlockTypeRegistry]]
- [[Type]]
- [[com.blockreality.api.material.BlockType]]
- [[material]]
- [[register]]
