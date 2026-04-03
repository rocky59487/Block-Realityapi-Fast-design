# CLAUDE.md

Claude Code（claude.ai/code）在此倉庫中的開發指引。

## 專案概覽

Block Reality — Minecraft Forge 1.20.1 結構物理模擬引擎。兩個 Gradle 子專案（`api`、`fastdesign`）加上 TypeScript sidecar（`MctoNurbs-review`）。使用者主要語言為繁體中文。

## 建置與執行

所有 Gradle 指令在 `Block Reality/` 下執行：

```bash
cd "Block Reality"

# 建置
./gradlew build                      # 完整建置（兩個模組）
./gradlew mergedJar                  # 合併 mpd.jar → 專案根目錄（可放入 mods/）
./gradlew :api:jar                   # 僅 API 模組
./gradlew :fastdesign:jar            # 僅 Fast Design 模組

# 執行 Minecraft
./gradlew :api:runClient             # 僅 API 客戶端
./gradlew :fastdesign:runClient      # Fast Design + API 客戶端
./gradlew :api:runServer             # 專用伺服器

# 部署至 PrismLauncher 開發實例
./gradlew :api:copyToDevInstance
./gradlew :fastdesign:copyToDevInstance

# 測試（JUnit 5）
./gradlew test                       # 所有 Java 測試
./gradlew :api:test                  # 僅 API 測試
./gradlew :api:test --tests "com.blockreality.api.physics.ForceEquilibriumSolverTest"  # 單一測試類別
```

TypeScript sidecar 指令在 `MctoNurbs-review/` 下：

```bash
cd MctoNurbs-review
npm install                          # 安裝依賴
npm run build                        # 編譯 TS → dist/sidecar.js
npm test                             # 執行 vitest
npm run test:watch                   # 監聽模式
npm start                            # 啟動 RPC 伺服器
```

Sidecar 在 `fastdesign:processResources` 時自動建置，開發時無需手動操作。

## 架構

```
api/  (com.blockreality.api)           ← 基礎層，獨立模組
  physics/       UnionFind 連通性、ForceEquilibriumSolver (SOR + 3D力向量 + LRFD荷載組合)、
                 BeamStressEngine (Euler-Bernoulli)、ColumnBucklingCalculator (Johnson+Euler)、
                 LateralTorsionalBuckling (AISC §F2)、LoadCombination (ASCE 7-22)、ForceVector3D
  material/      BlockTypeRegistry、DefaultMaterial（10+ 種材料）、CustomMaterial.Builder、DynamicMaterial (RC 融合 97/3)
  blueprint/     Blueprint ↔ NBT 序列化、BlueprintIO 檔案 I/O、LitematicImporter
  collapse/      CollapseManager — 物理失效時觸發崩塌
  chisel/        10×10×10 體素子方塊造型系統
  sph/           SPH 應力引擎（Monaghan 1992 立方樣條核心 + Teschner 空間雜湊鄰域搜索）
  sidecar/       SidecarBridge — stdio IPC 連接 TypeScript
  client/render/ GreedyMesher、AnimationEngine、RenderPipeline、Vulkan RT、後製特效
  node/          BRNode 節點圖系統、EvaluateScheduler 拓撲排序
  spi/           ModuleRegistry 中心、SPI 擴展接口

fastdesign/  (com.blockreality.fastdesign)  ← 擴充層，依賴 :api
  client/        3D 全息投影預覽、HUD 覆蓋、GUI 畫面、鑿刻工具
  client/node/   節點編輯器（90+ 節點實作：材料/物理/渲染/工具/輸出）
  command/       /fd 命令系統、撤銷管理
  construction/  施工事件處理
  network/       封包同步
  sidecar/       NURBS/STEP 匯出橋接

MctoNurbs-review/                    ← TypeScript sidecar（Node.js）
  src/pipeline.ts    NURBS 匯出管線（雙路徑：GreedyMesh / DualContouring）
  src/rpc-server.ts  JSON-RPC 2.0 伺服器（stdio）
  src/greedy-mesh.ts 貪婪網格化
  src/sdf/           SDF 網格 + Hermite 資料
  src/dc/            雙輪廓面重建 + QEF 求解器
  src/cad/           opencascade.js CAD 核心（Mesh→BRep→STEP）
```

**依賴方向**：`fastdesign` → `api`（絕不反向）。Sidecar 透過 `SidecarBridge` 以 stdio JSON-RPC 與 Java 通訊。

## 基本慣例

- **Java 17** 工具鏈、**Gradle 8.8** wrapper、daemon 停用、3GB heap (`-Xmx3G`)
- **Forge 1.20.1** (47.4.13) + **Official Mappings**
- 所有 Java 原始碼使用 **UTF-8** 編碼
- 測試使用 **JUnit 5** (Jupiter) — `useJUnitPlatform()`
- 物理值使用真實工程單位（MPa 強度、GPa 楊氏模量、kg/m³ 密度）
- Access Transformer: `api/src/main/resources/META-INF/accesstransformer.cfg`
- Mod 中繼資料: `api/src/main/resources/META-INF/mods.toml`；合併版在 `Block Reality/merged-resources/`

