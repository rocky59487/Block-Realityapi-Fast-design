# Block Reality RAG Knowledge Base — Agent 操作手冊

> 本文件專供 AI coding agent 閱讀。在你回答任何與 Block Reality 專案相關的問題之前，請先閱讀本手冊，以確保你能正確、高效地使用本知識庫。

---

## 一、知識庫定位

本知識庫（`rag_kb/`）是 Block Reality 專案的「外掛式大腦」，用來彌補上下文視窗有限的問題。它的目標是：

- **讓你不必逐檔搜尋**就能快速定位關鍵類別、規則與陷阱。
- **提供結構化索引**，包含自動掃描的類別/方法，以及手寫的高價值架構知識。
- **雙軌輸出**：JSON 索引供 CLI 搜尋，Obsidian Vault 供人類可視化閱讀。

---

## 二、目錄結構與優先級

```
rag_kb/
├── search_kb.py              ← 最重要！Agent 的主要入口
├── build_index_v2.py         ← 索引重建器（結構變更時執行）
├── obsidian_export.py        ← 產生 Obsidian Vault
├── AGENTS_GUIDE.md           ← 本文件
├── chunks.json               ← 自動生成的 11,000+ 檢索區塊
├── class_graph.json          ← 類別繼承關係圖
├── inverted_index.json       ← 反向索引
│
├── index_*.json              ← 手寫高價值索引（核心知識來源）
│   ├── index_architecture.json
│   ├── index_rules.json          ← 強制約定（必讀）
│   ├── index_spi.json
│   ├── index_pfsf_physics.json
│   ├── index_rendering.json
│   ├── index_nodes.json
│   ├── index_python_ml.json
│   ├── index_patterns.json       ← 開發模板
│   ├── index_troubleshooting.json ← 常見錯誤
│   ├── index_native.json
│   ├── index_gradle_build.json
│   ├── index_dataflow.json       ← 資料流（高價值）
│   ├── index_toolchain.json
│   ├── index_test_coverage.json
│   └── index_key_classes.json    ← 核心類別快速參考卡（極高價值）
│
└── obsidian/                 ← 人類可視化 Vault（可選參考）
    ├── 00 - MOC/Home.md
    ├── 00 - Canvas/              ← 視覺化白板
    └── 01~10 - 主題區/
```

### 優先級原則

1. **永遠先搜尋手寫索引**：`index_rules.json`、`index_key_classes.json`、`index_patterns.json`、`index_troubleshooting.json`、`index_dataflow.json` 是最高知識密度的來源。
2. **自動索引用於定位細節**：當你需要確認某個類別的精確路徑、方法簽名、或 shader 檔名時，才搜尋 `chunks.json`。
3. **Obsidian Vault 是輔助**：如果你需要理解複雜的資料流，可以參考 `obsidian/00 - Canvas/` 的白板圖，但你不應該試圖「讀完」整個 vault（有 10,000+ 筆記）。

---

## 三、CLI 搜尋標準工作流

當使用者提出任何修改請求時，請依以下順序執行：

### Step 1: 高層定位
```bash
python rag_kb/search_kb.py "<關鍵字>" --top 10
```
目的：了解該功能屬於哪個模組（api / fastdesign / brml / libpfsf）。

### Step 2: 檢查強制規則
```bash
python rag_kb/search_kb.py "<關鍵字>" --tag rule
python rag_kb/search_kb.py "<關鍵字>" --tag critical
```
目的：確認是否有不可違反的約定（如 `sigmaMax`、`client-only`、`26 連通`）。

### Step 3: 查看開發模式
```bash
python rag_kb/search_kb.py "<關鍵字>" --tag pattern
```
目的：找到標準的開發模板（如「如何新增節點」、「如何新增 SPI」）。

### Step 4: 查看核心類別參考卡
```bash
python rag_kb/search_kb.py "<關鍵字>" --tag key-class
```
目的：快速掌握關鍵類別的職責、線程安全、修改注意事項。

### Step 5: 查看資料流
```bash
python rag_kb/search_kb.py "<關鍵字>" --tag dataflow
```
目的：確認修改會影響上下游的哪些檔案。

### Step 6: 查看陷阱與測試
```bash
python rag_kb/search_kb.py "<關鍵字>" --tag troubleshooting
python rag_kb/search_kb.py "<關鍵字>" --tag test_coverage
```
目的：避免已知錯誤，並知道修改後該跑什麼測試。

### Step 7: 精確定位原始碼
```bash
python rag_kb/search_kb.py "<類別名>" --type class
python rag_kb/search_kb.py --methods-of <ClassName>
```
目的：打開原始檔進行修改。

---

## 四、常用搜尋範例

