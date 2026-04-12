---
id: "java_api:com.blockreality.api.client.render.animation.BRAnimationEngine"
type: class
tags: ["java", "api", "animation", "client-only"]
---

# 🧩 com.blockreality.api.client.render.animation.BRAnimationEngine

> [!info] 摘要
> Block Reality 動畫引擎 - 完整的每實體骨骼動畫系統  功能特性： • 每實體的 AnimatableInstance (類似 GeckoLib 的 AnimatableInstanceCache) • 每實體支援多個 AnimationController (例如: 身體、手臂、頭部) • 正確的骨骼矩陣計算 (worldMatrix × inverseBindMatrix) • 預先編譯的動畫片段 (方塊放置、破壞、選擇脈衝、結構崩塌)  架構類比： GeckoLib AnimatableInstanceCache → AnimatableInstance GeckoLib AnimationController → AnimationController GeckoLib 骨骼計算 → BoneHierarchy.computeSkinningMatrices()

## 🔗 Related
- [[AnimatableInstance]]
- [[AnimationController]]
- [[BRAnimationEngine]]
- [[Bone]]
- [[BoneHierarchy]]
- [[compute]]
- [[computeSkinningMatrices]]
- [[render]]
