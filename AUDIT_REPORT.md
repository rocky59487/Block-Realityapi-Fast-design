# Block Reality 全專案深度靜態掃描與架構審核報告

**專案**: Block Reality — Minecraft Forge 1.20.1 (47.4.13) 結構物理模組  
**審核日期**: 2026-04-10  
**審核範圍**: `api/` (361 Java 檔)、`fastdesign/` (263 Java 檔)、`brml/` (36 Python 檔)、`libpfsf/` (C++ 核心)  
**總發現問題數**: **58 條重大問題**（含 Critical / High / Medium）

---

## 前 10 大問題（詳細分析）

### 1. `BlockRealityMod.java` / `FdCreativeTab.java` — 阻斷性編譯錯誤

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/BlockRealityMod.java:47`<br>`fastdesign/src/main/java/com/blockreality/fastdesign/registry/FdCreativeTab.java:16` |
| **嚴重度** | 🔴 Critical |
| **問題描述** | Forge 1.20.1 不存在 `ForgeRegistries.CREATIVE_MODE_TABS`。該符號是 1.20.2+ 現代化 Registry 重構後才加入的。 |
| **觸發影響** | `api` 與 `fastdesign` 模組均無法編譯，`./gradlew build` 直接失敗，阻斷所有開發與發布流程。 |
| **修復建議** | 改為 `net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB`： |

```java
DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, MOD_ID);
```

---

### 2. `PFSFBufferManager.freeAll()` — 違反引用計數語義導致 UAF / Double-Free

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/physics/pfsf/PFSFBufferManager.java:115-121` |
| **嚴重度** | 🔴 Critical |
| **問題描述** | 文件註解聲稱 A4-fix 使用 `release()` 進行引用計數保護，但 `freeAll()` 直接呼叫 `buf.free()`。若 `shutdown()` 時仍有 async frame in flight（`batch` 已提交但 callback 未執行），`free()` 會立即銷毀 Vulkan buffer，而回調中的 `finalBuf.release()` 會對已釋放記憶體再次操作，導致 **Use-After-Free** 或 **Double-Free**。 |
| **修復建議** | 統一使用 `buf.release()`，讓引用計數歸零時才觸發底層 `free()`： |

```java
static void freeAll() {
    for (PFSFIslandBuffer buf : buffers.values()) {
        buf.release(); // 不是 buf.free()
    }
    buffers.clear();
    sparseTrackers.clear();
}
```

---

### 3. `PFSFEngineInstance.notifyBlockChange()` — `wasAir` 邏輯永遠為 `false`

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/physics/pfsf/PFSFEngineInstance.java:339-359` |
| **嚴重度** | 🟠 High |
| **問題描述** | 方法開頭已檢查 `if (buf == null || !buf.contains(pos)) { sparse.markFullRebuild(); return; }`，因此後續執行到的 `wasAir = !buf.contains(pos)` 中，`buf.contains(pos)` 必定為 `true`，導致 `wasAir` 永遠是 `false`。這使得 `if (newMaterial == null || wasAir)` 只在方塊**被破壞**（`newMaterial == null`）時才觸發 `incrementTopologyVersion()`，但**方塊新增**的情況永遠不會使 BFS 快取失效，殘留錯誤拓撲。 |
| **修復建議** | 移除無效的 `wasAir` 近似判斷，改由 caller（`PFSFEngine` facade 或 `BlockPhysicsEventHandler`）傳入明確的變更類型（`PLACE` / `BREAK` / `MATERIAL_CHANGE`）： |

```java
public void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial,
                               Set<BlockPos> anchors, ChangeType changeType) {
    // ...
    if (changeType == ChangeType.PLACE || changeType == ChangeType.BREAK) {
        buf.incrementTopologyVersion();
    }
}
```

---

### 4. `NodeRegistry.syncToApiNodeGraphIO()` — 註冊 null 工廠導致反序列化 NPE

| 欄位 | 內容 |
|------|------|
| **檔案** | `fastdesign/src/main/java/com/blockreality/fastdesign/client/node/NodeRegistry.java:479-491` |
| **嚴重度** | 🟠 High |
| **問題描述** | 該方法向 `api.node.NodeGraphIO` 註冊了約 150 個 fastdesign 節點，但工廠 lambda 明確回傳 `null`。當任何 server-side 或 config 載入流程透過 `api.node.NodeGraphIO` 反序列化這些節點時，會得到 `null`，後續 `node.setInput(...)` 或圖遍歷必定觸發 `NullPointerException`。 |
| **修復建議** | 直接**移除** `syncToApiNodeGraphIO()`。fastdesign 的節點圖只在 client 使用，應由 `fastdesign.client.node.NodeGraphIO` 獨自負責反序列化，無需也無法向 api 層註冊 null 工廠。 |

---

### 5. `FluidSyncPacket.decode()` — 過大封包導致玩家被強制斷線

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/network/FluidSyncPacket.java:38-52` |
| **嚴重度** | 🟠 High |
| **問題描述** | 當 `size > 8192` 時直接 `throw new IllegalStateException()`。在 Netty 的 `SimpleChannel` decode 流程中，unchecked exception 會被視為嚴重協定錯誤，導致該玩家的網路連線被強制中斷（kick）。 |
| **修復建議** | 安全丟棄異常資料，不中斷連線： |

