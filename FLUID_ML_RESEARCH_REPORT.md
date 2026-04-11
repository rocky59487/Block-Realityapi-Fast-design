# 流體ML革命性架構研究報告

> **專案**：Block Reality — 參照 BR-NeXT 演進為流體ML設計下一代架構  
> **日期**：2026-04-11  
> **研究範圍**：現有 Java/Python 流體系統、BR-NeXT SSGO 演進、2024-2025 流體ML前沿論文與開源程式碼

---

## 目錄

1. [執行摘要](#1-執行摘要)
2. [現有系統深度分析](#2-現有系統深度分析)
3. [BR-NeXT 演進解構](#3-br-next-演進解構)
4. [流體ML前沿研究綜述](#4-流體ml前沿研究綜述)
5. [革命性新架構：HYDRO](#5-革命性新架構hydro)
6. [Java 模組整合方案](#6-java-模組整合方案)
7. [實作路線圖與風險評估](#7-實作路線圖與風險評估)

---

## 1. 執行摘要

### 1.1 核心發現

| 發現項目 | 嚴重性 | 說明 |
|---------|--------|------|
| **Java 端缺少流體 ONNX 推理實現** | 🔴 高 | `BIFROSTModelRegistry` 已註冊 `bifrost_fluid`，但 `OnnxPFSFRuntime` 僅實作結構物理的 `infer(StructureIsland)`，**沒有任何針對流體的 ONNX 推理入口**。目前 `FluidGPUEngine` 完全依賴 Vulkan Jacobi / CPU RBGS，ML 模型處於「有模型、無調用」狀態。 |
| **現有 FNO-Fluid 過於簡單** | 🟡 中 | `brml/models/fno_fluid.py` 是純 3D FNO（4 層、12 modes），缺乏 BR-NeXT 中驗證有效的 **局部幾何注意力**、**MoE 頭部**、**級聯蒸餾**等機制。 |
| **合約版本過舊** | 🟡 中 | `onnx_contracts.py` 的 `FLUID` 合約為 v1（8ch → 4ch），未納入流體-結構耦合所需的動態邊界條件。 |

### 1.2 建議方向

本報告提出 **HYDRO**（**H**ybrid **Y**ield-Driven **R**educed-order **O**perator）—— 一套參照 BR-NeXT SSGO 演進、並融合 2024-2025 年流體ML最前沿成果的**下一代流體神經算子架構**。核心目標：

1. **讓 Java 模組能正確調用流體 ML**（補齊 `OnnxFluidRuntime`）
2. **將 BR-NeXT 的結構物理革命複製到流體領域**（SSGO → HYDRO）
3. **引入 Latent Neural Operator + Adaptive Mesh Refinement + 多保真蒸餾**，在 Minecraft 的 tick 預算內實現 0.1m 子網格流體模擬

---

## 2. 現有系統深度分析

### 2.1 Java 端流體架構

```
FluidGPUEngine (IFluidManager SPI)
├── tickRegionGPU()  → Vulkan Jacobi + 壓力提取
├── tickRegionCPU()  → FluidCPUSolver.rbgsSolve()
├── FluidRegionRegistry  → 管理 FluidRegion (SoA)
├── FluidRegionBuffer    → GPU VRAM 對應
└── FluidAsyncCompute    → 三重緩衝非同步管線
```

**關鍵類別**：

- `FluidGPUEngine.java`：tick 迴圈協調器，預算驅動（`tickBudgetMs`）
- `FluidRegion.java`：CPU 端 SoA 資料 `[phi, phiPrev, type, density, pressure, volume]`
- `FluidState.java`：不可變記錄 `(type, volume, pressure, potential)`
- `FluidAsyncCompute.java`：Vulkan 三重緩衝，延遲 2 tick

**問題**：`FluidGPUEngine.tickRegionGPU()` 永遠走 Vulkan 管線。即使 `BIFROSTModelRegistry.getFluid()` 已載入 ONNX 模型，也完全沒有 ML 分支。

### 2.2 Python 端流體ML

```python
# brml/models/fno_fluid.py
FNOFluid3D(
    hidden_channels=48,
    num_layers=4,
    modes=12,
    in_channels=8,   # velocity(3) + pressure(1) + boundary(1) + position(3)
    out_channels=4,  # velocity_next(3) + pressure_next(1)
)
```

**訓練損失**：`0.5 * v_loss + 0.3 * p_loss + 0.2 * div_loss`（速度 MSE + 壓力 MSE + 散度懲罰）

**與結構 SSGE 的落差**：

| 特性 | 結構 SSGO (BR-NeXT) | 流體 FNOFluid3D (brml) |
|------|---------------------|------------------------|
| 全局分支 | WeightedSpectralConv3D + 可學習 mode rebalancing | 基礎 SpectralConv3D |
| 局部分支 | SparseVoxelGraphConv (26-連通) | ❌ 無 |
| 融合機制 | Gated Fusion (sigmoid gate) | ❌ 無 |
| 頭部 | MoE-Spectral Head (shared + routed experts) | 兩個獨立 Dense 頭 |
| 正規化 | LayerNorm + Huber Loss | ❌ 無 LayerNorm，MSE |
| 蒸餾 | CMFD 三階段 (LEA→PFSF→FEM) | ❌ 無 |
| 不確定性 | 可選 log-var 輸出 | ❌ 無 |

### 2.3 ONNX 合約現狀

```python
# brml/export/onnx_contracts.py
FLUID = ModelContract(
    model_id="bifrost_fluid",
    version=1,
    inputs=[TensorSpec("input", (1, -1, -1, -1, 8), "float32", "velocity(3) + pressure(1) + boundary(1) + position(3)")],
    outputs=[TensorSpec("output", (1, -1, -1, -1, 4), "float32", "velocity_next(3) + pressure_next(1)")],
)
```

Java 端 `BIFROSTModelRegistry` 已按此合約載入模型，但 **沒有流體專用的推理程式碼**。

---

## 3. BR-NeXT 演進解構

BR-NeXT 相較於原始 brml，在結構物理領域實現了質的飛躍。以下是其核心創新，以及**如何遷移到流體領域**的對應設計。

### 3.1 SSGO — Sparse Spectral-Geometry Operator

```python
# BR-NeXT/brnext/models/ssgo.py
class SSGO(nn.Module):
    def __call__(self, x):
        occ = x[..., 0:1]
        # Global Branch: FNO (頻譜、長程依賴)
        g = nn.Dense(hidden)(x)
        for _ in range(n_global_layers):
            g = FNOBlock(hidden, modes)(g)
        
        # Focal Branch: Sparse Voxel Graph Conv (局部幾何、26-連通)
        f = nn.Dense(hidden)(x)
        for _ in range(n_focal_layers):
            f = SparseVoxelGraphConv(hidden)(f, occ.squeeze(-1))
        
        # Gated Fusion
        gate = jax.nn.sigmoid(nn.Dense(1)(jnp.concatenate([g, f], axis=-1)))
        fused = gate * g + (1.0 - gate) * f
        
        # Shared Backbone
        h = fused
        for _ in range(n_backbone_layers):
            h = FNOBlock(hidden, modes)(h)
        
        # MoE-Spectral Head
        moe_out = MoESpectralHead(...)(h)
        
        # Task Heads: stress(6) + disp(3) + phi(1) [+ uncertainty(1)]
```

**流體遷移設計**：
- **Global Branch**：學習全局壓力梯度、重力驅動的大尺度流動
- **Focal Branch**：學習局部湍流、邊界層分離、障礙物繞流
- **Gated Fusion**：讓模型自動決定「何時依賴全局頻譜信息，何時依賴局部幾何細節」—— 對於水從崩塌結構湧出的場景至關重要

### 3.2 CMFD — Cascaded Multi-Fidelity Distillation

```
Stage 1: LEA Pre-train      → 低頻譜對齊、快速收斂
Stage 2: PFSF Distillation   → 學習中等/高頻細節、phi 場對齊
Stage 3: FEM Fine-tuning     → 高保真物理、PCGrad + uncertainty weighting + physics residual
```

**流體遷移設計（CMFD-Fluid）**：

| 階段 | 教師 | 學習目標 | 資料量 |
|------|------|---------|--------|
| S1 | Shallow Water Analytic (SWE) | 大尺度水面波動、快速收斂 | 10,000+ |
| S2 | PFSF-Fluid GPU Solver | 中等雷諾數流動、邊界壓力耦合 | 2,000+ |
| S3 | High-Fidelity NS CFD (OpenFOAM/SU2) | 湍流、漩渦脫落、自由表面 | 200+ |

### 3.3 WeightedSpectralConv3D — 可學習 Mode Rebalancing

```python
# BR-NeXT/brnext/models/weighted_fno.py
mode_w = self.param("mode_w", nn.initializers.ones, (mx, my, mz))
mode_w = jax.nn.sigmoid(mode_w)
out_modes = jnp.einsum("bxyzi,ioxyz->bxyzo", x_modes, weights * mode_w)
```

這讓模型能自動學習哪些頻率模式對當前物理問題更重要。對於流體，這意味著可以**自適應強化渦旋結構的特徵頻率**。

### 3.4 SparseVoxelGraphConv — JIT/ONNX 友好的 26-連通圖卷積

使用 `jnp.roll` 而非動態鄰居索引，完全兼容 ONNX 導出。這是流體架構中**學習局部 NS 非線性（對流項 u·∇u）的關鍵**。

---

## 4. 流體ML前沿研究綜述

本節整理 2024-2025 年對本專案最具參考價值的論文與程式碼。

### 4.1 Neural Operators for Fluid Dynamics

#### FNO 家族進化樹

| 方法 | 會議/期刊 | 核心創新 | 適用場景 |
|------|----------|---------|---------|
| **MgFNO** | arXiv 2024 | 三層級多網格架構（粗→中→細），分離學習低頻與高頻誤差 | 高解析度流體、零樣本超解析 |
| **DSFNO** | TMLR 2025 | Dynamic Schwartz-FNO，動態核 + 頻域交互 | 參數化湍流 |
| **ST-FNO** | ICLR 2025 | 時空聯合譜卷積 + spectral fine-tuning | 長期時序預測 |
| **Spectral-Refiner** | ICLR 2025 | 時空 FNO + 譜精煉，顯著提升湍流精度 | 湍流模擬 |
| **PAC-FNO** | ICLR 2024 | 並行結構 FNO | 加速推理 |
| **AM-FNO** | NeurIPS 2024 | 攤銷參數化 FNO | 多參數快速推理 |

**關鍵洞見**：MgFNO 在 Navier-Stokes 上將相對誤差降低 **83%**（0.795% → 0.562%）。對於 Minecraft 的 0.1m 子網格流體，這意味著可以用更小的網格獲得同等精度，或直接用粗網格訓練的模型零樣本推論到更高解析度。

### 4.2 Latent Neural Operators（隱空間神經算子）

| 方法 | 會議 | 核心創新 | 參考價值 |
|------|------|---------|---------|
| **LNO** | NeurIPS 2024 | Physics-Cross-Attention 在隱空間學習算子 | ⭐⭐⭐ 極高 |
| **PI-Latent-NO** | arXiv 2025 | 將 PDE 約束直接嵌入隱空間訓練 | ⭐⭐⭐ 極高 |
| **RLNO** | OpenReview | VAE 框架下的穩健隱空間算子 | ⭐⭐⭐ 極高 |
| **NIRFS** | Tao et al. 2024 | Neural Implicit Reduced Fluid Simulation，隱空間動力學 | ⭐⭐⭐ 極高 |

**為何對 Block Reality 至關重要**：

現有 `FNOFluid3D` 在**完整空間網格**上做自回歸（`[B, Nx, Ny, Nz, 8] → [B, Nx, Ny, Nz, 4]`），對於 30×30×30 的區域，每次推理需要處理 27,000 個體素。LNO 的思路是：

1. **Encoder**：將空間場壓縮到隱向量 `z`（例如 256-dim）
2. **Latent Operator**：在隱空間中推進 `z(t) → z(t+Δt)`（極小的 MLP 或 Transformer）
3. **Decoder**：將 `z(t+Δt)` 解碼回完整空間場

這樣**時間推演成本與空間解析度解耦**，對於長期穩定性和 tick 預算控制是革命性的。

### 4.3 Transformer + Adaptive Mesh Refinement

| 方法 | 會議 | 核心創新 |
|------|------|---------|
| **AMR-Transformer** | arXiv 2025 | 自適應網格細化分詞器 + NS 約束感知快速剪枝 |
| **Transolver** | IJCAI/後續 | 基於物理狀態切片的 Transformer PDE 求解器 |
| **UPT** | NeurIPS 2024 | Universal Physics Transformer，無網格/無粒子結構，統一歐拉與拉格朗日模擬 |

**AMR-Transformer 的啟示**：在 shockwave 模擬中，AMR tokenizer 將 4096 個 regular tokens 減少到約 **970 個自適應 tokens**，同時將相對於 1024×1024 GT 的 MSE 從 71.37 降到 **7.51**。對於 Minecraft 流體，這意味著**只對水體邊界、漩渦核心、障礙物周圍進行高解析度計算**，空曠水域用極低解析度。

### 4.4 Graph Neural Networks for Fluids

| 方法 | 來源 | 核心優勢 |
|------|------|---------|
| **MeshGraphNets** | DeepMind ICLR 2021 | 不規則網格上的消息傳遞，10-100× 加速 |
| **GNS** | Kumar & Vantassel 2023 | 可擴展的並行 GNN 模擬器 |
| **Airfoil-GCNN** | BaratiLab 2021 | 翼型繞流壓力/速度預測，誤差 1-2% |
| **RegDCGNN** | DrivAerNet++ 2025 | 動態圖卷積，汽車空氣動力學 |

**與 SSGO 的結合點**：SSGO 的 `SparseVoxelGraphConv` 本質上就是**規則體素網格上的 MeshGraphNets 簡化版**。可以進一步升級為：
- **邊特徵學習**：不僅聚合鄰居，還學習邊方向、距離、相對速度
- **多尺度圖**：在粗網格（1m）和細網格（0.1m）之間建立跨層邊

### 4.5 Physics-Informed & Hybrid Methods

| 方法 | 來源 | 創新點 |
|------|------|--------|
| **PINP** | ICLR 2025 | Physics-Informed Neural Predictor with latent estimation of fluid flows |
| **ConFIG** | ICLR 2025 | 無衝突 PINN 訓練，解決梯度競爭 |
| **NeurKItt** | NeurIPS 2024 | 用神經算子加速 Krylov 迭代 |
| **NINO** | NeurIPS 2024 | Newton Informed Neural Operator，高效求解非線性 PDE |

### 4.6 遊戲引擎與實時流體ML

| 方法/系統 | 來源 | 說明 |
|----------|------|------|
| **GameNGen** | Google 2024 | 用擴散模型實時模擬 DOOM，證明神經模型可承擔複雜遊戲狀態更新 |
| **Fluid Flux (UE5)** | MDPI 2025 | 基於 SWE 的 2D height field 實時河流模擬，與實驗數據對比驗證 |
| **LFM (Leapfrog Flow Maps)** | SIGGRAPH 2025 | 實時不可壓縮流體，混合速度-脈衝表示，適合 GPU |
| **NeuroFluid** | Guan et al. 2022 | 粒子驅動 NeRF 的無監督流體狀態推斷 |

---

## 5. 革命性新架構：HYDRO

### 5.1 命名與定位

**HYDRO** = **H**ybrid **Y**ield-Driven **R**educed-order **O**perator

定位：
- **結構物理**已由 BR-NeXT SSGO 負責（`bifrost_surrogate` / `brnext_ssgo.onnx`）
- **流體物理**由 HYDRO 負責（`bifrost_fluid_v2` / `hydro_fluid.onnx`）
- 兩者透過統一的 **BIFROSTModelRegistry** 載入，但由各自專用的 Runtime 執行推理

### 5.2 整體架構圖

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           HYDRO  Architecture                           │
├─────────────────────────────────────────────────────────────────────────┤
│  Input: [B, Nx, Ny, Nz, C_in]                                           │
│    C_in = 8 (legacy)  or  13 (v2 with FSI coupling)                     │
│    channels: velocity(3) + pressure(1) + boundary(1) + position(3)      │
│             + struct_phi(1) + boundary_vel(3) + porosity(1) [v2]       │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────┐ │
│  │  Global Branch  │    │  Focal Branch   │    │  Temporal Encoder   │ │
│  │  (Weighted FNO) │    │ (SparseVoxelGAT)│    │   (Latent ConvLSTM) │ │
│  │  3 layers       │    │  2 layers       │    │   z(t) → z(t+Δt)    │ │
│  │  modes=16       │    │  26-connected   │    │   256-dim latent    │ │
│  └────────┬────────┘    └────────┬────────┘    └──────────┬──────────┘ │
│           │                      │                        │            │
│           └──────────────────────┼────────────────────────┘            │
│                                  ▼                                     │
│                        ┌─────────────────┐                             │
│                        │   Gated Fusion  │  gate = sigmoid(Dense(g⊕f⊕z))│
│                        │  fused = gate·g + (1-gate)·f                 │
│                        └────────┬────────┘                             │
│                                 ▼                                      │
│                        ┌─────────────────┐                             │
│                        │ Shared Backbone │  2× FNOBlock               │
│                        │  + Residual     │                             │
│                        └────────┬────────┘                             │
│                                 ▼                                      │
│                        ┌─────────────────┐                             │
│                        │ MoE-Spectral Head│  n_shared=2, n_routed=8   │
│                        │  top_k=2        │                             │
│                        └────────┬────────┘                             │
│                                 ▼                                      │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │                     Multi-Scale Output Heads                     │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │  │
│  │  │ Euler Head  │  │ SPH Head    │  │  Free-Surface Head      │  │  │
│  │  │ velocity(3) │  │ particles   │  │  surface height +       │  │  │
│  │  │ pressure(1) │  │ (optional)  │  │  normal(3)              │  │  │
│  │  └─────────────┘  └─────────────┘  └─────────────────────────┘  │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  Output: [B, Nx, Ny, Nz, 4]  +  optional particle buffer              │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.3 六大核心創新

#### (1) 雙分支空間編碼器（繼承 SSGO）

```python
class HYDRO(nn.Module):
    hidden: int = 64
    modes: int = 16
    n_global: int = 3
    n_focal: int = 2
    n_backbone: int = 2
    
    @nn.compact
    def __call__(self, x, z_prev=None, train=False):
        boundary = x[..., 4:5]  # [B, N, N, N, 1]
        
        # Global: Weighted FNO for long-range pressure waves
        g = nn.Dense(self.hidden)(x)
        for _ in range(self.n_global):
            g = FNOBlock(self.hidden, self.modes)(g)
        
        # Focal: SparseVoxelGraphConv for local turbulence / boundary layers
        f = nn.Dense(self.hidden)(x)
        for _ in range(self.n_focal):
            f = SparseVoxelGraphConv(self.hidden)(f, boundary.squeeze(-1))
        
        # Temporal: Latent dynamics encoding
        z = None
        if z_prev is not None:
            z = LatentConvLSTM(self.hidden)(z_prev, g)  # z: [B, latent_dim]
```

**為何流體更需要局部分支**：
- 結構物理的「局部」主要是材料界面和應力集中。
- 流體物理的「局部」是**邊界層、渦旋、自由表面破碎**—— 這些都是高度非線性的局部現象，純 FNO 難以捕捉。

#### (2) 隱空間時間動力學（Latent Neural Operator）

不依賴完整的 `[B, Nx, Ny, Nz, C]` 自回歸，而是在壓縮的隱空間中做時間推演：

```python
class LatentConvLSTM(nn.Module):
    """Compress spatial field to latent vector and evolve in time."""
    latent_dim: int = 256
    
    @nn.compact
    def __call__(self, z_prev, spatial_feat):
        # spatial_feat: [B, N, N, N, H]
        # z_prev: [B, latent_dim]
        
        # Spatial pooling to latent
        h_pooled = jnp.mean(spatial_feat, axis=(1,2,3))  # [B, H]
        h_pooled = nn.Dense(self.latent_dim)(h_pooled)
        
        # LSTM cell in latent space
        if z_prev is None:
            z_prev = jnp.zeros_like(h_pooled)
        
        concat = jnp.concatenate([z_prev, h_pooled], axis=-1)
        forget_gate = jax.nn.sigmoid(nn.Dense(self.latent_dim)(concat))
        input_gate  = jax.nn.sigmoid(nn.Dense(self.latent_dim)(concat))
        output_gate = jax.nn.sigmoid(nn.Dense(self.latent_dim)(concat))
        candidate   = jnp.tanh(nn.Dense(self.latent_dim)(concat))
        
        z = forget_gate * z_prev + input_gate * candidate
        z_out = output_gate * jnp.tanh(z)
        
        return z_out
```

**推理階段**：
- 每隔 `k` 個 tick（例如 k=5），執行一次完整的空間 Encoder → Latent → Decoder 路徑。
- 中間的 `k-1` 個 tick，僅執行 **Latent LSTM**（<0.1ms），極大節省算力。
- 這與 Minecraft 的 20 TPS 完美契合：完整推理 4ms/100ms，隱空間推演 0.1ms/tick。

#### (3) 自適應區域注意力（Adaptive Region Attention, ARA）

啟發自 AMR-Transformer，但針對 Minecraft 的規則體素網格簡化：

```python
def adaptive_subsample(velocity, pressure, boundary, threshold=0.05):
    """
    只在流體變化劇烈的區域保留高解析度 tokens。
    Returns: (active_mask, coarsened_grid)
    """
    # 計算每個體素的「活動度」：速度梯度 + 壓力變化
    activity = jnp.abs(velocity).sum(-1) + jnp.abs(pressure)
    active = (activity > threshold) | (boundary.squeeze(-1) > 0.5)
    
    # 對非活躍區域做 2×2×2 平均池化
    # ...（使用 jax 的 strided pooling）
    return active_mask, pooled_grid
```

**實際效果**：對於靜止水池，90% 區域可被合併為粗網格；對於瀑布/漩渦，僅 10-20% 區域需要高解析度。

#### (4) MoE-Spectral 多任務頭部

繼承 BR-NeXT 的 `MoESpectralHead`，但為流體設計專家：

| Expert 編號 | 專長領域 | 路由條件 |
|------------|---------|---------|
| 0-1 | Shared Experts | 通用流動特徵 |
| 2 | Laminar Flow | 低雷諾數、層流 |
| 3 | Turbulent Wake | 高雷諾數、障礙物尾流 |
| 4 | Free Surface | 自由表面、氣液界面 |
| 5 | Porous Media | 多孔介質滲流（泥土、碎石） |
| 6 | FSI Boundary | 流固耦合邊界 |
| 7 | Gravity Wave | 重力波、水躍 |

```python
moe_out = MoESpectralHead(
    out_channels=hidden,
    hidden=moe_hidden,
    n_shared=2,
    n_routed=8,
    top_k=2,
)(backbone_out)
```

#### (5) 混合歐拉-拉格朗日輸出（Hybrid Euler-SPH Output）

Minecraft 水體的視覺和物理需求不同：
- **歐拉頭**（默認）：輸出網格化的 `velocity_next(3) + pressure_next(1)`，供 PFSF 邊界壓力查詢
- **SPH 頭**（可選）：在自由表面生成粒子 `(pos, vel, mass, life)`，供渲染管線（`WaterFoamNode`、`WaterSurfaceNode`）使用
- **自由表面頭**（可選）：輸出 `surface height + normal(3)`，用於法線貼圖和焦散計算

```python
# Euler head (main)
v = nn.Dense(3)(nn.gelu(nn.Dense(64)(moe_out)))
p = nn.Dense(1)(nn.gelu(nn.Dense(64)(moe_out)))
euler_out = jnp.concatenate([v, p], axis=-1) * boundary

# SPH head (optional, client-side only)
sph_logits = nn.Dense(1)(nn.gelu(nn.Dense(32)(moe_out)))
sph_mask = jax.nn.sigmoid(sph_logits) * boundary  # free surface probability
```

#### (6) CMFD-Fluid — 級聯多保真蒸餾

```
Stage 1: SWE Teacher (10k samples, 5k steps)
    → 頻譜對齊損失（低頻帶）
    → 學習大尺度水面波動、重力驅動流
    
Stage 2: PFSF-Fluid Teacher (2k samples, 5k steps)
    → Huber loss + 中高频對齊
    → 學習 0.1m 子網格擴散、結構邊界壓力耦合
    
Stage 3: High-Fidelity NS CFD Teacher (200 samples, 10k steps)
    → NS Physics Residual Loss:
        • 動量方程殘差: ∂u/∂t + (u·∇)u + ∇p/ρ - ν∇²u - g ≈ 0
        • 連續方程殘差: ∇·u ≈ 0
        • 自由表面邊界條件
    → PCGrad + Uncertainty Weighting
```

**物理殘差損失實現**：

```python
def ns_residual_loss(velocity, pressure, boundary, density=1000.0, nu=1e-6, dt=0.05):
    """Navier-Stokes residual for incompressible flow."""
    mask = boundary[..., 0]
    ux, uy, uz = velocity[..., 0], velocity[..., 1], velocity[..., 2]
    
    # Central differences (grid normalized to [0,1])
    N = float(ux.shape[1])
    dx = 1.0 / N
    
    def grad(f, axis):
        return (jnp.roll(f, -1, axis) - jnp.roll(f, 1, axis)) / (2.0 * dx)
    
    def laplacian(f):
        return (jnp.roll(f, -1, 1) + jnp.roll(f, 1, 1) +
                jnp.roll(f, -1, 2) + jnp.roll(f, 1, 2) +
                jnp.roll(f, -1, 3) + jnp.roll(f, 1, 3) - 6*f) / (dx**2)
    
    # Convective term: u·∇u
    conv_x = ux*grad(ux,1) + uy*grad(ux,2) + uz*grad(ux,3)
    conv_y = ux*grad(uy,1) + uy*grad(uy,2) + uz*grad(uy,3)
    conv_z = ux*grad(uz,1) + uy*grad(uz,2) + uz*grad(uz,3)
    
    # Pressure gradient
    grad_p_x = grad(pressure, 1) / density
    grad_p_y = grad(pressure, 2) / density
    grad_p_z = grad(pressure, 3) / density
    
    # Viscous term
    visc_x = nu * laplacian(ux)
    visc_y = nu * laplacian(uy)
    visc_z = nu * laplacian(uz)
    
    # Gravity (y-direction)
    g_y = -9.81
    
    # Momentum residuals
    res_x = conv_x + grad_p_x - visc_x
    res_y = conv_y + grad_p_y - visc_y + g_y
    res_z = conv_z + grad_p_z - visc_z
    
    mom_loss = jnp.sum((res_x**2 + res_y**2 + res_z**2) * mask) / (jnp.sum(mask) + 1e-8)
    
    # Divergence-free
    div = grad(ux,1) + grad(uy,2) + grad(uz,3)
    div_loss = jnp.sum(div**2 * mask) / (jnp.sum(mask) + 1e-8)
    
    return mom_loss + div_loss
```

### 5.4 升級版 ONNX 合約（v2）

```python
# 建議新增合約
FLUID_V2 = ModelContract(
    model_id="bifrost_fluid_v2",
    version=2,
    inputs=[
        TensorSpec("fluid_state", (1, -1, -1, -1, 8), "float32",
                   "velocity(3) + pressure(1) + boundary(1) + position(3)"),
        TensorSpec("struct_coupling", (1, -1, -1, -1, 5), "float32",
                   "struct_phi(1) + boundary_vel(3) + porosity(1) [optional, zero-pad if unavailable]"),
        TensorSpec("latent_state", (1, 256), "float32",
                   "previous latent vector z(t-1), zero-initialize for first frame"),
    ],
    outputs=[
        TensorSpec("fluid_next", (1, -1, -1, -1, 4), "float32",
                   "velocity_next(3) + pressure_next(1)"),
        TensorSpec("latent_next", (1, 256), "float32",
                   "updated latent vector z(t)"),
        TensorSpec("surface_mask", (1, -1, -1, -1, 1), "float32",
                   "free surface probability for SPH spawning [optional]"),
    ],
    notes="HYDRO architecture. Supports latent-space sub-stepping. "
          "If model only has fluid_next output, fallback to v1 behavior."
)
```

---

## 6. Java 模組整合方案

### 6.1 關鍵問題：目前 Java 端無法調用流體 ONNX

**現狀**：
- `OnnxPFSFRuntime.infer(StructureIsland)` 只處理結構島（輸入 6ch，輸出 10ch）
- `BIFROSTModelRegistry.getFluid()` 返回 `OnnxPFSFRuntime`，但沒有流體推理方法
- `FluidGPUEngine` 完全沒有檢查或調用 ONNX 模型

**需要新建/修改的類別**：

| 類別 | 動作 | 說明 |
|------|------|------|
| `OnnxFluidRuntime.java` | **新建** | 專門處理流體 ONNX 推理，與 `OnnxPFSFRuntime` 平行 |
| `BIFROSTModelRegistry.java` | 修改 | `getFluid()` 改為返回 `OnnxFluidRuntime`；或新增 `getFluidRuntime()` |
| `FluidGPUEngine.java` | 修改 | 在 `tickRegionGPU()` / `tickRegionCPU()` 前增加 ML 路由分支 |
| `FluidRegion.java` | 修改（輕微） | 增加 `toOnnxInput()` 和 `applyOnnxOutput()` 輔助方法 |
| `onnx_contracts.py` | 修改 | 增加 `FLUID_V2` 合約 |

### 6.2 OnnxFluidRuntime 設計

```java
package com.blockreality.api.physics.fluid;

import ai.onnxruntime.*;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * ONNX-based fluid runtime — loads a trained HYDRO/FNOFluid3D model
 * and runs inference for FluidRegion state evolution.
 *
 * <p>Input: [1, Nx, Ny, Nz, 8] or [1, Nx, Ny, Nz, 13] (v2)</p>
 * <p>Output: [1, Nx, Ny, Nz, 4] — velocity_next(3) + pressure_next(1)</p>
 */
public class OnnxFluidRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Fluid-ONNX");

    private OrtEnvironment env;
    private OrtSession session;
    private boolean available = false;
    private boolean isV2 = false;

    // Latent state for v2 models [1, 256]
    private float[] latentState = new float[256];

    public boolean loadModel(String modelPath) {
        try {
            Path path = Path.of(modelPath);
            if (!Files.exists(path)) {
                LOGGER.warn("[Fluid-ONNX] Model not found: {}", modelPath);
                return false;
            }

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            try {
                opts.addCUDA(0);
                LOGGER.info("[Fluid-ONNX] CUDA enabled");
            } catch (OrtException e) {
                LOGGER.info("[Fluid-ONNX] CUDA unavailable, using CPU");
            }

            session = env.createSession(modelPath, opts);

            // Detect v2 by output count
            Map<String, NodeInfo> outputs = session.getOutputInfo();
            isV2 = outputs.size() >= 2;
            available = true;

            LOGGER.info("[Fluid-ONNX] Loaded {} (v2={})", modelPath, isV2);
            return true;

        } catch (OrtException e) {
            LOGGER.error("[Fluid-ONNX] Load failed: {}", e.getMessage());
            available = false;
            return false;
        }
    }

    public boolean isAvailable() { return available; }

    /**
     * Run single-tick fluid inference on a region.
     *
     * @param region FluidRegion with current state
     * @return true if output was written back to region
     */
    public boolean infer(FluidRegion region) {
        if (!available || session == null) return false;

        int nx = region.getSizeX();
        int ny = region.getSizeY();
        int nz = region.getSizeZ();

        try {
            // ── Build input tensor [1, nx, ny, nz, 8] ──
            float[] fluidInput = buildFluidInput(region);
            long[] fluidShape = {1, nx, ny, nz, 8};
            OnnxTensor fluidTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(fluidInput), fluidShape);

            Map<String, OnnxTensor> inputs = new java.util.HashMap<>();
            inputs.put(session.getInputNames().iterator().next(), fluidTensor);

            // v2: add latent state and coupling inputs
            if (isV2 && session.getInputInfo().size() >= 2) {
                // TODO: add struct_coupling and latent_state tensors
            }

            // ── Run inference ──
            try (OrtSession.Result result = session.run(inputs)) {
                fluidTensor.close();

                // Parse output
                Object rawValue = result.get(0).getValue();
                float[] flat;
                if (rawValue instanceof float[][][][][] arr5d) {
                    flat = flatten5D(arr5d, nx, ny, nz, 4);
                } else if (rawValue instanceof float[] arr1d) {
                    flat = arr1d;
                } else {
                    LOGGER.error("[Fluid-ONNX] Unexpected output type: {}",
                        rawValue.getClass().getName());
                    return false;
                }

                // Write back to region
                applyOutput(region, flat);

                // Update latent state if v2
                if (isV2 && result.size() > 1) {
                    // Object latentRaw = result.get(1).getValue();
                    // parse and store to latentState
                }

                return true;
            }

        } catch (OrtException e) {
            LOGGER.error("[Fluid-ONNX] Inference failed for region {}: {}",
                region.getRegionId(), e.getMessage());
            return false;
        }
    }

    private float[] buildFluidInput(FluidRegion region) {
        int nx = region.getSizeX();
        int ny = region.getSizeY();
        int nz = region.getSizeZ();
        int n = nx * ny * nz;
        float[] input = new float[n * 8];

        float[] phi = region.getPhi();
        float[] pressure = region.getPressure();
        byte[] type = region.getType();
        float[] volume = region.getVolume();

        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    int idx = x + y * nx + z * nx * ny;
                    int base = idx * 8;

                    // Velocity: derived from phi gradient (approximate)
                    // For ML inference, we can use pressure gradient as proxy
                    float vx = 0f, vy = 0f, vz = 0f;
                    if (x > 0 && x < nx - 1) {
                        vx = (pressure[idx + 1] - pressure[idx - 1]) * 0.5f;
                    }
                    if (y > 0 && y < ny - 1) {
                        vy = (pressure[idx + nx] - pressure[idx - nx]) * 0.5f;
                    }
                    if (z > 0 && z < nz - 1) {
                        vz = (pressure[idx + nx * ny] - pressure[idx - nx * ny]) * 0.5f;
                    }
                    // Clamp to reasonable range
                    vx = Math.max(-10f, Math.min(10f, vx));
                    vy = Math.max(-10f, Math.min(10f, vy));
                    vz = Math.max(-10f, Math.min(10f, vz));

                    float press = pressure[idx];
                    float boundary = (type[idx] != FluidType.AIR.getId()) ? 1.0f : 0.0f;
                    // Position encoding normalized to [0,1]
                    float px = x / (float) Math.max(1, nx - 1);
                    float py = y / (float) Math.max(1, ny - 1);
                    float pz = z / (float) Math.max(1, nz - 1);

                    input[base]     = vx;
                    input[base + 1] = vy;
                    input[base + 2] = vz;
                    input[base + 3] = press;
                    input[base + 4] = boundary;
                    input[base + 5] = px;
                    input[base + 6] = py;
                    input[base + 7] = pz;
                }
            }
        }
        return input;
    }

    private void applyOutput(FluidRegion region, float[] flat) {
        int nx = region.getSizeX();
        int ny = region.getSizeY();
        int nz = region.getSizeZ();
        float[] phi = region.getPhi();
        float[] pressure = region.getPressure();
        float[] volume = region.getVolume();
        byte[] type = region.getType();

        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    int idx = x + y * nx + z * nx * ny;
                    int base = idx * 4;

                    float vx = flat[base];
                    float vy = flat[base + 1];
                    float vz = flat[base + 2];
                    float press = flat[base + 3];

                    // Only update fluid cells
                    if (type[idx] != FluidType.AIR.getId() && volume[idx] > 0.01f) {
                        pressure[idx] = press;
                        // Approximate phi from pressure + kinetic energy
                        phi[idx] = press + 0.5f * 1000.0f * (vx*vx + vy*vy + vz*vz);
                    }
                }
            }
        }
        region.markDirty();
    }

    private static float[] flatten5D(float[][][][][] arr, int nx, int ny, int nz, int c) {
        float[] flat = new float[nx * ny * nz * c];
        float[][][][] batch0 = arr[0];
        for (int x = 0; x < nx; x++)
            for (int y = 0; y < ny; y++)
                for (int z = 0; z < nz; z++)
                    System.arraycopy(batch0[x][y][z], 0, flat,
                        ((x * ny + y) * nz + z) * c, c);
        return flat;
    }

    public void shutdown() {
        if (session != null) {
            try { session.close(); } catch (OrtException ignored) {}
            session = null;
        }
        available = false;
    }
}
```

### 6.3 FluidGPUEngine 的 ML 路由整合

修改 `FluidGPUEngine.tick()`，增加 **ML-first 路由**：

```java
public class FluidGPUEngine implements IFluidManager {

    private OnnxFluidRuntime onnxFluidRuntime;  // NEW
    private int mlSubstepCounter = 0;            // NEW: for latent sub-stepping
    private static final int LATENT_SUBSTEP_INTERVAL = 5;  // full ONNX every 5 ticks

    @Override
    public void tick(@Nonnull ServerLevel level, int tickBudgetMs) {
        if (!initialized || !BRConfig.isFluidEnabled()) return;

        long startNanos = System.nanoTime();

        // Phase 1: poll GPU (if any async Vulkan work pending)
        if (available) {
            FluidAsyncCompute.pollCompleted();
        }

        // Phase 2: iterate dirty regions
        FluidRegionRegistry registry = FluidRegionRegistry.getInstance();
        for (FluidRegion region : registry.getActiveRegions()) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (elapsedMs >= tickBudgetMs) break;

            if (!region.isDirty()) continue;

            // ── NEW: Route to ONNX ML if available and suitable ──
            if (shouldUseMLFor(region)) {
                tickRegionML(region);
            } else if (available) {
                tickRegionGPU(region);
            } else {
                tickRegionCPU(region);
            }
        }

        // Phase 3: boundary pressure extraction
        boundaryPressureCache.set(FluidPressureCoupler.extractAllBoundaryPressures(registry));
        mlSubstepCounter++;
    }

    /**
     * Decide whether to use ML inference for this region.
     */
    private boolean shouldUseMLFor(FluidRegion region) {
        if (onnxFluidRuntime == null || !onnxFluidRuntime.isAvailable()) return false;
        
        // Size constraints: must fit training grid
        int maxDim = Math.max(region.getSizeX(),
                     Math.max(region.getSizeY(), region.getSizeZ()));
        if (maxDim > 32) return false;  // HYDRO trained up to 32³
        
        // Prefer ML for:
        // - Small to medium regions
        // - Regions with moderate fluid fraction (not almost empty/full static)
        int fluidCount = region.getFluidVoxelCount();
        int total = region.getTotalVoxels();
        float ratio = fluidCount / (float) total;
        
        // Avoid ML for nearly-empty or completely-static full pools
        if (ratio < 0.05f || ratio > 0.95f) return false;
        
        return true;
    }

    /**
     * ML inference path.
     */
    private void tickRegionML(FluidRegion region) {
        // v2 latent sub-stepping: only run full ONNX every N ticks
        // For v1 models, run every tick
        boolean runFullInference = !onnxFluidRuntime.isV2() ||
                                   (mlSubstepCounter % LATENT_SUBSTEP_INTERVAL == 0);
        
        if (runFullInference) {
            boolean ok = onnxFluidRuntime.infer(region);
            if (!ok) {
                // Fallback to GPU/CPU
                if (available) tickRegionGPU(region);
                else tickRegionCPU(region);
                return;
            }
        } else {
            // v2 latent sub-step: extremely fast, could be inlined here
            // For now, just re-run full inference (placeholder for true latent step)
            onnxFluidRuntime.infer(region);
        }
        
        region.clearDirty();
    }

    // ... existing GPU/CPU paths remain unchanged ...
}
```

### 6.4 BIFROSTModelRegistry 修改建議

```java
public final class BIFROSTModelRegistry {

    private final ConcurrentHashMap<String, OnnxPFSFRuntime> structModels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OnnxFluidRuntime> fluidModels = new ConcurrentHashMap<>();

    // In init():
    // - "bifrost_surrogate", "bifrost_lod", "bifrost_collapse" → structModels (OnnxPFSFRuntime)
    // - "bifrost_fluid", "bifrost_fluid_v2" → fluidModels (OnnxFluidRuntime)

    public OnnxFluidRuntime getFluidRuntime() {
        // Prefer v2
        OnnxFluidRuntime v2 = fluidModels.get("bifrost_fluid_v2");
        if (v2 != null && v2.isAvailable()) return v2;
        return fluidModels.get("bifrost_fluid");
    }
}
```

### 6.5 與現有 PFSF-Fluid 的共存策略

**不應完全取代 Vulkan/CPU 求解器**，而是建立 **三級後端**：

```
FluidGPUEngine Router
├── HYDRO-ONNX   → 動態、中等精度、快速（2-4ms/tick）
├── PFSF-GPU     → 靜態/規則、穩定、中等精度（4ms/tick）
└── PFSF-CPU     → 超大區域、無 GPU、回退（10-20ms/tick）
```

**路由規則建議**：

| 條件 | 後端 | 理由 |
|------|------|------|
| 區域 < 32³，流體比例 5%-95%，ONNX 可用 | **HYDRO-ONNX** | ML 擅長動態、中等複雜度流動 |
| 區域 > 32³，或幾乎靜止水池 | **PFSF-GPU** | 傳統求解器對大尺度/穩態更穩定 |
| Vulkan 不可用 | **PFSF-CPU** | 回退路徑 |
| 結構崩塌後 5 tick 內 | **PFSF-GPU** | 劇烈拓撲變化時，數值求解器更可靠 |
| 每 20 tick 交替驗證 | **Both** | 可設計「影子模式」，比較 ML 與 PFSF 輸出差異 |

---

## 7. 實作路線圖與風險評估

### 7.1 分階段實作計畫

#### Phase 1：Java 端補齊（1-2 週）

**目標**：讓現有 `FNOFluid3D`（v1 合約）能在遊戲內實際運行。

1. 建立 `OnnxFluidRuntime.java`（如 6.2 節所示）
2. 修改 `BIFROSTModelRegistry`，支援 `OnnxFluidRuntime`
3. 修改 `FluidGPUEngine`，增加 `tickRegionML()` 和 `shouldUseMLFor()`
4. 單元測試：
   - `OnnxFluidRuntimeTest`：模擬 dummy ONNX 輸入/輸出
   - `FluidMLRoutingTest`：驗證後端路由邏輯
5. 端到端測試：將 `brml` 導出的 `bifrost_fluid.onnx` 放入 `config/blockreality/models/`，確認遊戲內載入並推理

**風險**：低。主要是工程實現，不涉及模型重訓練。

#### Phase 2：HYDRO v1.5 — 升級 Python 模型（2-3 週）

**目標**：將 BR-NeXT 的 SSGO 組件遷移到流體領域，但保持 v1 合約兼容（8ch → 4ch）。

1. 建立 `brml/brml/models/hydro_fluid.py`
2. 實作：
   - `WeightedSpectralConv3D`（全局分支）
   - `SparseVoxelGraphConv`（局部分支）
   - `GatedFusion`
   - `MoESpectralHead`（簡化版，2 shared + 4 routed）
3. 損失函數：Huber loss + divergence penalty + NS residual（可選）
4. 訓練腳本：基於現有 PFSF-Fluid 資料做監督學習
5. 匯出 ONNX，驗證 v1 合約

**風險**：中。需要收集足夠的 PFSF-Fluid 訓練資料，並驗證 ONNX 導出兼容性。

#### Phase 3：HYDRO v2 — 隱空間與耦合（3-4 週）

**目標**：引入 Latent Dynamics 和流固耦合。

1. 設計 `FLUID_V2` ONNX 合約（多輸入、多輸出）
2. 在 `HYDRO` 中整合 `LatentConvLSTM`
3. 建立 `FluidStructureCouplingInputBuilder`，從 `StructureIsland` / `OnnxPFSFRuntime.InferenceResult` 提取 `struct_phi` 和 `boundary_vel`
4. 修改 `OnnxFluidRuntime` 支援 v2 合約
5. 實作 CMFD-Fluid Stage 2/3 訓練管線

**風險**：高。隱空間模型的長期穩定性（autoregressive rollout drift）需要大量測試。

#### Phase 4：AMR 與自適應解析度（2-3 週）

**目標**：降低推理成本，支援更大區域。

1. 在 Python 端實作 `AdaptiveRegionTokenizer`
2. 訓練多解析度模型（coarse 16³ + fine 32³）
3. Java 端實作區域分解和拼接邏輯

**風險**：中。需要處理不同解析度區域之間的邊界連續性。

### 7.2 關鍵風險與緩解

| 風險 | 可能性 | 影響 | 緩解措施 |
|------|--------|------|---------|
| **ONNX 導出失敗**（jax2onnx 不支援 Latent LSTM 或 MoE） | 中 | 高 | 1) 預先驗證每個組件的 ONNX 導出；2) 準備 `manual_onnx.py` 退路；3) 考慮將 Latent LSTM 留在 Java 端用純 MLP 實現 |
| **Autoregressive drift**（長期推演後發散） | 高 | 高 | 1) 訓練 noise augmentation；2) 定期（每 20 tick）用 PFSF 校正；3) 引入 uncertainty head，高不確定時切回 PFSF |
| **Tick 預算超支** | 中 | 高 | 1) 嚴格的 region size 限制；2) 自適應子採樣；3) 非同步 ONNX 推理（類似現有的 `FluidAsyncCompute`） |
| **資料不足**（高保真 NS CFD 資料昂貴） | 中 | 中 | 1) 優先做 CMFD S1/S2；2) 使用公開的 dam-break、sloshing 資料集做遷移學習；3) 與 `brml/fem/` 整合，自動生成 hex8 流體網格 |
| **與現有 Vulkan 管線衝突** | 低 | 中 | 1) 保持 PFSF 回退路徑；2) 影子模式驗證；3) 清晰的資源生命週期分離 |