## 程式碼慣例

### 命名規則
- 套件前綴：`com.blockreality.api.*`（基礎層）、`com.blockreality.fastdesign.*`（擴充層）
- 自訂方塊類別以 `R` 前綴：`RBlock`、`RBlockEntity`、`RStructure`、`RWorldSnapshot`
- SPI 接口以 `I` 前綴：`ICableManager`、`ICuringManager`、`IFusionDetector`
- 預設實作以 `Default` 前綴：`DefaultCableManager`、`DefaultCuringManager`
- 節點類別以 `Node` 後綴：`ConcreteMaterialNode`、`ForceEquilibriumNode`

### 套件結構規範
- `api/` 中的公開接口 **不得** 引用 `fastdesign/` 中的任何類別
- `spi/` 套件下的接口是擴展點 — 外部模組可實作這些接口
- `client/` 套件下的類別僅在客戶端載入，不可在伺服器端引用
- 網路封包類別必須同時存在於客戶端和伺服器端

### 事件處理模式
- Forge 事件使用 `@SubscribeEvent` 註解
- 物理事件透過 `event/` 套件自訂事件類別分發
- 節點系統事件透過 `EvaluateScheduler` 拓撲排序執行

## SPI 擴展點

所有擴展點透過 `ModuleRegistry` 統一註冊與查詢：

| 接口 | 用途 | 預設實作 |
|------|------|---------|
| `IFusionDetector` | RC 融合偵測（鋼筋+混凝土→複合材料） | `RCFusionDetector` |
| `ICableManager` | 纜索張力物理管理 | `DefaultCableManager` |
| `ICuringManager` | 混凝土養護進度追蹤 | `DefaultCuringManager` |
| `ILoadPathManager` | 荷載路徑傳遞與串級崩塌 | `LoadPathEngine` |
| `IMaterialRegistry` | 材料中央註冊表（執行緒安全） | 內建 |
| `ICommandProvider` | 自訂 Brigadier 指令註冊 | — |
| `IRenderLayerProvider` | 自訂客戶端渲染層 | — |
| `IBlockTypeExtension` | 自訂方塊類型行為 | — |
| `IBinder<T>` | 節點圖埠值↔運行時物件綁定 | `MaterialBinder`、`PhysicsBinder`、`RenderConfigBinder` |

### 註冊範例
```java
// 在模組初始化時
ModuleRegistry.registerCommandProvider(myCommandProvider);
ModuleRegistry.setCableManager(myCustomCableManager);

// 查詢
ICableManager cables = ModuleRegistry.getCableManager();
IMaterialRegistry materials = ModuleRegistry.getMaterialRegistry();
```

## 節點系統開發

節點編輯器位於 `fastdesign/client/node/`，基於 `api/node/` 的核心圖結構。

### 新增節點步驟
1. 繼承 `BRNode`，定義輸入/輸出 `Port`
2. 實作 `evaluate()` 方法（拓撲排序時自動呼叫）
3. 在 `NodeRegistry` 中註冊
4. 若需綁定運行時物件，實作對應 `IBinder<T>`

### 節點分類
- **材料節點** (`impl/material/`) — 基礎材料、混合、運算、造型、視覺化
- **物理節點** (`impl/physics/`) — 崩塌、荷載、結果、求解器
- **渲染節點** (`impl/render/`) — 光照、LOD、管線、後製、水體、天氣
- **工具節點** (`impl/tool/`) — 輸入、放置、選取、UI
- **輸出節點** (`impl/output/`) — 匯出、監控

### Binder 對接
```java
// 實作 IBinder<T> 連接節點到運行時系統
IBinder<MutableRenderConfig> binder = new RenderConfigBinder();
binder.bind(nodeGraph);      // 掃描節點建立映射
binder.apply(renderConfig);  // 推送節點值到運行時
binder.pull(renderConfig);   // 從運行時拉取值到節點
```

## Sidecar IPC 協議

Java 與 TypeScript 之間使用 stdio JSON-RPC 2.0 通訊：

### 已註冊方法
| 方法 | 說明 | 參數 |
|------|------|------|
| `ping` | 連線測試 | 無 |
| `dualContouring` | 體素→STEP 匯出 | `blocks[]`、`smoothing`(0.0-1.0)、`resolution`(1-4)、`outputPath` |
| `ifc4Export` | **IFC 4.x 結構匯出**（P3-A）— 含元素分類、材料屬性、應力利用率屬性集 | `blocks[]`、`outputPath`、`projectName?`、`authorOrg?`、`includeGeometry?`(bool) |

### 限制
- 最大方塊數：10,000
- 最大網格格數：256³
- 解析度範圍：1-4
- 匯出逾時：30 秒

### 管線雙路徑
- `smoothing = 0` → GreedyMesh（快速、銳利邊緣）
- `smoothing > 0` → SDF Grid → Dual Contouring（平滑曲面）

## 常見陷阱