```java
if (size > 8192) {
    return new FluidSyncPacket(new HashMap<>());
}
```

---

### 6. `CollapseEffectPacket.encode()` — 無上限封包大小，可能導致 Client OOM

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/network/CollapseEffectPacket.java:41-48` |
| **嚴重度** | 🟠 High |
| **問題描述** | `encode` 直接將 `collapseData.size()` 寫入 `int`，沒有上限檢查。若 server 端邏輯異常產生數萬筆崩塌資料（例如連鎖反應計算錯誤），會產生超過 `MAX_PACKET_BYTES`（512KB）的巨型封包，client 端 decode 時可能 OOM 或導致幀時間暴漲。 |
| **修復建議** | 在 encode 前加入硬上限截斷： |

```java
public static void encode(CollapseEffectPacket packet, FriendlyByteBuf buf) {
    int count = Math.min(packet.collapseData.size(), 65536);
    buf.writeInt(count);
    int i = 0;
    for (Map.Entry<BlockPos, CollapseInfo> entry : packet.collapseData.entrySet()) {
        if (i++ >= count) break;
        buf.writeLong(entry.getKey().asLong());
        buf.writeByte(entry.getValue().type().ordinal());
        buf.writeInt(entry.getValue().materialId());
    }
}
```

---

### 7. `StressHeatmapRenderer.onRenderLevelStage()` — Cache 邏輯形同虛設，每幀重建

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/client/StressHeatmapRenderer.java:96-99` |
| **嚴重度** | 🟠 High |
| **問題描述** | `worldTick = event.getPartialTick() == 0 ? 0 : System.nanoTime()`。由於 `getPartialTick()` 在幾乎所有渲染幀都不為 0，`worldTick` 幾乎永遠是新的 `nanoTime()`，導致 `lastRebuildTick == worldTick` 永遠不成立。這使得 `meshDirty` 的 dirty-check 機制完全失效，每幀都重建 `BufferBuilder` 並 iterate 最多 4096 筆應力資料，造成顯著的 render thread 開銷。 |
| **修復建議** | 改用穩定的 world game time： |

```java
long worldTick = Minecraft.getInstance().level != null
    ? Minecraft.getInstance().level.getGameTime()
    : 0;
```

---

