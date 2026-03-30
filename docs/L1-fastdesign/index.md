# Fast Design 模組總覽

> 所屬：L1-fastdesign

## 概述

Fast Design 是 Block Reality 的擴充層模組，提供 3D 全息預覽、CAD 介面、節點式編輯器、CLI 指令系統及 NURBS/STEP 匯出管線。模組 ID 為 `fastdesign`，依賴 `:api` 模組但不可被反向依賴。

## 模組入口

**`FastDesignMod`** (`com.blockreality.fastdesign.FastDesignMod`)
- 註冊 `FdItems`、`FdCreativeTab` 至 Deferred Register
- 載入 `FastDesignConfig` (COMMON)
- 在 `commonSetup` 中呼叫 `FdNetwork.register()`
- 在 `RegisterCommandsEvent` 中註冊 `/fd`、`/br_blueprint`、`/br_zone` 指令
- 玩家斷線時透過 `UndoManager.onPlayerDisconnect()` 釋放記憶體

## 套件結構

| 套件 | 說明 | 文件連結 |
|------|------|----------|
| `client/` | 畫面、全息渲染、HUD 疊層 | [L2-client-ui](L2-client-ui/index.md) |
| `client/node/` | Grasshopper 風格節點編輯器 | [L2-node-editor](L2-node-editor/index.md) |
| `command/` | `/fd` 指令與復原系統 | [L2-command](L2-command/index.md) |
| `construction/` | 施工工序事件攔截 | [L2-construction](L2-construction/index.md) |
| `network/` | Fast Design 專屬封包頻道 | [L2-network](L2-network/index.md) |
| `sidecar/` | NURBS/STEP 匯出橋接 | [L2-sidecar-export](L2-sidecar-export/index.md) |
| `config/` | `FastDesignConfig` — ForgeConfigSpec |  |
| `item/` | `FdWandItem` — 選取/放置工具 |  |
| `registry/` | `FdItems`、`FdCreativeTab` |  |

## 依賴方向

```
fastdesign ──→ api (Block Reality API)
fastdesign ──→ MctoNurbs-review (TypeScript sidecar，透過 SidecarBridge stdio RPC)
```

API 層提供 Blueprint、SidecarBridge、ConstructionZone、PlayerSelection 等基礎設施，Fast Design 在此之上實作互動介面與進階功能。
