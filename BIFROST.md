# BIFROST — Bridged Intelligence for Structural Toolkit

> Minecraft 結構物理引擎 + FEM 對齊 ML 預測系統

BIFROST 是 Block Reality 的下一代物理分析核心。它將傳統 PFSF 迭代求解器與 FEM 訓練的神經算子（FNO）智慧整合，針對不同結構形態自動選擇最佳求解後端。

```
                        ┌─────────────────┐
                        │  Structure      │
                        │  Island         │
                        └───────┬─────────┘
                                │
                        ┌───────▼─────────┐
                        │ ShapeClassifier │
                        │ irregularity()  │
                        └───────┬─────────┘
                                │
                    ┌───────────┴───────────┐
                    │                       │
            score < 0.45              score ≥ 0.45
                    │                       │
            ┌───────▼───────┐       ┌───────▼───────┐
            │     PFSF      │       │   FNO (ML)    │
            │  GPU Jacobi   │       │  FEM-trained  │
            │  V-Cycle MG   │       │  Neural Op.   │
            │  Phase-Field  │       │  10ch output  │
            └───────┬───────┘       └───────┬───────┘
                    │                       │
                    └───────────┬───────────┘
                                │
                        ┌───────▼─────────┐
                        │  Unified φ +    │
                        │  σ_ij + u_i     │
                        │  failure check  │
                        └─────────────────┘
```

## 架構總覽

| 組件 | 語言 | 用途 |
|------|------|------|
| **PFSF Engine** | Java/LWJGL | Vulkan GPU 迭代求解器（勢場擴散） |
| **IPFSFRuntime** | Java | 後端抽象介面（Strategy pattern） |
| **HybridPhysicsRouter** | Java | 逐島路由：普通→PFSF，異形→FNO |
| **ShapeClassifier** | Java + Python | 結構不規則度分類（0-1 分數） |
| **FNO3DMultiField** | Python/JAX | 3D 傅立葉神經算子（10 通道輸出） |
| **FEM Solver** | Python/SciPy | 離線 hex8 有限元素分析（訓練資料） |
| **Auto-Train** | Python | 自動生成→FEM→訓練→匯出 管線 |
| **Training UI** | Python/Gradio | 網頁 + 終端 雙介面訓練控制台 |
| **libpfsf** | C++ | 獨立 C++ 求解器（Phase 1 骨架） |

## 快速開始

### 一鍵訓練（零手動資料）

```bash
cd brml
pip install -e ".[ui]"

# 快速驗證（~5 分鐘）
python -m brml.pipeline.auto_train --grid 8 --structures 50 --steps 2000

# 正式訓練（~1-2 小時）
python -m brml.pipeline.auto_train --grid 16 --structures 500 --steps 20000

# 帶 UI 訓練（瀏覽器介面）
brml-ui
```

### 訓練管線流程

```
Stage 1: 自動生成 Minecraft 結構
         ├── 7 種異形：bridge, cantilever, arch, spiral, tree, cave, overhang
         └── 2 種普通：random, tower
         ↓
Stage 2: 對每個結構跑真 FEM
         ├── 8 節點六面體元素（hex8, 2×2×2 Gauss 積分）
         ├── 全域稀疏組裝 + 共軛梯度法求解
         └── 應力張量 + 位移場 + von Mises 恢復
         ↓
Stage 3: 訓練 FNO3DMultiField
         ├── 輸入：occupancy + E + ν + ρ + Rcomp（5ch）
         ├── 輸出：σ_ij(6) + u(3) + φ(1)（10ch）
         └── 多場損失：0.5×L_stress + 0.3×L_disp + 0.2×L_phi
         ↓
Stage 4: 匯出 → ONNX → Java 推理
```

## 三種求解模式

### 1. PFSF 模式（普通結構）
牆壁、地板、柱子等規則形狀。Vulkan GPU 上 Jacobi + Chebyshev + V-Cycle 多重網格迭代。

