
# BLOCK REALITY v1 STABLE 完整安全扫描报告
**Repository:** rocky59487/block-realityapi-fast-design  
**Branch:** main  
**扫描日期:** 2026-04-06  
**扫描版本:** v1.0

---

## 执行摘要

本次安全扫描分为两个 WAVE，共执行 21 个专项审计代理：
- **WAVE 1: 安全扫描** - 12 个专项 (SEC-01 ~ SEC-12)
- **WAVE 2: 稳定性扫描** - 9 个专项 (STB-01 ~ STB-09)

### 关键统计

| 类别 | 发现问题数 | P0 (严重) | P1 (高) | P2 (中) | P3 (低) |
|------|-----------|-----------|---------|---------|---------|
| 安全漏洞 | 45+ | 5 | 18 | 15 | 10+ |
| 稳定性问题 | 35+ | 3 | 12 | 15 | 8 |
| **总计** | **80+** | **8** | **30** | **30** | **18** |

---

## WAVE 1: 安全扫描结果

### SEC-02: 文件 I/O 路径安全

| 文件 | 行号 | 风险类型 | CVSS | 修复建议 |
|------|------|----------|------|----------|
| BlueprintIO.java | 61-78 | 路径遍历防护不完整 | 5.3 | 使用 `contains("..")` 替代 `replaceAll("\.\.", "")` |
| BlueprintIO.java | 81-91 | 符号链接攻击 | 4.3 | 使用 `toRealPath()` 替代 `normalize()` |
| BlueprintIO.java | 319 | TOCTOU 竞争 | 4.7 | 使用 `Files.createTempFile()` 生成唯一临时文件名 |
| BlueprintIO.java | 352-376 | .litematic 路径遍历 | 6.5 | 添加 `toRealPath()` 路径验证 |
| LitematicImporter.java | 39-49 | 缺少路径验证 | 6.5 | 添加 `toRealPath()` 路径验证 |

### SEC-03: 注入与反序列化风险

| # | 文件 | 行号 | 类型 | 影响 | 修复建议 |
|---|------|------|------|------|----------|
| 1 | BlueprintNBT.java | 213-218 | anchorPoints 无上限 | OOM | 添加 MAX_ANCHOR_POINTS = 1000 |
| 2 | LitematicImporter.java | 39-87 | blocks 无上限检查 | 绕过 MAX_BLOCKS 限制 | 添加累计 blocks 检查 |
| 3 | BlueprintIO.java | 182 | 无 try-catch | 服务器崩溃 | 包裹 NbtIo.readCompressed |
| 4 | BlueprintNBT.java | 143 | author 无长度限制 | 日志注入 | 添加 MAX_AUTHOR_LENGTH = 64 |
| 5 | BlueprintNBT.java | 142 | name 无长度限制 | 日志注入/UI 问题 | 添加 MAX_NAME_LENGTH = 128 |
| 6 | ConstructionZoneManager.java | 135-139 | zones 无上限 | OOM | 添加 MAX_ZONES = 10000 |
| 7 | LitematicImporter.java | 216-221 | palette 无上限 | OOM | 添加 MAX_PALETTE_SIZE = 100000 |

### SEC-04: 并发竞态与资料竞争

| 文件:行 | 竞态类型 | 触发条件 | 修复 |
|---------|----------|----------|------|
| PFSFEngine.java:55 | 非原子复合操作 | 多线程并发读写`descriptorResetCountdown` | 改用`AtomicInteger` |
| PFSFEngine.java:158 | ConcurrentHashMap非原子复合操作 | `syncCounters.merge()`与读取竞态 | 使用`compute()`合并判断逻辑 |
| PFSFEngine.java:76 | 非volatile字段可见性 | 跨线程setter/getter | 添加`volatile`修饰符 |
| PFSFAsyncCompute.java:215 | Frame状态竞态 | 多线程poll/submit | 改用`ConcurrentLinkedDeque` |

### SEC-07: 客户端→伺服器信任边界

#### 🔴 严重 (Critical)

1. **`handleLoad:485-511`** — Blueprint 尺寸未验证 — 严重
   - 问题：加载蓝图后直接粘贴，未验证 `bp.getBlocks().size()`，恶意大蓝图可导致服务器崩溃
   - 修复：粘贴前添加 `if (bp.getBlocks().size() > MAX_BLOCKS) return;`

