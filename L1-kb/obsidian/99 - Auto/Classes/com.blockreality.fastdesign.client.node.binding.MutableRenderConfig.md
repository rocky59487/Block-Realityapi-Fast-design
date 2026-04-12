---
id: "java_fd:com.blockreality.fastdesign.client.node.binding.MutableRenderConfig"
type: class
tags: ["java", "fastdesign", "binding", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.binding.MutableRenderConfig

> [!info] 摘要
> BRRenderConfig 可變鏡像 — 設計報告 §12.1 N3-1  BRRenderConfig 使用 static final（JIT 內聯），無法在 runtime 修改。 此類鏡像所有欄位為 volatile mutable，供節點系統覆蓋。  LivePreviewBridge 在每幀渲染前注入這些覆蓋值。 不修改 api 模組 — 保持 fastdesign → api 單向依賴。

## 🔗 Related
- [[BRRenderConfig]]
- [[Config]]
- [[LivePreviewBridge]]
- [[MutableRenderConfig]]
- [[bind]]
