# L1-api — Block Reality API 模組

> Block Reality 的基礎層，作為獨立 Forge Mod 運行。

## 概述

`api` 模組是 Block Reality 的核心基礎層，提供結構物理模擬、材料系統、藍圖管理、崩塌邏輯及客戶端渲染管線。`fastdesign` 模組依賴本模組，但本模組可獨立運行。

## 套件結構

| 套件 | 說明 | 文件 |
|------|------|------|
| `physics/` | 力學引擎：力平衡求解、梁應力、連通性分析、纜索、載重路徑 | [L2-physics](L2-physics/index.md) |
| `material/` | 材料系統：預設材料、自訂材料、動態 RC 融合材料 | [L2-material](L2-material/index.md) |
| `blueprint/` | 藍圖序列化：NBT 讀寫、檔案 I/O、Litematica 匯入 | [L2-blueprint](L2-blueprint/index.md) |
| `collapse/` | 崩塌觸發：應力分析驅動的結構破壞 | [L2-collapse](L2-collapse/index.md) |
| `chisel/` | 雕刻系統：10x10x10 子體素形狀與截面屬性 | [L2-chisel](L2-chisel/index.md) |
| `sph/` | SPH 粒子系統：爆炸壓力波與應力視覺化 | [L2-sph](L2-sph/index.md) |
| `sidecar/` | Sidecar 橋接：Java ↔ TypeScript stdio RPC 通訊 | [L2-sidecar](L2-sidecar/index.md) |
| `client/render/` | 渲染管線：管線架構、貪婪合併、動畫、後處理、Vulkan RT | [L2-render](L2-render/index.md) |
| `spi/` | 服務提供者介面：模組擴展點、纜索/養護/材料 SPI | [L2-spi](L2-spi/index.md) |
| `node/` | 節點圖：DAG 視覺化程式設計系統 | [L2-node](L2-node/index.md) |

## 依賴資訊

- **Java**: 17
- **Forge**: 1.20.1 (47.2.0)，使用 Official Mappings
- **Gradle**: 8.8，daemon 停用，heap 3GB
- **方向**: `fastdesign` → `api`（單向依賴，不可反向）

## 建置指令

```bash
cd "Block Reality"
./gradlew :api:jar          # 編譯 API 模組
./gradlew :api:test          # 執行 JUnit 5 測試
./gradlew :api:runClient     # 啟動 API 客戶端
```

## 核心入口

- `BlockRealityMod` — Forge Mod 主類別（MOD_ID 註冊）
- `BRConfig` — 全域設定（BFS 限制、RC 融合係數等）
- `PhysicsConstants` — 物理常數集中管理（重力、方塊面積等）
