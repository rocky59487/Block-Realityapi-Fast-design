# FD 指令系統

> 所屬：L1-fastdesign > [L2-command](index.md)

## 概述

Fast Design 的所有指令透過 `FdCommandRegistry` 統一註冊至 Brigadier，以 `/fd` 為根指令。另有 `BlueprintCommand`（`/br_blueprint`）與 `ConstructionCommand`（`/br_zone`）提供獨立指令樹。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `FdCommandRegistry` | `command.FdCommandRegistry` | `/fd` 主指令樹註冊，整合所有子指令 |
| `BlueprintCommand` | `command.BlueprintCommand` | `/br_blueprint` 藍圖 CRUD 指令 |
| `ConstructionCommand` | `command.ConstructionCommand` | `/br_zone` 施工區域管理指令 |
| `HologramCommand` | `command.HologramCommand` | `/fd hologram` 全息投影子指令 |
| `FdExtendedCommands` | `command.FdExtendedCommands` | 擴充指令：剪貼簿、鏡像、旋轉、填充、取代等 |
| `SelectionExclusionManager` | `command.SelectionExclusionManager` | 選取區域內個別方塊排除管理 |

## `/fd` 子指令一覽

### 選取與基本操作

| 指令 | 說明 |
|------|------|
| `/fd pos1` | 將玩家腳下座標設為選取點 1 |
| `/fd pos2` | 將玩家腳下座標設為選取點 2 |
| `/fd deselect` | 清除選取區域並同步客戶端 |
| `/fd undo` | 還原最近一次操作 |
| `/fd panel` | 提示玩家按 G 鍵開啟控制面板 |
| `/fd wand` | 給予 FD 選取游標道具 |
| `/fd info` | 顯示選取資訊、剪貼簿狀態、Undo/Redo 堆疊深度 |

### 建築指令

| 指令 | 說明 |
|------|------|
| `/fd box <material>` | 以指定材料填滿選取區域（支援 Tab 自動完成） |
| `/fd extrude <direction> <distance>` | 沿方向擠出選取區域（up/down/north/south/east/west，1-64 格） |
| `/fd rebar-grid <spacing>` | 生成 3D 鋼筋網格（XZ 水平面 + Y 垂直柱，間距 1-8） |
| `/fd walls <material>` | 在選取區域邊界建造四面牆壁 |
| `/fd fill <block>` | 以指定方塊填滿選取區域（使用 DeltaUndoManager） |
| `/fd replace <from> <to>` | 替換選取區域內的方塊類型 |
| `/fd clear` | 清除選取區域內所有方塊 |

### 剪貼簿指令

| 指令 | 說明 |
|------|------|
| `/fd copy` | 複製選取區域至剪貼簿（記憶體內 Blueprint） |
| `/fd paste` | 生成貼上預覽，等待確認 |
| `/fd paste confirm` | 確認放置剪貼簿內容 |
| `/fd paste cancel` | 取消貼上預覽 |
| `/fd mirror <x\|y\|z>` | 沿指定軸鏡像剪貼簿內容 |
| `/fd rotate <90\|180\|270>` | 繞 Y 軸旋轉剪貼簿內容 |

### 藍圖與匯出

| 指令 | 說明 |
|------|------|
| `/fd save <name>` | 儲存選取區域為藍圖檔案 |
| `/fd load <name>` | 載入藍圖並放置於玩家位置 |
| `/fd export [smoothing] [resolution]` | NURBS/STEP 匯出（smoothing=0 體素，>0 曲面化） |
| `/fd cad [name]` | 開啟 CAD 檢視畫面（從選取區域或已存藍圖） |

### 全息投影

| 指令 | 說明 |
|------|------|
| `/fd hologram load <name>` | 載入藍圖並投影全息影像 |
| `/fd hologram clear` | 清除全息投影 |
| `/fd hologram move <dx> <dy> <dz>` | 移動全息投影位置（-64 ~ +64） |
| `/fd hologram rotate` | 旋轉全息投影 90 度 |

## `/br_blueprint` 指令

獨立藍圖管理指令，由 `BlueprintCommand` 註冊：

| 指令 | 說明 |
|------|------|
| `/br_blueprint pos1` / `pos2` | 設定選取點 |
| `/br_blueprint save <name>` | 儲存藍圖 |
| `/br_blueprint load <name>` | 載入藍圖至玩家位置 |
| `/br_blueprint list` | 列出所有已存藍圖 |
| `/br_blueprint delete <name>` | 刪除藍圖 |

## `/br_zone` 指令

施工區域管理指令，由 `ConstructionCommand` 註冊：

| 指令 | 說明 |
|------|------|
| `/br_zone pos1` / `pos2` | 設定區域端點 |
| `/br_zone create` | 在選取範圍建立施工區域 |
| `/br_zone advance` | 推進當前所在區域的工序階段 |
| `/br_zone info` | 顯示當前所在施工區域資訊 |
| `/br_zone list` | 列出所有施工區域 |
| `/br_zone remove` | 移除當前所在施工區域 |

## 核心方法

### `FdCommandRegistry.register(dispatcher)`
- **參數**: `CommandDispatcher<CommandSourceStack>` — Brigadier 分發器
- **回傳**: `void`
- **說明**: 註冊 `/fd` 根指令及所有子指令節點，包含 `HologramCommand.buildHologramNode()` 和 `FdExtendedCommands` 的 10 個擴充子指令

### `SelectionExclusionManager.toggle(playerId, pos)`
- **參數**: `UUID` 玩家 ID, `BlockPos` 方塊位置
- **回傳**: `boolean` — `true` 表示已排除，`false` 表示已恢復
- **說明**: 切換指定位置的排除狀態，fill/replace/clear 等操作會跳過被排除的位置

## 關聯接口
- 依賴 → [PlayerSelectionManager](../../L1-api/L2-command/L3-selection.md)（API 層選取管理）
- 依賴 → [BlueprintIO](../../L1-api/L2-blueprint/L3-io.md)（藍圖檔案 I/O）
- 依賴 → [UndoManager / DeltaUndoManager](L3-undo.md)（撤銷系統）
- 依賴 → [NurbsExporter](../L2-sidecar-export/L3-nurbs-bridge.md)（NURBS 匯出）
- 被依賴 ← [FdNetwork](../L2-network/L3-packets.md)（網路封包觸發指令邏輯）
