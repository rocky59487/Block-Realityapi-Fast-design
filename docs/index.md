# Block Reality 文檔索引

> 結構物理模擬引擎 — Minecraft Forge 1.20.1

本文檔採用四層分層架構，從模組總覽到具體 API 接口逐層深入。

## 導航結構

```
L1 模組總覽 → L2 功能大類 → L3 接口文檔（含 L4 詳細方法與關聯性）
```

---

## 第一層：模組總覽

### [L1-api — Block Reality API（基礎層）](L1-api/index.md)

核心物理引擎、材料系統、藍圖序列化、崩塌模擬、渲染管線。獨立運作的基礎模組。

**功能大類**：[物理引擎](L1-api/L2-physics/index.md) · [材料系統](L1-api/L2-material/index.md) · [藍圖系統](L1-api/L2-blueprint/index.md) · [崩塌模擬](L1-api/L2-collapse/index.md) · [鑿刻系統](L1-api/L2-chisel/index.md) · [SPH 粒子](L1-api/L2-sph/index.md) · [Sidecar 橋接](L1-api/L2-sidecar/index.md) · [渲染管線](L1-api/L2-render/index.md) · [SPI 擴展](L1-api/L2-spi/index.md) · [節點圖核心](L1-api/L2-node/index.md)

---

### [L1-fastdesign — Fast Design（擴充層）](L1-fastdesign/index.md)

CAD 風格設計工具、節點視覺編輯器、全息投影預覽、施工指令系統。依賴 `:api` 模組。

**功能大類**：[客戶端 UI](L1-fastdesign/L2-client-ui/index.md) · [節點編輯器](L1-fastdesign/L2-node-editor/index.md) · [指令系統](L1-fastdesign/L2-command/index.md) · [施工系統](L1-fastdesign/L2-construction/index.md) · [網路封包](L1-fastdesign/L2-network/index.md) · [NURBS 匯出](L1-fastdesign/L2-sidecar-export/index.md)

---

### [L1-sidecar — MctoNurbs Sidecar（TypeScript）](L1-sidecar/index.md)

Node.js RPC 伺服器，處理體素→NURBS 曲面→STEP CAD 檔案的轉換管線。使用 opencascade.js。

**功能大類**：[RPC 伺服器](L1-sidecar/L2-rpc/index.md) · [轉換管線](L1-sidecar/L2-pipeline/index.md) · [SDF 系統](L1-sidecar/L2-sdf/index.md) · [CAD 核心](L1-sidecar/L2-cad/index.md)

---

## 依賴關係

```
fastdesign ──依賴──→ api ──IPC──→ MctoNurbs sidecar
   │                  │
   │                  ├── physics/     結構分析
   │                  ├── material/    材料屬性
   │                  ├── blueprint/   藍圖 I/O
   │                  ├── spi/         擴展接口
   │                  └── node/        節點圖核心
   │
   ├── client/node/   視覺節點編輯器
   ├── command/        /fd 指令
   └── sidecar/        NURBS 匯出橋接
```

## 快速查找

| 想要... | 前往 |
|---------|------|
| 相場斷裂 / Phase-Field Fracture | [L3-pfsf](L1-api/L2-physics/L3-pfsf.md) |
| 了解力平衡求解器 | [L3-force-solver](L1-api/L2-physics/L3-force-solver.md) |
| 新增自訂材料 | [L3-custom-material](L1-api/L2-material/L3-custom-material.md) |
| 理解 RC 融合機制 | [L3-dynamic-material](L1-api/L2-material/L3-dynamic-material.md) |
| 使用藍圖 API | [L3-blueprint-core](L1-api/L2-blueprint/L3-blueprint-core.md) |
| 擴展 SPI 接口 | [L2-spi](L1-api/L2-spi/index.md) |
| 開發新節點 | [L2-node-editor](L1-fastdesign/L2-node-editor/index.md) |
| 了解 Binder 系統 | [L3-binding](L1-fastdesign/L2-node-editor/L3-binding.md) |
| 理解 STEP 匯出流程 | [L3-pipeline-core](L1-sidecar/L2-pipeline/L3-pipeline-core.md) |
| JSON-RPC 協議細節 | [L3-rpc-server](L1-sidecar/L2-rpc/L3-rpc-server.md) |

## 歷史文檔

原有審核報告、開發手冊等歸檔於 [archive/](archive/) 目錄。
