---
id: "java_test:com.blockreality.api.physics.pfsf.VramBudgetManagerTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.physics.pfsf.VramBudgetManagerTest

> [!info] 摘要
> VramBudgetManager test — Verify alloc/free counters, partition isolation, stress metrics.  v0.2a API: init(VkPhysicalDevice, int usagePercent) — requires a real Vulkan device, not called during testing tryRecord(long handle, long size, int partition) → boolean recordFree(long handle) getTotalUsage() → long getPartitionUsage(int) → long getTotalBudget() → long getPressure() → float getFreeMemory() 

## 🔗 Related
- [[Partition]]
- [[VkPhysicalDevice]]
- [[VramBudgetManager]]
- [[VramBudgetManagerTest]]
- [[com.blockreality.api.physics.pfsf.VramBudgetManager]]
- [[free]]
- [[getFreeMemory]]
- [[getPartitionUsage]]
- [[getPressure]]
- [[getTo]]
- [[getTotalBudget]]
- [[getTotalUsage]]
- [[handle]]
- [[init]]
- [[record]]
- [[recordFree]]
- [[ring]]
- [[size]]
- [[test]]
- [[tryRecord]]
