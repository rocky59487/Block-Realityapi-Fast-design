# Fast Design UX 三階段升級計畫書

**版本**: v1.0
**日期**: 2026-03-25
**目標**: 徹底減少指令輸入，提供視覺化、互動式的建築體驗

---

## 現狀分析

### 已有基礎
| 元件 | 狀態 | 位置 |
|------|------|------|
| SelectionWandHandler | ✅ 存在但用 carrot_on_a_stick + NBT hack | client/SelectionWandHandler.java |
| Preview3DRenderer | ✅ 選取區域橙色線框 + CAD 模式方塊輪廓 | client/Preview3DRenderer.java |
| ClientSelectionHolder | ✅ volatile 雙 BlockPos 儲存 | client/ClientSelectionHolder.java |
| FastDesignScreen | ✅ CAD 介面 + 工具列按鈕 | client/FastDesignScreen.java |
| FdActionPacket | ✅ C2S 9 種操作 | network/FdActionPacket.java |
| FdExtendedCommands | ✅ 10 個指令 + doCopy/doPaste/doClear API | command/FdExtendedCommands.java |
| FastDesignConfig | ✅ wandEnabled 等配置 | config/FastDesignConfig.java |

### 問題
1. 選取杖是胡蘿蔔釣竿 hack — 不能擁有自訂紋理、描述、工具提示
2. 線框永遠顯示 — 不分手持什麼物品
3. 沒有尺寸信息 — 框好區域後不知道多大
4. 操作全靠指令 — 沒有「按 G 跳出面板」的快捷流程
5. 材質選擇要打字 — 沒有下拉選單

---

## Level 1：實體 Blueprint Wand (FD 游標)

### 設計

**物品名稱**: `fd_wand` (Fast Design 游標)
**註冊 MOD**: `fastdesign` (FD 自有的 DeferredRegister)
**Creative Tab**: 自建 `Fast Design` 頁籤
**外觀**: 自訂紋理 `textures/item/fd_wand.png` (暫用程式碼生成的占位圖)

### 行為
- **左鍵點方塊** → 設定 pos1
- **右鍵點方塊** → 設定 pos2
- **Shift + 右鍵（空氣）** → 開啟 Control Panel (Level 3)
- ActionBar 顯示 "§6[FD] Pos1: (x,y,z) | Pos2: (x,y,z) | 體積: N"

### 實作步驟

#### 1-1. 建立 FD 物品註冊系統
```
fastdesign/registry/FdItems.java
```
- `DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "fastdesign")`
- `RegistryObject<FdWandItem> FD_WAND = ITEMS.register("fd_wand", FdWandItem::new)`
- 在 FastDesignMod 構造函數呼叫 `FdItems.ITEMS.register(modBus)`

#### 1-2. 建立 Creative Tab
```
fastdesign/registry/FdCreativeTab.java
```
- `DeferredRegister<CreativeModeTab> TABS`
- 自定義圖示 (用 fd_wand 物品)
- 包含所有 FD 物品

#### 1-3. 建立 FdWandItem 類別
```
fastdesign/item/FdWandItem.java
```
- extends Item
- 堆疊上限 1
- `useOn(UseOnContext)` → 設定 pos2 (右鍵點方塊)
- `use(Level, Player, InteractionHand)` → Shift+右鍵空氣開面板
- 發光效果 `isFoil() = true`
- 客製化工具提示 `appendHoverText()`

#### 1-4. 改造 SelectionWandHandler
- 左鍵仍由 `PlayerInteractEvent.LeftClickBlock` 攔截（因為 Item.useOn 不觸發左鍵）
- 改為檢查 `stack.getItem() instanceof FdWandItem` 替代 NBT tag 檢查
- 保留 `carrot_on_a_stick` NBT 回退相容

#### 1-5. 更新 /fd wand 指令
- 改為給玩家正式的 `fd_wand` 物品（而非 NBT hack）

#### 1-6. 紋理佔位
- 建立 `assets/fastdesign/textures/item/fd_wand.png` (16x16 像素佔位圖)
- `assets/fastdesign/models/item/fd_wand.json`
- `assets/fastdesign/lang/en_us.json` + `zh_tw.json`

