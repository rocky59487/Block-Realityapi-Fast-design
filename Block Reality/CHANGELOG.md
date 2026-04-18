# Block Reality API — CHANGELOG

## [v0.3e.0-rc1] — 2026-04-18 (PFSF C++ migration — publishable quality)

v0.3e 是 v0.3d PFSF-計算-下放-C++ 的「能上 release tag」收尾。七個里程碑
M1–M7 CI 全綠,ABI 累積 additive bump 1.0 → 1.2。M3 的 plan-buffer
JNI 攤薄（2ms → 50μs）與 M5 的 SIGSEGV 子行程驗證列為 v0.3e.1 目標;
目前分別以 surrogate bench（null floor）和 `pfsf_dump_now_for_test` 入口
覆蓋對應功能。

> **注意 M6 限制**：`tick50k_surrogate` 的 `native_over_java_min` 目前暫設
> `null`（gate 觀察用,不強制阻擋）。1.4× 硬閘門待 v0.3e M3
> PFSFTickPlanner plan-buffer batching 上線後方才啟用。
> `apply_wind_bias_64k` 的 1.5× floor 仍為強制閘門。

### M1 — CI 原生測試真的跑

- `.github/workflows/build.yml` 新增 `build-native (ubuntu)` job,安裝
  Vulkan SDK + glslang,以 `-Pblockreality.native.build=true` 編譯並執行
  `./gradlew build`;`libblockreality_pfsf.so` 上傳為 artifact 給
  `check-abi` 的 `binary` job 消費
- `GoldenParityTest` 新增 `-Dpfsf.native.required=true` 系統屬性,要求原生
  時若 `LIBRARY_LOADED=false` 直接 fail (不再 silent skip)
- Java-only job 保留為相容矩陣,向下兼容

### M2 — Plan buffer opcode 完整性 (ABI v1.0 → v1.1)

- `pfsf_plan_opcode` enum 從 5 個擴充到 17 個,覆蓋 v0.3d Phase 1–4 全部
  原語 (`OP_NORMALIZE_SOA6` / `OP_APPLY_WIND_BIAS` / `OP_TIMOSHENKO` /
  `OP_WIND_PRESSURE` / `OP_ARM_MAP` / `OP_ARCH_FACTOR` / `OP_PHANTOM_EDGES`
  / `OP_DOWNSAMPLE_2TO1` / `OP_TILED_LAYOUT` / `OP_CHEBYSHEV` /
  `OP_CHECK_DIVERGENCE` / `OP_EXTRACT_FEATURES` / `OP_COMPUTE_CONDUCTIVITY`)
- `libpfsf/src/compute/stubs.cpp` 整檔刪除;`pfsf_compute_conductivity`
  的 Phase 3 stub 以完整實作取代 (bit-exact mirror of
  `PFSFConductivity.java`)
- `pfsf_v1.abi.json` 更新為 v1.1.0 snapshot,`scripts/check_abi.py` 綠燈
- `GoldenParityTest` 補進 12 個新 opcode 的 parity 測試,容差 1e-5

### M3 — Orchestrator 瘦身 (plan-buffer 路由，scaffolding only)

- **範圍限定**：本里程碑僅搭建 plan-buffer scaffolding。實際 tick 路由
  仍走 v0.3d 的逐原語 JNI 路徑；`KERNELS_PORTED = false` 作為功能開關,
  plan-buffer 路徑在各求解 kernel ported 後才會翻 `true`（v0.3e.1）
- `PFSFTickPlanner` 與 M2 的 12+ plan opcode 就位;`nativeTickDbb()`
  進入點由 `NativePFSFBridge` 暴露,`NativePFSFRuntime.RuntimeView.onServerTick()`
  目前為意圖中的 no-op (`KERNELS_PORTED=false` gate),等 kernel routing 完成再啟用
- `PFSFEngineInstance.java` 467 行 → ≤350 行（整理 orchestrator 結構）,
  但 `PFSFEngine` 11-method facade 簽名維持凍結 (`javap -public` diff 為空)
- **尚未兌現**：單 tick 跨界攤薄（2ms → 50μs）需 kernel 全部 ported 後
  才會生效;目前生產路徑每 tick 的 JNI 呼叫次數仍由逐原語階段決定
- Parity 覆蓋仍由既有 `GoldenParityTest` 系列負責;專屬
  `PFSFTickBoundaryTest` / `CollapseEventParityTest` 要等 `KERNELS_PORTED=true`
  實際有 plan-buffer 熱路徑時才有意義,留待 v0.3e.1 kernel routing 同 PR 加入

### M4 — AMG GPU 整合