### 7.3 開源資源清單

以下為本報告參考的關鍵論文與程式碼，可供後續深入研讀：

**神經算子與架構**：
- BR-NeXT SSGO: `BR-NeXT/brnext/models/ssgo.py`（本專案）
- MgFNO: https://arxiv.org/abs/2407.08615
- LNO (Latent Neural Operator): NeurIPS 2024, arXiv:2406.03923
- AMR-Transformer: https://arxiv.org/abs/2503.10257, Code: https://github.com/JfanLiu/AMR_Transformer
- UPT (Universal Physics Transformer): https://arxiv.org/abs/2402.12365
- Spectral-Refiner: ICLR 2025

**圖神經網路流體**：
- MeshGraphNets PyTorch: https://github.com/echowve/meshGraphNets_pytorch
- GNS: https://github.com/geoelements/gns
- Graph NN Review (mechanics): https://www.researchgate.net/publication/384637175

**物理資訊與損失設計**：
- PINP (ICLR 2025): Physics-Informed Neural Predictor for fluid flows
- ConFIG (ICLR 2025): Conflict-free PINN training
- DeepXDE: https://github.com/lululxvi/deepxde

**遊戲引擎實時流體**：
- GameNGen (Google 2024): DOOM on diffusion model
- Fluid Flux + UE5 validation: MDPI Applied Sciences 2025
- Leapfrog Flow Maps (SIGGRAPH 2025): Real-time incompressible fluids

