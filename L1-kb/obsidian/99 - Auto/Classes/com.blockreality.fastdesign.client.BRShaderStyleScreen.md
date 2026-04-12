---
id: "java_fd:com.blockreality.fastdesign.client.BRShaderStyleScreen"
type: class
tags: ["java", "fastdesign", "client", "client-only"]
---

# 🧩 com.blockreality.fastdesign.client.BRShaderStyleScreen

> [!info] 摘要
> Block Reality 光影風格設定總畫面（取代 {@link BRGraphicsSettingsScreen}）。  <h2>三分頁架構</h2> <ul> <li><b>「風格」</b> — 視覺卡片選擇器，8 種內建風格 + 使用者自訂預設， 即時套用至節點圖並觸發 BidirectionalSync 同步。</li> <li><b>「進階」</b> — 按類別整理的特效開關與滑桿（光照 / 陰影 / 後製 / 大氣 / 水體 / 效能），手動調整後自動標記為「自定義」狀態。</li> <li><b>「節點圖」</b> — 嵌入完整 {@link NodeCanvasScreen}，供進階使用者 直接操作節點圖。從此處修改同樣觸發 BidirectionalSync 回寫「進階」頁。</li> </ul>  <h2>競品差異化設計</h2> <ul> <li>卡片縮圖 + 效能

> [!tip] 資訊
> 🔼 Extends: Screen

## 🔗 Related
- [[BRGraphicsSettingsScreen]]
- [[BRShaderStyleScreen]]
- [[BidirectionalSync]]
- [[NodeCanvasScreen]]
