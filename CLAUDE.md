# CLAUDE.md

Claude Code（claude.ai/code）在此倉庫中的開發指引。

## 專案概覽

Block Reality — Minecraft Forge 1.20.1 結構物理模擬引擎。兩個 Gradle 子專案（`api`、`fastdesign`）。使用者主要語言為繁體中文。

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


## 架構

```
api/  (com.blockreality.api)           ← 基礎層，獨立模組
  physics/pfsf/  PFSFEngine — GPU 勢場標量求解器（見下方「PFSF 求解器架構」）
                 PFSFScheduler (殘差驅動自適應 + 頻譜半徑估算 + 發散偵測)
                 IslandFeatureExtractor (ML 特徵提取，12 維向量)
  physics/       UnionFind 連通性、LoadType (ASCE 7-22)、
                 FailureType (9 種: 懸臂/壓碎/無支撐/拉斷/靜水壓/熱應力/熱剝落/風傾覆/扭斷/疲勞)
  material/      BlockTypeRegistry、DefaultMaterial（10+ 種材料）、CustomMaterial.Builder、DynamicMaterial (RC 融合 97/3)
  blueprint/     Blueprint ↔ NBT 序列化、BlueprintIO 檔案 I/O、LitematicImporter
  collapse/      CollapseManager — 物理失效時觸發崩塌；CollapseJournal — 因果鏈追蹤與可逆回滾
  chisel/        10×10×10 體素子方塊造型系統
  sph/           SPH 應力引擎（Monaghan 1992 立方樣條核心 + Teschner 空間雜湊鄰域搜索）
  physics/fluid/ PFSF-Fluid 流體模擬引擎（勢場擴散 + GPU Jacobi + 結構耦合）
  client/render/ GreedyMesher、AnimationEngine、RenderPipeline、Vulkan RT、後製特效
  node/          BRNode 節點圖系統、EvaluateScheduler 拓撲排序
  spi/           ModuleRegistry 中心、SPI 擴展接口

fastdesign/  (com.blockreality.fastdesign)  ← 擴充層，依賴 :api
  client/        3D 全息投影預覽、HUD 覆蓋、GUI 畫面、鑿刻工具
  client/node/   節點編輯器（90+ 節點實作：材料/物理/渲染/工具/輸出）
  command/       /fd 命令系統、撤銷管理
  construction/  施工事件處理
  network/       封包同步
```

**依賴方向**：`fastdesign` → `api`（絕不反向）。

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
| `IFluidManager` | 流體模擬管理（init/tick/query） | `FluidGPUEngine` |
| `IBinder<T>` | 節點圖埠值↔運行時物件綁定 | `MaterialBinder`、`PhysicsBinder`、`RenderConfigBinder`、`FluidBinder` |

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
- **輸出節點** (`impl/output/`) — 監控

### Binder 對接
```java
// 實作 IBinder<T> 連接節點到運行時系統
IBinder<MutableRenderConfig> binder = new RenderConfigBinder();
binder.bind(nodeGraph);      // 掃描節點建立映射
binder.apply(renderConfig);  // 推送節點值到運行時
binder.pull(renderConfig);   // 從運行時拉取值到節點
```


## PFSF 求解器架構

核心求解管線（每個 island 每 tick）：

```
PFSFDataBuilder    → 計算 source/conductivity/type，上傳 GPU
                     ★ sigma 正規化：除以 sigmaMax（rcomp/rtens 同步）
PFSFDispatcher     → 自適應 RBGS→PCG 切換
  ├─ Phase 1: RBGS 8-color smoother（高頻消除）
  │   ├─ 26 連通 Laplacian（6 面 + 12 邊×0.35 + 8 角×0.15）
  │   ├─ Chebyshev 半迭代加速（WARMUP=2 步後啟用）
  │   └─ 每 MG_INTERVAL 步插入 V-Cycle（restrict→coarse solve→prolong）
  ├─ Phase 2: PCG Jacobi-preconditioned（低頻收斂）
  │   ├─ matvec: 26 連通（與 RBGS 相同算子 — CG 收斂要求）
  │   ├─ 預條件: z = r / diag(A₂₆)（即時計算，無額外 buffer）
  │   └─ 內積: r·z（非 r·r）
  └─ 停滯偵測: 殘差下降率 < 5% → 早切 PCG
PFSFFailureRecorder → failure_scan shader（壓碎/拉斷/懸臂偵測）
PFSFPhaseFieldRecorder → Ambati 2015 損傷演化
  └─ hField 由 smoother 獨佔寫入，phase_field_evolve 唯讀
```

### 關鍵 Shader（`assets/blockreality/shaders/compute/pfsf/`）

| Shader | 連通性 | 說明 |
|--------|--------|------|
| `rbgs_smooth.comp.glsl` | 26 | 主求解器，8-color in-place |
| `jacobi_smooth.comp.glsl` | 26 | 粗網格求解（shared memory tiled） |
| `pcg_matvec.comp.glsl` | 26 | PCG 矩陣-向量乘積 |
| `pcg_update.comp.glsl` | 26 | PCG 更新 + Jacobi 預條件 z=M⁻¹r |
| `pcg_direction.comp.glsl` | 26 | PCG 方向更新 p=z+βp |
| `mg_restrict.comp.glsl` | 6 | 多網格 restriction（導率加權） |
| `mg_prolong.comp.glsl` | — | 多網格 prolongation（三線性插值） |
| `failure_scan.comp.glsl` | 6 | 失效偵測 + macro-block 殘差 |
| `phase_field_evolve.comp.glsl` | 6 | Ambati 2015 損傷場演化 |