2. **`BuildModeState.decodePayload:74-96`** — BuildMode enum 未验证 — 严重
   - 问题：`BuildMode.valueOf(parts[0])` 未 try-catch，恶意 payload 可导致服务器异常
   - 修复：使用 try-catch 包装，失败时默认 `BuildMode.NORMAL`

#### 🟠 高 (High)

3. **`handleSave:449-480`** — 名称长度未验证 — 高
4. **`handlePaste:572-609`** — 剪贴簿大小未验证 — 高
5. **`handlePasteConfirm:614-632`** — 粘贴位置未验证世界边界 — 高

### SEC-08: 密钥与凭证审查

**✅ 通过** - 未发现任何硬编码凭据或敏感信息泄露

### SEC-09: 日志资讯泄露审查

| FILE:LINE | 泄露类型 | 影响 | 修复建议 |
|-----------|----------|------|----------|
| DeltaUndoManager.java:108 | 玩家 UUID 记录 | 可追踪玩家活动 | 移除 UUID 或使用匿名化标识符 |
| PastePlacePacket.java:107 | 坐标信息泄露 | 暴露玩家建筑位置 | 仅在 DEBUG 级别记录坐标 |
| LitematicImporter.java:70,82 | 完整文件路径 | 暴露服务器目录结构 | 仅记录文件名 |
| FdActionPacket.java:244-247 | 异常消息暴露给客户端 | 泄露实现细节 | 使用通用错误消息 |

### SEC-10: 资源消耗 (DoS) 防护

#### ✅ 已配置限制（良好）

| FILE:LINE | 资源类型 | 当前值 | 建议 |
|-----------|----------|--------|------|
| BRConfig.java:291 | PFSFMaxIslandSize | 1,000,000 / 2,000,000 | 保持当前限制 |
| FastDesignConfig.java:50 | MAX_SELECTION_VOLUME | 125,000 / 1,000,000 | 保持当前限制 |
| BlueprintNBT.java:21 | MAX_BLOCKS | 1,048,576 | 保持当前限制 |
| BlueprintNBT.java:24 | MAX_STRUCTURES | 10,000 | 保持当前限制 |

#### ❌ 发现的问题

| 优先级 | FILE:LINE — 资源类型 — 当前状态 — 建议上限 |
|--------|-------------------------------------------|
| **P0** | `NodeGraph.java:110` — evaluate() iterations — **无限制** — 建议 MAX_EVAL_ITERATIONS = 10,000 |
| **P1** | `UnionFind.java:13` — UnionFind nodes — **无限制** — 建议 MAX_NODES = 100,000 |
| **P1** | `SparseVoxelOctree.java:63` — sections — **无限制** — 建议 MAX_SECTIONS = 100,000 |

### SEC-11: 网络封包大小限制

| PACKET_CLASS:LINE | 问题 | 上限建议 | 修复 |
|-------------------|------|----------|------|
| StressSyncPacket:43 | decode 无 try-catch 保护 | 建议添加 | 添加 try-catch 包裹 buf 读取操作 |
| AnchorPathSyncPacket:82 | decode 无 try-catch 保护 | 建议添加 | 添加 try-catch 包裹 buf 读取操作 |
| ChiselSyncPacket:62 | decode 无 try-catch 保护 | 建议添加 | 添加 try-catch 包裹 buf 读取操作 |
| ChiselControlPacket:52 | encode/decode String 长度不一致 | 建议统一为 256 | 将 encode 的 writeUtf 第二个参数改为 256 |
| OpenCadScreenPacket:28 | Blueprint 传输无分片机制/大小限制 | 建议添加 MAX_BLUEPRINT_SIZE | 在 encode/decode 中添加 Blueprint 大小检查 |
| FdActionPacket:113 | payload 限制 512 超出建议值 256 | 建议改为 256 | 将 writeUtf/readUtf 的 512 改为 256 |

### SEC-12: 第三方依赖漏洞

| 依赖名称 | 版本 | 已知 CVE | 建议升级版本 |
|---------|------|---------|-------------|
| **ForgeGradle** | 6.0.24 | CVE-2024-47554, CVE-2023-2976 (7.1 High) | 6.0.36+ |
| **Minecraft Forge** | 1.20.1-47.4.13 | CVE-2023-2976, CVE-2023-34462 | 47.5.0+ |
| **opencascade.js** | 2.0.0-beta.b5ff984 | ⚠️ **PRE-RELEASE BETA** | 生产环境避免使用 |

