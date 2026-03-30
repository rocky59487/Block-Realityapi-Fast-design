# BRNode、NodeGraph、Wire、NodePort

> 所屬：L1-api > L2-node

## 概述

節點圖的核心資料結構：節點（BRNode）、有向圖管理器（NodeGraph）、連線（Wire）、埠（NodePort）與型別系統（PortType）。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BRNode` | `com.blockreality.api.node` | 抽象節點基類 |
| `NodeGraph` | `com.blockreality.api.node` | DAG 管理器（拓撲排序 + 評估） |
| `Wire` | `com.blockreality.api.node` | 有向連線（output → input，含自動型別轉換） |
| `NodePort` | `com.blockreality.api.node` | 節點埠（input/output，含值與連線狀態） |
| `PortType` | `com.blockreality.api.node` | 埠型別枚舉（14 種，含自動轉換規則） |

## BRNode

### 建構

子類別在建構子中透過 `addInput()`/`addOutput()` 宣告埠，並實作 `evaluate()` 方法。

### 核心方法

#### `evaluate()`
- **抽象方法**：計算輸出值，由圖排程器在 dirty 狀態時呼叫

#### `markDirty()`
- **說明**：標記需要重新評估。依賴拓撲排序保證上游先於下游評估

#### `getFloat(portName)` / `getInt(portName)` / `getBool(portName)`
- **說明**：便利方法，從 input 埠讀取值（含型別安全轉換）

#### `setOutput(portName, value)`
- **說明**：設定 output 埠值（不觸發自身 markDirty，避免無限循環）

### 屬性

| 屬性 | 說明 |
|------|------|
| `nodeId` | UUID 唯一識別 |
| `displayName` | 顯示名稱 |
| `category` | 分類（render/material/physics/tool/export） |
| `color` | 分類顏色 |
| `posX, posY` | 編輯器畫布位置 |
| `collapsed` | 是否摺疊 |
| `dirty` | 是否需要重新評估 |
| `enabled` | 是否啟用 |

## NodeGraph

### 核心方法

#### `addNode(BRNode)` / `removeNode(String nodeId)`
- **說明**：增刪節點，自動標記拓撲順序需重建

#### `connect(NodePort source, NodePort target)`
- **回傳**：`Wire`
- **說明**：建立連線，input 埠已連接時先斷開舊線。自動標記下游 dirty

#### `evaluate()`
- **回傳**：評估的節點數
- **說明**：重建拓撲順序（若需要），按順序評估所有 dirty 且 enabled 的節點

#### `markDownstreamDirty(BRNode)`
- **說明**：BFS 傳播 dirty 到所有下游節點（O(N+E) 索引化）

#### `findConnectedSubgraph(String nodeId)`
- **回傳**：`List<BRNode>`（無向連通子圖）

### 拓撲排序

使用 Kahn's algorithm，處理環路時將剩餘節點附加在末尾（未定義順序）。

## Wire

### 自動型別轉換

| 來源 | 目標 | 轉換 |
|------|------|------|
| FLOAT | INT | `Math.round()` |
| INT | FLOAT | 直接轉型 |
| BOOL | INT/FLOAT | `true→1, false→0` |
| VEC3 | COLOR | RGB float[3] → 0xRRGGBB |
| COLOR | VEC3 | 0xRRGGBB → RGB float[3] |
| BLOCK | MATERIAL | 直接傳遞 |

## PortType（14 種）

`FLOAT`, `INT`, `BOOL`, `VEC2`, `VEC3`, `VEC4`, `COLOR`, `MATERIAL`, `BLOCK`, `SHAPE`, `TEXTURE`, `ENUM`, `CURVE`, `STRUCT`

每種型別定義 Java 類型、ID 字串與連線顏色。`canConnectTo()` 定義允許的自動轉換規則。

## NodePort

- Input 埠最多接受一條連線（`connectedWire`）
- Output 埠可扇出到多條連線（由 NodeGraph 管理）
- `getValue()` — 若已連線則從 Wire 拉取值（含自動轉換），否則返回本地值
- `setValue()` — 設值並觸發 owner `markDirty()`
- `setValueDirect()` — 直接設值不觸發 dirty（供 output 埠使用）
- 可選 `setRange(min, max)` — 供 slider UI 使用

## 關聯接口

- 被依賴 ← [EvaluateScheduler](L3-evaluate.md) — 排程評估
- 被依賴 ← `fastdesign/client/node/` — 90+ 節點實作
- 被依賴 ← `NodeGraphIO` — 序列化/反序列化
