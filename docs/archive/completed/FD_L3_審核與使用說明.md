# Fast Design Level 3 — 審核報告與使用說明

## 建置修復

### ForgeGradle 依賴解析錯誤

錯誤訊息中 `forge-1.20.1-47.2.0_mapped_official_..._at_fdfb8904bb...` 表示 ForgeGradle 尚未在本地產生去混淆後的 Forge artifact。這不是程式碼問題，是環境快取問題。

**修復方式（Windows CMD）：**

```bat
cd "Block Reality"
gradlew.bat --refresh-dependencies compileJava
```

如仍失敗，清除 ForgeGradle 快取後重建：

```bat
rd /s /q "%USERPROFILE%\.gradle\caches\forge_gradle"
gradlew.bat compileJava
```

### 程式碼修復清單

| 問題 | 檔案 | 修復 |
|------|------|------|
| `BlueprintIO.capture()` 不存在 | `api/blueprint/BlueprintIO.java` | 新增 public `capture(level, min, max)` 便捷方法 |
| `captureBlueprint()` 為 private | `api/blueprint/BlueprintIO.java` | 改為 public，新增完整 JavaDoc |
| `BlueprintIO.load(name, level, pos)` 3 參數不存在 | `network/FdActionPacket.java` handleLoad | 改用 `load(name)` + `paste(level, bp, origin)` |
| `FdExtendedCommands.buildCopy()` 等方法名錯誤 | `command/FdCommandRegistry.java` | 改為 `copy()`, `paste()` 等正確方法名 |
| ControlPanelScreen 缺少 `@OnlyIn(Dist.CLIENT)` | `client/ControlPanelScreen.java` | 已加上 |

---

## 三階段 UX 架構總覽

```
Level 1: Blueprint Wand (FdWandItem)
  └─ 左鍵 → pos1 | 右鍵 → pos2 | Shift+右鍵 → Control Panel

Level 2: Hologram Bounding Box
  └─ 半透明填充 + 線框 + 角落標記 + 尺寸標籤 + 體積標籤

Level 3: Control Panel (ControlPanelScreen)
  └─ G 鍵 or Shift+右鍵 or /fd panel
  └─ 4 區塊: 建築操作 | 材質選擇 | 編輯工具 | 進階功能
```

---

## Level 3 檔案對照表

| 檔案 | 用途 | 執行環境 |
|------|------|---------|
| `FdKeyBindings.java` | G 鍵註冊 → 開啟面板 | CLIENT |
| `ControlPanelState.java` | 材質/參數狀態追蹤 + payload 編碼 | CLIENT |
| `ControlPanelScreen.java` | 400×310 GUI 面板，4 區塊按鈕 | CLIENT |
| `FdActionPacket.java` | C→S 封包，22 種 Action + handler | SERVER |
| `FdExtendedCommands.java` | 13 個 public do* API（供封包呼叫） | SERVER |
| `FdCommandRegistry.java` | `/fd panel` 指令 | SERVER |
| `FdWandItem.java` | Shift+右鍵開啟面板 | BOTH |

---

## 使用方式

### 取得游標
```
/fd wand
```

### 選取區域
- **左鍵點方塊** → 設定 pos1 (綠色角標)
- **右鍵點方塊** → 設定 pos2 (紅色角標)
- 選取後自動顯示全息外框 + 尺寸標籤

### 開啟控制面板（三種方式）
1. 按 **G 鍵**
2. 手持游標 **Shift + 右鍵空氣**
3. 輸入 `/fd panel` 查看提示

### 面板四區塊

**建築操作**（需先選取區域）
- 實心方塊 — 用選定材質填滿整個選取
- 空心牆壁 — 只建造四面牆壁
- 拱門 — X 軸方向半圓拱
- 斜撐 — X 型對角支撐
- 樓板 — 只填底面一層
- 鋼筋網 — 三維鋼筋骨架

**材質選擇**
- 混凝土 / 鋼筋 / 鋼材 / 木材 / 自訂方塊 ID

**編輯工具**
- 複製 / 粘貼 / 鏡像 X / 旋轉 90° / 填充 / 替換 / 清除 / 還原

**進階功能**
- 儲存 / 載入藍圖（需輸入名稱）
- NURBS 匯出
- 全息投影開關（客戶端即時切換）
- CAD 檢視（擷取選取區域進入 3D 檢視器）

---

## 封包通訊架構

```
ControlPanelScreen (CLIENT)
  ↓ FdActionPacket(Action, payload)
  ↓ sendToServer()
FdActionPacket.handle() (SERVER)
  ↓ switch(action)
  ↓ 呼叫 FdExtendedCommands.do*()
  ↓ 回傳 displayClientMessage
Player Chat (CLIENT)
```

payload 格式: `material=concrete,spacing=4,block=minecraft:stone`

---

## 已知限制

1. 控制面板「替換」按鈕目前預設 from=stone，需要未來增加輸入框讓使用者指定來源方塊
2. 鏡像固定 X 軸、旋轉固定 90°，未來可增加下拉選單
3. `BlueprintIO.capture()` 不含 RBlock 材質的完整 undo 快照（與原始 copy 指令行為一致）