### 8. `PFSFDataBuilder.updateSourceAndConductivity()` — 每 tick 數十 MB 臨時陣列分配

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/physics/pfsf/PFSFDataBuilder.java:90-202` |
| **嚴重度** | 🟠 High |
| **問題描述** | 每個 island、每個 solve tick 都會新建 `float[N]` × 4、`float[6N]`、`byte[N]` 等陣列。對於 1M 方塊級 island，這相當於每次呼叫分配約 **30–40MB** 的 heap memory。在大量 island 或高頻 tick 下，這會導致年輕代 GC 頻繁觸發（甚至晉升到老年代），造成 tick 時間不穩定（GC pause）。 |
| **修復建議** | 在 `PFSFIslandBuffer` 中配置可重複使用的 staging arrays（`float[] stagingSource`、`float[] stagingConductivity` 等），並在 `allocate()` 時依 `N` 預分配。`PFSFDataBuilder` 改為從 `buf.borrowStagingArrays()` 借用，上傳完畢後歸還。 |

---

### 9. `FdActionPacket` / `PastePlacePacket` — 缺少世界邊界與領地保護檢查

| 欄位 | 內容 |
|------|------|
| **檔案** | `fastdesign/src/main/java/com/blockreality/fastdesign/network/FdActionPacket.java`<br>`fastdesign/src/main/java/com/blockreality/fastdesign/network/PastePlacePacket.java` |
| **嚴重度** | 🟡 Medium |
| **問題描述** | `handlePlaceMulti`、`handlePaste`、`handleBuildSolid` 等操作雖檢查 `isSpectator()` 與 `requireBuildPermission()`，但**沒有**檢查目標位置是否超出 `WorldBorder`，也沒有觸發標準的 `BlockEvent.EntityPlaceEvent`。這允許玩家透過 ghost preview 在世界邊界外、受保護區域（FTBChunks / Claim）或他人領地內放置方塊，構成明顯的安全漏洞。 |
| **修復建議** | 在 `level.setBlock` 前加入邊界與事件檢查： |

```java
if (!level.getWorldBorder().isWithinBounds(pos)) {
    player.displayClientMessage(Component.literal("§c超出世界邊界"), true);
    return;
}
var event = new net.minecraftforge.event.world.BlockEvent.EntityPlaceEvent(
    level, pos, placeState, player);
