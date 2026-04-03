---
# v3fix_vision.md — Block Reality 企業憲法

## 專案核心身份
專案名稱：Block Reality
目標：Minecraft Forge 1.20.1 模組，實現結構物理模擬、CAD 設計工具與建設模擬。
技術棧：Java（Forge 1.20.1）+ TypeScript（Node.js Sidecar）
模組架構：
  - :api        — 核心物理引擎基礎層（獨立運作）
  - :fastdesign — CAD 設計工具擴充層（依賴 :api）
  - :sidecar    — MctoNurbs TypeScript 轉換管線（IPC 接 :api）

依賴關係（不得在任何文件中錯誤描述）：
  fastdesign ──依賴──→ api ──IPC──→ MctoNurbs sidecar

## CEO 長期願景
1. 打造一個讓玩家能在 Minecraft 中體驗真實結構物理的沉浸式世界。
2. 每一個方塊都有材料屬性、承重極限與破壞行為。
3. 提供專業 CAD 風格的建設工具，讓玩家設計可匯出為 STEP 格式的建築。
4. 以高科展（TISDC / 全國科展）標準的原創性與技術深度為品質基準。

## 任務分類矩陣

| 維度 \ 層級 | 基礎設施 | Framework | Mod Feature | Tooling |
|------------|----------|-----------|-------------|---------|
| 效能 PERF  | JVM/GC調優 | tick節流 | 物理計算優化 | profiler工具 |
| 穩定性 STBL | Gradle環境 | 依賴管理 | 崩塌算法修復 | CI健康 |
| UX         | Sidecar IPC | API接口 | 玩家流程 | 介面測試 |
| 安全 SEC   | 依賴CVE掃描 | 版本升級 | 輸入驗證 | 敏感資訊 |
| 研發 RND   | 新算法POC | 架構實驗 | 新物理效果 | 新工具評估 |
| 文件 DOCS  | README更新 | CLAUDE.md | Javadoc | 導航索引 |

## 每週主題對照
- 週一 PERF：JVM / TPS / draw call 優化
- 週二 CHAOS：壓力測試、邊界條件、防呆
- 週三 SEC：依賴掃描、Vulkan/Forge 升級
- 週四 UX：玩家流程、介面順暢度
- 週五 CLEAN：DRY 重構、死碼清除
- 週六 RND：前沿圖學 / 物理論文實作
- 週日 MAP：文件導航地圖更新

## 部門職責邊界

### 01_API_UX_Dept（API 與 UX）
負責：Block Reality API Java 接口、Fast Design TypeScript 邏輯、
      Sidecar IPC 穩定性、玩家 UI 流程、@OnlyIn CLIENT 邊界。
不負責：Vulkan 渲染管線、物理算法核心、Gradle 環境。

### 02_Mechanics_Dept（力學與算力）
負責：R-stress 計算、崩塌算法、JVM 調優、Gradle 環境、
      tick 效能、壓力測試設計、Construction Intern 力學。
不負責：玩家 UI、Node.js 邏輯、渲染管線。

### 03_Rendering_Dept（渲染）
負責：Vulkan/LWJGL 管線、Forge 渲染事件、Custom Render Layer、
      FPS 優化、Three.js 視覺效果、VRAM 管理、CVE 渲染依賴掃描。
不負責：物理算法、API 接口設計、Gradle 環境。

### 04_Coordinator_Hub（協調）
負責：跨部門請求路由、Git/CI 衝突處理、GLOBAL_PENDING 維護。
不負責：程式碼實作、任務設計。

### 05_Nightly_Integration（夜間統整）
負責：資料提煉、NIGHTLY_REPORT 產出、RAW_DATA_ARCHIVE 封存。
不負責：編譯、測試、任何程式碼執行。

## 硬性禁止事項（所有 Agent 通用）
1. Nightly Integration 不得執行編譯或測試。
2. 任何 Agent（大總監除外）不得提出問題，必須自行判斷並記錄 ASSUMPTION。
3. 不得刪除或覆寫 WORK_LOGS / R_AND_D_NOTES 的舊行，只能 append。
4. 不得改動其他部門的 INBOX_TASKS（只有 Director / Coordinator 可以）。
5. 開發部門不得編輯 NIGHTLY_REPORT。
6. 所有跨部門需求必須透過 OUTBOX_REQUESTS，不得直接改別部門檔案。
7. 所有 Agent 每次 sprint 至少要有一條 WORK_LOG 產出，不得空手結束。

## NIGHTLY_REPORT 格式規範（夜間統整必須嚴格遵守）
章節順序固定：
  1. 今日總覽
  2. 關鍵突破（最多 3 項）
  3. 重大風險與卡關（覆蓋所有 GLOBAL_PENDING）
  4. 研發突破簡表
  5. 明日/下週建議焦點
報告不得包含：stack trace、長 log、code diff。

## 任務配額規則
- 每日 80 任務中，≥ 60% 必須標記當日 WEEKDAY_THEME。
- 每部門每日上限約 27 個任務（80 ÷ 3 平均）。
- P0 任務每日每部門不超過 3 個，避免資源過度集中。

## Git 規範
- commit message 格式：[DEPT][TASK_ID] 簡短描述
  例：[MECH][2026-04-04-023] 修復 R-stress 邊界條件溢位
- 所有任務在 WORK_LOGS 中須記錄對應 GIT_REF（branch / commit hash）。
---
