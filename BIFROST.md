# BIFROST — Bridged Intelligence for Structural Toolkit

> Minecraft 結構物理引擎 + PFSF 原生 ML 加速系統

## 核心理念

```
傳統路線（已棄用）：    離線 FEM → 10ch FNO → ONNX → Java 解析 → 取 φ
BIFROST 路線（v1）：    遊戲內 PFSF → 1ch FNO → φ → 直接進 failure_scan
                         ↑ 零翻譯、零離線成本、邊玩邊學
```

ML 不取代 PFSF，而是**學會跳過 PFSF 的迭代過程**。
訓練資料來自 PFSF 本身 — 每次求解都是免費的訓練樣本。

## 架構

```
玩家操作
  ↓
ChunkPhysicsLOD（全域物理 LOD）
  ├── Tier 0 SKIP:  未修改地形 → 零成本
  ├── Tier 1 MARK:  輕度修改 → StructuralKeystone 關鍵方塊追蹤
  ├── Tier 2 PFSF:  常規結構 → GPU Jacobi+PCG+MG 迭代求解
  └── Tier 3 FNO:   異形結構 → ONNX ML 推理（~0.5ms）
                      ↓
              ShapeClassifier 分數 < 0.45 → PFSF
              ShapeClassifier 分數 ≥ 0.45 → FNO
```

## 三個 ML 模型

| 模型 | 輸入 | 輸出 | 用途 |
|------|------|------|------|
| **PFSFSurrogate** | 5ch [occ, E, ν, ρ, Rcomp] | 1ch φ | 跳過 PFSF 迭代 |
| **FluidSurrogate** | 8ch [vel, p, boundary, pos] | 4ch [vel', p'] | 水體 0.1m 模擬 |
| **CollapsePredictor** | graph [nodes, edges] | 5-class + prob | 崩塌預測 |

所有模型使用：
- **Huber Loss** — 抗噪、抗離群值
- **LayerNorm** — 數值穩定性
- **FNO 頻譜卷積** — rFFT 軸正確夾持（修復 modes > Lz//2+1 bug）

## 並行流水線

```
┌─ Producer Thread ──────────────────┐  ┌─ Consumer Thread ────────────────┐
│                                     │  │                                  │
│  generate_structure()               │  │  等待 20 筆樣本...               │
│  → FEM/PFSF solve()                │  │  ← queue.get()                   │
│  → queue.put(sample)               │→ │  → JIT train_step()              │
│  → 繼續生成下一個...                │  │  → 更新 loss                     │
│                                     │  │  → 每 2000 步自動存檔            │
└─────────────────────────────────────┘  └──────────────────────────────────┘
   持續生產，不等訓練完                     20 筆就開始訓練，不等全部生成
```

## 快速開始

```bash
# Windows — 雙擊
start-trainer.bat

# Linux/Mac
./start-trainer.sh

# 或指定模式
./start-trainer.sh --tui         # 終端模式
./start-trainer.sh --auto        # 無 UI 自動訓練
```

Web UI 功能：
- 🌐 繁體中文 / English 動態切換
- ☑ 多模型同時選擇（surrogate + fluid + collapse）
- 🔄 並行/循序 流水線模式
- 📊 即時 loss 圖表 + GPU 狀態
- 💾 自動存檔 + 斷點續訓

## 模型硬化

| 技術 | 作用 | 位置 |
|------|------|------|
| Huber Loss | δ=1.0 內 MSE、外 MAE — 離群值不爆炸 | 全部 3 模型 |
| LayerNorm | 每層正規化 — 梯度穩定、收斂快 | FNOBlock + MPNN |
| rFFT 夾持 | mz = min(modes, Lz//2+1) — 防止越界讀零 | SpectralConv3D |
| Gradient Clip | max_norm=1.0 — 防止梯度爆炸 | Optax chain |
| Cosine Schedule | warmup + cosine decay — 平穩收斂 | 學習率 |

## Java 整合

```
PFSFEngine.init()
  ├── PFSFEngineInstance (PFSF GPU)
  ├── BIFROSTModelRegistry (ONNX 模型載入)
  │     config/blockreality/models/
  │     ├── bifrost_surrogate.onnx
  │     ├── bifrost_fluid.onnx
  │     └── bifrost_collapse.onnx
  ├── HybridPhysicsRouter (PFSF ↔ FNO 路由)
  └── ChunkPhysicsLOD (全域物理分級)

OnnxPFSFRuntime:
  input:  [1, L, L, L, 5]  → FloatBuffer row-major
  output: [1, L, L, L, 1]  → flatten5D → getPhi(pos)
  支援: CUDA + CPU fallback
  無 ONNX Runtime JAR → 靜默降級，全走 PFSF
```

## 目錄結構

```
├── start-trainer.bat / .sh / .ps1  ← 一鍵啟動
├── BIFROST.md                       ← 本文件
├── brml/                            ← ML 訓練管線
│   ├── brml/models/unified.py       ← 3 模型 + Huber + LayerNorm
│   ├── brml/pipeline/
│   │   ├── auto_train.py            ← 循序訓練（舊）
│   │   └── concurrent_trainer.py    ← 並行流水線（新）
│   ├── brml/ui/
│   │   ├── web_ui.py               ← Gradio 網頁 UI
│   │   ├── tui.py                  ← 終端 UI
│   │   └── i18n.py                 ← 繁體中文 / English
│   ├── brml/fem/                    ← FEM 求解器（離線驗證用）
│   └── brml/export/                 ← ONNX 匯出 + 合約
├── Block Reality/api/.../pfsf/
│   ├── OnnxPFSFRuntime.java        ← ONNX 推理
│   ├── BIFROSTModelRegistry.java   ← 模型管理
│   ├── HybridPhysicsRouter.java    ← 智慧路由
│   ├── ChunkPhysicsLOD.java        ← 全域物理 LOD
│   ├── StructuralKeystone.java     ← 關鍵方塊偵測
│   ├── ShapeClassifier.java        ← 不規則度分類
│   └── BlueprintTrainingExporter.java ← 遊戲→訓練匯出
└── libpfsf/                         ← C++ 獨立求解器（Phase 1）
```

## 授權

Block Reality 專案內部使用。