| 場景 | 指令 |
|------|------|
| 修改 PFSF buffer / threshold | `python rag_kb/search_kb.py "PFSF sigmaMax"` |
| 修改 shader stencil | `python rag_kb/search_kb.py "stencil" --tag critical` |
| 新增 client-only 渲染器 | `python rag_kb/search_kb.py "client-only" --tag pattern` |
| 新增網路封包 | `python rag_kb/search_kb.py "network packet" --tag pattern` |
| 新增節點 | `python rag_kb/search_kb.py "NodeRegistry" --tag pattern` |
| 查類別繼承關係 | `python rag_kb/search_kb.py --graph-children BRNode` |
| 查類別方法 | `python rag_kb/search_kb.py --methods-of PFSFDataBuilder` |
| 新增材料 | `python rag_kb/search_kb.py "material" --tag spi` |
| 新增 SPI | `python rag_kb/search_kb.py "ModuleRegistry" --tag pattern` |
| 編譯錯誤排查 | `python rag_kb/search_kb.py "NoClassDefFoundError"` |
| 查詢 Gradle 指令 | `python rag_kb/search_kb.py "mergedJar"` |
| 修改後該跑什麼測試 | `python rag_kb/search_kb.py "PFSFDataBuilder" --tag test_coverage` |

---

## 五、同義詞與標籤速查

### 自動同義詞擴展
搜尋時會自動擴展以下同義詞，提升召回率：
- `pfsf` → `gpu`, `vulkan`, `compute`
- `client` → `client-only`, `render`, `gui`
- `node` → `noderegistry`, `port`, `wire`, `evaluate`
- `shader` → `glsl`, `compute`, `rt`
- `onnx` → `ml`, `inference`, `surrogate`
- `crash` → `error`, `exception`, `noclassdeffound`

### 關鍵標籤
- `#critical` — 極重要，通常涉及編譯錯誤或執行期崩潰
- `#client-only` — 僅在客戶端載入，錯誤引用會導致伺服器 crash
- `#pfsf` — GPU 物理引擎相關
- `#node` — FastDesign 節點系統
- `#shader` — GLSL / Vulkan Compute
- `#pattern` — 開發模板與最佳實踐
- `#troubleshooting` — 常見錯誤排查
- `#test_coverage` — 測試對應映射
- `#dataflow` — 管線與資料流說明
- `#key-class` — 核心類別快速參考卡

---

## 六、類別關係圖查詢

當你需要分析「修改某個類別會影響多少子類別」時，使用類圖查詢：

```bash
# 列出所有繼承 BRNode 的類別（共 166 個）
python rag_kb/search_kb.py --graph-children BRNode

# 列出所有實作 IFusionDetector 的類別
python rag_kb/search_kb.py --graph-implements IFusionDetector

# 查詢某類別的父類別與實作介面
python rag_kb/search_kb.py --graph-parents ConcreteMaterialNode
```

---

## 七、Obsidian Vault 使用建議（給 Agent 的輔助參考）

雖然你主要使用 CLI 搜尋，但在以下情況可以參考 Obsidian Vault：

1. **理解複雜資料流**：打開 `obsidian/00 - Canvas/PFSF Pipeline.canvas` 或 `Rendering Pipeline.canvas`，視覺化白板能幫助你快速建立「全局地圖」。
2. **人類溝通時**：如果你需要向使用者解釋某個架構概念，可以直接引用 `obsidian/08 - Key Classes/` 下的筆記標題。
3. **快速查閱核心類別卡片**：`08 - Key Classes/` 的筆記經過濃縮整理，比直接讀原始碼更高效。

**請不要試圖閱讀 `99 - Auto/` 下的數千個方法筆記**，那是噪音區。

---

## 八、維護責任

如果你修改了以下任何一類內容，**必須同步更新對應的 `index_*.json`**：

- 新增/修改 SPI 接口
- 新增 PFSF buffer 或 shader
- 新增節點類別
- 修改網路封包設計模式
- 新增或調整 Gradle 任務
- 發現新的常見陷阱或錯誤模式
- 新增測試類別且需要建立對應映射

更新後，執行：
```bash
python rag_kb/build_index_v2.py
python rag_kb/obsidian_export.py
```

---

## 九、絕對禁止的事項

1. **不要在 `api` 模組中引用 `fastdesign` 模組的任何類別** — 會導致編譯失敗。
2. **不要讓 `network/` 封包類別直接 import `client/` 下的類別** — 會導致專用伺服器 `NoClassDefFoundError`。
3. **不要忘記為 PFSF 新增 threshold buffer 做 `/= sigmaMax`** — 會導致 GPU 數值尺度錯誤。
4. **不要修改 RC 融合比例** — 固定為 97% 混凝土 / 3% 鋼筋。
5. **不要開啟 Gradle daemon** — 未經測試，daemon 會導致建置不穩定。

---

## 十、快速參考卡：最重要的 10 個搜尋關鍵字

1. `PFSF sigmaMax` — GPU 物理核心約定
2. `client-only` — 客戶端/伺服器端分離
3. `NodeRegistry` — 節點註冊
4. `ModuleRegistry` — SPI 註冊中心
5. `RCFusionDetector` — RC 融合偵測
6. `OnnxPFSFRuntime` — ML 推論入口
7. `BRVulkanRT` — Vulkan 光追主控
8. `mergedJar` — 合併 JAR 建置
9. `fix_imports` — 自動修復封包 import
10. `NoClassDefFoundError` — 最常見的 crash 排查

---

> **總結**：在使用本知識庫時，**先搜尋手寫索引 → 再查自動索引 → 最後進原始碼**。善用 `search_kb.py` 的 tag 過濾與類圖查詢，可以大幅節省時間並避免已知陷阱。
