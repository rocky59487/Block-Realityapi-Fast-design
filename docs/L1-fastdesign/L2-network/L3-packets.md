# FdNetwork 與封包系統

> 所屬：L1-fastdesign > [L2-network](index.md)

## 概述

`FdNetwork` 管理 Fast Design 專屬的 Forge SimpleChannel 網路頻道，註冊 6 種封包類型，處理客戶端與伺服器之間的狀態同步與操作代理。

## 關鍵類別

| 類別 | 套件路徑 | 方向 | 說明 |
|------|---------|------|------|
| `FdNetwork` | `network.FdNetwork` | — | 頻道註冊中心 |
| `FdSelectionSyncPacket` | `network.FdSelectionSyncPacket` | S→C | 同步選取區域至客戶端渲染 |
| `OpenCadScreenPacket` | `network.OpenCadScreenPacket` | S→C | 開啟 CAD 檢視畫面 |
| `HologramSyncPacket` | `network.HologramSyncPacket` | S→C | 全息投影控制（載入/清除/移動/旋轉） |
| `FdActionPacket` | `network.FdActionPacket` | C→S | GUI 操作代理封包（20+ 種操作） |
| `PastePreviewSyncPacket` | `network.PastePreviewSyncPacket` | S→C | 貼上預覽半透明方塊同步 |
| `PastePlacePacket` | `network.PastePlacePacket` | C→S | 玩家在預覽位置確認放置 |

## FdNetwork

### `register()`
- **說明**: 以 `AtomicInteger` 自動分配封包 ID，依序註冊所有封包類型的 encode/decode/handle 方法
- **協議版本**: `"1"`（客戶端與伺服器必須版本一致）

## 封包詳述

### FdSelectionSyncPacket（S→C）
- **用途**: 玩家設定 pos1/pos2 後同步選取框至客戶端，驅動選取框渲染
- **欄位**: `min: BlockPos`, `max: BlockPos`, `hasSelection: boolean`
- **客戶端處理**: 呼叫 `ClientSelectionHolder.update()` 或 `clear()`
- **工廠方法**: `clearSelection()` — 建立清除選取的封包

### OpenCadScreenPacket（S→C）
- **用途**: 伺服器端擷取藍圖後通知客戶端開啟 `FastDesignScreen`
- **欄位**: `blueprintTag: CompoundTag`（藍圖 NBT 序列化資料）
- **客戶端處理**: 反序列化 Blueprint 並呼叫 `Minecraft.getInstance().setScreen()`

### HologramSyncPacket（S→C）
- **用途**: 控制客戶端全息投影顯示
- **操作類型**: `LOAD`（載入藍圖 + 原點座標）、`CLEAR`、`MOVE`（dx/dy/dz 偏移）、`ROTATE`（90 度）
- **客戶端處理**: 委託至 `HologramState` 靜態方法
- **容錯**: LOAD 時若 `blueprintTag` 為 null 則降級為 CLEAR

### FdActionPacket（C→S）
- **用途**: FastDesignScreen GUI 按鈕觸發伺服器操作，無需輸入指令
- **欄位**: `action: Action`（enum）, `payload: String`（最多 512 字元）
- **支援操作**:
  - 基本：`UNDO`, `REDO`, `SAVE`, `LOAD`, `EXPORT`, `COPY`, `PASTE`, `PASTE_CONFIRM`, `PASTE_CANCEL`, `CLEAR`, `SET_POS1`, `SET_POS2`, `DESELECT`
  - 建築：`BUILD_SOLID`, `BUILD_WALLS`, `BUILD_ARCH`, `BUILD_BRACE`, `BUILD_SLAB`, `BUILD_REBAR`
  - 編輯：`MIRROR`, `ROTATE`, `FILL`, `REPLACE`
  - 進階：`HOLOGRAM_TOGGLE`, `OPEN_CAD`, `PLACE_MULTI`
  - 選取：`SHIFT_SELECTION`, `RESIZE_SELECTION`, `EXCLUDE_BLOCK`

### PastePreviewSyncPacket（S→C）
- **用途**: 將 paste 預覽資料同步至客戶端顯示半透明幽靈方塊
- **欄位**: `active: boolean`, `origin: BlockPos`, `blocks: Map<BlockPos, BlockState>`
- **客戶端處理**: `active=true` 時呼叫 `GhostPreviewRenderer.setPreview()`；`active=false` 時清除預覽
- **序列化**: 方塊狀態以 `Block.getId()` / `Block.stateById()` 轉換為整數 ID

### PastePlacePacket（C→S）
- **用途**: 玩家在 GhostPreview 位置右鍵確認放置
- **欄位**: `targetPos: BlockPos`（放置目標位置）
- **伺服器處理**:
  1. 檢查 pending paste 是否存在
  2. 使用 `DeltaUndoManager.captureBeforeState()` 記錄操作前狀態
  3. 呼叫 `BlueprintIO.paste()` 放置方塊
  4. 提交差異至 DeltaUndoManager
  5. 發送 `PastePreviewSyncPacket(active=false)` 清除客戶端預覽

## 關聯接口
- 依賴 → [DeltaUndoManager](../L2-command/L3-undo.md)（差異式撤銷）
- 依賴 → `BlueprintIO`、`BlueprintNBT`（API 層藍圖序列化）
- 依賴 → `ClientSelectionHolder`、`HologramState`、`GhostPreviewRenderer`（客戶端渲染）
- 被依賴 ← [FdCommandRegistry](../L2-command/L3-fd-commands.md)（指令透過封包同步客戶端）
