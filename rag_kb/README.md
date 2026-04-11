# Block Reality RAG Knowledge Base v3.0

這是專為 Block Reality 專案設計的輕量級檢索知識庫（RAG KB），用來輔助 AI coding agent 快速了解專案架構、定位關鍵檔案、避免常見陷阱，大幅節省逐檔搜尋的時間。

---

## 雙重使用方式

本知識庫提供 **兩種介面**：

1. **CLI 搜尋**（給 Agent）— 快速、精確、可程式化調用
2. **Obsidian Vault**（給人類）— 可視化 Graph View、標籤過濾、Wiki-link 導航、**Dataview 動態查詢**、**Canvas 視覺化白板**、**Folder Note 資料夾入口**

---

## 快速開始：Obsidian 可視化閱讀（推薦給人類）

### 1. 產生/更新 Obsidian Vault

```bash
python rag_kb/obsidian_export.py
```

這會在 `rag_kb/obsidian/` 下建立一個完整的 Obsidian Vault。

### 2. 用 Obsidian 開啟

1. 安裝 [Obsidian](https://obsidian.md/)
2. 選擇「Open folder as vault」
3. 開啟 `rag_kb/obsidian/` 資料夾
4. **（推薦）安裝社群外掛 [Dataview](https://github.com/blacksmithgu/obsidian-dataview)**：這樣 MOC 頁面底部的表格會動態列出該資料夾的所有筆記

### 3. Vault 結構

| 資料夾 | 內容 |
|--------|------|
| `00 - MOC/` | 總覽 `Home.md` + 各主題 MOC |
| `00 - Canvas/` | **視覺化白板**（PFSF / Rendering / ML / Node 資料流） |
| `01 - Architecture/` | 模組邊界、規則、守則 |
| `02 - SPI/` | SPI 擴展點 |
| `03 - Physics/` | PFSF 引擎 + 資料流 |
| `04 - Rendering/` | Vulkan RT、LOD、Shader |
| `05 - Nodes/` | FastDesign 節點系統 |
| `06 - ML/` | JAX/Flax、ONNX、BIFROST |
| `07 - Toolchain/` | Gradle、Fix 腳本、CI |
| `08 - Key Classes/` | **核心類別快速參考卡** |
| `09 - Troubleshooting/` | 常見錯誤排查 |
| `10 - Test Coverage/` | 測試與原始碼對應 |
| `99 - Auto/` | 自動匯出的類別/方法/Shader/測試 |

### 4. 三大新功能

#### 🎨 Canvas 視覺化白板

位於 `00 - Canvas/`，目前提供 4 張白板：

| 白板 | 用途 |
|------|------|
| `PFSF Pipeline.canvas` | 物理計算從世界 → 島嶼 → GPU → 崩塌的完整鏈路 |
| `Rendering Pipeline.canvas` | Vulkan RT 渲染管線（Chunk → Mesh → BVH → Shader → PostFX） |
| `ML Training Pipeline.canvas` | 從資料 → FEM → 訓練 → ONNX → 遊戲內推論 |
| `Node Evaluation Pipeline.canvas` | FastDesign 節點圖求值流程 |

使用方式：在 Obsidian 中直接點擊 `.canvas` 檔案，可以**拖曳節點、縮放、點擊節點跳轉到對應筆記**。

#### 📁 Folder Note 資料夾入口

**每個資料夾內都有 `README.md`**。當你在檔案瀏覽器中點開某個資料夾時，可以直接打開 `README.md`，它會自動嵌入該區的 MOC 目錄。例如：
- `03 - Physics/README.md` → 內嵌 `Physics MOC`
- `99 - Auto/Methods/PFSFDataBuilder/README.md` → 內嵌 `PFSFDataBuilder Methods MOC`

#### 🔍 Dataview 動態查詢

每個 MOC 頁面底部都有類似這樣的區塊：

```markdown
## 🔍 Dataview 動態查詢
> [!info] 需安裝 Dataview 外掛才能看到動態結果

```dataview
TABLE type, summary
FROM "03 - Physics/Concepts" OR "03 - Physics/Dataflows"
WHERE type != "moc"
SORT file.name ASC
LIMIT 50
```

安裝 Dataview 後，這個區塊會**即時列出該主題下的所有筆記**，並顯示 type 和 summary，比靜態表格更靈活。

### 5. 其他建議用法

- **Graph View**：`Ctrl+G` / `Cmd+G`，顏色已預設分類（紅=critical、綠=pattern、黃=troubleshooting、青=test_coverage、紫=key-class、藍=dataflow）
- **Tags 面板**：左側快速篩選 `#pfsf`、`#shader`、`#node` 等標籤
- **核心類別卡片**：`08 - Key Classes/` 的筆記整理為「職責 / 主要方法 / 修改注意 / WARNING」格式，適合快速查閱

---

## 快速開始：CLI 搜尋（給 Agent）

```bash
# 基本搜尋（支援同義詞自動擴展）
python rag_kb/search_kb.py "PFSF sigmaMax"

# 限制結果數量
python rag_kb/search_kb.py "client-only renderer" --top 10

# 按 tag 過濾
python rag_kb/search_kb.py "node" --tag fastdesign

# 按類型過濾
python rag_kb/search_kb.py "BRVulkanRT" --type class

# 查詢類別繼承關係
python rag_kb/search_kb.py --graph-children BRNode
python rag_kb/search_kb.py --graph-implements ILoadPathManager
python rag_kb/search_kb.py --graph-parents ConcreteMaterialNode

# 查詢某個類別有哪些公開方法
python rag_kb/search_kb.py --methods-of PFSFDataBuilder

# 查找修改某功能後應該執行的測試
python rag_kb/search_kb.py "PFSFDataBuilder" --tag test_coverage

# 查看熱門標籤統計
python rag_kb/search_kb.py --list-tags
```

### 重建索引

當專案結構有重大變更時，執行：

```bash
python rag_kb/build_index_v2.py
python rag_kb/obsidian_export.py
```

---

## 知識庫結構總覽

### 自動生成索引

| 檔案 | 內容 |
|------|------|
| `chunks.json` | **11,023** 個檢索區塊 |
| `class_graph.json` | Java 類別繼承與實作關係圖 |
| `inverted_index.json` | 11,316 tokens 反向索引 |

### 手寫高價值索引

| 檔案 | 內容 |
|------|------|
| `index_architecture.json` | 模組邊界、client/server 分離 |
| `index_rules.json` | 架構守則、單位約定 |
| `index_spi.json` | SPI 擴展點 |
| `index_pfsf_physics.json` | PFSF GPU 引擎、ONNX surrogate |
| `index_rendering.json` | Vulkan RT、DDGI/ReSTIR |
| `index_nodes.json` | 90+ 節點分類 |
| `index_python_ml.json` | brml JAX/Flax、FEM |
| `index_patterns.json` | 開發模式與程式碼模板 |
| `index_troubleshooting.json` | 常見編譯/執行期錯誤排查 |
| `index_native.json` | JNI / GLSL Shader 索引 |
| `index_gradle_build.json` | Gradle、CI、版本對應 |
| `index_dataflow.json` | PFSF/Render/Network/ML 完整資料流 |
| `index_toolchain.json` | fix 腳本、Docker、除錯工具 |
| `index_test_coverage.json` | 15 組核心測試映射 |
| `index_key_classes.json` | 16 個核心類別快速參考卡 |

---

## 給 Agent 的檢索策略

1. **查詢高層架構**
2. **查詢相關規則**（`--tag rule`）
3. **查詢開發模式**（`--tag pattern`）
4. **查詢具體類別/方法**（`--type class/method` 或 `--methods-of`）
5. **查詢類別關係**（`--graph-children <類別>`）
6. **查詢資料流**（`--tag dataflow`）
7. **查詢常見陷阱**（`--tag troubleshooting`）
8. **查詢測試覆蓋**（`--tag test_coverage`）

---

## 維護說明

- `build_index_v2.py` **不會**覆蓋 `index_*.json`，只會更新自動生成的 JSON。
- `obsidian_export.py` 會**刪除舊 vault 並重建**，確保每次都是最新狀態。
- 每次提交前如果 Java 類別結構變化較大，請執行：
  ```bash
  python rag_kb/build_index_v2.py
  python rag_kb/obsidian_export.py
  ```