```
優點：經過驗證、零延遲啟動、無 ML 依賴
缺點：僅標量勢場（φ），非真應力張量
適用：fill ratio > 0.6，低懸臂比，規則柱狀結構
```

### 2. FNO 模式（異形結構）
懸臂、拱橋、螺旋樓梯、樹狀分支等不規則形狀。一次 forward pass 產出 FEM 等級應力場。

```
優點：FEM 精度、<1ms 推理、完整應力張量
缺點：需要訓練、ONNX Runtime 依賴、泛化有限
適用：overhang ratio > 0.15，fill ratio < 0.4，高表面積比
```

### 3. 混合模式（預設）
`HybridPhysicsRouter` 根據 `ShapeClassifier` 分數自動選擇。

```java
// 在 PFSFEngine 中自動路由
HybridPhysicsRouter.Backend backend = router.route(islandId, members, anchors, epoch);
// backend == PFSF → PFSFEngineInstance.onServerTick()
// backend == FNO  → OnnxPFSFRuntime.infer()  (Phase 2)
```

## 不規則度分類

Java 和 Python 使用**完全相同**的 4 指標加權公式：

```
irregularity = 0.25 × (1 - fill_ratio)
             + 0.25 × min(surface_ratio, 2) / 2
             + 0.25 × profile_variance
             + 0.25 × overhang_ratio

threshold = 0.45 (可調)
```

| 指標 | 普通結構 | 異形結構 |
|------|---------|---------|
| Fill ratio | > 0.6 | < 0.4 |
| Surface ratio | ~1.0 | > 1.5 |
| Profile variance | ~0 | > 0.3 |
| Overhang ratio | ~0 | > 0.15 |
| **總分** | **< 0.3** | **> 0.5** |

## FNO 多場輸出格式

```
FNO3DMultiField output: [B, Lx, Ly, Lz, 10]

Channel  Field              Unit     Description
───────  ─────              ────     ───────────
  0      σ_xx               Pa       Normal stress (X)
  1      σ_yy               Pa       Normal stress (Y)
  2      σ_zz               Pa       Normal stress (Z)
  3      τ_xy               Pa       Shear stress (XY)
  4      τ_yz               Pa       Shear stress (YZ)
  5      τ_xz               Pa       Shear stress (XZ)
  6      u_x                m        Displacement (X)
  7      u_y                m        Displacement (Y)
  8      u_z                m        Displacement (Z)
  9      φ                  —        PFSF-compatible potential
```

- PFSF 系統讀 channel 9 → 直接接入現有 failure detection
- 進階分析讀 channel 0-5 → 真 von Mises、主應力方向
- 渲染系統讀 channel 6-8 → 變形視覺化

## FEM 求解器

真實有限元素分析，用於離線生成訓練資料。

```
元素：    8 節點六面體（hex8, trilinear shape functions）
積分：    2×2×2 Gauss quadrature
本構：    各向同性線性彈性（E, ν → D 矩陣）
荷載：    重力體力 f = ∫ N^T · [0, -ρg, 0] dV
邊界：    錨點節點 → 罰函數法固定位移
求解：    scipy.sparse CG + 對角預條件
後處理：  B·u → ε → σ = D·ε → von Mises
```

測試覆蓋：9 項（剛度對稱、半正定、體力守恆、von Mises 單軸/靜水、重力位移方向、材料剛度比較）

## 訓練 UI

### 網頁版 (Gradio)

```bash
brml-ui --web    # http://localhost:7860
```

- 三模型切選（surrogate / recommender / collapse）
- 所有超參數滑桿（grid, structures, steps, LR, hidden, layers, modes...）
- 即時 loss 圖表 + 進度顯示
- Start / Resume / Stop 按鈕
- 自動存檔 + 斷點續訓

### 終端版

```bash
brml-ui --tui
```

