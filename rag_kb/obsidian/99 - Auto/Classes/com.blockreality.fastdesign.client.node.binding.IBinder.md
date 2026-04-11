---
id: "java_fd:com.blockreality.fastdesign.client.node.binding.IBinder"
type: class
tags: ["java", "fastdesign", "binding", "client-only", "node"]
---

# 🧩 com.blockreality.fastdesign.client.node.binding.IBinder

> [!info] 摘要
> 資料綁定介面 — 設計報告 §12.1 N3  將節點圖的端口值綁定到 Runtime 物件（渲染配置、材料、物理引擎等）。  生命週期： 1. bind(graph) — 載入節點圖時呼叫，建立綁定映射 2. apply(target) — 每幀呼叫，將節點值推送到 runtime 3. pull(target) — 首次載入或外部修改時，從 runtime 拉回值到節點 4. isDirty() — 是否有任何綁定值變更  @param <T> Runtime 目標型別

## 🔗 Related
- [[IBinder]]
- [[apply]]
- [[bind]]
- [[isDirty]]
- [[pull]]