### 需要的新檔案
| 檔案 | 用途 |
|------|------|
| `registry/FdItems.java` | 物品 DeferredRegister |
| `registry/FdCreativeTab.java` | 創造模式頁籤 |
| `item/FdWandItem.java` | 游標物品邏輯 |
| `assets/fastdesign/models/item/fd_wand.json` | 物品模型 |
| `assets/fastdesign/textures/item/fd_wand.png` | 物品紋理 |
| `assets/fastdesign/lang/en_us.json` | 英文語言檔 |
| `assets/fastdesign/lang/zh_tw.json` | 中文語言檔 |

### 需要修改的檔案
| 檔案 | 修改 |
|------|------|
| `FastDesignMod.java` | 註冊 FdItems, FdCreativeTab |
| `client/SelectionWandHandler.java` | 改檢查 instanceof FdWandItem |
| `command/FdExtendedCommands.java` | /fd wand 改給正式物品 |

---

## Level 2：全息外框 (Hologram Bounding Box)

### 設計

**觸發條件**: 玩家主手持有 FdWandItem 時才顯示
**視覺效果**:
1. 橙色線框包圍 pos1~pos2 區域（已有 Preview3DRenderer）
2. **新增**: 半透明填充面（alpha 0.1 的橙色面）提供體積感
3. **新增**: 尺寸標註浮動文字 — 在線框的三條邊上顯示 "Xm × Ym × Zm"
4. **新增**: 體積資訊浮動文字 — 在外框頂部中央顯示 "N blocks"
5. **新增**: 角落小球標記 pos1 (綠色) 和 pos2 (紅色)

### 實作步驟

#### 2-1. 增強 Preview3DRenderer — 手持判斷
- 在 `onRenderLevel()` 開頭加入：如果玩家主手不是 FdWandItem，return
- 新增 Config 項 `alwaysShowSelection` (預設 false) 允許始終顯示

#### 2-2. 新增半透明填充面
- 在線框渲染後，用 `VertexFormat.Mode.QUADS` 繪製 6 面
- 顏色: RGBA(255, 165, 0, 25) — 極淡的橙色

#### 2-3. 新增尺寸標註浮動文字
```
fastdesign/client/SelectionOverlayRenderer.java
```
- 使用 `Font.drawInBatch()` 渲染 3D 世界空間中的文字
- X 邊長度標示在前方邊的中點
- Y 邊長度標示在左側邊的中點
- Z 邊長度標示在底邊的中點
- 文字面向玩家 (billboard 效果)
- 體積顯示在頂面中央

#### 2-4. 角落標記點
- pos1 位置: 小綠色方塊 (3x3 像素效果)
- pos2 位置: 小紅色方塊

#### 2-5. 動態 ActionBar HUD
- 手持 FdWandItem 時在 ActionBar 持續顯示:
  `§6[FD] §aA: (x,y,z) §c→ §aB: (x,y,z) §7| §f12×8×5 = 480 blocks`
- 使用 RenderGuiOverlayEvent 或直接在 SelectionWandHandler 發送 ActionBar 訊息

### 需要的新檔案
| 檔案 | 用途 |
|------|------|
| `client/SelectionOverlayRenderer.java` | 尺寸標註 + 角落標記 + 填充面 |

### 需要修改的檔案
| 檔案 | 修改 |
|------|------|
| `client/Preview3DRenderer.java` | 加入手持判斷, 呼叫 SelectionOverlayRenderer |
| `config/FastDesignConfig.java` | 新增 alwaysShowSelection 配置 |

---

## Level 3：快捷參數面板 (Control Panel)

### 設計

**觸發方式**:
1. 快捷鍵 `G` (可自訂)
2. Shift + 右鍵點空氣（手持游標）
3. `/fd panel` 指令

**面板佈局** (480×320 像素 Screen):