- 互動式參數輸入
- 即時進度條 + 速度 + ETA
- Ctrl+C 安全停止 + 自動存檔
- 下次啟動自動偵測 checkpoint

## 訓練建議

### 精度 vs 時間

| 配置 | 結構數 | 步數 | 時間 (CPU) | 預期精度 |
|------|-------|------|-----------|---------|
| 快速驗證 | 50 | 2K | 5 min | ~70% |
| 開發用 | 200 | 10K | 30 min | ~85% |
| 正式用 | 500 | 50K | 2-3 hr | ~92% |
| 高品質 | 2000 | 100K | 8-12 hr | ~96% |

### 微調建議

1. **先跑小 grid 驗證管線** → `--grid 8 --structures 30 --steps 1000`
2. **確認 loss 收斂後加大** → `--grid 12 --structures 200 --steps 10000`
3. **最終訓練用大 grid** → `--grid 16 --structures 1000 --steps 50000`
4. **GPU 加速** → 安裝 `jax[cuda12]`，訓練速度提升 10-50×
5. **恢復訓練** → 直接再跑同樣指令，自動從 checkpoint 續訓
6. **調 threshold** → 降低 threshold (0.3) = 更多結構用 FNO，升高 (0.6) = 更保守

### 改進路線

| 階段 | 改進 | 效果 |
|------|------|------|
| **現在** | 多場訓練 (stress+disp+phi) | PFSF 和 FEM 兩端都能讀 |
| **短期** | PINO (Physics-Informed) | 減少 FEM 資料需求 50% |
| **中期** | 自適應 threshold | 根據 FNO 預測信心動態調整 |
| **長期** | Online learning | 遊戲中持續微調，越用越準 |

## 目錄結構

```
Block-Realityapi-Fast-design/
├── Block Reality/
│   └── api/src/main/java/com/blockreality/api/physics/pfsf/
│       ├── IPFSFRuntime.java           ← 後端抽象介面
│       ├── PFSFEngine.java             ← Static facade + HybridRouter
│       ├── PFSFEngineInstance.java      ← PFSF Vulkan 實作
│       ├── ShapeClassifier.java        ← 結構不規則度分類
│       ├── HybridPhysicsRouter.java    ← 智慧路由決策
│       └── ... (其他 PFSF 組件)
├── brml/                               ← BIFROST ML Training Pipeline
│   ├── brml/
│   │   ├── fem/                        ← 有限元素求解器
│   │   │   ├── hex8_element.py         ← 8 節點六面體元素
│   │   │   └── fem_solver.py           ← 全域組裝 + CG 求解
│   │   ├── models/                     ← 神經網路模型
│   │   │   ├── pfsf_surrogate.py       ← FNO3D + FNO3DMultiField
│   │   │   ├── collapse_predictor.py   ← MPNN 崩塌預測
│   │   │   └── node_recommender.py     ← GAT 節點推薦
│   │   ├── pipeline/
│   │   │   └── auto_train.py           ← 一鍵：生成→FEM→訓練→匯出
│   │   ├── ui/
│   │   │   ├── web_ui.py              ← Gradio 網頁介面
│   │   │   ├── tui.py                 ← 終端介面
│   │   │   └── session.py             ← 訓練會話 + checkpoint
│   │   ├── data/                       ← 資料載入器
│   │   ├── train/                      ← 訓練腳本
│   │   └── export/                     ← ONNX 匯出
│   └── tests/
│       ├── test_fem.py                 ← 9 項 FEM 驗證
│       └── test_models.py             ← 模型 forward pass 測試
├── libpfsf/                            ← C++ 獨立求解器（Phase 1）
│   ├── include/pfsf/
│   │   ├── pfsf.h                     ← 公開 C API (20 函式)
│   │   └── pfsf_types.h               ← 型別定義
│   └── src/                           ← Vulkan compute 骨架
└── BIFROST.md                          ← 本文件
```

## 授權

Block Reality 專案內部使用。
