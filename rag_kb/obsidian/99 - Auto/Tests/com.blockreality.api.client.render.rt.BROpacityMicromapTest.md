---
id: "java_test:com.blockreality.api.client.render.rt.BROpacityMicromapTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.client.render.rt.BROpacityMicromapTest

> [!info] 摘要
> BROpacityMicromap 單元測試。  測試覆蓋： - OMM 狀態/格式常數值 - 透明材料註冊/取消/查詢/計數 - Section OMM 狀態追蹤 (onSectionUpdated / getSectionState / onSectionRemoved) - any-hit skip / trigger 計數器 - getOMMEfficiency 效率計算 - buildOMMArray 邊界條件（Phase 1：hasOMM=false 下均返回 null） - shouldUseOpaqueFlag（無 OMM 擴充時恆 false） - clear() 重置所有可變狀態  所有測試為純 CPU 邏輯，不依賴 Vulkan / BRAdaRTConfig.detect()。 BRAdaRTConfig.hasOMM() 在測試環境下恆為 false（未呼叫 de

## 🔗 Related
- [[BRAdaRTConfig]]
- [[BROpacityMicromap]]
- [[BROpacityMicromapTest]]
- [[Config]]
- [[State]]
- [[build]]
- [[buildOMMArray]]
- [[clear]]
- [[com.blockreality.api.client.render.rt.BROpacityMicromap]]
- [[detect]]
- [[getOMMEfficiency]]
- [[getSection]]
- [[getSectionState]]
- [[hasOMM]]
- [[move]]
- [[onSectionRemoved]]
- [[onSectionUpdated]]
- [[render]]
- [[shouldUseOpaqueFlag]]
