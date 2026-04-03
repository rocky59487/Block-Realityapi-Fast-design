# Block Reality API + Fast Design

![Minecraft Forge 1.20.1](https://img.shields.io/badge/Minecraft%20Forge-1.20.1-orange)
![Java 17](https://img.shields.io/badge/Java-17-blue)
![License: MIT](https://img.shields.io/badge/License-MIT-green)
![Tests: 539](https://img.shields.io/badge/Unit%20Tests-539-brightgreen)

**A structural physics simulation engine for Minecraft Forge 1.20.1 — if it can't stand in the real world, it won't stand here.**

**Minecraft Forge 1.20.1 結構物理模擬引擎 — 現實中撐不住的，這裡也撐不住。**

---

## Table of Contents / 目錄

- [English](#english)
  - [Overview](#overview)
  - [Key Features](#key-features)
  - [Architecture](#architecture)
  - [Tech Stack](#tech-stack)
  - [Quick Start](#quick-start)
  - [Project Structure](#project-structure)
  - [Documentation](#documentation)
  - [Future Roadmap](#future-roadmap)
  - [Contributing](#contributing)
  - [License](#license)
- [中文版](#中文版)
  - [概述](#概述)
  - [功能特色](#功能特色)
  - [架構](#架構)
  - [技術棧](#技術棧)
  - [快速開始](#快速開始)
  - [專案結構](#專案結構)
  - [文件說明](#文件說明)
  - [未來展望](#未來展望)
  - [貢獻指南](#貢獻指南)

---

# English

## Overview

Block Reality is a Minecraft Forge mod that brings **real-world structural engineering** into the game. Every block has physical material properties — compressive strength, tensile strength, shear resistance, density, and Young's modulus — and a physics engine evaluates whether structures can actually support themselves.

The project consists of three tightly integrated components:

- **Block Reality API** — the foundation layer providing physics engines, material systems, blueprint serialization, and collapse simulation
- **Fast Design** — a CAD-style extension module adding 3D hologram previews, construction HUD overlays, rebar placement tools, and a full GUI editor
- **MctoNurbs Sidecar** — a TypeScript/Node.js process handling NURBS surface generation and STEP CAD export via opencascade.js

## Key Features

### Physics Engine
- **Union-Find connectivity analysis** for real-time structural integrity checks
- **Force equilibrium solver** using Successive Over-Relaxation (SOR) with adaptive omega
- **Euler buckling checks** and beam stress evaluation
- **Load path tracing** from structure to ground
- Structures that lose support **collapse dynamically** with SPH particle effects

### Material System
- **10+ default materials**: concrete, steel, timber, brick, glass, bedrock, and more
- All materials use **real engineering units** (MPa for strength, GPa for Young's modulus, kg/m3 for density)
- Custom materials via `CustomMaterial.Builder`
- **Dynamic materials** with RC fusion (97/3 concrete-rebar composite)

### Blueprint System
- Save, load, and share structural designs as NBT data
- Multi-block placement with rotation, mirroring, and offset support
- File I/O for blueprint import/export

### Fast Design (CAD Extension)
- **3D hologram preview** of structures before placement
- **Construction HUD overlay** with real-time structural feedback
- **Rebar placement system** for reinforced concrete design
- **NURBS/STEP export** pipeline to professional CAD formats
- Full GUI editor with intuitive controls (with interactive tooltips & empty-state feedback)

### Chisel System
- **10x10x10 voxel sub-block shapes** for detailed architectural modeling

### Collapse Simulation
- When physics determines a structure has failed, blocks break and fall
- **SPH (Smoothed Particle Hydrodynamics)** particle effects for realistic destruction visuals

## Architecture

```
Block Reality API (com.blockreality.api)           Foundation Layer
  ├── physics/          Force equilibrium solver, beam stress engine,
  │                     Union-Find connectivity, load path tracing
  ├── material/         BlockTypeRegistry, DefaultMaterial (10+ types),
  │                     CustomMaterial.Builder, DynamicMaterial (RC fusion)
  ├── blueprint/        Blueprint <-> NBT serialization, BlueprintIO
  ├── collapse/         CollapseManager — triggers destruction on physics failure
  ├── chisel/           10x10x10 voxel sub-block shape system
  ├── sph/              SPH particle effects for collapse visuals
  ├── sidecar/          SidecarBridge — stdio IPC to TypeScript process
  └── client/render/    GreedyMesher, AnimationEngine, RenderPipeline

Fast Design (com.blockreality.fastdesign)          Extension Layer
  ├── client/           3D hologram preview, HUD overlay, GUI screens
  ├── construction/     Rebar placement system
  ├── sidecar/          NURBS/STEP export pipeline
  └── network/          Packet sync (hologram state, build actions)

MctoNurbs-review/                                  TypeScript Sidecar
  ├── src/pipeline.ts       NURBS export pipeline
  ├── src/rpc-server.ts     Stdio RPC server (Java <-> TypeScript)
  ├── src/greedy-mesh.ts    Mesh optimization
  └── src/cad/              opencascade.js CAD kernel operations
```

**Dependency direction**: `fastdesign` -> `api` (never the reverse). The sidecar communicates with Java via stdio RPC through `SidecarBridge`.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Game Platform | Minecraft Forge 1.20.1 (47.2.0), Official Mappings |
| Language (Mod) | Java 17 |
| Build System | Gradle 8.8, daemon disabled, 3GB heap |
| Language (Sidecar) | TypeScript, Node.js |
| CAD Kernel | opencascade.js |
| Testing | JUnit 5 (Jupiter) — 539 unit tests |
| IPC | Stdio RPC (JSON protocol) |

## Quick Start

### Prerequisites
- **Java 17** JDK
- **Gradle 8.x** (wrapper included)
- **Node.js 18+** and npm (for the sidecar)

### Build

```bash
# Clone
git clone https://github.com/rocky59487/Block-Realityapi-Fast-design.git
cd Block-Realityapi-Fast-design

# Build the Forge mod (both API + Fast Design modules)
cd "Block Reality"
./gradlew build

# Build the merged JAR (ready for mods/ folder)
./gradlew mergedJar

# The sidecar is auto-built during fastdesign:processResources
```

### Run

```bash
# Run Minecraft client with Fast Design + API
./gradlew :fastdesign:runClient

# Run Minecraft client with API only
./gradlew :api:runClient

# Run tests
./gradlew test
```

### Deploy to PrismLauncher

```bash
./gradlew :fastdesign:copyToDevInstance
```

## Project Structure

```
Block-Realityapi-Fast-design/
├── Block Reality/               Minecraft Forge mod (Gradle multi-project)
│   ├── api/                     Foundation layer source code
│   │   ├── src/main/java/       Physics, materials, blueprints, collapse
│   │   └── src/test/java/       539 JUnit 5 unit tests
│   ├── fastdesign/              Extension layer source code
│   │   └── src/main/java/       CAD tools, GUI, rebar, NURBS export
│   ├── merged-resources/        Unified mod metadata
│   ├── build.gradle             Root build config (mergedJar task)
│   ├── settings.gradle          Subproject definitions
│   └── gradle/                  Gradle wrapper
│
├── MctoNurbs-review/            TypeScript sidecar (Node.js)
│   ├── src/                     RPC server, NURBS pipeline, mesh optimizer
│   ├── package.json             Dependencies (opencascade.js, vitest)
│   └── tsconfig.json            TypeScript configuration
│
├── docs/                        Project documentation
│   ├── completed/               Finalized manuals and audit reports
│   ├── incomplete/              Work-in-progress documents
│   └── old/                     Archived versions and legacy reports
│
├── CLAUDE.md                    Claude Code project guidance
└── README.md                    This file
```

## Documentation

All project documentation is organized in the `docs/` directory:

| Document | Description |
|----------|-------------|
| [Node-Based Visual Config Design Report v1.1](docs/completed/Block%20Reality%20Node-Based%20Visual%20Configuration%20System%20-%20Design%20Report%20v1.1.md) | Latest design report for the visual configuration system |
| [Fast Design Operation Manual](docs/completed/fastdesign_操作總表.xlsx) | Complete operation reference for Fast Design tools |
| [Fast Design Dev Handbook v1](docs/completed/Fast%20Design%20開發手冊%20v1.md) | Developer handbook for the Fast Design module |
| [Block Reality Manual v3](docs/completed/block-reality-manual-v3fix.md) | Comprehensive project manual |
| [v1.0.0 Final Audit](docs/completed/BlockReality-v1.0.0-Final-Audit.pdf) | Final audit report for v1.0.0 release |
| [Optimization Execution Report](docs/completed/OPTIMIZATION_EXECUTION_REPORT.md) | Performance optimization documentation |
| [v3 Migration Research](docs/completed/v3_移植研究報告.xlsx) | Research report for v3 large-scale optimization |

## Future Roadmap

### v3.0 — Large-Scale Structural Optimization
- Support for **1200x1200x300 block structures** with real-time physics
- Chunk-level parallel computation for physics evaluation
- LOD (Level of Detail) system for distant structure rendering
- Memory-optimized data structures for massive builds

### Node-Based Visual Configuration System
- Visual node graph editor for defining custom material behaviors
- Drag-and-drop physics rule composition
- Real-time preview of material property effects
- Export/import of custom configuration presets

### Multiplayer Structural Sync
- Server-authoritative physics validation
- Efficient delta-based structure synchronization
- Collaborative building with shared structural feedback
- Per-player physics computation offloading

### Extended Material Library
- Composite materials (carbon fiber, fiber-reinforced polymer)
- Temperature-dependent material properties
- Fatigue and cyclic loading simulation
- Material aging and degradation models

### CAD Integration Enhancement
- Bi-directional STEP/IGES import/export
- Integration with professional structural analysis software (SAP2000, ETABS format export)
- Parametric design with constraint-based modeling
- Automated structural optimization suggestions

## Contributing

Contributions are welcome! Please follow these guidelines:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Write tests for new functionality
4. Ensure all 539+ tests pass (`./gradlew test`)
5. Submit a pull request with a clear description

## License

MIT

---

# 中文版

## 概述

Block Reality 是一款 Minecraft Forge 模組，將**真實世界的結構工程**帶入遊戲中。每個方塊都具備物理材料屬性——抗壓強度、抗拉強度、抗剪強度、密度與楊氏模量——物理引擎會即時評估結構是否能夠自我支撐。

本專案由三個緊密整合的組件構成：

- **Block Reality API** — 基礎層，提供物理引擎、材料系統、藍圖序列化與崩塌模擬
- **Fast Design** — CAD 風格擴充模組，新增 3D 全息投影預覽、施工 HUD 覆蓋、鋼筋佈置工具與完整 GUI 編輯器
- **MctoNurbs Sidecar** — TypeScript/Node.js 程序，透過 opencascade.js 處理 NURBS 曲面生成與 STEP CAD 匯出

## 功能特色

### 物理引擎
- **Union-Find 連通性分析**，即時檢測結構完整性
- **力平衡求解器**，使用逐次超鬆弛法（SOR）搭配自適應鬆弛因子
- **尤拉挫屈檢查**與梁應力評估
- **荷載路徑追蹤**，從結構追蹤至地面
- 失去支撐的結構會**動態崩塌**，搭配 SPH 粒子效果

### 材料系統
- **10+ 種預設材料**：混凝土、鋼材、木材、磚塊、玻璃、基岩等
- 所有材料使用**真實工程單位**（MPa 抗壓/抗拉強度、GPa 楊氏模量、kg/m3 密度）
- 透過 `CustomMaterial.Builder` 自訂材料
- **動態材料**支援 RC 融合（97/3 混凝土-鋼筋複合材料）

### 藍圖系統
- 將結構設計儲存、載入、分享為 NBT 資料
- 多方塊放置支援旋轉、鏡像與偏移
- 檔案 I/O 進行藍圖匯入/匯出

### Fast Design（CAD 擴充模組）
- **3D 全息投影預覽**，放置前預覽結構
- **施工 HUD 覆蓋**，提供即時結構回饋
- **鋼筋佈置系統**，用於鋼筋混凝土設計
- **NURBS/STEP 匯出**管線，匯出至專業 CAD 格式
- 完整 GUI 編輯器，直覺操控（包含互動式操作提示與空狀態防呆反饋）

### 鑿刻系統
- **10x10x10 體素子方塊造型**，用於精細建築建模

### 崩塌模擬
- 當物理判定結構失效，方塊會斷裂墜落
- **SPH（光滑粒子流體動力學）** 粒子效果，呈現逼真的破壞視覺

## 架構

```
Block Reality API (com.blockreality.api)           基礎層
  ├── physics/          力平衡求解器、梁應力引擎、
  │                     Union-Find 連通性、荷載路徑追蹤
  ├── material/         BlockTypeRegistry、DefaultMaterial（10+ 種）、
  │                     CustomMaterial.Builder、DynamicMaterial（RC 融合）
  ├── blueprint/        Blueprint <-> NBT 序列化、BlueprintIO
  ├── collapse/         CollapseManager — 物理失效時觸發崩塌
  ├── chisel/           10x10x10 體素子方塊造型系統
  ├── sph/              崩塌視覺的 SPH 粒子效果
  ├── sidecar/          SidecarBridge — stdio IPC 連接 TypeScript
  └── client/render/    GreedyMesher、AnimationEngine、RenderPipeline

Fast Design (com.blockreality.fastdesign)          擴充層
  ├── client/           3D 全息投影預覽、HUD 覆蓋、GUI 畫面
  ├── construction/     鋼筋佈置系統
  ├── sidecar/          NURBS/STEP 匯出管線
  └── network/          封包同步（全息投影狀態、建造動作）

MctoNurbs-review/                                  TypeScript Sidecar
  ├── src/pipeline.ts       NURBS 匯出管線
  ├── src/rpc-server.ts     Stdio RPC 伺服器（Java <-> TypeScript）
  ├── src/greedy-mesh.ts    網格優化
  └── src/cad/              opencascade.js CAD 核心運算
```

**依賴方向**：`fastdesign` -> `api`（絕不反向）。Sidecar 透過 `SidecarBridge` 以 stdio RPC 與 Java 通訊。

## 技術棧

| 組件 | 技術 |
|------|------|
| 遊戲平台 | Minecraft Forge 1.20.1 (47.2.0)，官方映射 |
| 語言（模組） | Java 17 |
| 建置系統 | Gradle 8.8，守護程序停用，3GB 堆記憶體 |
| 語言（Sidecar） | TypeScript、Node.js |
| CAD 核心 | opencascade.js |
| 測試框架 | JUnit 5 (Jupiter) — 539 項單元測試 |
| 行程間通訊 | Stdio RPC（JSON 協議） |

## 快速開始

### 前置需求
- **Java 17** JDK
- **Gradle 8.x**（已內含 wrapper）
- **Node.js 18+** 與 npm（用於 sidecar）

### 建置

```bash
# 克隆
git clone https://github.com/rocky59487/Block-Realityapi-Fast-design.git
cd Block-Realityapi-Fast-design

# 建置 Forge 模組（API + Fast Design 兩個模組）
cd "Block Reality"
./gradlew build

# 建置合併 JAR（可直接放入 mods/ 資料夾）
./gradlew mergedJar

# Sidecar 會在 fastdesign:processResources 時自動建置
```

### 運行

```bash
# 以 Fast Design + API 啟動 Minecraft 客戶端
./gradlew :fastdesign:runClient

# 僅以 API 啟動 Minecraft 客戶端
./gradlew :api:runClient

# 執行測試
./gradlew test
```

### 部署至 PrismLauncher

```bash
./gradlew :fastdesign:copyToDevInstance
```

## 專案結構

```
Block-Realityapi-Fast-design/
├── Block Reality/               Minecraft Forge 模組（Gradle 多專案）
│   ├── api/                     基礎層原始碼
│   │   ├── src/main/java/       物理、材料、藍圖、崩塌
│   │   └── src/test/java/       539 項 JUnit 5 單元測試
│   ├── fastdesign/              擴充層原始碼
│   │   └── src/main/java/       CAD 工具、GUI、鋼筋、NURBS 匯出
│   ├── merged-resources/        統一模組中繼資料
│   ├── build.gradle             根建置設定（mergedJar 任務）
│   ├── settings.gradle          子專案定義
│   └── gradle/                  Gradle wrapper
│
├── MctoNurbs-review/            TypeScript sidecar（Node.js）
│   ├── src/                     RPC 伺服器、NURBS 管線、網格優化器
│   ├── package.json             相依套件（opencascade.js、vitest）
│   └── tsconfig.json            TypeScript 設定
│
├── docs/                        專案文件
│   ├── completed/               已完成的手冊與審核報告
│   ├── incomplete/              進行中的文件
│   └── old/                     歸檔版本與歷史報告
│
├── CLAUDE.md                    Claude Code 專案指引
└── README.md                    本檔案
```

## 文件說明

所有專案文件整理於 `docs/` 目錄：

| 文件 | 說明 |
|------|------|
| [節點視覺配置設計報告 v1.1](docs/completed/Block%20Reality%20Node-Based%20Visual%20Configuration%20System%20-%20Design%20Report%20v1.1.md) | 視覺配置系統最新設計報告 |
| [Fast Design 操作總表](docs/completed/fastdesign_操作總表.xlsx) | Fast Design 工具完整操作參考 |
| [Fast Design 開發手冊 v1](docs/completed/Fast%20Design%20開發手冊%20v1.md) | Fast Design 模組開發手冊 |
| [Block Reality 手冊 v3](docs/completed/block-reality-manual-v3fix.md) | 完整專案手冊 |
| [v1.0.0 最終審核](docs/completed/BlockReality-v1.0.0-Final-Audit.pdf) | v1.0.0 版本最終審核報告 |
| [優化執行報告](docs/completed/OPTIMIZATION_EXECUTION_REPORT.md) | 效能優化文件 |
| [v3 移植研究報告](docs/completed/v3_移植研究報告.xlsx) | v3 大規模優化研究報告 |

## 未來展望

### v3.0 — 大規模結構優化
- 支援 **1200x1200x300 方塊結構**的即時物理運算
- 區塊級平行運算進行物理評估
- 遠距結構的 LOD（細節層次）渲染系統
- 記憶體優化資料結構，應對大型建築

### 節點式視覺配置系統
- 視覺化節點圖編輯器，定義自訂材料行為
- 拖放式物理規則組合
- 材料屬性效果的即時預覽
- 自訂配置預設的匯出/匯入

### 多人結構同步
- 伺服器權威的物理驗證
- 高效的差異式結構同步
- 共享結構回饋的協作建造
- 每位玩家的物理運算卸載

### 擴充材料庫
- 複合材料（碳纖維、纖維強化聚合物）
- 溫度相關材料屬性
- 疲勞與循環荷載模擬
- 材料老化與劣化模型

### CAD 整合強化
- 雙向 STEP/IGES 匯入/匯出
- 與專業結構分析軟體整合（SAP2000、ETABS 格式匯出）
- 基於約束的參數化設計
- 自動化結構優化建議

## 貢獻指南

歡迎貢獻！請遵循以下指引：

1. Fork 本倉庫
2. 建立功能分支（`git checkout -b feature/your-feature`）
3. 為新功能撰寫測試
4. 確保所有 539+ 項測試通過（`./gradlew test`）
5. 提交 Pull Request 並附上清楚的說明

## 授權

MIT
