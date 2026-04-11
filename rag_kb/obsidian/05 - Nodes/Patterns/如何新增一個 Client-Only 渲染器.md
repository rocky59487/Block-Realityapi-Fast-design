---
id: "pattern:client-renderer"
type: pattern
tags: ["pattern", "render", "client-only", "howto"]
---

# 🧩 如何新增一個 Client-Only 渲染器

## 🪜 步驟
- 1. 在 api/client/render/... 或 fastdesign/client/... 下建立新類別。
- 2. 在類別上加上 @OnlyIn(Dist.CLIENT)。
- 3. 在 ClientSetup 或對應的 event bus 註冊渲染事件（如 RenderLevelStageEvent）。
- 4. 絕對不在任何 server-side 邏輯中直接引用該類別。

> [!example] 範例類別: [[GhostBlockRenderer]]

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/ClientSetup.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/client/GhostBlockRenderer.java`

## 🔗 Related Notes
- [[CLIENT)]]
- [[ClientSetup]]
- [[GhostBlockRenderer]]
- [[render]]
