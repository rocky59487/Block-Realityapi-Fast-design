---
id: "java_test:com.blockreality.api.spi.MaterialRegistryConcurrencyTest"
type: test
tags: ["java", "test", "junit"]
---

# 🧩 com.blockreality.api.spi.MaterialRegistryConcurrencyTest

> [!info] 摘要
> IMaterialRegistry Multi-thread Concurrency Security Test — M6  Test strategy: Verify the implementation of ModuleRegistry.getMaterialRegistry() in high concurrency scenarios: 1. ConcurrentModificationException does not occur during concurrent reading 2. Concurrent writing does not cause data competition or deadlock 3. Mixing reading and writing does not lead to inconsistent status 4. The preloaded

## 🔗 Related
- [[IMaterialRegistry]]
- [[MaterialRegistryConcurrencyTest]]
- [[ModuleRegistry]]
- [[getMaterial]]
- [[getMaterialRegistry]]
- [[load]]
- [[read]]
- [[reload]]
- [[ring]]
