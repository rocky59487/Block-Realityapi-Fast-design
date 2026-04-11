---
id: "keyclass:RCFusionDetector"
type: key_class
tags: ["key-class", "physics", "material", "rc"]
fqn: "com.blockreality.api.physics.RCFusionDetector"
thread_safety: "thread-safe (pure computation)"
---

# 🧩 RCFusionDetector 快速參考

> [!info] Quick Facts
> **FQN**: `com.blockreality.api.physics.RCFusionDetector`
> **Thread Safety**: thread-safe (pure computation)

## 📋 職責
偵測混凝土與鋼筋方塊相鄰時是否應融合為 RC（鋼筋混凝土）。固定比例 97% 混凝土 / 3% 鋼筋，使用經驗公式計算融合後強度。線程安全：純計算，無副作用。主要方法：detect(ServerLevel, BlockPos)、fuse(BlockPos, BlockPos)。修改注意：比例與公式為設計核心，不可隨意調整。

> [!warning] WARNING
> RC 融合比例固定為 97% 混凝土 / 3% 鋼筋

> [!tip] Metadata
> ⚠️ **WARNING**: RC 融合比例固定為 97% 混凝土 / 3% 鋼筋

## 🔗 Related Notes
- [[BlockPos]]
- [[RCFusionDetector]]
- [[detect]]