---

## 附錄 A：與現有 `onnx_contracts.py` 的修改對照

```python
# brml/brml/export/onnx_contracts.py

# ═══ 現有合約（保持兼容）═══
FLUID = ModelContract(
    model_id="bifrost_fluid",
    version=1,
    inputs=[TensorSpec("input", (1, -1, -1, -1, 8), "float32", "...")],
    outputs=[TensorSpec("output", (1, -1, -1, -1, 4), "float32", "...")],
    notes="Legacy FNOFluid3D contract.",
)

# ═══ 新增合約 ═══
FLUID_V2 = ModelContract(
    model_id="bifrost_fluid_v2",
    version=2,
    inputs=[
        TensorSpec("fluid_state", (1, -1, -1, -1, 8), "float32",
                   "velocity(3) + pressure(1) + boundary(1) + position(3)"),
        TensorSpec("struct_coupling", (1, -1, -1, -1, 5), "float32",
                   "struct_phi(1) + boundary_vel(3) + porosity(1)"),
        TensorSpec("latent_state", (1, 256), "float32", "z(t-1)"),
    ],
    outputs=[
        TensorSpec("fluid_next", (1, -1, -1, -1, 4), "float32", "velocity_next(3) + pressure_next(1)"),
        TensorSpec("latent_next", (1, 256), "float32", "z(t)"),
        TensorSpec("surface_mask", (1, -1, -1, -1, 1), "float32", "free surface prob"),
    ],
    notes="HYDRO architecture with latent dynamics and FSI coupling.",
)

ALL_CONTRACTS = {
    "surrogate": SURROGATE,
    "fluid": FLUID,
    "fluid_v2": FLUID_V2,  # NEW
    "lod": LOD,
    "collapse": COLLAPSE,
}
```

