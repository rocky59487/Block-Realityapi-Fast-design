---
id: "java_api:com.blockreality.api.client.render.optimization.BRThreadedMeshBuilder"
type: class
tags: ["java", "api", "optimization", "client-only"]
---

# 🧩 com.blockreality.api.client.render.optimization.BRThreadedMeshBuilder

> [!info] 摘要
> Block Reality 多线程 LOD 网格构建系统 (C2ME 启发)  本类提供高效的并行网格生成功能，针对客户端渲染优化。 使用固定大小的线程池、任务队列和细粒度的区域锁定机制。  主要特性: - 基于优先级队列的任务调度 - 每个网格区域独立的可重入锁 - 工作窃取算法实现动态负载均衡 - 详细的性能统计和监控 - 优雅的线程池关闭机制  线程安全: 所有公共方法都是线程安全的  @author Block Reality Team @version 1.0

## 🔗 Related
- [[BRThreadedMeshBuilder]]
- [[Builder]]
- [[Thread]]
- [[author]]
- [[read]]
- [[render]]