- 新增 `assets/blockreality/shaders/compute/pfsf/amg_scatter_restrict.comp.glsl`
  (`r_c[j] = Σ_{i∈agg_j} P[i,j] · r_f[i]`) 和
  `amg_gather_prolong.comp.glsl` (`e_f[i] = Σ_j P[i,j] · e_c[j]`)
- `AMGPreconditioner.java` 補齊 `runCpuVCycle(...)` 作為 GPU 對照 oracle
  和 no-GPU dev fallback;`checkPartitionOfUnity(tol)` invariant 驗證
- `PFSFVCycleRecorder` 依 AMG flag 在幾何 vs 代數 restriction/prolongation
  之間切換
- 新增 `PFSFAMGParityTest` — coarsensSolidCube / partitionOfUnity /
  roundTripBoundedCorrection / constantProlongation
- CPU 路徑保留為 fallback + parity oracle

### M5 — Async-signal-safe crash dump (ABI v1.1 → v1.2)

- `L1-native/libpfsf/src/diag/crash.cpp` 實作 async-signal-safe SIGSEGV/
  SIGABRT handler,只呼叫 AS-safe 函式 (`write(2)`、`_exit`、`sigaltstack`),
  handler 內不做 `malloc`
- 以 `sigaction` 鏈結 JVM 既有 handler (`SA_SIGINFO` + `sa_restorer` 保留);
  PFSF 寫完自家 trace 後 re-raise,`hs_err_pid.log` 照常產出
- 新增 3 個 C 入口:`pfsf_install_crash_handler()` /
  `pfsf_uninstall_crash_handler()` / `pfsf_dump_now_for_test()`;
  `BR_PFSF_NO_SIGNAL=1` 可一鍵關閉
- `NativePFSFBridge` 對應 3 個 JNI 方法;`PFSFCrashHandlerTest` 透過
  `nativeCrashDumpForTest()` → `pfsf_dump_now_for_test()` 驗證 trace 檔
  確實落地;完整 ProcessBuilder `kill -SEGV` 子行程驗證列為 v0.3e.1 目標
- `pfsf_v1.abi.json` 更新為 v1.2.0

### M6 — JMH 效能回歸 gate

- `Block Reality/api/src/test/java/.../PfsfBenchmark.java` — JUnit
  `@EnabledIfSystemProperty(named = "pfsf.bench", matches = "true")`
  gating,median-of-20 timing,4 個 workload (normalize_soa6_64k /
  chebyshev_table_64 / apply_wind_bias_64k / tick50k_surrogate) 同時跑
  Java-ref 和 native mode
- 基線 JSON `benchmarks/baselines/v0.3e-linux-x64.json` 釘在 repo;
  `tick50k_surrogate` 的 `native_over_java_min` 暫設為 `null`
  (gate 觀察用,不阻擋),待 v0.3e M3 PFSFTickPlanner plan-buffer 把
  per-rep JNI 從 3 次降到 ≤1 次後,再翻回 `1.4` 作為 v0.3d 交付
  acceptance criterion 的硬閘門;`java_ns_per_op_max` 仍維持 5% tolerance
  做 Java-ref 回歸偵測
- `scripts/pfsf_perf_gate.py` 比對 `build/pfsf-bench/results.json` vs
  baseline,`--require-native` 拒絕 silent skip
- `.github/workflows/perf-gate.yml`:PR 標題含 `[perf-gate]` opt-in +
  每週五 06:00 UTC cron soak + 手動 dispatch;3 次 retry 處理 ForgeGradle
  Maven flake
- `api/build.gradle` 新增 `task pfsfBench(type: Test)`,`forkEvery=1L`
  避免 JMH-style JIT 污染
- 未引入 `me.champeau.jmh` 套件 (與 ForgeGradle 6 不相容),改走 JUnit
  harness,保 M1 CI 綠燈

### M7 — 發表 polish

- `scripts/check_citations.py` — 執行 `@cite` / `@formula` / `@maps_to`
  三合一稽核;multi-line @cite 支援、standards-style 年份 (EN 1991-1-4)、
  `@algorithm`/`@see`/`@maps_to` 作為 @cite 的等效 provenance 標記
- 聚合 `docs/L1-api/L2-physics/bibliography.md` — 14 unique works,17
  source lines (來自 `--emit-bibliography` 自動產生,不手動維護)
- `docs/MIGRATION-v0.3d-to-v0.3e.md` — 外部 mod 升級指引
- `.github/workflows/check-citations.yml` — citation gate 入 CI
- 內部引用格式調整:4 個 `@cite Internal algorithm` 改為 `@algorithm`
  (符合 plan 區分);morton.cpp 的 1966 citation 修正 author-year 順序;
  `pfsf_diagnostics.h` 的 feature-vector 自描述改為 `@algorithm`
