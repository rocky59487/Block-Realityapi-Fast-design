# Fast Design 模組 — 完整開發手冊 v1.0

> **前置依賴**：Block Reality API（已通過 v7.1 嚴格審核，83/100 B+，所有 bug 已修復）
> **平台**：Minecraft Forge 1.20.1-47.2.0 / JDK 17 / Official Mappings
> **開發語言**：Java（Forge 端）+ TypeScript（Node.js Sidecar）
> **模組 ID**：`blockreality_fastdesign`

---

## 目錄

1. [模組定位與架構總覽](#1-模組定位與架構總覽)
2. [與 Block Reality API 的介面契約](#2-與-block-reality-api-的介面契約)
3. [CLI 指令系統（Brigadier）](#3-cli-指令系統brigadier)
4. [三視角 CAD 介面](#4-三視角-cad-介面)
5. [藍圖系統（Blueprint）](#5-藍圖系統blueprint)
6. [TypeScript Sidecar 整合（NURBS 輸出）](#6-typescript-sidecar-整合nurbs-輸出)
7. [開發規範與約定](#7-開發規範與約定)
8. [可參考的開源模組與關鍵類別](#8-可參考的開源模組與關鍵類別)
9. [開發排程與里程碑](#9-開發排程與里程碑)
10. [已知風險與降級策略](#10-已知風險與降級策略)
11. [網路封包系統（Network Packets）](#11-網路封包系統network-packets)
12. [各模組完成標準與工時估算](#12-各模組完成標準與工時估算)
13. [TypeScript Sidecar 介面規範](#13-typescript-sidecar-介面規範)
14. [Construction Intern 全息投影預覽](#14-construction-intern-全息投影預覽)
15. [施工工序狀態機預覽](#15-施工工序狀態機預覽)

---

## 1. 模組定位與架構總覽

### 1.1 在三模組架構中的位置

```
Block Reality 三模組架構
│
├─ 底層：Block Reality API（純 Java，已完成）
│   ├─ RMaterial / RBlock / RStructure 資料層
│   ├─ UnionFindEngine / SupportPathAnalyzer / SPHStressEngine 計算層
│   ├─ ForceEquilibriumSolver / BeamStressEngine 力學引擎
│   ├─ SidecarBridge / NurbsExporter Sidecar 基礎設施
│   └─ BRNetwork / StressSyncPacket 網路同步
│
├─ 模組一：Fast Design（本手冊）← 設計工具
│   ├─ CLI 指令系統（/fd 指令族）
│   ├─ CAD 介面（三視角 + 透視預覽）
│   ├─ 藍圖系統（NBT + GZIP 打包，含 R 氏數據）
│   └─ NURBS 輸出管線（Java → Sidecar → TypeScript → OBJ/NURBS）
│
└─ 模組二：Construction Intern（施工模組，後續開發）
    ├─ 藍圖全息投影（幽靈方塊）
    ├─ 施工工序狀態機
    └─ R 氏應力掃描儀
```

### 1.2 Fast Design 的核心職責

Fast Design 是「設計階段」的工具集，讓玩家能夠：

1. **快速建造結構** — 透過 `/fd box`、`/fd extrude`、`/fd rebar-grid` 等指令批量操作
2. **視覺化確認** — CAD 介面提供三視角正交投影 + 3D 透視預覽
3. **保存與分享設計** — 藍圖系統將選取區域打包為 `.brblp` 檔案，含完整 R 氏結構數據
4. **輸出工程級模型** — 透過 TypeScript Sidecar 執行 Dual Contouring → PCA → NURBS 管線，產出 `.obj` 檔

### 1.3 套件結構

```
src/main/java/com/blockreality/fastdesign/
├── FastDesignMod.java              ← @Mod 入口，modid="blockreality_fastdesign"
├── command/                         ← CLI 指令系統
│   ├── FdCommandRegistry.java       ← RegisterCommandsEvent 總註冊
│   ├── FdBoxCommand.java            ← /fd box
│   ├── FdExtrudeCommand.java        ← /fd extrude
│   ├── FdRebarGridCommand.java      ← /fd rebar-grid
│   ├── FdSaveCommand.java           ← /fd save
│   ├── FdLoadCommand.java           ← /fd load
│   ├── FdExportCommand.java         ← /fd export（觸發 Sidecar）
│   ├── FdSelectCommand.java         ← /fd pos1, /fd pos2
│   └── PlayerSelectionManager.java  ← WorldEdit 風格兩點選取
├── client/                          ← Client-only（CAD 介面）
│   ├── FastDesignScreen.java        ← 主 Screen 類別
│   ├── ClientSelectionHolder.java   ← Client 端選取狀態
│   ├── OrthoViewRenderer.java       ← 正交投影渲染
│   └── Preview3DRenderer.java       ← 3D 透視預覽（RenderLevelStageEvent）
├── blueprint/                       ← 藍圖系統
│   ├── Blueprint.java               ← 數據結構
│   ├── BlueprintNBT.java            ← NBT 序列化/反序列化
│   └── BlueprintIO.java             ← GZIP 檔案存取 + paste
├── sidecar/                         ← TypeScript Sidecar 整合
│   └── NurbsSidecar.java            ← ProcessBuilder + JSON 通訊
└── network/                         ← Fast Design 專屬封包
    ├── FdSelectionSyncPacket.java   ← Server→Client 選取同步
    └── FdNetwork.java               ← SimpleChannel 註冊
```

---

## 2. 與 Block Reality API 的介面契約

### 2.1 Fast Design 使用的 API 類別

| API 類別 | 用途 | 呼叫時機 |
|----------|------|---------|
| `RMaterial` | 讀取材料屬性（Rcomp, Rtens, density 等） | CLI 指令填充方塊時 |
| `RBlockEntity` | 存取方塊物理狀態 | 藍圖保存/載入時 |
| `RBlock` | 方塊邏輯層（BlockState + Material） | 所有方塊操作 |
| `BlockType` | 方塊類型（PLAIN/REBAR/CONCRETE/RC_NODE） | 藍圖序列化 |
| `DefaultMaterial` | 預設材料 enum | CLI 材料參數解析 |
| `VanillaMaterialMap` | 原版方塊→材料映射 | 藍圖保存非 R 方塊 |
| `SidecarBridge` | Java↔TypeScript IPC 橋接 | `/fd export` 呼叫 |
| `NurbsExporter` | NURBS 匯出（已含於 API） | `/fd export` 底層 |
| `BRNetwork.CHANNEL` | 網路頻道 | 選取區域同步 |
| `BRConfig` | 全域設定 | 讀取限制參數 |

### 2.2 關鍵介面契約

```java
// ── 材料查詢 ──
RMaterial mat = DefaultMaterial.CONCRETE;        // 預設材料
RMaterial custom = CustomMaterial.get("custom_id"); // 自訂材料
double rcomp = mat.getRcomp();                   // MPa
double density = mat.getDensity();               // kg/m³

// ── 方塊操作（透過 RBlockManager 靜態方法） ──
RBlockManager.setBlock(level, pos, material);     // 放置 R 方塊
RBlockManager.removeBlock(level, pos);            // 移除 R 方塊
RBlock rb = RBlockManager.getBlock(level, pos);   // 查詢 R 方塊

// ── Sidecar 呼叫 ──
SidecarBridge bridge = SidecarBridge.getInstance();
bridge.start();                                    // 啟動 Node.js
JsonObject result = bridge.call("nurbs.fit", params, 30_000); // RPC
bridge.stop();                                     // 關閉

// ── 網路封包 ──
BRNetwork.CHANNEL.send(                           // 已有的頻道
    PacketDistributor.PLAYER.with(() -> player),
    new FdSelectionSyncPacket(pos1, pos2)
);
```

### 2.3 系統櫃模組預留介面

v7 審核報告中設計了 SubElement API，未來系統櫃模組需要的擴充已在 API 中預留。Fast Design 應注意：

- `Blueprint` 保存格式預留 `subElements` 欄位（目前為空 List）
- `getEffectiveMaterial()` 方法是物理引擎的統一入口，Fast Design 的 `/fd box` 等指令只需設定 `RMaterial`，無需關心底層 SubElement

---

## 3. CLI 指令系統（Brigadier）

### 3.1 指令總表

| 指令 | 功能 | 權限 | 參數 |
|------|------|------|------|
| `/fd pos1` | 設定選取點 1（腳下方塊） | OP 2 | — |
| `/fd pos2` | 設定選取點 2（腳下方塊） | OP 2 | — |
| `/fd box <material>` | 選取區域填充 R 方塊 | OP 2 | material: 材料 ID |
| `/fd box <x1 y1 z1> <x2 y2 z2> <material>` | 指定座標填充 | OP 2 | 座標 + 材料 |
| `/fd extrude <direction> <distance> <material>` | 沿方向擠出選取面 | OP 2 | direction: up/down/north/south/east/west |
| `/fd rebar-grid <spacing>` | 在選取區域生成鋼筋網 | OP 2 | spacing: 間距（格數） |
| `/fd save <name>` | 選取區域保存為藍圖 | OP 2 | name: 藍圖名稱 |
| `/fd load <name>` | 載入藍圖到世界 | OP 2 | name: 藍圖名稱 |
| `/fd export [format]` | 匯出選取區域為 OBJ/NURBS | OP 2 | format: obj(預設)/nurbs |
| `/fd cad` | 開啟 CAD 介面 | OP 2 | — |
| `/fd undo` | 還原上一個操作 | OP 2 | — |

### 3.2 指令註冊架構

**關鍵參考：** Mojang Brigadier — https://github.com/Mojang/brigadier

```java
// FdCommandRegistry.java — 統一註冊入口
@Mod.EventBusSubscriber(modid = "blockreality_fastdesign", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FdCommandRegistry {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        // 每個子指令各自 register，Brigadier 自動 merge 同名 literal 節點
        FdSelectCommand.register(dispatcher);
        FdBoxCommand.register(dispatcher);
        FdExtrudeCommand.register(dispatcher);
        FdRebarGridCommand.register(dispatcher);
        FdSaveCommand.register(dispatcher);
        FdLoadCommand.register(dispatcher);
        FdExportCommand.register(dispatcher);
        FdCadCommand.register(dispatcher);
        FdUndoCommand.register(dispatcher);
    }
}
```

### 3.3 PlayerSelectionManager 設計

仿 WorldEdit 兩點選取。儲存在 server 端記憶體（伺服器重啟清空）。

**關鍵參考：** WorldEdit `ClipboardHolder` — https://github.com/EngineHub/WorldEdit

```java
// 核心數據結構
public class PlayerSelectionManager {
    private static final PlayerSelectionManager INSTANCE = new PlayerSelectionManager();
    private final Map<UUID, BlockPos> pos1Map = new HashMap<>();
    private final Map<UUID, BlockPos> pos2Map = new HashMap<>();

    public record SelectionBox(BlockPos min, BlockPos max) {
        public int volume() { /* ... */ }
        public Iterable<BlockPos> allPositions() {
            return BlockPos.betweenClosed(min, max);
        }
    }
}
```

### 3.4 Tab 自動補全

材料參數需要支援 Tab 補全，讓玩家快速選擇：

```java
// 自訂 SuggestionProvider
private static final SuggestionProvider<CommandSourceStack> MATERIAL_SUGGESTIONS =
    (ctx, builder) -> {
        for (DefaultMaterial mat : DefaultMaterial.values()) {
            builder.suggest(mat.getMaterialId());
        }
        // 也包含自訂材料
        for (String id : CustomMaterial.getAllIds()) {
            builder.suggest(id);
        }
        return builder.buildFuture();
    };

// 使用
Commands.argument("material", StringArgumentType.word())
    .suggests(MATERIAL_SUGGESTIONS)
```

### 3.5 /fd rebar-grid 鋼筋網生成演算法

這是 Fast Design 的核心特色指令，自動生成 RC 工法所需的鋼筋網格：

```java
/**
 * 在選取區域內，以指定間距生成三向（X/Y/Z）鋼筋網格。
 *
 * 演算法：
 *   for each axis (X, Y, Z):
 *     for each position along that axis at `spacing` intervals:
 *       fill a line of REBAR blocks along the other two axes
 *
 * 間距規範：
 *   - spacing ≥ 1（至少每格一根）
 *   - spacing ≤ rc_fusion.rebar_spacing_max（Config，預設 3）
 *   - 超過 max → 警告玩家「間距過大，可能觸發蜂窩弱點」
 */
public static int generateRebarGrid(ServerLevel level, SelectionBox box,
                                     int spacing, RMaterial rebarMaterial) {
    int count = 0;
    BlockPos min = box.min(), max = box.max();

    for (int x = min.getX(); x <= max.getX(); x++) {
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                boolean onXGrid = (x - min.getX()) % spacing == 0;
                boolean onYGrid = (y - min.getY()) % spacing == 0;
                boolean onZGrid = (z - min.getZ()) % spacing == 0;

                // 三向中任兩向交叉處放置鋼筋
                int axisCount = (onXGrid ? 1 : 0) + (onYGrid ? 1 : 0) + (onZGrid ? 1 : 0);
                if (axisCount >= 2) {
                    BlockPos pos = new BlockPos(x, y, z);
                    RBlockManager.setBlock(level, pos, rebarMaterial, BlockType.REBAR);
                    count++;
                }
            }
        }
    }
    return count;
}
```

### 3.6 /fd undo 還原系統

使用簡易快照 stack，每次操作前記錄受影響區域的 BlockState + NBT：

```java
public class UndoManager {
    private static final int MAX_UNDO_STACK = 10;
    private static final Map<UUID, Deque<UndoSnapshot>> stacks = new HashMap<>();

    public record BlockRecord(BlockPos pos, BlockState state,
                               @Nullable CompoundTag nbt) {}
    public record UndoSnapshot(List<BlockRecord> records) {}

    /** 操作前呼叫：保存當前狀態 */
    public static void pushSnapshot(UUID player, ServerLevel level, SelectionBox box) {
        List<BlockRecord> records = new ArrayList<>();
        for (BlockPos pos : box.allPositions()) {
            BlockState state = level.getBlockState(pos);
            CompoundTag nbt = null;
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) nbt = be.saveWithoutMetadata();
            records.add(new BlockRecord(pos.immutable(), state, nbt));
        }
        stacks.computeIfAbsent(player, k -> new ArrayDeque<>());
        Deque<UndoSnapshot> stack = stacks.get(player);
        if (stack.size() >= MAX_UNDO_STACK) stack.removeLast();
        stack.push(new UndoSnapshot(records));
    }

    /** /fd undo：還原上一個操作 */
    public static int undo(UUID player, ServerLevel level) {
        Deque<UndoSnapshot> stack = stacks.get(player);
        if (stack == null || stack.isEmpty()) return 0;
        UndoSnapshot snapshot = stack.pop();
        for (BlockRecord rec : snapshot.records()) {
            level.setBlock(rec.pos(), rec.state(), 3);
            if (rec.nbt() != null) {
                BlockEntity be = level.getBlockEntity(rec.pos());
                if (be != null) be.load(rec.nbt());
            }
        }
        return snapshot.records().size();
    }
}
```

---

## 4. 三視角 CAD 介面

### 4.1 設計決策

**v3fix 決策 5 結論：** 使用自訂 `Screen` class，而非 `RenderGameOverlayEvent`。

原因：Screen 提供完整的滑鼠/鍵盤事件（框選、拖拽），而 Overlay 的滑鼠處於鎖定狀態。

**降級策略（推薦）：**
- 左側面板：正交投影（2D 像素格繪製，Tab 切換 TOP/FRONT/SIDE）
- 右側面板：3D 透視預覽（方塊數量統計 + 簡易線框）
- 不實作真正的 3D 繪圖工具，僅視覺確認

### 4.2 架構設計

```
FastDesignScreen (extends Screen)
├── 左側面板：OrthoViewRenderer
│   ├── TOP 視角：X-Z 平面（俯視）
│   ├── FRONT 視角：X-Y 平面（正面）
│   └── SIDE 視角：Z-Y 平面（側面）
├── 右側面板：Preview3DRenderer
│   └── 使用 RenderLevelStageEvent 繪製 3D 線框
├── 頂部：模式指示器 + Tab 切換提示
└── 底部：座標資訊 + 選取範圍資訊
```

### 4.3 正交視角渲染（核心）

正交投影使用 `GuiGraphics.fill()` 繪製方格，完全繞開 3D 矩陣問題：

```java
private void renderOrthoPanel(GuiGraphics graphics, int mouseX, int mouseY, float pt) {
    if (cachedBlocks.isEmpty()) {
        graphics.drawString(minecraft.font, "（無選取區域）", 10, height / 2, 0xAAAAAA);
        return;
    }

    // 計算邊界，用於自動縮放
    int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE;
    int minB = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;
    for (var entry : cachedBlocks) {
        int a = getAxisA(entry.pos());
        int b = getAxisB(entry.pos());
        minA = Math.min(minA, a); maxA = Math.max(maxA, a);
        minB = Math.min(minB, b); maxB = Math.max(maxB, b);
    }

    float scale = Math.min(
        (float)(leftPanelWidth - 16) / Math.max(maxA - minA + 1, 1),
        (float)(height - 32)         / Math.max(maxB - minB + 1, 1)
    );

    for (var entry : cachedBlocks) {
        int a = getAxisA(entry.pos()) - minA;
        int b = getAxisB(entry.pos()) - minB;
        int sx = (int)(8 + a * scale);
        int sy = (int)(20 + b * scale);
        int sz = Math.max(1, (int)(scale - 1));
        int color = getBlockColor(entry.state());
        graphics.fill(sx, sy, sx + sz, sy + sz, color);
    }
}

// 視角軸映射
private int getAxisA(BlockPos pos) {
    return switch (orthoMode) {
        case TOP   -> pos.getX();
        case FRONT -> pos.getX();
        case SIDE  -> pos.getZ();
    };
}

private int getAxisB(BlockPos pos) {
    return switch (orthoMode) {
        case TOP   -> pos.getZ();
        case FRONT -> pos.getY();
        case SIDE  -> pos.getY();
    };
}
```

### 4.4 3D 透視預覽

使用 `RenderLevelStageEvent` 在 `AFTER_TRANSLUCENT_BLOCKS` 階段繪製。Screen 內無法直接做 3D，需要一個靜態 flag 協調：

```java
// Preview3DRenderer.java — 在 RenderLevelStageEvent 中繪製
@Mod.EventBusSubscriber(modid = "blockreality_fastdesign",
                         value = Dist.CLIENT,
                         bus = Mod.EventBusSubscriber.Bus.FORGE)
public class Preview3DRenderer {

    private static volatile boolean active = false;

    public static void setActive(boolean b) { active = b; }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        PoseStack poseStack = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        // 偏移到世界座標
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // 繪製選取區域的線框方塊
        renderSelectionWireframe(poseStack);

        poseStack.popPose();
    }
}
```

**關鍵參考：** Forge RenderLevelStageEvent JavaDoc — https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.19.3/net/minecraftforge/client/event/RenderLevelStageEvent.html

### 4.5 開啟 CAD 的指令

```java
// FdCadCommand.java
// CAD 介面開啟必須在 Client 端執行
public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
        Commands.literal("fd")
        .then(Commands.literal("cad")
            .executes(ctx -> {
                // 透過 network packet 通知 client 開啟 Screen
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenCadScreenPacket()
                );
                return 1;
            })
        )
    );
}

// Client 端收到封包後：
Minecraft.getInstance().execute(() ->
    Minecraft.getInstance().setScreen(new FastDesignScreen())
);
```

### 4.6 已知坑與解決

| 坑 | 解決 |
|----|------|
| Screen.render 是純 2D，不能直接 3D | 3D 部分必須在 RenderLevelStageEvent 中，Screen 只做 2D overlay |
| GuiGraphics.fill 的 color 是 ARGB | 高位元要有 alpha：`0xFF` + RGB |
| getMapColor(null, null) 會 NPE | try-catch，fallback 到 `0xFF888888` |
| Tab 鍵預設聚焦下一個 widget | 先 return true 不呼叫 super |
| Client/Server 選取同步 | 建立 FdSelectionSyncPacket（見 §4.4） |
| isPauseScreen 預設 true | override 回傳 false，避免 SSP 暫停 |

---

## 5. 藍圖系統（Blueprint）

### 5.1 檔案格式

| 項目 | 規格 |
|------|------|
| 副檔名 | `.brblp`（Block Reality Blueprint） |
| 序列化 | Minecraft NBT（CompoundTag） |
| 壓縮 | GZIP（NbtIo.readCompressed / writeCompressed） |
| 加密 | AES-128（選配，預設關閉） |
| 儲存位置 | `config/blockreality/blueprints/` |
| 版本 | `version: int`（目前 v1，向前相容用） |

### 5.2 數據結構

```java
public class Blueprint {
    public static final int CURRENT_VERSION = 1;

    // ── 元數據 ──
    public String name;
    public String author;
    public long   timestamp;
    public int    version = CURRENT_VERSION;

    // ── 尺寸 ──
    public int sizeX, sizeY, sizeZ;

    // ── 方塊列表 ──
    public List<BlueprintBlock> blocks = new ArrayList<>();

    // ── 結構體數據（Union-Find 序列化） ──
    public List<BlueprintStructure> structures = new ArrayList<>();

    // ── 未來擴充：SubElement（系統櫃） ──
    public List<Object> subElements = new ArrayList<>();  // 預留

    public static class BlueprintBlock {
        public int relX, relY, relZ;      // 相對原點
        public BlockState blockState;
        public String rMaterialId;         // RMaterial registry key
        public String blockTypeName;       // BlockType enum name
        public int structureId;
        public boolean isAnchored;
        public float stressLevel;
    }

    public static class BlueprintStructure {
        public int id;
        public float compositeRcomp;
        public float compositeRtens;
        public List<int[]> anchorPoints = new ArrayList<>();
    }
}
```

### 5.3 NBT 序列化

```java
public class BlueprintNBT {

    public static CompoundTag write(Blueprint bp) {
        CompoundTag root = new CompoundTag();
        root.putInt("version", bp.version);
        root.putString("name", bp.name);
        root.putString("author", bp.author);
        root.putLong("timestamp", bp.timestamp);
        root.putInt("sizeX", bp.sizeX);
        root.putInt("sizeY", bp.sizeY);
        root.putInt("sizeZ", bp.sizeZ);

        // 方塊列表
        ListTag blockList = new ListTag();
        for (BlueprintBlock b : bp.blocks) {
            CompoundTag bt = new CompoundTag();
            bt.putInt("rx", b.relX);
            bt.putInt("ry", b.relY);
            bt.putInt("rz", b.relZ);
            bt.putString("state",
                BuiltInRegistries.BLOCK.getKey(b.blockState.getBlock()).toString());
            bt.putString("mat", b.rMaterialId);
            bt.putString("type", b.blockTypeName);
            bt.putInt("structId", b.structureId);
            bt.putBoolean("anchored", b.isAnchored);
            bt.putFloat("stress", b.stressLevel);
            blockList.add(bt);
        }
        root.put("blocks", blockList);

        // 結構體
        ListTag structList = new ListTag();
        for (BlueprintStructure s : bp.structures) {
            CompoundTag st = new CompoundTag();
            st.putInt("id", s.id);
            st.putFloat("rcomp", s.compositeRcomp);
            st.putFloat("rtens", s.compositeRtens);
            structList.add(st);
        }
        root.put("structures", structList);

        return root;
    }

    public static Blueprint read(CompoundTag root) {
        Blueprint bp = new Blueprint();
        bp.version = root.getInt("version");
        bp.name = root.getString("name");
        bp.author = root.getString("author");
        bp.timestamp = root.getLong("timestamp");
        bp.sizeX = root.getInt("sizeX");
        bp.sizeY = root.getInt("sizeY");
        bp.sizeZ = root.getInt("sizeZ");

        // 方塊
        ListTag blockList = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag bt = blockList.getCompound(i);
            BlueprintBlock b = new BlueprintBlock();
            b.relX = bt.getInt("rx");
            b.relY = bt.getInt("ry");
            b.relZ = bt.getInt("rz");
            b.rMaterialId = bt.getString("mat");
            b.blockTypeName = bt.getString("type");
            b.structureId = bt.getInt("structId");
            b.isAnchored = bt.getBoolean("anchored");
            b.stressLevel = bt.getFloat("stress");
            // BlockState 需從 registry 還原
            ResourceLocation blockId = new ResourceLocation(bt.getString("state"));
            b.blockState = BuiltInRegistries.BLOCK.get(blockId).defaultBlockState();
            bp.blocks.add(b);
        }

        // 結構體
        ListTag structList = root.getList("structures", Tag.TAG_COMPOUND);
        for (int i = 0; i < structList.size(); i++) {
            CompoundTag st = structList.getCompound(i);
            BlueprintStructure s = new BlueprintStructure();
            s.id = st.getInt("id");
            s.compositeRcomp = st.getFloat("rcomp");
            s.compositeRtens = st.getFloat("rtens");
            bp.structures.add(s);
        }

        return bp;
    }
}
```

### 5.4 檔案存取（BlueprintIO）

```java
public class BlueprintIO {

    private static Path getBlueprintDir() throws IOException {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("blockreality/blueprints");
        Files.createDirectories(dir);
        return dir;
    }

    /** 將選取區域儲存為 .brblp 檔案 */
    public static void save(ServerLevel level, SelectionBox box,
                             String name, ServerPlayer player) throws IOException {
        Blueprint bp = new Blueprint();
        bp.name = name;
        bp.author = player.getName().getString();
        bp.timestamp = System.currentTimeMillis();
        BlockPos origin = box.min();
        bp.sizeX = box.max().getX() - origin.getX() + 1;
        bp.sizeY = box.max().getY() - origin.getY() + 1;
        bp.sizeZ = box.max().getZ() - origin.getZ() + 1;

        for (BlockPos pos : box.allPositions()) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;

            BlueprintBlock bb = new BlueprintBlock();
            bb.relX = pos.getX() - origin.getX();
            bb.relY = pos.getY() - origin.getY();
            bb.relZ = pos.getZ() - origin.getZ();
            bb.blockState = state;

            // R 方塊數據
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RBlockEntity rbe) {
                bb.rMaterialId = rbe.getMaterial().getId();
                bb.blockTypeName = rbe.getBlockType().name();
                bb.structureId = rbe.getStructureId();
                bb.isAnchored = rbe.isAnchored();
                bb.stressLevel = rbe.getStressLevel();
            } else {
                bb.rMaterialId = VanillaMaterialMap.getInstance()
                    .getMaterial(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
                    .getMaterialId();
                bb.blockTypeName = BlockType.PLAIN.name();
            }

            bp.blocks.add(bb);
        }

        CompoundTag tag = BlueprintNBT.write(bp);
        Path file = getBlueprintDir().resolve(name + ".brblp");
        NbtIo.writeCompressed(tag, file.toFile());
    }

    /** 從檔案讀取藍圖 */
    public static Blueprint load(String name) throws IOException {
        Path file = getBlueprintDir().resolve(name + ".brblp");
        if (!Files.exists(file)) {
            throw new FileNotFoundException("藍圖不存在：" + name);
        }
        CompoundTag tag = NbtIo.readCompressed(file.toFile());
        return BlueprintNBT.read(tag);
    }

    /** 將藍圖貼到世界（以 origin 為基點） */
    public static int paste(ServerLevel level, Blueprint bp, BlockPos origin) {
        int count = 0;
        for (BlueprintBlock b : bp.blocks) {
            BlockPos target = origin.offset(b.relX, b.relY, b.relZ);
            level.setBlock(target, b.blockState, 3);

            // 還原 R 方塊數據
            if (b.rMaterialId != null && !b.rMaterialId.isEmpty()) {
                RMaterial mat = RMaterialRegistry.get(b.rMaterialId);
                if (mat == null) mat = DefaultMaterial.STONE;
                BlockType type = BlockType.valueOf(b.blockTypeName);
                RBlockManager.setBlock(level, target, mat, type);
            }
            count++;
        }
        return count;
    }
}
```

### 5.5 大型藍圖性能處理

超過 10 萬方塊的藍圖需要異步處理：

```java
// save 異步版本
public static CompletableFuture<Void> saveAsync(ServerLevel level, SelectionBox box,
                                                  String name, ServerPlayer player) {
    // 主線程收集數據（需要 level 存取權）
    Blueprint bp = collectBlueprint(level, box, name, player);

    // IO 線程寫入檔案
    return CompletableFuture.runAsync(() -> {
        try {
            CompoundTag tag = BlueprintNBT.write(bp);
            Path file = getBlueprintDir().resolve(name + ".brblp");
            NbtIo.writeCompressed(tag, file.toFile());
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    });
}
```

---

## 6. TypeScript Sidecar 整合（NURBS 輸出）

### 6.1 架構概覽

```
/fd export 指令
    │
    ▼
NurbsSidecar.export(level, box)
    │
    ├── Step 1: collectBlockData() → JsonArray
    ├── Step 2: ProcessBuilder("node", "nurbs_pipeline.js")
    ├── Step 3: stdin.write(JSON) → close
    ├── Step 4: 並行讀取 stdout / stderr（2 個 Future）
    ├── Step 5: process.waitFor(30s) → 超時強制終止
    └── Step 6: parse stdout JSON → return outputPath
```

### 6.2 通訊協定

**v3fix 決策 3 結論：** 使用 JSON over stdin/stdout。

理由：200³ 以內體素的 JSON payload < 1MB，本地 IPC 差距 < 25ms，JSON 的透明性讓除錯成本大幅降低。

**輸入格式（Java → TypeScript）：**
```json
{
  "originX": 100, "originY": 64, "originZ": 200,
  "blocks": [
    {
      "x": 0, "y": 0, "z": 0,
      "material": "concrete",
      "blockType": "CONCRETE",
      "stressLevel": 0.3,
      "isAnchored": true
    }
  ]
}
```

**輸出格式（TypeScript → Java）：**
```json
{
  "outputPath": "/path/to/exports/building_001.obj",
  "stats": {
    "vertexCount": 1234,
    "patchCount": 56,
    "processingTimeMs": 2800
  }
}
```

**錯誤格式：**
```json
{ "error": "Dual Contouring failed: insufficient vertices" }
```

### 6.3 TypeScript 管線流程

```
輸入體素資料
    │
    ▼
Dual Contouring（Ju et al., SIGGRAPH 2002）
    │ ── 將體素邊界轉為多邊形網格
    ▼
PCA 簡化（50%~90% 頂點縮減）
    │ ── 主成分分析降維，保留幾何特徵
    ▼
Trust-Region Reflective NURBS 擬合
    │ ── 非線性最佳化擬合 B-Spline 曲面
    ▼
OBJ / NURBS 檔案輸出
```

### 6.4 API 層已有的基礎設施

Block Reality API 已包含以下已審核通過的 Sidecar 類別：

| 類別 | 位置 | 功能 | 狀態 |
|------|------|------|------|
| `SidecarBridge` | `api.sidecar` | JSON-RPC 2.0 over stdio，ReadWriteLock，請求超時清理 | ✅ 通過 v7 審核 |
| `NurbsExporter` | `api.sidecar` | ProcessBuilder 啟動 + 結果解析 | ✅ 已修復 H-6 資源洩漏 |

Fast Design 的 `NurbsSidecar.java` 可直接委託給 API 層的 `SidecarBridge`/`NurbsExporter`，無需重複實作通訊層。

### 6.5 已知坑與解決

| 坑 | 解決 |
|----|------|
| `ProcessBuilder` 找不到 `node`（PATH 問題） | Config 提供 node 路徑設定，或用 `System.getenv("NODE_PATH")` |
| stdin 關閉後 Node.js 才開始處理 | 用 try-with-resources 明確 close stdin |
| stdout/stderr 不並行讀取 → 緩衝區滿 → deadlock | 必須用 2 個 Future 並行讀取 |
| Node.js 輸出含換行 → fromJson 失敗 | `stdout.trim()` |
| 大型區域（10 萬方塊）JSON 達數十 MB | 限制匯出上限 5000 方塊，或改用 MessagePack |

---

## 7. 開發規範與約定

### 7.1 命名規範

| 範疇 | 規則 | 範例 |
|------|------|------|
| 套件 | `com.blockreality.fastdesign.*` | `command`, `client`, `blueprint`, `sidecar` |
| 類別 | PascalCase，前綴 `Fd` | `FdBoxCommand`, `FdNetwork` |
| 指令 | 小寫 kebab-case | `/fd rebar-grid`, `/fd pos1` |
| Config key | snake_case | `fd.export_max_blocks` |
| 藍圖檔案 | 使用者自訂名稱 + `.brblp` | `my_house.brblp` |
| 網路封包 | PascalCase + `Packet` | `FdSelectionSyncPacket` |

### 7.2 執行緒安全規則

1. **所有 level 存取必須在主線程**（Minecraft Server Thread）
2. **Sidecar 通訊使用 CompletableFuture**，結果 sync 回主線程
3. **PlayerSelectionManager** 使用 `ConcurrentHashMap` 或確保僅在主線程存取
4. **Client Screen** 僅在 Render Thread 操作

### 7.3 對 API 的依賴規則

```groovy
// build.gradle
dependencies {
    implementation project(':blockreality-api')
}
```

Fast Design **只能**呼叫 API 的 public interface，不可：
- 直接存取 `ForceEquilibriumSolver`、`UnionFindEngine` 等內部引擎
- 繞過 `RBlockManager` 直接修改 `RBlockEntity` 的 NBT
- 直接讀取 `PhysicsExecutor` 的內部狀態

---

## 8. 可參考的開源模組與關鍵類別

### 8.1 藍圖與投影系統

| 參考 | GitHub | 關鍵類別 | 挪用重點 |
|------|--------|---------|---------|
| **Litematica** | https://github.com/maruohon/litematica | `SchematicRenderingEngine`, `RenderChunkSchematicVbo`, `BlockModelRendererSchematic` | Ghost block 半透明渲染、NBT schematic 格式、chunk 級 VBO 快取 |
| Litematica Forge 移植 | https://github.com/ThinkingStudios/Litematica-Forge | 同上（Forge 版） | Forge 事件整合方式 |
| Litematica 渲染深度文件 | https://deepwiki.com/maruohon/litematica/7.1-schematic-rendering-engine | 架構文件 | 理解 chunk 編譯 + render layer 分離 |

### 8.2 選取系統與區域操作

| 參考 | GitHub | 關鍵類別 | 挪用重點 |
|------|--------|---------|---------|
| **WorldEdit** | https://github.com/EngineHub/WorldEdit | `ClipboardHolder`, `PasteBuilder`, `EditSession` | 兩點選取邏輯、clipboard 旋轉/鏡像、undo 系統 |
| WorldEdit Clipboard API | https://worldedit.enginehub.org/en/latest/api/examples/clipboard/ | API 範例 | Copy/Paste/Transform 的設計模式 |

### 8.3 指令框架

| 參考 | GitHub | 關鍵類別 | 挪用重點 |
|------|--------|---------|---------|
| **Mojang Brigadier** | https://github.com/Mojang/brigadier | `CommandDispatcher`, `ArgumentType`, `SuggestionProvider` | 子指令樹建構、Tab 自動補全、權限檢查 |
| CommandAPI 文件 | https://docs.commandapi.dev/ | 使用指南 | Brigadier 進階用法（自訂 ArgumentType） |

### 8.4 GUI / Screen 系統

| 參考 | GitHub | 關鍵類別 | 挪用重點 |
|------|--------|---------|---------|
| **Create** | https://github.com/Creators-of-Create/Create | `AbstractSimiScreen`, 各種 widget | 多面板 Screen 佈局、自訂 widget 組合 |
| Forge Screen 文件 | https://docs.minecraftforge.net/en/1.19.x/gui/screens/ | 官方文件 | Screen 生命週期、widget 系統 |
| **RenderLevelStageEvent** | Forge JavaDoc | Event 類別 | 3D overlay 渲染時機（AFTER_TRANSLUCENT_BLOCKS） |

### 8.5 NURBS / 幾何處理

| 參考 | 連結 | 挪用重點 |
|------|------|---------|
| Dual Contouring 論文 | https://www.cs.wustl.edu/~taoju/research/dualContour.pdf | 核心演算法 |
| NURBS Surface Fitting 論文 | ECPPM 2014（搜尋 "NURBS surface fitting point cloud ECPPM 2014"） | 擬合演算法 |
| Gauss-Newton NURBS Fitting | https://www.diva-portal.org/smash/get/diva2:1018048/FULLTEXT01.pdf | 最佳化方法 |

### 8.6 物理引擎（API 已實作，供理解用）

| 參考 | 連結 | 相關 |
|------|------|------|
| PBD 論文（繩索物理） | https://matthias-research.github.io/pages/publications/posBasedDyn.pdf | DefaultCableManager 基礎 |
| Valkyrien Skies 2 | https://github.com/ValkyrienSkies | 多執行緒物理架構 |
| Catenary 數學 | https://www.alanzucconi.com/2020/12/13/catenary-1/ | 鋼索視覺渲染 |

---

## 9. 開發排程與里程碑

### 9.1 建議開發順序（8 週 / ~90 小時）

| 週次 | 工時 | 目標 | 完成標準 |
|------|------|------|---------|
| 第 1 週 | 12h | CLI 框架 + `/fd pos1`/`pos2` + `/fd box` | `/fd box concrete` 成功填充選取區域 |
| 第 2 週 | 12h | `/fd extrude` + `/fd rebar-grid` + `/fd undo` | 鋼筋網格正確生成，undo 還原成功 |
| 第 3 週 | 12h | Blueprint 系統 Phase 1：save + load | roundtrip 無數據遺失 |
| 第 4 週 | 12h | Blueprint 系統 Phase 2：旋轉/鏡像 + list/delete | 90° 旋轉後放置位置正確 |
| 第 5 週 | 12h | TypeScript Sidecar 整合 + `/fd export` | Java 送出體素 → Node.js 回傳 OBJ |
| 第 6 週 | 6h | Sidecar 穩定性測試 + 錯誤處理 | 超時/異常退出正確處理 |
| 第 7 週 | 12h | CAD 介面（降級版）：正交視角 + Tab 切換 | 三視角切換正常，方塊顏色正確 |
| 第 8 週 | 12h | CAD 框選 + Client↔Server 同步 + 整合測試 | 全功能 demo 可運行 |

### 9.2 關鍵依賴路徑

```
藍圖格式 (§5) ──→ CLI save/load (§3) ──→ Sidecar export (§6)
    │                                         │
    ▼                                         ▼
CAD 介面 (§4)                      Construction Intern 全息投影
```

**核心路徑：** `§3.2 CLI 框架` → `§5 藍圖` → `§3 save/load` → `§6 Sidecar` → `§4 CAD`（最後）

---

## 10. 已知風險與降級策略

| 風險 | 機率 | 影響 | 降級策略 |
|------|------|------|---------|
| 三視角 CAD 過於複雜 | 高 | 工期超支 | 降為單視角 + 旋轉按鈕，放棄同步三視角 |
| NURBS 擬合品質不佳 | 中 | 輸出精度不足 | 改用 Linear Polyline 替代 NURBS，輸出折線段 OBJ |
| Blueprint 旋轉/鏡像計算錯誤 | 中 | 放置位置偏移 | 退化為僅支援 0° 方向 |
| Sidecar socket 通訊不穩 | 中 | 匯出失敗 | 改用 file exchange（JSON 檔案寫入/讀取） |
| 大型藍圖卡主線程 | 低 | TPS 掉落 | save 異步化（§5.5）+ 匯出上限 5000 方塊 |
| Node.js 不在 PATH 中 | 低 | Sidecar 啟動失敗 | Config 提供 node 絕對路徑設定 |

---

## 附錄 A：Fast Design Config 參數

| 參數 | 預設值 | 說明 |
|------|--------|------|
| `fd.max_selection_volume` | 100,000 | 選取區域最大方塊數 |
| `fd.max_export_blocks` | 5,000 | NURBS 匯出最大方塊數 |
| `fd.undo_stack_size` | 10 | undo 歷史最大層數 |
| `fd.sidecar_timeout_seconds` | 30 | Sidecar 超時秒數 |
| `fd.node_path` | `"node"` | Node.js 可執行檔路徑 |
| `fd.blueprint_encryption` | `false` | 是否啟用 AES 加密 |
| `fd.cad_auto_refresh_ms` | 500 | CAD 介面自動重新整理間隔 |

---

## 附錄 B：想法文件中的期許對照

| 想法文件期許 | 本手冊覆蓋 | 章節 |
|-------------|-----------|------|
| CAD 介面（三視角覆蓋 + 透視圖） | ✅ 降級版設計 | §4 |
| CLI 指令系統（Brigadier） | ✅ 完整覆蓋 | §3 |
| /fd box, /fd extrude, /fd rebar-grid | ✅ 完整覆蓋 | §3.1, §3.5 |
| 藍圖打包（NBT + 加密格式，含 R 氏數據） | ✅ 完整覆蓋 | §5 |
| 輸出管線（Java → Sidecar → TypeScript DC+PCA+TRR → OBJ/NURBS） | ✅ 完整覆蓋 | §6 |
| RC 節點融合引擎整合 | ✅ 透過 API 契約 | §2.2 |
| 系統櫃模組預留 | ✅ SubElement 預留欄位 | §2.3, §5.2 |

---

## 11. 網路封包系統（Network Packets）

### 11.1 FdNetwork — Fast Design 專屬頻道

Fast Design 需要自己的 `SimpleChannel`，與 Block Reality API 的 `BRNetwork.CHANNEL` 分離，避免 packetId 衝突。

```java
package com.blockreality.fastdesign.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fast Design 專屬網路頻道。
 * 與 BRNetwork 分離，避免 packetId 衝突。
 */
public class FdNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("blockreality_fastdesign", "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static final AtomicInteger packetId = new AtomicInteger(0);

    /**
     * 在 mod 初始化（FMLCommonSetupEvent）時呼叫。
     */
    public static void register() {
        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            FdSelectionSyncPacket.class,
            FdSelectionSyncPacket::encode,
            FdSelectionSyncPacket::decode,
            FdSelectionSyncPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            OpenCadScreenPacket.class,
            OpenCadScreenPacket::encode,
            OpenCadScreenPacket::decode,
            OpenCadScreenPacket::handle
        );
    }
}
```

### 11.2 FdSelectionSyncPacket — 選取區域同步

Server → Client 方向。當玩家透過 `/fd pos1`、`/fd pos2` 設定選取點後，同步到 client 端供 CAD 介面使用。

```java
package com.blockreality.fastdesign.network;

import com.blockreality.fastdesign.client.ClientSelectionHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client：同步玩家的選取區域。
 * 用於讓 CAD 介面（client-side Screen）知道當前選取範圍。
 */
public class FdSelectionSyncPacket {

    private final BlockPos min;
    private final BlockPos max;
    private final boolean hasSelection;  // false 表示選取已清除

    public FdSelectionSyncPacket(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
        this.hasSelection = true;
    }

    /** 清除選取的空封包 */
    public FdSelectionSyncPacket() {
        this.min = BlockPos.ZERO;
        this.max = BlockPos.ZERO;
        this.hasSelection = false;
    }

    // ── 編碼 ──────────────────────────────────────────────
    public static void encode(FdSelectionSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.hasSelection);
        if (pkt.hasSelection) {
            buf.writeBlockPos(pkt.min);
            buf.writeBlockPos(pkt.max);
        }
    }

    // ── 解碼 ──────────────────────────────────────────────
    public static FdSelectionSyncPacket decode(FriendlyByteBuf buf) {
        boolean has = buf.readBoolean();
        if (has) {
            BlockPos min = buf.readBlockPos();
            BlockPos max = buf.readBlockPos();
            return new FdSelectionSyncPacket(min, max);
        }
        return new FdSelectionSyncPacket();
    }

    // ── 處理（Client 端） ─────────────────────────────────
    public static void handle(FdSelectionSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 必須透過 DistExecutor 避免 Server 端 class loading
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (pkt.hasSelection) {
                    ClientSelectionHolder.update(pkt.min, pkt.max);
                } else {
                    ClientSelectionHolder.clear();
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
```

### 11.3 OpenCadScreenPacket — 開啟 CAD 介面

Server → Client。當玩家執行 `/fd cad` 時，Server 發送此封包通知 Client 開啟 `FastDesignScreen`。

```java
package com.blockreality.fastdesign.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class OpenCadScreenPacket {

    // 無參數封包

    public static void encode(OpenCadScreenPacket pkt, FriendlyByteBuf buf) {
        // 無需寫入任何數據
    }

    public static OpenCadScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenCadScreenPacket();
    }

    public static void handle(OpenCadScreenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreen(
                        new com.blockreality.fastdesign.client.FastDesignScreen()
                    )
                );
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
```

### 11.4 選取同步觸發時機

在 `PlayerSelectionManager.setPos1()` / `setPos2()` 中觸發同步：

```java
// PlayerSelectionManager.java — 在 setPos1/setPos2 末尾加入同步
public void setPos1(Player player, BlockPos pos) {
    pos1Map.put(player.getUUID(), pos);
    player.sendSystemMessage(Component.literal("[FD] 選取點1 設定為 " + formatPos(pos)));

    // ★ 同步到 Client
    if (player instanceof ServerPlayer sp) {
        BlockPos p2 = pos2Map.get(player.getUUID());
        if (p2 != null) {
            BlockPos min = new BlockPos(
                Math.min(pos.getX(), p2.getX()),
                Math.min(pos.getY(), p2.getY()),
                Math.min(pos.getZ(), p2.getZ()));
            BlockPos max = new BlockPos(
                Math.max(pos.getX(), p2.getX()),
                Math.max(pos.getY(), p2.getY()),
                Math.max(pos.getZ(), p2.getZ()));
            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> sp),
                new FdSelectionSyncPacket(min, max)
            );
        }
    }
}
```

### 11.5 封包規範

| 規範 | 說明 |
|------|------|
| 頻道 ResourceLocation | `blockreality_fastdesign:main` |
| Protocol Version 檢查 | 嚴格相等（`equals`） |
| packetId 管理 | `AtomicInteger`（與 BRNetwork 相同模式） |
| 處理執行緒 | 全部 `enqueueWork`，不在 Netty 線程執行邏輯 |
| Client 類別隔離 | 所有 client class 引用透過 `DistExecutor.unsafeRunWhenOn` |

---

## 12. 各模組完成標準與工時估算

### 12.1 CLI 指令系統（§3）

**完成標準：**

- [ ] `/fd pos1` 和 `/fd pos2` 正確設定兩點並顯示座標
- [ ] `/fd box <material>` 在選取區域填充方塊，RBlock 材料數據同步寫入
- [ ] `/fd extrude <direction> <distance>` 依選取面正確擠出，不超出 64 格
- [ ] `/fd rebar-grid <spacing>` 在選取盒內正確生成三向鋼筋網格，blockType = REBAR
- [ ] `/fd save <name>` / `/fd load <name>` 成功讀寫藍圖檔案
- [ ] `/fd export` 在背景執行緒啟動 Sidecar，30 秒內返回結果或顯示超時錯誤
- [ ] `/fd undo` 正確還原上一個操作（BlockState + NBT）
- [ ] 所有指令錯誤情況（未選取、材料不存在）皆有明確紅色提示訊息
- [ ] Tab 自動補全對材料名稱有效

**預估工時：**

| 項目 | 工時 |
|------|------|
| 指令框架搭建 + pos1/pos2 | 2h |
| box / extrude / rebar-grid | 3h |
| save / load（含 BlueprintIO 對接） | 2h |
| export（含 Sidecar 啟動） | 2h |
| undo 系統 | 1.5h |
| 測試 + 除錯 | 3h |
| **合計** | **13.5h** |

**踩坑清單（v3fix 整理）：**

| # | 坑 | 解決方法 |
|---|---|---|
| 1 | `RegisterCommandsEvent` 在 **FORGE** bus 而非 MOD bus 觸發 | `@Mod.EventBusSubscriber(bus = Bus.FORGE)` — 千萬別用 Bus.MOD |
| 2 | `sendSuccess` 在 1.20.1 簽名變為 `sendSuccess(Supplier<Component>, boolean)` | 改用 lambda：`() -> Component.literal(...)` |
| 3 | 兩個 class 都 register `Commands.literal("fd")` 會怎樣？ | 不衝突，Brigadier 自動 merge 同名 literal 節點。但若兩個 class 都 register 相同路徑的 `.executes` 會衝突 |
| 4 | `BlockPos.betweenClosed` 回傳 `MutableBlockPos` | 存入 List 時必須 `.immutable()`，否則所有元素指向同一實例 |
| 5 | 大範圍 fillBox（100³ = 百萬方塊）卡主線程 | 加入 chunk-batch：每 tick 最多 1000 個，用 `ServerLevel.getServer().tell(TickTask...)` 排隊 |
| 6 | OP 等級 `hasPermission(2)` 在單人遊戲 | 單人自動 OP 4，所以沒問題。非 OP 玩家需 `hasPermission(0)` 或 config 開關 |

### 12.2 三視角 CAD 介面（§4）

**完成標準：**

- [ ] 開啟 Screen 後左側顯示選取區域的正交投影方塊圖（有顏色區分）
- [ ] Tab 鍵可在 TOP / FRONT / SIDE 三個視角間切換，標籤即時更新
- [ ] 在左側面板框選拖曳時顯示綠色選取矩形
- [ ] 右側面板顯示方塊數量統計（完整 3D 為選配）
- [ ] Screen 開啟/關閉時不暫停遊戲（`isPauseScreen = false`）
- [ ] 無 NPE crash（getMapColor / null 選取區域均有 guard）
- [ ] Client↔Server 選取同步封包正確運作

**預估工時：**

| 項目 | 工時 |
|------|------|
| Screen 骨架 + 面板分割 | 1.5h |
| 正交視角渲染（含自動縮放） | 3h |
| Tab 切換 + 框選互動 | 2h |
| Client↔Server 選取同步 packet | 2h |
| 3D 預覽（RenderLevelStageEvent 版，選配） | 3h |
| **合計（無 3D 預覽）** | **8.5h** |

### 12.3 藍圖系統（§5）

**完成標準：**

- [ ] `Blueprint` 物件可完整序列化 / 反序列化，roundtrip 無資料遺失
- [ ] `.brblp` 檔案以 GZIP 壓縮，大小合理（100 方塊 < 10 KB）
- [ ] `BlueprintIO.save` 正確儲存到 `config/blockreality/blueprints/`
- [ ] `BlueprintIO.load` 若檔案不存在拋出明確例外
- [ ] `BlueprintIO.paste` 正確還原 BlockState 和 RBlock 材料
- [ ] 版本欄位（version=1）已寫入，未來升版時可做 migration
- [ ] BlockState 使用 `NbtUtils.writeBlockState` / `readBlockState` 完整序列化（含 properties）

**預估工時：**

| 項目 | 工時 |
|------|------|
| Blueprint 數據結構設計 | 1h |
| NBT 序列化/反序列化 | 3h |
| GZIP IO + 路徑管理 | 1.5h |
| paste 功能 | 1h |
| 單元測試（JUnit） | 2h |
| **合計** | **8.5h** |

**踩坑清單（v3fix 整理）：**

| # | 坑 | 解決方法 |
|---|---|---|
| 1 | `NbtIo.writeCompressed` / `readCompressed` 簽名差異 | 查 1.20.1 API：`NbtIo.writeCompressed(CompoundTag, OutputStream)`，import 為 `net.minecraft.nbt.NbtIo` |
| 2 | BlockState 僅存 blockId 會遺失 properties（waterlogged 等） | 改用 `NbtUtils.writeBlockState(state)` / `NbtUtils.readBlockState(holderLookup, nbt)` 完整序列化 |
| 3 | GZIP 雙重壓縮：`NbtIo.writeCompressed` 本身含 GZIP，再包 `GZIPOutputStream` 會損毀 | 擇一：使用 `NbtIo.write(tag, DataOutputStream)` + 自己包 GZIP，或直接用 `NbtIo.writeCompressed` 不另包 |
| 4 | `FMLPaths.CONFIGDIR` client/server 路徑差異 | 統一用 FMLPaths，兩端一致。專用伺服器 config 在 server 根目錄 |
| 5 | 大型藍圖（10 萬方塊）save 卡主線程 | `CompletableFuture.runAsync()` 放入 IO executor 異步寫入 |

### 12.4 TypeScript Sidecar（§6）

**完成標準：**

- [ ] `/fd export` 成功啟動 Node.js 進程並傳遞 JSON 數據
- [ ] Node.js 回傳 outputPath，Java 端顯示給玩家
- [ ] 30 秒超時後進程被強制終止，玩家收到明確錯誤訊息
- [ ] stderr 輸出被記錄到 `logs/latest.log`
- [ ] Node.js 異常退出（exit code ≠ 0）時 Java 拋出含 exit code 的 IOException
- [ ] 選取區域為空（0 個 RBlock）時給出友善提示，不啟動進程

**預估工時：**

| 項目 | 工時 |
|------|------|
| ProcessBuilder 通訊框架 | 2h |
| JSON 序列化（RBlock → Json） | 1.5h |
| 超時 / 錯誤處理 | 2h |
| TypeScript 端接口定義 | 1h |
| 整合測試（本地 Node.js） | 2h |
| **合計** | **8.5h** |

**踩坑清單（v3fix 整理）：**

| # | 坑 | 解決方法 |
|---|---|---|
| 1 | `ProcessBuilder` 找不到 `node`（PATH 問題） | 用 `System.getenv("NODE_PATH")` 或 mod config 提供 node 絕對路徑 |
| 2 | stdin 關閉後 Node.js 才開始處理 | 用 try-with-resources 明確 close stdin |
| 3 | stdout / stderr 不並行讀取 → 緩衝區滿 → deadlock | 必須用 2 個 Future 並行讀取 |
| 4 | `process.waitFor` 超時後 `stdoutFuture.get()` 拋 `CancellationException` | destroy 進程後先 `ioPool.shutdownNow()`，再拋 TimeoutException |
| 5 | Node.js 輸出含換行 → fromJson 失敗 | `stdout.trim()` 後再 `fromJson` |
| 6 | 大型區域（10 萬方塊）JSON 達數十 MB | 限制匯出上限 5000 方塊（Config），或改用 MessagePack |

---

## 13. TypeScript Sidecar 介面規範

### 13.1 TypeScript 型別定義

```typescript
// ── 輸入型別（Java → TypeScript via stdin） ──────────────────

interface SidecarInput {
  originX: number;
  originY: number;
  originZ: number;
  blocks: Array<{
    x: number;       // 相對於 origin 的偏移
    y: number;
    z: number;
    material: string; // RMaterial ID（如 "concrete", "steel"）
    blockType: "PLAIN" | "REBAR" | "CONCRETE" | "RC_NODE";
    stressLevel: number;  // 0.0~2.0
    isAnchored: boolean;
  }>;
}

// ── 輸出型別（TypeScript → Java via stdout） ──────────────────

interface SidecarOutput {
  outputPath: string;   // OBJ 或 NURBS 檔案的絕對路徑
  stats?: {
    vertexCount: number;
    patchCount: number;
    processingTimeMs: number;
  };
}

// ── 錯誤型別 ──────────────────────────────────────────────────

interface SidecarError {
  error: string;
}
```

### 13.2 TypeScript 主程式範本（nurbs_pipeline.js）

```typescript
// nurbs_pipeline.js — Sidecar 入口
// 放置於：mods/sidecar/nurbs_pipeline.js

process.stdin.setEncoding("utf-8");
let inputData = "";

process.stdin.on("data", (chunk: string) => {
  inputData += chunk;
});

process.stdin.on("end", async () => {
  try {
    const input: SidecarInput = JSON.parse(inputData);

    // 驗證輸入
    if (!input.blocks || input.blocks.length === 0) {
      throw new Error("No blocks provided");
    }

    // 執行 NURBS 管線
    const outputPath = await runNurbsPipeline(input);

    // 輸出結果（必須是單行 JSON）
    const result: SidecarOutput = {
      outputPath,
      stats: {
        vertexCount: 0,    // 由管線填充
        patchCount: 0,
        processingTimeMs: 0
      }
    };
    process.stdout.write(JSON.stringify(result) + "\n");
    process.exit(0);

  } catch (err: any) {
    // 錯誤輸出到 stdout（Java 端解析 "error" 欄位）
    process.stdout.write(JSON.stringify({ error: err.message }) + "\n");
    process.exit(1);
  }
});

/**
 * NURBS 管線主流程：
 * 1. Dual Contouring（體素 → 多邊形網格）
 * 2. PCA 簡化（頂點縮減 50%~90%）
 * 3. Trust-Region Reflective NURBS 擬合
 * 4. 輸出 OBJ 檔案
 */
async function runNurbsPipeline(input: SidecarInput): Promise<string> {
  const outputDir = process.env.NURBS_OUTPUT_DIR || "./exports";
  const timestamp = Date.now();
  const outputPath = `${outputDir}/export_${timestamp}.obj`;

  // Step 1: Dual Contouring
  const mesh = dualContouring(input.blocks);

  // Step 2: PCA Simplification
  const simplified = pcaSimplify(mesh, 0.7);  // 保留 70% 頂點

  // Step 3: NURBS Fitting
  const nurbs = nurbsFit(simplified);

  // Step 4: Write OBJ
  writeOBJ(outputPath, nurbs);

  return outputPath;
}

// ── 以下為管線各步驟的 stub（需實作） ──────────────────────
function dualContouring(blocks: SidecarInput["blocks"]) { /* TODO */ return {}; }
function pcaSimplify(mesh: any, ratio: number) { /* TODO */ return mesh; }
function nurbsFit(mesh: any) { /* TODO */ return mesh; }
function writeOBJ(path: string, data: any) { /* TODO */ }
```

### 13.3 Sidecar 腳本目錄結構

```
.minecraft/mods/sidecar/
├── nurbs_pipeline.js         ← 主入口（Java ProcessBuilder 啟動此檔案）
├── package.json              ← Node.js 依賴管理
├── node_modules/             ← npm install 後的依賴
├── src/
│   ├── dual-contouring.ts    ← Dual Contouring 實作
│   ├── pca-simplify.ts       ← PCA 頂點縮減
│   ├── nurbs-fit.ts          ← Trust-Region Reflective 擬合
│   └── obj-writer.ts         ← OBJ 格式輸出
└── tsconfig.json             ← TypeScript 編譯設定
```

---

## 14. Construction Intern 全息投影預覽

> **注意**：此章為 Construction Intern 模組的預覽，非 Fast Design 範圍。但全息投影直接依賴 Fast Design 的 Blueprint 系統，開發者需理解兩者的銜接。

### 14.1 全息投影架構

全息投影讓玩家在施工前看到藍圖的半透明預覽（幽靈方塊），不放置真實方塊。

**技術要點：**
- 純 client-side 渲染，無需 Server 同步
- 使用 `RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS`
- `BufferBuilder` 繪製帶 alpha 的方塊模型
- 關鍵參考：**Litematica** 的 `SchematicRenderingEngine`

### 14.2 核心類別

```
com.blockreality.construction.hologram/
├── HologramState.java        ← Client singleton，持有當前投影藍圖 + 偏移/旋轉
├── HologramRenderer.java     ← RenderLevelStageEvent handler，繪製半透明方塊
└── CiHologramCommand.java    ← /ci hologram load|clear|move|rotate
```

### 14.3 指令介面

| 指令 | 功能 |
|------|------|
| `/ci hologram load <name>` | 載入藍圖到全息投影，以玩家腳下為原點 |
| `/ci hologram clear` | 清除全息投影 |
| `/ci hologram move <dx> <dy> <dz>` | 偏移投影位置 |
| `/ci hologram rotate` | 繞 Y 軸旋轉 90° |

### 14.4 與 Fast Design 的銜接點

```
Fast Design /fd save <name>
    │
    ├── Blueprint 物件 → BlueprintNBT.write → GZIP → .brblp 檔案
    │
    ▼
Construction Intern /ci hologram load <name>
    │
    ├── BlueprintIO.load(name) → Blueprint 物件
    ├── HologramState.load(bp, playerPos)
    └── HologramRenderer 每 frame 讀取 HologramState 並繪製
```

### 14.5 渲染踩坑（v3fix 整理）

| 坑 | 解決 |
|----|------|
| `RenderLevelStageEvent` 需要 `value = Dist.CLIENT` | 少了會在 server 端觸發 class loading error |
| PoseStack 座標在 camera space | 先 `translate(-camPos.x, -y, -z)` 轉回世界座標 |
| `brd.renderSingleBlock` 在 1.20.1 需 `ModelData` 參數 | 用 `ModelData.EMPTY` |
| 大型藍圖（千方塊）每 frame 全部重繪掉幀 | 建立 VAO/VBO 快取（`VertexBuffer`），僅在藍圖變更時重建 |
| `bufferSource.endBatch` 必須配對正確 RenderType | 每個 RenderType 都要明確 `endBatch` |

---

## 15. 施工工序狀態機預覽

> **注意**：此章為 Construction Intern 模組的預覽，預先了解可幫助 Fast Design 在 Blueprint 中預留工序相關欄位。

### 15.1 六階段工序

```
EXCAVATION（開挖地基）
    ↓
ANCHOR（打錨定樁）
    ↓
REBAR（綁鋼筋網）       ← Fast Design /fd rebar-grid 的產出在此階段驗證
    ↓
FORMWORK（架模板）
    ↓
POUR（澆灌混凝土）       ← 自動觸發 RC 融合分析
    ↓
CURE（養護凝固）          ← 等待，禁止手動放置
```

### 15.2 對 Fast Design 的影響

Blueprint 資料結構應預留以下欄位供 Construction Intern 使用：

```java
// Blueprint.java 中已預留的擴充點
public class Blueprint {
    // ... 既有欄位 ...

    // ★ 預留：施工工序元數據
    public String suggestedPhaseOrder;      // 建議工序（可選）
    public List<Object> subElements;         // 系統櫃模組（已預留）

    // ★ 預留：BlueprintBlock 增加
    public static class BlueprintBlock {
        // ... 既有欄位 ...
        public String blockTypeName;         // BlockType enum（PLAIN/REBAR/CONCRETE/RC_NODE）
        // Construction Intern 會用 blockTypeName 判斷方塊屬於哪個工序
    }
}
```

---

## 附錄 C：v3fix 手冊章節索引

本手冊整合了 v3fix 手冊以下章節的完整內容：

| v3fix 章節 | 內容 | 本手冊對應 |
|-----------|------|-----------|
| 第二章 §2.1 | CLI 指令系統 | §3, §12.1 |
| 第二章 §2.2 | 三視角 CAD 介面（降級版） | §4, §12.2 |
| 第二章 §2.3 | 藍圖格式定義 | §5, §12.3 |
| 第二章 §2.4 | TypeScript Sidecar 整合 | §6, §12.4, §13 |
| 決策 3 | Sidecar 通訊格式選擇 | §6.2 |
| 決策 5 | CAD UI 技術選型 | §4.1 |
| 可行性評估 #7 | Fast Design CAD 可行性 | §10 |
| 可行性評估 #8 | Brigadier CLI 可行性 | §3 |
| 可行性評估 #9 | 藍圖打包可行性 | §5 |
| 可行性評估 #10 | TypeScript Sidecar NURBS 可行性 | §6, §10 |
| 開發排程第三階段 | Fast Design 週期規劃 | §9 |
| 第三章 §3.1 | 藍圖全息投影 | §14 |
| 第三章 §3.2 | 施工工序狀態機 | §15 |

---

## 附錄 D：工時總計

| 模組 | 工時 | 章節 |
|------|------|------|
| CLI 指令系統 | 13.5h | §3, §12.1 |
| 三視角 CAD 介面（降級版） | 8.5h | §4, §12.2 |
| 藍圖系統 | 8.5h | §5, §12.3 |
| TypeScript Sidecar | 8.5h | §6, §12.4 |
| 網路封包系統 | 3h | §11 |
| 整合測試 + 緩衝 | 5h | — |
| **Fast Design 合計** | **~47.5h** | — |
| **8 週排程（含 buffer）** | **~90h** | §9 |

> 47.5h 為純開發時間，90h 排程包含學習、除錯、重構、整合測試的 buffer 時間。

---

## 附錄 E：完整套件結構（更新版）

```
src/main/java/com/blockreality/fastdesign/
├── FastDesignMod.java                    ← @Mod 入口
│   └── FMLCommonSetupEvent → FdNetwork.register()
│
├── command/
│   ├── FdCommandRegistry.java            ← RegisterCommandsEvent 總註冊
│   ├── FdSelectCommand.java              ← /fd pos1, /fd pos2
│   ├── FdBoxCommand.java                 ← /fd box
│   ├── FdExtrudeCommand.java             ← /fd extrude
│   ├── FdRebarGridCommand.java           ← /fd rebar-grid
│   ├── FdSaveCommand.java                ← /fd save
│   ├── FdLoadCommand.java                ← /fd load
│   ├── FdExportCommand.java              ← /fd export
│   ├── FdCadCommand.java                 ← /fd cad
│   ├── FdUndoCommand.java                ← /fd undo
│   ├── PlayerSelectionManager.java       ← 兩點選取（WorldEdit 風格）
│   └── UndoManager.java                  ← 快照還原系統
│
├── client/
│   ├── FastDesignScreen.java             ← 主 Screen（正交 + 3D 預覽）
│   ├── ClientSelectionHolder.java        ← Client 端選取狀態
│   ├── OrthoViewRenderer.java            ← 正交投影渲染
│   └── Preview3DRenderer.java            ← RenderLevelStageEvent 3D 渲染
│
├── blueprint/
│   ├── Blueprint.java                    ← 藍圖數據結構
│   ├── BlueprintNBT.java                 ← NBT 序列化/反序列化
│   └── BlueprintIO.java                  ← GZIP 檔案存取 + paste
│
├── sidecar/
│   └── NurbsSidecar.java                 ← ProcessBuilder + JSON 通訊
│
└── network/
    ├── FdNetwork.java                    ← SimpleChannel 註冊
    ├── FdSelectionSyncPacket.java        ← Server→Client 選取同步
    └── OpenCadScreenPacket.java          ← Server→Client 開啟 CAD
```