### 正規化約定（★ 極重要）

`PFSFDataBuilder` 上傳前會除以 `sigmaMax`（最大導率值）：
- `conductivity[i] /= sigmaMax` → 值域 [0, 1]
- `source[i] /= sigmaMax` → 等比例縮放
- `maxPhi[i] /= sigmaMax` → 懸臂閾值同步
- `rcomp[i] /= sigmaMax` → 壓碎閾值同步
- `rtens[i] /= sigmaMax` → 拉斷閾值同步

**phi 場不變**（A×phi=source 兩邊同除 sigmaMax 自動抵消）。
failure_scan shader 直接比較 `flux > rcomp[i]`，無需額外換算。

### 26 連通一致性要求

RBGS、Jacobi、PCG matvec **必須**使用相同的 26 連通 stencil，
包括相同的 `SHEAR_EDGE_PENALTY=0.35` 和 `SHEAR_CORNER_PENALTY=0.15`。
若任一 shader 的 stencil 不同 → CG 收斂到錯誤解 / 多網格發散。

## 常見陷阱

1. **物理單位混用** — 所有強度值必須用 MPa，楊氏模量用 GPa，密度用 kg/m³。不要混用 Pa 或 N/mm²
2. **Forge 事件優先級** — `@SubscribeEvent` 的 `priority` 參數影響執行順序，物理事件通常需要 `EventPriority.HIGH`
3. **Access Transformer** — 修改 AT 後需要 `./gradlew :api:jar` 重新建置才生效
4. **Gradle daemon** — 本專案停用 daemon，建置速度較慢但更穩定
5. **RC 融合比例** — 固定為 97% 混凝土 / 3% 鋼筋，不可調整
6. **節點圖序列化** — `NodeGraphIO` 處理序列化，Port 類型必須正確匹配否則連線靜默失敗
7. **客戶端/伺服器端分離** — `client/` 下的類別使用 `@OnlyIn(Dist.CLIENT)` 或相當邏輯，在伺服器載入會 crash
8. **流體系統預設關閉** — `BRConfig.isFluidEnabled()` 預設為 false，需明確啟用。流體與結構耦合有 1 tick 延遲（設計如此）
9. **PFSF 正規化** — `PFSFDataBuilder` 會除以 sigmaMax。新增 buffer（如 rcomp/rtens）**必須**同步正規化，否則 failure_scan 閾值量級不對
10. **PFSF 26 連通一致性** — 修改任何 smoother/PCG 的 stencil 時，所有相關 shader 都必須同步更新。不一致 → CG 收斂到錯誤解
11. **hField 寫入權** — `hField`（歷史應變能場）僅由 Jacobi/RBGS smoother 寫入（`max(old, psi_e)`）。`phase_field_evolve` 唯讀，避免 GPU race condition

## 文檔索引

結構化 API 參考文檔位於 `docs/`，採四層分層架構：

- [docs/index.md](docs/index.md) — 總索引入口
- [docs/L1-api/](docs/L1-api/index.md) — Block Reality API 基礎層
- [docs/L1-fastdesign/](docs/L1-fastdesign/index.md) — Fast Design 擴充層


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
│   ├── L2-render/              渲染管線（5 個 L3）
│   ├── L2-spi/                 SPI 擴展（3 個 L3）
│   └── L2-node/                節點圖核心（2 個 L3）
├── L1-fastdesign/              Fast Design 擴充層（6 個 L2）
│   ├── L2-client-ui/           客戶端 UI（3 個 L3）
│   ├── L2-node-editor/         節點編輯器（7 個 L3）
│   ├── L2-command/             指令系統（2 個 L3）
│   ├── L2-construction/        施工系統（1 個 L3）
│   ├── L2-network/             網路封包（1 個 L3）
└── archive/                    歷史文檔歸檔
```

## 代碼審查規範

每當使用者要求審查（「審查」、「check」、「review」、「檢查」），
在開始之前主動提出針對**當下任務**的確認問題，格式如下：

```
在開始之前確認：
1. 發現問題時 → 直接修改 + commit，還是只產報告？
2. [任務特有的技術疑問，例如：hardcoded 值要動態查詢還是用安全預設？]
3. 性能/效果驗證 → 沙箱無 GPU，從代碼邏輯確認即可？
4. 建置失敗範圍 → 只管本次審查範圍，無關錯誤跳過？
5. 分支 → 確認目標分支名稱？
```

**注意**：問題 2 要根據當下任務內容替換，其餘 4 點幾乎固定。
已知本專案的預設答案（除非使用者另外說明）：
- 問題 1：直接修改 + commit
- 問題 3：代碼邏輯確認即可
- 問題 4：跳過無關錯誤
- 問題 5：目前主要審查分支為 `audit-fixes`
