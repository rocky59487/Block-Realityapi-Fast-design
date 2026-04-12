---
id: "java_fd:com.blockreality.fastdesign.client.StructuralSafetyHud"
type: class
tags: ["java", "fastdesign", "client", "client-only"]
---

# 🧩 com.blockreality.fastdesign.client.StructuralSafetyHud

> [!info] 摘要
> 結構安全 HUD — 每個結構元素的利用率 %（P3-B Killer Feature）  <p><b>功能：</b> <ul> <li>3D 世界空間浮動標籤 — 在每個結構方塊正上方顯示利用率 %， 僅在 {@code RENDER_DISTANCE} = 32 格內渲染，billboard 朝向相機</li> <li>顏色編碼： <ul> <li>§a 綠色  (0–50%)  — 安全</li> <li>§e 黃色 (50–80%) — 注意</li> <li>§6 橙色 (80–90%) — 警告</li> <li>§c 紅色 (90–100%) — 危險</li> <li>閃爍紫色 (>100%) — 結構失效</li> </ul> </li> <li>2D 摘要面板（右上角）： 最大利用率、臨界元素數量、整體結構健康評分（A–F）</li> <li>切換按鍵：J 鍵（可在控制

## 🔗 Related
- [[StructuralSafetyHud]]