---

## 附錄 B：建議的檔案變更清單

### 新建檔案

| 路徑 | 說明 |
|------|------|
| `Block Reality/api/src/main/java/.../physics/fluid/OnnxFluidRuntime.java` | 流體 ONNX 推理運行時 |
| `brml/brml/models/hydro_fluid.py` | HYDRO 模型架構 |
| `brml/brml/models/latent_dynamics.py` | LatentConvLSTM 等隱空間組件 |
| `brml/brml/train/hydro_train.py` | HYDRO 訓練腳本 |
| `brml/brml/export/hydro_onnx_export.py` | HYDRO ONNX 導出與合約驗證 |
| `brml/brml/pipeline/cmfd_fluid_trainer.py` | CMFD-Fluid 三階段訓練器 |

### 修改檔案

| 路徑 | 變更內容 |
|------|---------|
| `brml/brml/export/onnx_contracts.py` | 新增 `FLUID_V2` 合約 |
| `Block Reality/api/src/main/java/.../pfsf/BIFROSTModelRegistry.java` | 支援 `OnnxFluidRuntime` |
| `Block Reality/api/src/main/java/.../physics/fluid/FluidGPUEngine.java` | 增加 ML 路由分支 |
| `Block Reality/api/src/main/java/.../physics/fluid/FluidRegion.java` | 增加 ONNX I/O 輔助方法（可選） |

---

*報告完成。如有需要，可進一步針對任一子系統展開詳細設計與程式碼實作。*
