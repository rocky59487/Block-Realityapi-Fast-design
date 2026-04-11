---
id: learning-path
type: "guide"
tags: [guide, learning, overview]
---

# 🎓 Block Reality 學習路徑

> [!info] 給新加入的開發者與 Agent 的閱讀建議
> 這是一條由淺入深的學習路徑，建議按照順序閱讀，不必一次讀完。

## Phase 1: 架構與邊界（第 1 天）

1. [[Architecture MOC]] — 了解模組邊界
2. [[模組邊界與依賴方向]] — `fastdesign → api` 的單向依賴
3. [[客戶端-伺服器端分離（極重要）]] — 這是 crash 的首要原因
4. [[sigmaMax 正規化約定（PFSF 核心）]] — 最容易被忽略的數值約定

## Phase 2: 核心物理引擎（第 2–3 天）

1. [[PFSF 引擎總覽]] — 知道 PFSF 在做什麼
2. [[PFSFDataBuilder 快速參考]] — 資料上傳的核心類別
3. [[PFSF 物理計算完整資料流]] — 建立全局地圖
4. [[CollapseManager 快速參考]] — 崩塌的生命周期
5. [[RCFusionDetector 快速參考]] — RC 融合的設計哲學

## Phase 3: 渲染管線（第 4 天）

1. [[渲染系統總覽]] — Vulkan RT 的大局觀
2. [[BRVulkanRT 快速參考]] — 渲染主控類別
3. [[Vulkan RT 渲染管線資料流]] — 從 Chunk 到畫面的鏈路

## Phase 4: 節點與 FastDesign（第 5 天）

1. [[節點核心類別]] — BRNode / NodeGraph / EvaluateScheduler
2. [[NodeRegistry 快速參考]] — 註冊表的職責與陷阱
3. [[如何新增一個 FastDesign 節點]] — 標準開發模板
4. [[如何安全地撰寫跨端網路封包]] — 避免 NoClassDefFoundError

## Phase 5: ML 與工具鏈（第 6 天）

1. [[OnnxPFSFRuntime 快速參考]] — 遊戲內 ML 推論入口
2. [[ML 訓練到部署資料流]] — 從 Python 到 Java 的鏈路
3. [[fix_imports.py — 修復 network 封包中的 client-only import]]
4. [[常用 Gradle 任務速查]]

## Phase 6: 錯誤排查與測試（持續參考）

- [[NoClassDefFoundError-client-only 類別在伺服器端載入]]
- [[PFSF GPU 求解發散或收斂極慢]]
- [[Test Coverage MOC]] — 修改後該跑什麼測試

## 🗺️ 視覺化導航

如果你偏好圖形化閱讀，請打開 [[Block Reality 總覽.canvas]]，這是一張精心挑選的知識地圖。

---

返回 [[Home]] | 閱讀 [[Agent Guide]]