```
┌─────────────────────────────────────────┐
│  Fast Design Control Panel     [X]      │
├─────────────────────────────────────────┤
│                                         │
│  ┌─ 建築操作 ─────────────────────┐     │
│  │ [實心方塊] [空心牆壁] [拱門]   │     │
│  │ [斜撐]     [樓板]     [鋼筋網] │     │
│  └────────────────────────────────┘     │
│                                         │
│  ┌─ 材質選擇 ─────────────────────┐     │
│  │  ◉ 混凝土  ○ 鋼筋  ○ 鋼材     │     │
│  │  ○ 木材    ○ 自訂方塊: [____]  │     │
│  └────────────────────────────────┘     │
│                                         │
│  ┌─ 編輯工具 ─────────────────────┐     │
│  │ [複製] [貼上] [鏡像] [旋轉]    │     │
│  │ [填充] [替換] [清除] [還原]    │     │
│  └────────────────────────────────┘     │
│                                         │
│  ┌─ 進階 ─────────────────────────┐     │
│  │ [儲存藍圖] [載入藍圖] [匯出]   │     │
│  │ [全息投影]  [CAD 檢視]         │     │
│  └────────────────────────────────┘     │
│                                         │
│  選取: (10,64,20) → (25,72,35)          │
│  尺寸: 16×9×16 = 2304 blocks            │
├─────────────────────────────────────────┤
│  鋼筋間距: [◄ 4 ►]                      │
│                                 [執行]   │
└─────────────────────────────────────────┘
```

### 按鈕行為對照

| 按鈕 | 動作 | 封包/指令 |
|------|------|-----------|
| 實心方塊 | 選取區域填滿選定材質的 R-Block | FdActionPacket → 等同 /fd box |
| 空心牆壁 | 只建造外壁 | FdActionPacket → 等同 /fd walls |
| 拱門 | 生成拱門結構 | FdActionPacket → 等同 /fd arch |
| 斜撐 | 生成斜撐 | FdActionPacket → 等同 /fd brace |
| 樓板 | 生成樓板 | FdActionPacket → 等同 /fd slab |
| 鋼筋網 | 3D 鋼筋網格 | FdActionPacket → 等同 /fd rebar |
| 複製/貼上/清除 | 剪貼簿操作 | 現有 FdActionPacket.Action |
| 鏡像/旋轉 | 剪貼簿變換 | 新增 FdActionPacket.Action |
| 儲存/載入藍圖 | 藍圖 I/O | 現有 FdActionPacket.Action |
| 全息投影 | 顯示/隱藏 hologram | 新增 FdActionPacket.Action |
| CAD 檢視 | 開啟 FastDesignScreen | 客戶端直接開啟 |
| 匯出 | NURBS 匯出 | 現有 FdActionPacket.Action |

### 實作步驟

#### 3-1. 註冊快捷鍵
```
fastdesign/client/FdKeyBindings.java
```
- `KeyMapping OPEN_PANEL` → 預設 G 鍵
- 在 `RegisterKeyMappingsEvent` 註冊
- 在 `TickEvent.ClientTickEvent` 監聽 `consumeClick()`

#### 3-2. 建立 ControlPanelScreen
```
fastdesign/client/ControlPanelScreen.java
```
- extends Screen
- 4 區域: 建築操作、材質選擇、編輯工具、進階
- 底部: 選取信息 + 參數調整 + 執行按鈕
- 所有按鈕透過 FdActionPacket 發送到伺服器

#### 3-3. 擴展 FdActionPacket
新增 Action enum 值:
- `BUILD_SOLID` — 實心方塊
- `BUILD_WALLS` — 空心牆壁
- `BUILD_ARCH` — 拱門
- `BUILD_BRACE` — 斜撐
- `BUILD_SLAB` — 樓板
- `BUILD_REBAR` — 鋼筋網
- `MIRROR` — 鏡像 (payload: axis)
- `ROTATE` — 旋轉 (payload: degrees)
- `HOLOGRAM_TOGGLE` — 全息投影開關
- `OPEN_CAD` — 開啟 CAD

#### 3-4. 擴展 FdActionPacket.handle()
每個新 Action 代理給對應的指令邏輯。

#### 3-5. 材質選擇狀態管理
```
fastdesign/client/ControlPanelState.java
```
- 追蹤當前選中的材質
- 追蹤鋼筋間距等參數
- payload 中傳遞 "material=concrete,spacing=4"

#### 3-6. 自訂方塊輸入框
- EditBox 元件讓玩家輸入 `minecraft:stone` 等方塊 ID
- Tab completion 建議

