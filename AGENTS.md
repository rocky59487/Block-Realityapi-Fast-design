# Block Reality — AI Agent 開發指引

> 本文件專為 AI coding agent 撰寫。閱讀前請假設你對本專案一無所知。
> 專案主要文件語言為**繁體中文**，技術術語保留英文。

---

## 專案概述

**Block Reality** 是 Minecraft Forge 1.20.1 的結構物理模擬引擎模組，核心願景是：「現實中撐不住的，這裡也撐不住。」

系統將每個 Minecraft 方塊轉化為具有真實工程屬性（抗壓強度 MPa、抗拉強度、剪切阻力、密度 kg/m³、楊氏模量 GPa）的結構元素，並透過 GPU 上的 **PFSF（Potential Field Structure Failure）** 引擎每 server tick 評估結構完整性。失去支撐的結構會動態崩塌。

專案採用多語言、多模組架構：
- **Java + Gradle**：Minecraft Forge 模組本體（`api` 基礎層 + `fastdesign` 擴充層）
- **Python + JAX/Flax**：ML 訓練管線（`brml/`），產生 ONNX surrogate 模型供遊戲內推理
- **C++ + Vulkan**：獨立 PFSF 求解器函式庫（`libpfsf/`）與 NRD 降噪器 JNI 橋接（`api/src/main/native/`）

---

## 倉庫結構

```
Block-Realityapi-Fast-design/
├── Block Reality/              Gradle 多專案根目錄
│   ├── api/                    com.blockreality.api（基礎層模組）
│   │   ├── src/main/java/.../physics/pfsf/     PFSF GPU 求解器
│   │   ├── src/main/java/.../material/         材料系統
│   │   ├── src/main/java/.../collapse/         崩塌管理
│   │   ├── src/main/java/.../blueprint/        藍圖 I/O
│   │   ├── src/main/java/.../client/render/    Vulkan 渲染（CLIENT ONLY）
│   │   ├── src/main/java/.../spi/              擴展接口中心
│   │   ├── src/main/java/.../node/             節點圖核心
│   │   ├── src/main/native/                    NVIDIA NRD JNI 橋接（C++）
│   │   └── src/test/java/...                   JUnit 5 測試
│   ├── fastdesign/             com.blockreality.fastdesign（擴充層）
│   │   ├── src/main/java/.../client/           全息投影、HUD、GUI
│   │   ├── src/main/java/.../client/node/      節點編輯器（90+ 節點）
│   │   ├── src/main/java/.../command/          /fd 指令系統
│   │   ├── src/main/java/.../construction/     施工事件處理
│   │   └── src/main/java/.../network/          封包同步
│   ├── build.gradle            根建置腳本（含 mergedJar 任務）
│   ├── settings.gradle         子專案設定 + Forge bootstrap 鏡像邏輯
│   ├── gradle.properties       -Xmx3G, daemon=false, disableLocking=true
│   └── merged-resources/       合併 JAR 用的 mods.toml / pack.mcmeta
│
├── brml/                       Python ML 訓練管線
│   ├── brml/models/            FNO3D、CollapsePredictor、NodeRecommender
│   ├── brml/fem/               FEM ground-truth 求解器（hex8）
│   ├── brml/pipeline/          auto_train.py、concurrent_trainer.py
│   ├── brml/export/            ONNX 匯出 + 合約驗證
│   ├── brml/train/             各模型獨立訓練腳本
│   ├── brml/ui/                Gradio Web UI + TUI
│   ├── tests/                  pytest 測試
│   └── pyproject.toml          套件設定（hatchling）
│
├── libpfsf/                    C++ 獨立 PFSF 求解器
│   ├── CMakeLists.txt
│   ├── src/core/               Vulkan context、buffer manager
│   ├── src/solver/             Jacobi、V-Cycle、phase-field
│   └── include/pfsf/           公開標頭檔
│
├── docs/                       四層分層架構文檔（L1→L2→L3，見 docs/index.md）
│   ├── L1-api/                 基礎層模組文檔
│   ├── L1-fastdesign/          擴充層模組文檔
│   └── archive/                歷史審核報告與舊文件
│
├── install/                    安裝腳本與說明
├── .github/workflows/          GitHub Actions CI（Build & Test）
├── .github/scripts/            Claude/Jules 自動化 workflow 腳本
├── Dockerfile                  多階段 Docker 建置
├── start-trainer.bat/.sh/.ps1  BIFROST ML 一鍵啟動腳本
├── fix_imports.py              修復 fastdesign 網路封包中的 client 引用
├── fix_only_in_client.py       自動為 client 套件類別加上 @OnlyIn(Dist.CLIENT)
├── fix_registry.py             自動將未註冊節點加入 NodeRegistry
├── mpd.jar                     合併輸出（api + fastdesign）
├── README.md                   專案說明（雙語）
├── CLAUDE.md                   Claude Code 專用開發指引
├── LICENSE                     GPL-3.0 授權
└── AGENTS.md                   ← 本文件
```