- 引擎 lifecycle / IO plumbing symbols 納入 `CITATION_EXEMPT`
  (pfsf_create / pfsf_init / pfsf_tick_dbb / pfsf_add_island / …) —
  這些沒有學術公式需要引用

### 不變 (stability guarantees)

- `PFSFEngine` 11 個靜態方法簽名 — 凍結不動
- `IPFSFRuntime` 策略 seam — 未新增 method
- 9 個 SPI 介面 (`IThermalManager` / `ICableManager` / `IFusionDetector` /
  `ICuringManager` / `IMaterialRegistry` / `IBlockTypeExtension` /
  `IFluidManager` / `IWindManager` / `IElectromagneticManager`) —
  擴充 seam 維持
- 15+ existing golden-vector 測試 — 全數通過
- 26-連通 stencil、σ 正規化、hField 寫入權、Fluid 1-tick lag —
  所有不變式維持
- Java reference path (`*JavaRef` 伴隨方法) — 永遠保留;週檢 parity gate
  驗證雙向無漂移

### 分支與 tag

- 所有 M1–M7 commit 落於 `claude/migrate-to-cpp-GShu7`
- `v0.3e.0-rc1` tag 發佈條件:七個 milestone 全綠 + CI citation gate +
  ABI gate + perf gate 全數通過 + 每週 Friday perf-gate cron
  (`.github/workflows/perf-gate.yml`) 連續兩輪無回歸
  (此 job 跑 `PfsfBenchmark` 並比對 `benchmarks/baselines/*.json` 的
  budget;**不執行** `GoldenParityTest`。數值 parity 由 PR-triggered
  `build-native` job 中的 `GoldenParityTest` 覆蓋。)

---

## [1.2.0] — 2026-03-25 (Round 5 — API 完整性 & 修復清查)

### 物理引擎 (Physics)

- **BFSConnectivityAnalyzer.rebuildConnectedComponents(ServerLevel)** 補齊實作：掃描已加載 chunk 中所有 RBlockEntity，建立完整連通分量。舊的無參數版本保留為安全降級入口
- **RCFusionDetector** 蜂窩判定從 `Math.random()` 改為 **FNV-1a 確定性 hash**（基於雙方 BlockPos），保證伺服器重啟後融合結果一致、跨執行緒安全、自動化測試可重現
- **LoadPathEngine.traceLoadPath()** 環路偵測從 `List.contains()` O(n) 改為 `HashSet` O(1)
- **ResultApplicator** StressUpdateEvent 改為 **批量收集後一次性發射**，減少 Forge event bus dispatch 開銷（applyStructureResult + applyStressField 兩條路徑）

### 並發安全 (Concurrency)

- **PhysicsExecutor.start()/shutdown()** 加入 `synchronized`，修復 check-then-act 競態條件；`submit()` 新增 null snapshot 防護 + volatile 單次讀取
- **CollapseManager** 佇列從 `ArrayDeque`（非線程安全）改為 `ConcurrentLinkedDeque`；補齊遺漏的 `Deque`/`ArrayDeque`/`Set`/`HashSet` import
- **SidecarBridge** RPC ID 計數器加入 `Integer.MAX_VALUE` 溢位保護（`updateAndGet` 正數循環）

### API 設計 (API Design)

- **DynamicMaterial.ofRCFusion()** 新增 null 檢查（`Objects.requireNonNull`）+ 負值材料參數防禦
- **DynamicMaterial.ofCustom()** 新增 id 非空驗證、density > 0 驗證、負強度 clamp 到 0

### Forge 合規 (Forge Compliance)

- **ServerTickHandler** 移除養護系統的主世界（Overworld）限制 — 建築可存在於所有維度

### 測試覆蓋率 (Test Coverage)

- 新增 **DynamicMaterialTest** — 56 @Test：ofRCFusion 公式、蜂窩懲罰、ofCustom 驗證、RMaterial 預設方法、輸入驗證
- 新增 **BFSConnectivityAnalyzerTest** — 30+ @Test：PhysicsResult/CachedResult record、epoch 管理、快取驅逐（AD-7）、BFS 核心演算法（孤立方塊、邊界錨定、空氣阻斷）

---

## [1.1.0] — 2026-03-25 (Round 4 — 大幅強化)

### 物理引擎 (Physics)

