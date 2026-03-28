# Block Reality API

**Structural physics simulation engine for Minecraft Forge 1.20.1**

Block Reality API brings real-world structural engineering to Minecraft. Every block has material properties — compressive strength, tensile strength, shear resistance, density, and Young's modulus — and the physics engine evaluates whether structures can actually stand.

> 📐 *If it wouldn't survive in the real world, it won't survive in Block Reality.*

## Features

**Physics Engine** — Union-Find connectivity analysis, force equilibrium solver (SOR with adaptive omega), Euler buckling checks, beam stress evaluation, and load path tracing. Structures that lose support will collapse.

**Material System** — 10+ default materials (concrete, steel, timber, brick, glass, bedrock…) with real engineering values. Custom materials via `CustomMaterial.Builder`. Dynamic materials with RC fusion (97/3 concrete-rebar composite).

**Blueprint System** — Save, load, and share structural designs. Multi-block placement with rotation, mirroring, and offset support.

**Fast Design (Extension)** — CAD-style building module with 3D hologram preview, construction HUD overlay, rebar placement, NURBS export, and a full GUI editor.

**Collapse Simulation** — When physics says a structure fails, blocks break and fall with SPH-based particle effects.

## Architecture

```
Block Reality API (blockreality)     ← Foundation layer
  ├── physics/        Structural analysis engines
  ├── material/       Material properties & registry
  ├── blueprint/      Structure serialization
  ├── placement/      Multi-block calculator
  ├── collapse/       Failure & destruction logic
  ├── sph/            Particle-based visual effects
  └── sidecar/        TypeScript IPC bridge

Fast Design (fastdesign)             ← Extension layer
  ├── client/         3D preview, HUD, GUI screens
  ├── construction/   Build tools & rebar system
  ├── network/        Packet sync (hologram, actions)
  └── sidecar/        NURBS export pipeline
```

## Tech Stack

- **Minecraft Forge** 1.20.1 (47.2.0), Official Mappings
- **Java 17**, Gradle 8.1.1
- **539 unit tests** covering physics, materials, placement, and engine behavior

## Quick Start

```bash
# Clone
git clone https://github.com/YOUR_USERNAME/block-reality-api.git
cd block-reality-api

# Build
./gradlew build

# Run Minecraft with the mod
./gradlew runClient
```

## License

MIT

---

# Block Reality API

**Minecraft Forge 1.20.1 結構物理模擬引擎**

Block Reality API 將真實世界的結構工程帶入 Minecraft。每個方塊都擁有材料屬性——抗壓強度、抗拉強度、抗剪強度、密度與楊氏模量——物理引擎會即時評估結構是否能夠站立。

> 📐 *現實中撐不住的，Block Reality 裡也撐不住。*

## 功能特色

**物理引擎** — Union-Find 連通性分析、力平衡求解器（SOR 自適應鬆弛）、尤拉挫屈檢查、梁應力評估、荷載路徑追蹤。失去支撐的結構會崩塌。

**材料系統** — 10+ 種預設材料（混凝土、鋼材、木材、磚塊、玻璃、基岩⋯）使用真實工程數值。透過 `CustomMaterial.Builder` 自訂材料。動態材料支援 RC 融合（97/3 混凝土-鋼筋複合）。

**藍圖系統** — 儲存、載入、分享結構設計。支援多方塊放置，含旋轉、鏡像、偏移。

**Fast Design（擴充模組）** — CAD 風格建築模組，3D 全息投影預覽、施工 HUD、鋼筋佈置、NURBS 匯出、完整 GUI 編輯器。

**崩塌模擬** — 當物理判定結構失效，方塊會斷裂並以 SPH 粒子效果墜落。

## 架構

```
Block Reality API (blockreality)     ← 基礎層
  ├── physics/        結構分析引擎
  ├── material/       材料屬性與註冊
  ├── blueprint/      結構序列化
  ├── placement/      多方塊計算器
  ├── collapse/       破壞與崩塌邏輯
  ├── sph/            粒子視覺效果
  └── sidecar/        TypeScript IPC 橋接

Fast Design (fastdesign)             ← 擴充層
  ├── client/         3D 預覽、HUD、GUI 畫面
  ├── construction/   建造工具與鋼筋系統
  ├── network/        封包同步（全息投影、動作）
  └── sidecar/        NURBS 匯出管線
```

## 技術棧

- **Minecraft Forge** 1.20.1 (47.2.0)，官方映射
- **Java 17**，Gradle 8.1.1
- **539 項單元測試**，涵蓋物理、材料、放置與引擎行為

## 快速開始

```bash
# 克隆
git clone https://github.com/YOUR_USERNAME/block-reality-api.git
cd block-reality-api

# 建置
./gradlew build

# 啟動帶 mod 的 Minecraft
./gradlew runClient
```

## 授權

MIT