**關鍵依賴方向（絕不可違反）**：`fastdesign` → `api`（單向）。`api` 不得引用 `fastdesign`。

---

## 技術棧

| 層級 | 技術 |
|------|------|
| 遊戲平台 | Minecraft Forge 1.20.1 (47.4.13) + Official Mappings |
| 模組語言 | Java 17 |
| 建置系統 | Gradle 8.8（wrapper）、daemon 停用、3 GB heap |
| GPU 計算 | Vulkan Compute（PFSF 物理） |
| GPU 渲染 | Vulkan RT（硬體光追、ReLAX denoiser） |
| LWJGL | 3.3.1（透過 boot classpath 注入繞過 Forge 限制） |
| ML 推理 | ONNX Runtime 1.17.3 |
| ML 訓練 | Python 3.10+、JAX/Flax、Optax、scipy |
| 測試 | JUnit 5（Java）、pytest（Python） |
| CI/CD | GitHub Actions、Docker |
| 授權 | GPL-3.0（見 LICENSE） |

---

## 建置與測試指令

### Java 模組（`Block Reality/` 目錄下執行）

```bash
cd "Block Reality"

# 完整建置
./gradlew build

# 合併 JAR（輸出至專案根目錄 mpd.jar，可直接放入 mods/）
./gradlew mergedJar

# 執行 Minecraft
./gradlew :fastdesign:runClient      # 推薦：Fast Design + API
./gradlew :api:runClient             # 僅 API
./gradlew :api:runServer             # 專用伺服器

# 部署至 PrismLauncher 開發實例
./gradlew :api:copyToDevInstance
./gradlew :fastdesign:copyToDevInstance

# 測試（JUnit 5）
./gradlew test                       # 全部
./gradlew :api:test                  # 僅 API
./gradlew :api:test --tests "com.blockreality.api.physics.ForceEquilibriumSolverTest"
```

**注意**：`gradle.properties` 預設設定了 `disableLocking=true`。若需要產生 lockfile，執行 `./gradlew dependencies --write-locks`，然後可移除或設為 `disableLocking=false`。

### Python ML 管線（`brml/` 目錄下執行）

```bash
cd brml

# 安裝（建議使用虛擬環境）
pip install -e .

# 或一鍵啟動（會自動建立 venv、安裝依賴、啟動 UI）
../start-trainer.sh        # Linux/Mac
..\start-trainer.bat       # Windows

# 自動訓練
python -m brml.pipeline.auto_train

# ONNX 匯出
python -m brml.export.onnx_export

# Web UI / TUI
python -m brml.ui.web_ui
python -m brml.ui.tui

# Python 測試
cd ..
pytest brml/tests/
```

### C++ 獨立求解器（`libpfsf/`）

```bash
cd libpfsf
mkdir build && cd build
cmake .. -DPFSF_BUILD_TESTS=ON
cmake --build .
```

### NRD JNI 橋接（`Block Reality/api/src/main/native/`）

```bash
cd "Block Reality/api/src/main/native"
mkdir build && cd build
cmake .. -DJAVA_HOME=$JAVA_HOME
cmake --build . --config Release
cmake --install .
```

