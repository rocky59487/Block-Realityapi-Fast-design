# BR-NeXT — Block Reality Neural Operator eXpert Transformer

獨立可攜式結構機器學習訓練器，專為 Minecraft Forge 模組 **Block Reality** 設計。

## 核心特色

- **SSGO**（Sparse Spectral-Geometry Operator）
  - 全局頻譜分支 + 局部幾何圖注意力（26-連通）
  - MoE 專家化頭部，動態適應不同結構風格
- **CMFD**（Cascaded Multi-Fidelity Distillation）
  - 三級教師：LEA（解析近似）→ PFSF（稀疏求解）→ FEM（高精度有限元）
  - 頻率對齊蒸餾 + 物理殘差自監督
- **JAHP**（JAX Async Hybrid Pipeline）
  - multiprocessing FEM worker pool + async H2D prefetch
  - 單 GPU 也能消除資料等待氣泡
- **FEM 異常剔除**
  - Direct DOF elimination（取代 penalty method）
  - 3×3×3 median filter + Winsorization 去除角落數值尖峰

## ONNX 輸出合約（與 Java 100% 兼容）

| Tensor | Shape | 說明 |
|--------|-------|------|
| Input | `[1, L, L, L, 6]` | occ, E_norm, ν, ρ_norm, Rcomp_norm, Rtens_norm |
| Output | `[1, L, L, L, 10]` | stress_voigt(6) + displacement(3) + phi(1) |

phi 通道直接對應 PFSF 的 failure_scan，無需額外縮放。

## 安裝

```bash
cd BR-NeXT
pip install -e .
```

若使用 CUDA：
```bash
pip install -e ".[cuda]"
```

## 快速開始

### 1. 全自動 CMFD 訓練

```bash
brnext-train --grid 12 --steps-s1 5000 --steps-s2 5000 --steps-s3 10000
```

或使用腳本：
```bash
CONFIG=configs/ssgo_small.yaml bash scripts/run_full_pipeline.sh
```

### 2. 匯出 ONNX

```bash
brnext-export --checkpoint brnext_output/checkpoint_10000 --grid 12 --output model.onnx
```

### 3. Java 整合

將 `model.onnx` 放入 `Block Reality/api/src/main/resources/models/`，
確保 `HybridPhysicsRouter` 的模型路徑指向該檔案即可。

## 測試

```bash
pytest BR-NeXT/tests/
```

## 目錄結構

```
BR-NeXT/
├── brnext/
│   ├── models/        # SSGO, Weighted FNO, Voxel GAT, MoE Head, Losses
│   ├── fem/           # FEMSolverV2, Corner Filter
│   ├── teachers/      # LEA, PFSF, FEM teachers
│   ├── pipeline/      # CMFD trainer, JAHP, Structure generator
│   ├── export/        # ONNX export + contract validation
│   └── utils/         # JAX helpers
├── tests/             # pytest
├── configs/           # YAML presets
└── scripts/           # Bash runners
```

## 授權

GPL-3.0（與 Block Reality 主專案一致）