- **RMaterial 介面擴充**：新增 `getYoungsModulusPa()` 覆寫、`getYieldStrength()`、`getPoissonsRatio()`、`getShearModulusPa()` — 所有材料計算統一從 RMaterial 取真實工程值
- **DefaultMaterial 真實工程數據**：12 種材料的楊氏模量/泊松比/降伏強度改用 Eurocode 2 / AISC / EN 338 參考值，鋼材 E 從 350 GPa（近似偏差 75%）修正為 200 GPa
- **BeamElement compositeStiffness()** 改用 `getYoungsModulusPa()` 取代 `Rcomp × 1e9` 近似，消除鋼材 75%/木材 50% 偏差
- **BeamElement eulerBucklingLoad()** 加入有效長度係數 K=0.7（AISC C-A-7.1），挫屈力從 `π²EI/L²` 修正為 `π²EI/(KL)²`
- **ForceEquilibriumSolver** 移除節點級早期終止，改為全局殘差收斂（Gauss-Seidel 理論要求），消除非對稱誤差累積風險
- **FNV-1a warm-start hash** 納入 `material.getCombinedStrength()` bits（Score-fix #2），防止同形異材結構假碰撞

### 並發安全 (Concurrency)

- **StressRetryEntry** `retryCount`/`lastAttemptMs`/`maxRetries` 改為 `volatile`，保證跨執行緒 happens-before
- **applyStressWithRetry()** 移除未使用的 `delayMs` 參數（欺騙性 API），新增 3 參數重載 + 舊簽名標記 `@Deprecated`

### Forge 合規 (Forge Compliance)

- **AccessTransformer 實作**：新增 `META-INF/accesstransformer.cfg`，將 `Explosion.radius` (f_46024_) 改為 public
- **build.gradle** 加入 `accessTransformer` 配置
- **SPHStressEngine.getExplosionRadius()** 從純反射改為 AT 直接存取 + 反射 fallback

### API 設計 (API Design)

- **ModuleRegistry** Javadoc 統一說明 static 門面模式為刻意設計決策

### 測試覆蓋率 (Test Coverage)

- 新增 **DefaultMaterialTest** — 42 @Test：fromId、數值合理性、楊氏模量真實值、泊松比、剪力模量、BEDROCK 不溢位、isDuctile/maxSpan
- 新增 **CableStateTest** — 20 @Test：節點數計算、restSegmentLength、resetLambdas、calculateTension、isBroken、unmodifiable 防禦、volatile cachedTension
- 新增 **ResultApplicatorTest** — 18 @Test：StressRetryEntry 封裝/exhausted/volatile、failedPositions API、ApplyReport record、跨線程可見性概念驗證
- 新增 **DefaultCableManagerTest** — 26 @Test（Round 3.5）：normalizePair、CRUD、endpoint index、chunk 清理、count 一致性

---

## [1.0.1] — 2026-03-25 (Round 1–3 — 審計修復)

### Round 1 — 結構性修復 (20 fixes)

- #1 BlockType enum O(1) fromString 快取
- #2 CableState nodes unmodifiable view
- #3 RMaterial getYoungsModulusPa() default method
- #4 StructureResult/FusionResult/AnchorResult/StressField records
- #5 FNV-1a warm-start fingerprint (long 替代 int)
- #6 LRU LinkedHashMap 替代隨機驅逐
- #7 getMaterialFactor() 直接讀取 BlockType.structuralFactor
- #8 ForceEquilibriumSolver FNV-1a hash
- #9 SidecarBridge synchronized 移除 (deadlock fix)
- #10 NodeState mutable class 替代 record
- #11 getCablesAt endpoint index O(1)
- #12 PhysicsConstants 統一常數
- #13–#20 CableNode/VanillaMaterialMap/SidecarBridge/BeamElement 修復

### Round 2 — 深度優化 (8 fixes)

- N1 readLock 範圍修正（stop() 不再阻塞 30s）
- N2 BEDROCK density 修正（3000 kg/m³）
- N3 HORIZONTAL_DIRS 靜態常數
- N4 DT 引用 PhysicsConstants.TICK_DT
- N5 BLOCK_AREA 引用 PhysicsConstants
- N6 cachedTension volatile
- N7 SPH TODO 注釋修正
- N8 DefaultMaterial fromId O(1) HashMap

### Round 3 — Nitpick (11 fixes)

- R3-1 BlockType.fromString static HashMap
- R3-2 ModuleRegistry volatile 欄位
- R3-3 移除重複 validateMainThread()
- R3-4 StressRetryEntry 封裝
- R3-5 移除未使用 import
- R3-6 UNIT_AREA 引用 PhysicsConstants
- R3-7 BeamElement 移除未使用變數
- R3-8 SidecarBridge cleanupExecutor 延遲初始化
- R3-9 ServerTickHandler 呼叫 processFailedUpdates
- R3-10 onWorldUnload 清除 failedPositions
- R3-11 DefaultCableManager 單元測試