---

## 程式碼風格與命名規範

### Java

- **Java 17** 功能皆可使用（records、sealed、text blocks）
- **套件前綴**：
  - `com.blockreality.api.*`（基礎層）
  - `com.blockreality.fastdesign.*`（擴充層）
- **類別前綴**：自訂方塊/材料相關以 `R` 開頭，如 `RBlock`、`RMaterial`
- **SPI 接口**以 `I` 開頭，如 `ICableManager`、`IFusionDetector`
- **預設實作**以 `Default` 開頭，如 `DefaultCuringManager`
- **節點類別**以 `Node` 結尾，如 `ConcreteMaterialNode`
- **物理單位**（強制）：
  - 強度：`MPa`
  - 楊氏模量：`GPa`
  - 密度：`kg/m³`
- **原始碼編碼**：UTF-8

### Python

- `ruff` 格式化，行長 100（見 `pyproject.toml`）
- 目標版本 `py310`

### 提交訊息格式

```
<類型>(<範圍>): <簡短說明>
```
類型：`feat` / `fix` / `refactor` / `test` / `docs` / `perf` / `chore`

---

## 架構守則與關鍵約定

### 1. 客戶端/伺服器端分離（極重要）

- `api/src/main/java/.../client/` 與 `fastdesign/src/main/java/.../client/` 下的類別**僅在客戶端載入**
- 這些類別必須標註 `@OnlyIn(Dist.CLIENT)`
- **嚴禁**在伺服器端引用 client 套件中的類別，否則會 crash
- 網路封包類別（`network/`）必須同時存在於兩端，但內部處理 client-only 渲染時須使用完整 FQN 或延遲解析，避免伺服器載入 client 類別

### 2. sigmaMax 正規化約定（PFSF 核心）

`PFSFDataBuilder` 上傳 GPU 前會除以 `sigmaMax`。任何新增的 threshold buffer **必須**同步處理：

```java
conductivity[i] /= sigmaMax;   // → [0, 1]
source[i]       /= sigmaMax;
maxPhi[i]       /= sigmaMax;   // 懸臂閾值
rcomp[i]        /= sigmaMax;   // 壓碎閾值
rtens[i]        /= sigmaMax;   // 拉斷閾值
// phi 場不變（A·φ=b 兩邊同除自動抵消）
```

### 3. 26 連通一致性（PFSF Shader）

RBGS、Jacobi、PCG matvec **必須**使用完全相同的 26 連通 stencil：
- 6 面鄰居：`×1.0`
- 12 邊鄰居：`×0.35`（`SHEAR_EDGE_PENALTY`）
- 8 角鄰居：`×0.15`（`SHEAR_CORNER_PENALTY`）

任一 shader 不一致 → CG 收斂到錯誤解 / 多網格發散。

### 4. hField 寫入權

- `hField`（歷史應變能場）**僅由** Jacobi/RBGS smoother 寫入（`max(old, ψ_e)`）
- `phase_field_evolve.comp.glsl` 對 `hField` **唯讀**
- 從兩個 shader 寫入會造成 GPU race condition

### 5. 節點開發流程

1. 繼承 `BRNode`，定義 `Port`
2. 實作 `evaluate()`（由 `EvaluateScheduler` 依拓撲排序呼叫）
3. 在 `NodeRegistry` 註冊
4. 如需 runtime 綁定，實作 `IBinder<T>`

若新增大量節點後忘記註冊，可執行：`python fix_registry.py`

實際節點分類目錄（`fastdesign/client/node/impl/`）：
- `material/` — 材料節點
- `physics/` — 物理節點
- `render/` — 渲染節點
- `tool/` — 工具節點
- `output/` — 輸出節點

### 6. SPI 擴展點

所有擴展透過 `ModuleRegistry` 統一註冊。主要接口：