if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event)) {
    player.displayClientMessage(Component.literal("§c無法在此位置放置"), true);
    return;
}
```

---

### 10. `BRRenderPipeline` 殘骸 — 大量 Dead Code 與 Deprecation Warning

| 欄位 | 內容 |
|------|------|
| **檔案** | `api/src/main/java/com/blockreality/api/client/render/pipeline/BRRenderPipeline.java`（及同目錄 `BRRTPipelineOrdering.java`、`RenderPass.java` 等） |
| **嚴重度** | 🟡 Medium |
| **問題描述** | `ClientSetup` 的 Javadoc 稱 "BRRenderPipeline 已移除"，但該檔案仍存在，且內部大量引用已被標註 `@Deprecated(forRemoval = true)` 的 `BRVulkanInterop`、`BRSVGFDenoiser`。這不僅產生 8+ 條編譯 deprecation warning，也誤導新開發者以為這些 RT 功能是現役管線，增加維護負擔與誤用風險。 |
| **修復建議** | 若確實不再使用，應直接刪除 `BRRenderPipeline.java`、`BRRTPipelineOrdering.java`、`RenderPass.java`、`RenderPassContext.java`、`RTRenderPass.java`。若仍有部分測試或實驗性程式碼依賴，應將其移入 `client/render/test/` 並明確標記為實驗性。 |

---

## 其他問題清單（共 48 條）

### 維度 1：程式碼品質與無效代碼 (Code Smell & Dead Code)

- **1-1** `api/client/render/rt/BRVulkanRT.java` (1709): 引用已標 `@Deprecated(forRemoval)` 的 `BRSVGFDenoiser`，產生 removal warning。
- **1-2** `api/network/BRNetwork.java` (50): 使用已標 removal 的 `new ResourceLocation(String, String)` 建構子。
- **1-3** `api/physics/sparse/SparseVoxelOctree.java` (359): `sectionKeyXStatic()` 被標為 deprecated 但缺少 `@Deprecated` 註解，導致 `-Xlint:dep-ann` 警告。
- **1-4** `api/client/render/ui/BRToolMask.java`: 使用 unchecked generic operations，產生 `-Xlint:unchecked` 警告。
- **1-5** `api/client/render/pipeline/BRRenderPipeline.java` (248, 257, 302, 305, 370, 371): 多處引用 `BRVulkanInterop` / `BRSVGFDenoiser`，皆為 `@Deprecated(forRemoval)`。
- **1-6** `api/physics/pfsf/VulkanComputeContext.java`: `waitFence()` 方法本身標為 `@Deprecated`，但內部仍被 `PFSFIslandBuffer` 的 `uploadSourceAndConductivity()` 間接呼叫（透過 `endSingleTimeCommandsWithFence`）。
- **1-7** `api/config/BRConfig.java`: `getVramBudgetMB()` 標為 `@Deprecated`，但 `ComputeRangePolicy` 中仍可能透過 `BRConfig.INSTANCE` 存取。
- **1-8** `fastdesign/client/node/impl/*` (約 150 個檔案): 位於 `client/` 套件下的節點類別幾乎全部缺少 `@OnlyIn(Dist.CLIENT)`，存在被 server-side 意外 import 導致 `ClassNotFoundException` 的風險（專案已有 `fix_only_in_client.py` 說明團隊意識到但未完全修復）。
- **1-9** `api/node/BRNode.java` 與 `fastdesign/client/node/BRNode.java`: 兩套同名同質的類別並存，架構職責重疊，易造成開發者混淆與維護困難。
- **1-10** `api/node/EvaluateScheduler.java` 與 `fastdesign/client/node/EvaluateScheduler.java`: 重複實作評估排程器，未明確分工。
- **1-11** `api/client/render/test/BRMemoryLeakScanner.java` 等 `test/` 目錄類別: 被編譯進正式产物中，不應出現在 `main/java`。

### 維度 2：潛在邏輯漏洞與執行緒安全 (Logic Bugs & Thread Safety)

- **2-1** `api/physics/StructureIslandRegistry.java` `registerBlock()` / `unregisterBlock()`: `members` 使用 `ConcurrentHashMap.newKeySet()`，但 `addMember`/`removeMember` 內部使用 `synchronized`；merge island 的複合操作（讀取多個 island 的 members 再寫入）並未在單一原子區塊內完成，高並發下可能產生不一致的 island 分割/合併結果。
- **2-2** `api/collapse/CollapseManager.java` `triggerCollapseAt()`: `level.getBlockEntity(pos)` 在 `removeBlockEntity` 之前有檢查，但並發情況下（如另一線程同時操作 chunk unload）可能出現 race condition，導致對已不存在的 BE 重複操作。
- **2-3** `api/blueprint/BlueprintNBT.java` `read()`: 雖有 `MAX_BLOCKS` 與 `MAX_STRUCTURES` 上限，但單個 `BlueprintBlock` 內部的 NBT（如動態材料的 `dynRcomp` 等）沒有數值範圍驗證，可能讀入 NaN / Inf。
- **2-4** `api/network/AnchorPathSyncPacket.java` `decode()`: 當 `nodeCount` 超過 65536 時，直接回傳 `new AnchorPathSyncPacket(entries)`，但此時 `entries` 可能已部分填入前面 path 的資料，導致收到殘缺路徑列表而非清空。
- **2-5** `api/network/StressSyncPacket.java` `encode()`: 沒有上限截斷，雖然 `decode` 有 65536 上限，但若 server 產生更大 map，encode 會寫出超過限制的資料，client decode 會將後續封包內容誤判為應力資料，導致封包解析錯位。
- **2-6** `api/physics/pfsf/PFSFDataBuilder.java` `updateSourceAndConductivity()`: `sigmaMax` 正規化後註解提到 `phi 不變`，但 `phase_field_evolve.comp` 與 `failure_scan.comp` 若對 `phi` 的單位假設不一致，可能導致相場演化與失效判定在不同量級上運作。
- **2-7** `api/event/ServerTickHandler.java` `onServerTick()`: PFSF、Fluid、Thermal、Wind、EM、MultiDomainCoupler 全部在單一 `ServerTickEvent.END` 中順序執行。若總和超過 50ms，會直接造成伺服器 TPS 下降，但沒有跨 tick 的剩餘工作排程機制。
- **2-8** `api/block/RBlockEntity.java` `scheduleDeferredFlush()`: 使用 `serverLevel.getServer().execute()` 排程 flush，但沒有在 `setRemoved()` 或 chunk unload 時取消 pending runnable，可能導致已移除的 BE 在 flush 時觸發 `sendBlockUpdated` 的無效同步。
- **2-9** `api/physics/pfsf/PFSFIslandBuffer.java` `flatIndex()`: 使用 `MortonCode.encode(x, y, z)`，但 `MortonCode` 的編碼範圍是否涵蓋 `Lx * Ly * Lz` 未在這裡檢查；若 island 尺寸超過 Morton 位元限制，會產生錯誤索引。
- **2-10** `api/physics/fluid/FluidGPUEngine.java`: 建構子中 `MinecraftForge.EVENT_BUS.register(this)`，監聽 `FluidBarrierBreachEvent`，但 `CollapseManager` 也可能在相同 event bus 上觸發崩塌事件，兩者互相觸發可能產生事件迴圈（event loop）。
- **2-11** `api/chisel/VoxelGrid.java` `fromLongArray()`: 當 `data.length != LONGS_NEEDED` 時僅記錄 warning 並用 zero padding，這會默默吞掉資料損毀錯誤，導致玩家看到的雕刻形狀與預期不符。
- **2-12** `api/item/ChiselItem.java` `handleCustomMode()`: `hitVx`/`hitVy`/`hitVz` 使用 `(int)((hit.x - pos.getX()) * 10)`，當 hit 座標恰好為負邊界（如 `-0.0`）時，`int` 截斷可能得到 `-1`，雖然後面有 `Mth.clamp`，但來源計算方式對邊緣情況不夠嚴謹。

### 維度 3：效能漏洞與記憶體管理 (Performance & Memory Leaks)

- **3-1** `api/client/ClientStressCache.java` `evictLowStress()`: 使用 `accessTime.entrySet().stream().sorted().collect(Collectors.toList())`，時間複雜度 O(n log n)，且每次 eviction 都複製整個 map。當快取達到 4096 上限時，頻繁觸發會產生大量臨時 ArrayList。
- **3-2** `api/physics/pfsf/PFSFIslandBuffer.java` `uploadBlockOffsets()` / `uploadFloatBufferToHandle()`: 每個獨立上傳方法都呼叫 `beginSingleTimeCommands()` + `endSingleTimeCommands()`，產生額外的 queue submit / fence wait。應合併為單一 command buffer 批次上傳。
- **3-3** `api/physics/pfsf/PFSFIslandBuffer.java` `readMaxPhi()`: 標記為 `@Deprecated`，但仍可能被舊程式碼呼叫。該方法讀回整個 `phi[]`（1M voxels = 4MB）到 CPU 再線性掃描，頻寬與 CPU 開銷極大。
- **3-4** `api/block/RBlockEntity.java` `saveAdditional()` / `load()`: 每次 chunk save/load 都會完整序列化 `chiselState`。若 `chiselState` 為 `CUSTOM`，會額外寫入 16 個 long（128 bytes）。雖然單筆不大，但大量客製化雕刻的區域會顯著增加 NBT 體積與 I/O 時間。
- **3-5** `api/physics/pfsf/PFSFDispatcher.java` `recordPhaseFieldEvolve()`: 每次錄製都透過 `stackPush()` 建立 descriptor set，雖有 `descriptorPoolMgr.tickResetIfNeeded()` 週期性重置，但若 frame 數超過 pool 容量，會導致 `ds == 0` 並直接 return，跳過該 island 的相場演化，卻不會標記錯誤或重試。
- **3-6** `api/physics/fluid/FluidGPUEngine.java` `tick()`: `FluidAsyncCompute.pollCompleted()` 與 `tickRegionGPU()` 都在同一 tick 內執行，但 `pollCompleted()` 沒有返回值，無法知道有多少 frame 完成，也就無法根據完成情況動態調整本 tick 的 dispatch 數量。
- **3-7** `api/client/render/pipeline/BRRenderPipeline.java` `init()`: 初始化時一口氣呼叫 20+ 個子系統的 `init()`，其中包含大量 GL/Vulkan 物件建立。若任何一個子系統初始化失敗，沒有回滾（rollback）機制，已建立的資源會洩漏。
- **3-8** `api/physics/pfsf/PFSFAsyncCompute.java` `init()`: `vkCreateFence` 失敗時直接 `return` 且不設定 `initialized = true`，但前面已建立的 fence / command buffer 不會被清理，導致資源洩漏。
- **3-9** `api/physics/thermal/ThermalEngine.java` `tick()`: 每 tick 新建 `HashMap<BlockPos, Float> stresses` 並 `putAll()` 所有區域的熱應力。區域數多時臨時 map 很大，且沒有重複使用。
- **3-10** `api/physics/wind/WindEngine.java` `tick()`: 每個 dirty region 都會 `new float[n]` 用於 velocity 陣列（若該 region 首次出現），對於大區域會產生數 MB 的臨時分配。

### 維度 4：模組間溝通與架構解耦 (Architecture & Coupling)

- **4-1** `api/physics/pfsf/PFSFEngine.java`: 全 static facade 設計，內部持有 singleton `instance`。雖然有 `PFSFEngineInstance`，但無法進行單元測試（難以 mock），也無法支援多維度（Overworld / Nether / End）隔離運算。
- **4-2** `api/physics/StructureIslandRegistry.java`: 全 static singleton，所有維度的 block 都登記在同一張 map 中。目前邏輯大多只處理 Overworld，但架構上難以擴展到多維度伺服器。
- **4-3** `api/physics/ConnectivityCache.java`: 全 static singleton，與 `StructureIslandRegistry` 同樣存在多維度資料混雜問題。
- **4-4** `api/collapse/CollapseManager.java`: 全 static singleton，queue 和 overflow buffer 無法按 dimension 隔離。
- **4-5** `api/event/ServerTickHandler.java` 與 `BlockRealityMod`: 兩者都向 `MinecraftForge.EVENT_BUS` 註冊 `ServerTickEvent` 監聽器，存在多個入口點，難以追蹤事件處理順序與重複執行風險。
- **4-6** `api/physics/fluid/FluidGPUEngine.java`: 實作 `IFluidManager` SPI，但同時又是 singleton 且直接向 Forge EventBus 註冊自身，違反了 SPI 應由提供者（provider）統一生命週期管理的原則。
- **4-7** `api/spi/ModuleRegistry.java`: 使用 static mutable state (`setVS2Bridge()`)，沒有 thread-safe 的發布機制，可能在伺服器啟動過程中被多個模組同時修改。
- **4-8** `api/client/render/pipeline/BRRenderPipeline.java`: 管線內硬編碼引用 `BRVulkanDevice`、`BRVulkanRT`、`BRVulkanInterop` 等 client-only RT 類別，雖然整個類標註 `@OnlyIn(Dist.CLIENT)`，但這種深度耦合使得未來要移除 RT 功能時必須清理大量交叉引用。

### 維度 5：Forge API 正確性與最佳實踐 (Forge API Best Practices)

- **5-1** `api/network/PFSFStressSyncPacket.java` `handle()` 與 `CollapseEffectPacket.java` `handle()`: 使用 `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)` 引用 `ClientStressCache` 等 client-only 類別。雖然 Forge 1.20.1 中這是安全的，但更嚴謹的做法應將 handler 完全移到 client proxy 中，避免未來 Forge 版本變更導致的 classloading 風險。
- **5-2** `api/BlockRealityMod.java` (64, 74) 與 `fastdesign/FastDesignMod.java` (41, 48): 仍使用 `FMLJavaModLoadingContext.get()` 與 `ModLoadingContext.get()`，兩者皆已標註 `@Deprecated(forRemoval = true)`。雖然 1.20.1 仍可用，但升級 Forge 版本時會直接編譯失敗。
- **5-3** `api/registry/BRBlocks.java`: `R_CONCRETE_ITEM` 等 BlockItem 的 supplier 中直接呼叫 `R_CONCRETE.get()`。雖然 Forge 按 registry type 順序處理（BLOCKS 先於 ITEMS），但這依賴實作細節，存在潛在的初始化順序風險。
- **5-4** `api/client/StressHeatmapRenderer.java`: 使用 `RenderSystem.disableDepthTest()` 繪製應力覆蓋層，導致應力框會穿透方塊顯示在背後，視覺效果不符合物理直覺（應只顯示可見表面）。
- **5-5** `api/client/render/pipeline/BRRenderPipeline.java`: `init()` 中直接呼叫 `Minecraft.getInstance().getWindow().getWidth()`，但在某些模組加載器或 early init 階段，window 可能尚未建立，導致 NPE。
- **5-6** `api/block/RBlock.java`: 若覆寫 `getShape()` 或 `getCollisionShape()` 時未正確使用 `RBlockEntity` 的 `cachedCustomShape`，每次呼叫都會重建 `VoxelShape`，對 entity collision 的熱路徑影響極大（需要確認實際實作）。

### 維度 6：安全性與防呆機制 (Security & Edge Cases)

- **6-1** `fastdesign/command/FdCommandRegistry.java` `/fd box <material>`、`/fd save <name>`: 使用 `StringArgumentType.word()`，沒有長度上限過濾。雖然 `BlueprintIO.sanitizeName()` 會清理，但指令層的 tab completion 與錯誤訊息可能暴露檔案系統資訊。
- **6-2** `fastdesign/network/FdActionPacket.java` `decode()`: `payload` 限制為 512 字元，但沒有對內容進行 sanitize（如禁止控制字元或 path separator）。雖然多數 handler 會忽略非法內容，但 `handleSave`/`handleLoad` 直接將其傳入 `BlueprintIO.save()`，依賴後端的 sanitize。
- **6-3** `api/network/ChiselControlPacket.java` `decode()`: `payload` 限制為 64 字元，但沒有檢查是否包含非法字元或不符合 `SubBlockShape` 名稱的內容，可能導致無效的 shape 名稱被設定到 ItemStack NBT。
- **6-4** `api/blueprint/BlueprintIO.java` `importAndSaveLitematic()`: 從 `.litematic` 匯入後直接以 `bp.getName()` 作為檔名儲存，雖然 `sanitizeName` 會處理，但 `bp.getName()` 若來自不可信的外部檔案，仍需加強驗證。
- **6-5** `api/physics/pfsf/VulkanComputeContext.java` `tryShareBRVulkanDevice()`: 透過反射存取標註 `@OnlyIn(Dist.CLIENT)` 的 `BRVulkanDevice`，雖然會 catch `ClassNotFoundException`，但如果 client class 存在但 static initializer 失敗，會拋出 `ExceptionInInitializerError`，目前的 catch 區塊雖能捕捉 `Throwable`，但沒有區分處理，可能掩蓋真正的初始化錯誤。
- **6-6** `api/material/DefaultMaterial.java` `BEDROCK`: 強度設為 `1e9` MPa 並透過 `isIndestructible()` 短路，但任何外部程式碼若未檢查 `isIndestructible()` 而直接將 `1e9` 用於 double 運算（如應力比計算），仍可能觸發極大數值問題。目前看來無風險，但依賴呼叫端紀律。
- **6-7** `api/physics/pfsf/PFSFEngineInstance.java` `onServerTick()`: `setWindVector()` 可由任何執行緒設定，但 `currentWindVec` 是 volatile 卻沒有防禦性拷貝。若 `Vec3` 實例被修改（雖然 Minecraft 的 Vec3 是不可變的，但若傳入子類別則不一定），會造成 race。
- **6-8** `api/physics/pfsf/PFSFDataBuilder.java` `buildMortonLayout()`: 使用 `Integer[]` 進行排序，對於大型 island（1M voxels）會產生約 1M 個 `Integer` 物件，GC 壓力極大。應改用 `int[]` + 自訂 quicksort。
- **6-9** `api/physics/pfsf/PFSFIslandBuffer.java` `allocate()`: 當 `N = Lx * Ly * Lz` 過大時（如 1M），`float6N` 計算使用 `long` 但後續 `stagingSize = float6N` 直接轉型為 `long`，雖然沒有溢位風險，但 `allocateDeviceBuffer` 的簽名若不接受 `long` 可能隱含截斷（需確認底層）。
- **6-10** `api/physics/fluid/FluidGPUEngine.java` `getOrCreateBuffer()`: 該方法在程式碼中被 `tickRegionGPU()` 呼叫，但從原始碼片段看 `getOrCreateBuffer` 似乎沒有明確實作（或為 private），若 `FluidBufferManager` 未正確清理舊 region buffer，會造成 VRAM 洩漏。

### 維度 7：全專案連貫性與跨語言介面 (Consistency & FFI)

- **7-1** `api/node/NodeGraphIO.java` 與 `fastdesign/client/node/NodeGraphIO.java`: 兩套完全獨立的序列化/反序列化邏輯。`NodeRegistry` 已移除同步，但 `api.node.NodeGraphIO` 中仍可能存在對 fastdesign 節點的預期，導致 JSON 格式不一致。
- **7-2** `api/client/render/rt/BRSVGFDenoiser.java` 與 `BRVulkanInterop.java`: 標註 `@Deprecated(forRemoval = true)`，但 `BRRenderPipeline` 與 `BRVulkanRT` 仍大量引用，形成「標記移除卻無法移除」的僵局。
- **7-3** `brml/brml/export/onnx_export.py`: ONNX 匯出邏輯與 Java 端的 `OnnxPFSFRuntime.java` 之間的 tensor shape contract 沒有統一的 schema 文件驗證，版本迭代時容易出現 shape mismatch。
- **7-4** `brml/brml/models/pfsf_surrogate.py`: 使用 JAX/Flax 訓練的 surrogate model，但輸入特徵（如 `armMap`、`archFactorMap`）的生成邏輯與 Java 端 `PFSFSourceBuilder` 是否完全一致，缺乏自動化一致性測試。
- **7-5** `libpfsf/src/core/vulkan_context.cpp` 與 Java `VulkanComputeContext.java`: C++ 側的 Vulkan context 建立邏輯與 Java 側（LWJGL）存在重複，且 JNI bridge 目前看來並未啟用（Java 端直接使用 LWJGL），`libpfsf` 可能處於半閒置狀態。
- **7-6** `libpfsf/src/solver/jacobi_solver.cpp` 與 Java `FluidJacobiRecorder.java`: 兩者都實作了 Jacobi solver，一個在 C++、一個在 Java GPU compute shader，維護雙重實作會導致算法修正時遺漏其中一側。
- **7-7** `brml/brml/data/blueprint_loader.py`: 載入 `.brblp`（gzip NBT）時使用 `nbtlib` 或自訂解析，但 Python 端對 `MAX_BLOCKS` 等安全上限的檢查是否與 Java 端 `BlueprintNBT` 一致，尚未確認。
- **7-8** `api/physics/pfsf/OnnxPFSFRuntime.java` `infer()`: 回傳 `InferenceResult`，但 `applyMLResult()` 中對 `materialLookup == null` 的 fallback 使用硬編碼 `30.0f` MPa（concrete）。若新增材料或修改 `DefaultMaterial`，此處不會同步更新。

---

## 結論與行動建議

### 立即行動（P0 — 本日完成）
1. 修復 `CREATIVE_MODE_TABS` 編譯錯誤（`BlockRealityMod.java` + `FdCreativeTab.java`）。
2. 將 `PFSFBufferManager.freeAll()` 改為 `buf.release()`，避免 UAF。

### 高優先（P1 — 本週完成）
3. 修復 `notifyBlockChange` 的 `wasAir` 邏輯漏洞。
4. 移除 `NodeRegistry.syncToApiNodeGraphIO()` 的 null 工廠污染。
5. 將 `FluidSyncPacket` 的異常改為安全丟棄。
6. 為 `CollapseEffectPacket.encode()` 加入大小上限。
7. 修復 `StressHeatmapRenderer` 的 cache 邏輯。

### 中優先（P2 — 下週完成）
8. 為 `PFSFDataBuilder` 引入 staging array pool，降低 GC 壓力。
9. 在 `FdActionPacket` / `PastePlacePacket` 中加入 `WorldBorder` 與 `BlockEvent` 檢查。
10. 清理 `BRRenderPipeline` 等 dead code 與 deprecation warning。
11. 批次為 fastdesign client-only 類別補上 `@OnlyIn(Dist.CLIENT)`。

### 長期改善（P3）
12. 將 static singleton（`PFSFEngine`、`StructureIslandRegistry`、`CollapseManager`）重構為 per-dimension 實例，支援多維度伺服器。
13. 建立 `brml/` ↔ `api/` 的 ONNX shape contract 自動化測試，防止 ML 模型與 Java runtime 脫節。
14. 評估 `libpfsf/` 的實際使用狀況，決定整合 JNI bridge 或移除以減少維護負擔。