---

## WAVE 2: 稳定性扫描结果

### STB-01: PFSF Engine NullPointer 路径

| FILE | LINE | 条件 | 影响 | 修复 |
|------|------|------|------|------|
| PFSFSparseUpdate.java | 147-148 | `allocateStagingBuffer` / `mapBuffer` 未检查 `isAvailable()` | **Crash** | 添加 `if (!VulkanComputeContext.isAvailable()) return;` |
| PFSFStressExtractor.java | 69 | `allocateStagingBuffer` 未检查 `isAvailable()` | **Crash** | 添加 guard 或 try-catch |
| PFSFEngine.java | 148 | `materialLookup` 调用依赖下游 null 检查 | **Silent Bug** | 调用前添加 `if (materialLookup == null) return;` |
| PFSFVCycleRecorder.java | 53 | `buf.getHFieldBuf()` 返回值未检查 | **Vulkan Error** | 添加 `if (buf.getHFieldBuf() == 0) return;` |

### STB-02: FastDesign 客户端 NullPointer

| 文件 | 行号 | NPE 条件 | 影响 | 修复 |
|------|------|----------|------|------|
| `NodeRegistry.java` | 55-61 | create() 在 registerAll() 前调用返回 null | 节点图加载失败 | 添加自动初始化机制 |
| `HologramState.java` | 29-47 | ServerLevel 变更后 Blueprint 引用失效 | 切换世界后崩溃 | 添加 level 变更监听和清除机制 |
| `PieMenuScreen.java` | 234 | Minecraft.getInstance() 可能返回 null | 执行操作时崩溃 | 添加 mc == null 检查 |
| `SelectionOverlayRenderer.java` | 126-127 | getInstance() 后无 null 检查 | 渲染时崩溃 | 添加 null 检查 |

### STB-03: Forge 事件系统稳定性

| 文件 | 行号 | 事件类型 | 问题 | 修复 |
|------|------|----------|------|------|
| `ServerTickHandler.java` | 51 | ServerTickEvent | 缺少try-catch保护 | 添加try-catch包裹 |
| `ServerTickHandler.java` | 111 | LevelTickEvent | 缺少try-catch保护 | 添加try-catch包裹 |
| `ChunkEventHandler.java` | 24 | ChunkEvent.Unload | 缺少空chunk防御 | 添加null检查 |
| `BlockPhysicsEventHandler.java` | 94 | BlockEvent.BreakEvent | 滥用HIGHEST优先级 | 降级为NORMAL或注释说明 |

### STB-05: 异常处理完整性审查

| FILE:LINE | 问题类型 | 影响 | 正确的处理方式 |
|-----------|----------|------|----------------|
| BlueprintIO.java:88 | GETMESSAGE_ONLY | 只记录异常消息，缺少堆栈信息 | 使用 logger.error("Error occurred", e) |
| BlueprintIO.java:326 | EMPTY_CATCH | 异常被静默吞掉 | 记录日志并重新抛出 |
| BRSDFRayMarcher.java:142 | THROWABLE_NO_STACK | Throwable被捕获但没有记录完整堆栈 | 使用 e.printStackTrace() |
| FdActionPacket.java:391 | EMPTY_CATCH | 异常被静默吞掉 | 记录日志并重新抛出 |
| FdActionPacket.java:443 | EMPTY_CATCH | 异常被静默吞掉 | 记录日志并重新抛出 |
| FdActionPacket.java:901 | EMPTY_CATCH | 异常被静默吞掉 | 记录日志并重新抛出 |

### STB-06: 初始化顺序与启动崩溃

| FILE:LINE | 初始化问题 | 崩溃条件 | 修复 |
|-----------|-----------|----------|------|
| `ClientSetup.java:93-100` | BRRenderTier/BRShaderEngine 在 RenderLevelStageEvent 中延迟初始化 | FMLClientSetupEvent 之前访问状态可能不一致 | 在 FMLClientSetupEvent 中显式初始化 |
| `ModuleRegistry.java:46-75` | 静态单例在类加载时初始化 DefaultMaterial | FMLCommonSetupEvent 之前访问可能得到未初始化状态 | 添加显式 init() 方法 |
| `VanillaMaterialMap.java:59-79` | init() 在 FMLCommonSetupEvent 中调用，但可能被提前访问 | 提前调用 getMaterial() 得到空 map | 添加初始化状态检查 |