| 接口 | 用途 | 預設實作 |
|------|------|---------|
| `IFusionDetector` | RC 融合偵測 | `RCFusionDetector` |
| `ICableManager` | 纜索張力管理 | `DefaultCableManager` |
| `ICuringManager` | 混凝土養護進度 | `DefaultCuringManager` |
| `ILoadPathManager` | 荷載路徑傳遞 | `LoadPathEngine` |
| `IMaterialRegistry` | 材料中央註冊表 | 內建 `DefaultMaterialRegistry` |
| `IFluidManager` | 流體模擬管理 | `FluidGPUEngine` |
| `IBinder<T>` | 節點圖綁定 | `MaterialBinder` 等 |
| `IVS2Bridge` | Valkyrien Skies 2 橋接 | `NoOpVS2Bridge` |

---

## 測試策略

### Java 測試

- 框架：**JUnit 5**（Jupiter）+ Mockito
- API 測試目錄：`api/src/test/java/`
- Fast Design 測試目錄：`fastdesign/src/test/java/`
- 測試類別命名以 `Test` 結尾，如 `BlueprintNBTTest.java`

### Python 測試

- 框架：**pytest**
- 測試檔案：`brml/tests/test_*.py`
- 內容：模型前向傳播 shape 驗證、FEM 求解器正確性、ONNX 合約檢查

### CI/CD

`.github/workflows/build.yml` 在 `push` / `pull_request` 到 `main` / `develop` 時觸發：
- 使用 Java 17（Temurin）
- 執行 `./gradlew build --no-daemon --stacktrace -PdisableLocking`
- 失敗時自動重試 3 次（指數退避：15s / 30s）
- 上傳 test reports 作為 artifact

---

## 常用維護腳本

| 腳本 | 用途 |
|------|------|
| `fix_imports.py` | 修復 fastdesign network 封包中對 client 類別的直接 import，改為完整 FQN |
| `fix_only_in_client.py` | 掃描 `fastdesign/client/`，自動為缺少 `@OnlyIn(Dist.CLIENT)` 的類別加上註解 |
| `fix_registry.py` | 掃描 `fastdesign/client/node/impl/`，自動將未註冊的節點加入 `NodeRegistry` |

---

## 安全與授權注意事項

- 本專案授權為 **GPL-3.0**（見 `LICENSE`）
- 建置於 Minecraft Forge 之上，使用本模組即代表同意遵守 Minecraft EULA
- 請勿將未經審查的 binary（`.jar`、`.dll`、`.so`）提交至倉庫
- 網路封包與 sidecar RPC 輸入皆須視為不可信資料，做好邊界檢查

---

## 文件維護規範

當原始碼變更時，**必須**同步更新對應分層文檔：

1. **定位**：根據套件路徑找到 `docs/L1-xxx/L2-xxx/L3-xxx.md`
2. **更新 L3**：修改類別表、方法簽名、關聯接口
3. **更新 L2/L1 index**：如有新增目錄或文件，加入連結
4. **更新 CLAUDE.md / AGENTS.md**：如涉及 SPI、建置流程、架構變更

詳見 `CLAUDE.md`「文件維護規範」章節。

---

## 常見陷阱速查

1. **物理單位混用** — 必須用 MPa / GPa / kg/m³，不可混用 Pa
2. **Forge 事件優先級** — 物理事件通常需要 `EventPriority.HIGH`
3. **Access Transformer** — 修改 `accesstransformer.cfg` 後須 `./gradlew :api:jar` 重建
4. **RC 融合比例** — 固定 97% 混凝土 / 3% 鋼筋，不可調整
5. **流體系統預設關閉** — `BRConfig.isFluidEnabled()` 預設 `false`，且流體-結構耦合有 1 tick 延遲（設計如此）
6. **FNO phi 正規化** — `OnnxPFSFRuntime.infer()` 輸出的 phi（channel 9）為物理尺度，進入 `failure_scan` 前**必須**除以 `sigmaMax`
7. **節點 Port 類型匹配** — `NodeGraphIO` 序列化 port 類型，類型不匹配會導致連線靜默失敗
8. **Gradle daemon** — 本專案停用 daemon，建置較慢但更穩定，未經測試請勿開啟
