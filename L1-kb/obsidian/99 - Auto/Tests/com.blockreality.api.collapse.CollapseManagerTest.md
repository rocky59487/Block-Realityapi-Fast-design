---
id: "java_test:com.blockreality.api.collapse.CollapseManagerTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.collapse.CollapseManagerTest

> [!info] 摘要
> CollapseManager queue behavior test — C-6  verify: - hasPending() = false when queue is empty - processQueue returns safely on empty queue - clearQueue clears the queue - Thread-safe ConcurrentLinkedDeque is chosen correctly  Note: triggerCollapseAt/checkAndCollapse requires ServerLevel, So only the queue management logic is tested (no world operations involved).

## 🔗 Related
- [[CollapseManager]]
- [[CollapseManagerTest]]
- [[Thread]]
- [[check]]
- [[clear]]
- [[clearQueue]]
- [[collapse]]
- [[com.blockreality.api.collapse.CollapseManager]]
- [[empty]]
- [[hasPending]]
- [[processQueue]]
- [[queue]]
- [[read]]
- [[safe]]
- [[test]]
- [[triggerCollapseAt]]