### 需要的新檔案
| 檔案 | 用途 |
|------|------|
| `client/FdKeyBindings.java` | 快捷鍵註冊 |
| `client/ControlPanelScreen.java` | 主面板 Screen |
| `client/ControlPanelState.java` | 面板狀態管理 |

### 需要修改的檔案
| 檔案 | 修改 |
|------|------|
| `FastDesignMod.java` | 客戶端事件註冊 |
| `network/FdActionPacket.java` | 新增 Action enum 值 + handler |
| `network/FdNetwork.java` | 無需修改 (同一封包類別) |
| `item/FdWandItem.java` | Shift+右鍵開面板 |
| `command/FdCommandRegistry.java` | 新增 /fd panel 指令 |

---

## 執行順序

### Phase 1 (Level 1) — 預估 8 個子任務
1. ✏️ FdItems.java + FdCreativeTab.java
2. ✏️ FdWandItem.java
3. ✏️ 資源檔 (model, texture, lang)
4. ✏️ FastDesignMod.java 整合
5. ✏️ SelectionWandHandler.java 改造
6. ✏️ FdExtendedCommands /fd wand 更新
7. 🔍 驗證物品註冊 + 事件處理
8. 🔍 驗證回退相容 (舊 NBT wand)

### Phase 2 (Level 2) — 預估 6 個子任務
1. ✏️ Preview3DRenderer 手持判斷
2. ✏️ 半透明填充面渲染
3. ✏️ SelectionOverlayRenderer (尺寸標註)
4. ✏️ 角落標記 + 體積文字
5. ✏️ ActionBar HUD 持續顯示
6. 🔍 視覺效果驗證

### Phase 3 (Level 3) — 預估 8 個子任務
1. ✏️ FdKeyBindings.java
2. ✏️ ControlPanelState.java
3. ✏️ ControlPanelScreen.java 框架 + 建築區
4. ✏️ ControlPanelScreen.java 材質區 + 編輯區
5. ✏️ ControlPanelScreen.java 進階區 + 底部
6. ✏️ FdActionPacket 擴展 (新 Action + handler)
7. ✏️ FdWandItem Shift+右鍵 + /fd panel 指令
8. 🔍 全面整合驗證

---

## 技術參考

### Forge 1.20.1 DeferredRegister 物品註冊
```java
public static final DeferredRegister<Item> ITEMS =
    DeferredRegister.create(ForgeRegistries.ITEMS, "fastdesign");
```

### Forge 1.20.1 Creative Tab 註冊
```java
public static final DeferredRegister<CreativeModeTab> TABS =
    DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "fastdesign");
```

### 快捷鍵註冊
```java
@SubscribeEvent
public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
    event.register(OPEN_PANEL);
}
```

### 3D 文字渲染 (RenderLevelStageEvent 內)
```java
Font font = Minecraft.getInstance().font;
MultiBufferSource.BufferSource bufferSource =
    Minecraft.getInstance().renderBuffers().bufferSource();
font.drawInBatch("16×9×16", x, y, color, false, matrix, bufferSource,
    Font.DisplayMode.SEE_THROUGH, 0, 15728880);
bufferSource.endBatch();
```

### WorldEdit 模式 — 選取杖左鍵事件
- 使用 `PlayerInteractEvent.LeftClickBlock` 因為 Item 類別沒有 left-click override
- 右鍵用 `Item.useOn()` 因為更精確且不需要事件取消

### Building Gadgets 模式 — GUI 封包通訊
- Screen 按鈕 → 建構 C2S 封包 → 伺服器解析並執行
- 我們已有 FdActionPacket 架構，只需擴展 Action enum

---

## 風險與注意事項

1. **FD 物品 vs API 物品**: 物品在 FD (fastdesign) MOD 下注冊，不影響 API 的 BRBlocks
2. **左鍵事件**: Item 類別無 leftClickBlock override，必須保留 PlayerInteractEvent 方式
3. **紋理**: 暫用程式碼生成佔位紋理，未來可替換美術資源
4. **Creative Tab**: Forge 1.20.1 使用新的 `CreativeModeTab` 註冊系統 (不是舊的 ItemGroup)
5. **Client 安全**: 所有渲染邏輯需要 `@OnlyIn(Dist.CLIENT)` 或 `DistExecutor` 隔離
