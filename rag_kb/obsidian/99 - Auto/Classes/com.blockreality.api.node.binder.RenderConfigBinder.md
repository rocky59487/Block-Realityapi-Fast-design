---
id: "java_api:com.blockreality.api.node.binder.RenderConfigBinder"
type: class
tags: ["java", "api", "binder"]
---

# 🧩 com.blockreality.api.node.binder.RenderConfigBinder

> [!info] 摘要
> ★ review-fix ICReM-7: RenderConfigBinder 代理層。  API 層只提供靜態代理介面，具體實作由模組層透過 setImplementation() 註冊。 這確保 API 不依賴 EffectToggleNode、QualityPresetNode 或 BRRenderSettings 的內部細節。  模組層（fastdesign）在初始化時呼叫: RenderConfigBinder.setImplementation(new FastDesignRenderConfigBinder());

## 🔗 Related
- [[BRRenderSettings]]
- [[Config]]
- [[Preset]]
- [[QualityPresetNode]]
- [[RenderConfigBinder]]
- [[bind]]
- [[reset]]
- [[setImplementation]]