1. **物理單位混用** — 所有強度值必須用 MPa，楊氏模量用 GPa，密度用 kg/m³。不要混用 Pa 或 N/mm²
2. **Forge 事件優先級** — `@SubscribeEvent` 的 `priority` 參數影響執行順序，物理事件通常需要 `EventPriority.HIGH`
3. **Access Transformer** — 修改 AT 後需要 `./gradlew :api:jar` 重新建置才生效
4. **Gradle daemon** — 本專案停用 daemon，建置速度較慢但更穩定
5. **Sidecar 路徑** — 生產環境中 sidecar 位於 `/blockreality/sidecar/dist/sidecar.js`，開發時由 Gradle 自動處理
6. **RC 融合比例** — 固定為 97% 混凝土 / 3% 鋼筋，不可調整
7. **節點圖序列化** — `NodeGraphIO` 處理序列化，Port 類型必須正確匹配否則連線靜默失敗
8. **客戶端/伺服器端分離** — `client/` 下的類別使用 `@OnlyIn(Dist.CLIENT)` 或相當邏輯，在伺服器載入會 crash

## 文檔索引

結構化 API 參考文檔位於 `docs/`，採四層分層架構：

- [docs/index.md](docs/index.md) — 總索引入口
- [docs/L1-api/](docs/L1-api/index.md) — Block Reality API 基礎層
- [docs/L1-fastdesign/](docs/L1-fastdesign/index.md) — Fast Design 擴充層
- [docs/L1-sidecar/](docs/L1-sidecar/index.md) — MctoNurbs TypeScript Sidecar

歷史文檔歸檔於 `docs/archive/`。

## 文檔維護規範

當原始碼有以下變更時，**必須同步更新**對應的分層文檔：

### 何時需要更新文檔

| 變更類型 | 影響的文檔層級 | 範例 |
|---------|-------------|------|
| 新增套件/模組 | L1 index + 新建 L2 目錄 | 新增 `api/weather/` 套件 |
| 新增功能類別 | L2 index + 新建 L3 檔案 | 在 `physics/` 下新增流體模擬 |
| 新增/修改公開 API | L3 檔案 | 新增 `ForceEquilibriumSolver.setMaxIterations()` |
| 新增/移除 SPI 接口 | L3 + CLAUDE.md SPI 表格 | 新增 `IWeatherProvider` |
| 新增節點類別 | L3 節點分類文檔 | 新增 `FluidSimNode` |
| 修改 Sidecar RPC 方法 | L3 + CLAUDE.md IPC 表格 | 新增 `meshSimplify` RPC 方法 |
| 修改建置流程 | CLAUDE.md 建置章節 | 新增 Gradle task |

### 更新步驟

1. **定位**：根據變更的套件路徑，找到對應的 `docs/L1-xxx/L2-xxx/L3-xxx.md`
2. **更新 L3**：修改接口文檔中的類別表、方法簽名、關聯接口
3. **更新 L2 index**：如新增了 L3 檔案，在 L2 的 `index.md` 中加入連結
4. **更新 L1 index**：如新增了 L2 目錄，在 L1 的 `index.md` 中加入連結
5. **更新總索引**：如有重大結構變更，更新 `docs/index.md` 的快速查找表
6. **更新 CLAUDE.md**：如涉及 SPI、IPC、建置流程或架構變更

### 文檔格式參照

每個 L3 文件遵循統一模板（見現有 L3 檔案），包含：
- 概述（一句話）
- 關鍵類別表格（類別、套件路徑、說明）
- 核心方法（簽名、參數、回傳、說明）
- 關聯接口（依賴方向 + 相對連結）

### 目錄結構速查

```
docs/
├── index.md                    總索引
├── L1-api/                     API 基礎層（10 個 L2）
│   ├── L2-physics/             物理引擎（6 個 L3）
│   ├── L2-material/            材料系統（4 個 L3）
│   ├── L2-blueprint/           藍圖系統（2 個 L3）
│   ├── L2-collapse/            崩塌模擬（1 個 L3）
│   ├── L2-chisel/              鑿刻系統（1 個 L3）
│   ├── L2-sph/                 SPH 應力引擎（1 個 L3）
│   ├── L2-sidecar/             Sidecar 橋接（2 個 L3）
│   ├── L2-render/              渲染管線（5 個 L3）
│   ├── L2-spi/                 SPI 擴展（3 個 L3）
│   └── L2-node/                節點圖核心（2 個 L3）
├── L1-fastdesign/              Fast Design 擴充層（6 個 L2）
│   ├── L2-client-ui/           客戶端 UI（3 個 L3）
│   ├── L2-node-editor/         節點編輯器（7 個 L3）
│   ├── L2-command/             指令系統（2 個 L3）
│   ├── L2-construction/        施工系統（1 個 L3）
│   ├── L2-network/             網路封包（1 個 L3）
│   └── L2-sidecar-export/      NURBS 匯出（1 個 L3）
├── L1-sidecar/                 MctoNurbs Sidecar（4 個 L2）
│   ├── L2-rpc/                 RPC 伺服器（1 個 L3）
│   ├── L2-pipeline/            轉換管線（2 個 L3）
│   ├── L2-sdf/                 SDF 系統（2 個 L3）
│   └── L2-cad/                 CAD 核心（2 個 L3）
└── archive/                    歷史文檔歸檔
```
