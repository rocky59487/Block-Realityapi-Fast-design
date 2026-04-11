---
id: "java_fd:com.blockreality.fastdesign.client.node.binding.LivePreviewBridge"
type: class
tags: ["java", "fastdesign", "binding", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.binding.LivePreviewBridge

> [!info] 摘要
> 即時預覽橋接器 — 設計報告 §12.1 N3-7  在每幀渲染前將節點值注入 runtime 系統： 1. 檢查各 Binder 是否有髒值 2. 將髒值推送到對應 runtime 物件 3. MutableRenderConfig 覆蓋在 RenderLevelStageEvent.AFTER_SETUP 時注入  使用方式： MinecraftForge.EVENT_BUS.register(LivePreviewBridge.getInstance());

## 🔗 Related
- [[Config]]
- [[LivePreviewBridge]]
- [[MutableRenderConfig]]
- [[bind]]
- [[getInstance]]
- [[register]]