### STB-07: 客户端/伺服器端分离

| FILE | LINE | 分离问题 | 崩溃条件 | 修复 |
|------|------|----------|----------|------|
| `AnchorPathRenderer.java` | 35 | 未标注 @OnlyIn(Dist.CLIENT) | dedicated server crash | 添加 `@OnlyIn(Dist.CLIENT)` |
| `ChiselMeshBuilder.java` | 18 | 未标注 @OnlyIn(Dist.CLIENT) | dedicated server crash | 添加 `@OnlyIn(Dist.CLIENT)` |
| `RBlockEntityRenderer.java` | 9 | 未标注 @OnlyIn(Dist.CLIENT) | dedicated server crash | 添加 `@OnlyIn(Dist.CLIENT)` |
| `SectionMeshCompiler.java` | 31 | 未标注 @OnlyIn(Dist.CLIENT) | dedicated server crash | 添加 `@OnlyIn(Dist.CLIENT)` |

### STB-09: 数组越界与整数溢位

| 文件 | 行号 | 问题类型 | 风险等级 |
|------|------|----------|----------|
| LitematicImporter.java | 152 | 除零风险 | 高 |
| Blueprint.java | 166 | 负数索引 | 中 |
| Blueprint.java | 134 | 整数溢位 | 中 |
| VoxelGrid.java | 47 | 溢位风险 | 中 |
| BRMeshletEngine.java | 397 | 数组越界 | 中 |

---

## 修复优先级建议

### 🔴 P0 - 立即修复 (严重安全/崩溃风险)

1. **FdActionPacket.java:485-511** - Blueprint 尺寸未验证 (DoS)
2. **FdActionPacket.java:74-96** - BuildMode enum 未验证 (崩溃)
3. **NodeGraph.java:110** - evaluate() 无迭代限制 (死循环/DoS)
4. **PFSFSparseUpdate.java:147-148** - Vulkan 未检查 isAvailable() (崩溃)
5. **ForgeGradle 6.0.24** - 已知 CVE 漏洞

### 🟠 P1 - 高优先级修复

1. 所有 decode() 方法添加 try-catch 保护 (11+ 个封包类)
2. BlueprintIO.java 路径遍历防护改进
3. UnionFind / SparseVoxelOctree 添加上限
4. 客户端/服务器分离问题 (4 个类)
5. 空 catch 块修复 (24 个位置)

### 🟡 P2 - 中优先级修复

1. 日志敏感信息泄露
2. 并发竞态问题
3. 初始化顺序问题
4. 数组越界风险

### 🟢 P3 - 低优先级/建议

1. 代码风格改进
2. 文档完善
3. 测试覆盖

---

## 生成的报告文件

- `/mnt/okcomputer/output/security_audit_report.md`
- `/mnt/okcomputer/output/nbt_json_security_audit.md`
- `/mnt/okcomputer/output/concurrency_audit_report.md`
- `/mnt/okcomputer/output/trust_boundary_audit_report.md`
- `/mnt/okcomputer/output/dos_audit_report.md`
- `/mnt/okcomputer/output/network_packet_security_audit.md`
- `/mnt/okcomputer/output/dependency_security_audit_report.md`
- `/mnt/okcomputer/output/pfsf_npe_audit_report.md`
- `/mnt/okcomputer/output/npe_audit_report.md`
- `/mnt/okcomputer/output/event_stability_report.md`
- `/mnt/okcomputer/output/vulkan_memory_leak_audit_report.md`
- `/mnt/okcomputer/output/java_exception_audit_final.txt`
- `/mnt/okcomputer/output/mod_init_audit_report.md`
- `/mnt/okcomputer/output/client_server_audit_report.md`
- `/mnt/okcomputer/output/array_overflow_audit_report.md`

---

## 审计结论

BLOCK REALITY v1 STABLE 代码库整体结构良好，但存在以下关键问题需要立即关注：

1. **信任边界验证不完整** - 多个封包处理器缺少输入验证
2. **资源限制缺失** - 多个核心组件缺少上限保护
3. **异常处理不完整** - 大量空 catch 块和缺少堆栈记录
4. **客户端/服务器分离问题** - 4 个类缺少 @OnlyIn 注解
5. **依赖漏洞** - ForgeGradle 存在已知 CVE

建议优先修复 P0 级别问题，然后进行 P1 级别修复，以确保系统的安全性和稳定性。
