"""
RebornConfig — Reborn 生成式建築設計引擎全域超參數。

所有階段的參數集中於此，方便實驗調整。
"""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Literal


@dataclass
class SimPConfig:
    """第二階段：SIMP 拓撲最佳化參數"""
    # 懲罰指數 p（Sigmund 2001 建議 p=3）
    p_simp: float = 3.0
    # 目標體積分率（保留 40% 材料）
    vol_frac: float = 0.40
    # 敏感度濾波半徑（體素）
    r_min: float = 1.5
    # 最小密度（防止矩陣奇異）
    x_min: float = 1e-3
    # 最大迭代次數（硬性上限，避免長時間運算）
    max_iter: int = 50
    # 收斂閾值（密度最大變化量）
    tol: float = 0.01
    # OC 更新步長限制
    move: float = 0.2
    # OC 二分搜尋精度
    bisect_tol: float = 1e-4


@dataclass
class StyleConfig:
    """第三階段：風格皮膚參數"""
    # 風格模式：gaudi / zaha / none
    mode: Literal["gaudi", "zaha", "none"] = "gaudi"
    # Gaudi — 懸鏈拱強度係數
    gaudi_arch_strength: float = 0.6
    # Gaudi — 平滑聯集 k 值（越大越平滑）
    gaudi_smin_k: float = 0.3
    # Gaudi — 雙曲面柱啟用閾值（主壓應力 > 此值才生成）
    gaudi_column_stress_thresh: float = 0.7
    # Zaha — 流線變形強度
    zaha_flow_alpha: float = 0.4
    # 主應力路徑提取：最小連續長度（體素數）
    stress_path_min_length: int = 4


@dataclass
class NurbsConfig:
    """第四階段：NURBS 輸出參數"""
    # SDF 等值面閾值
    iso_threshold: float = 0.5
    # 表面平滑度（0.0 = 體素直接輸出，1.0 = 完全平滑）
    smoothing: float = 0.6
    # SDF 次體素解析度乘數（1–4，指數級記憶體消耗）
    resolution: int = 2
    # NurbsExporter sidecar 埠號
    sidecar_port: int = 7890
    # 連線逾時（秒）
    sidecar_timeout: float = 30.0
    # 輸出目錄（None = 使用 experiments/outputs/）
    output_dir: str | None = None


@dataclass
class HYBRConfig:
    """HYBR 代理模型推論參數（可選精修路徑）"""
    # 是否啟用 HYBR 精修（需要 ONNX 模型檔）
    enabled: bool = False
    # HYBR 模型權重目錄
    weights_dir: str = "HYBR/weights"
    # 風格潛在向量維度
    style_dim: int = 8
    # CP 分解秩（rank）
    cp_rank: int = 4
    # AdaptiveSSGO FNO 模式數
    fno_modes: int = 8
    # AdaptiveSSGO 隱藏通道數
    hidden_channels: int = 32


@dataclass
class FNOProxyConfig:
    """FNO 應力代理模型參數"""
    # ONNX 模型檔路徑（None = 嘗試自動定位）
    onnx_path: str | None = None
    # 推論後端：onnxruntime-cpu / onnxruntime-gpu / mock
    backend: Literal["onnxruntime-cpu", "onnxruntime-gpu", "mock"] = "onnxruntime-cpu"
    # 正規化常數（與 OnnxPFSFRuntime.java 一致）
    E_SCALE: float = 200e9      # 楊氏模量 (Pa)
    RHO_SCALE: float = 7850.0   # 密度 (kg/m³)，鋼材
    RC_SCALE: float = 250.0     # 壓縮強度 (MPa)
    RT_SCALE: float = 500.0     # 拉伸強度 (MPa)


@dataclass
class RebornConfig:
    """
    Reborn 生成式建築設計引擎主設定。

    使用方式：
        cfg = RebornConfig()                    # 預設值
        cfg = RebornConfig(simp=SimPConfig(vol_frac=0.3))  # 自訂
    """
    # --- 各階段設定 ---
    simp: SimPConfig = field(default_factory=SimPConfig)
    style: StyleConfig = field(default_factory=StyleConfig)
    nurbs: NurbsConfig = field(default_factory=NurbsConfig)
    hybr: HYBRConfig = field(default_factory=HYBRConfig)
    fno: FNOProxyConfig = field(default_factory=FNOProxyConfig)

    # --- 全域設定 ---
    # 詳細日誌輸出
    verbose: bool = False
    # 實驗輸出根目錄
    output_root: str = "Block Reality/experimental/reborn/experiments/outputs"
    # 隨機種子（保證可重現性）
    seed: int = 42


# ---------------------------------------------------------------------------
# 訓練設定（A100 級訓練管線）
# ---------------------------------------------------------------------------

@dataclass
class TrainingConfig(RebornConfig):
    """
    Reborn v2 訓練管線設定。

    擴展 RebornConfig，新增 StyleConditionedSSGO 訓練所需的全部超參數。
    預設值針對單卡 A100（80GB）最佳化，預估 ~3 小時完成 13,000 步。
    """
    # --- 模型架構 ---
    hidden_channels: int = 48
    fno_modes: int = 6
    n_global_layers: int = 3
    n_focal_layers: int = 2
    n_backbone_layers: int = 2
    moe_hidden: int = 32
    latent_dim: int = 32
    hypernet_widths: tuple = (128, 128)
    cp_rank: int = 2
    encoder_type: str = "spectral"
    n_styles: int = 4               # raw(0), gaudi(1), zaha(2), hybrid(3)
    style_sdf_channels: int = 1     # 額外 SDF 輸出通道
    style_alpha_init: float = 0.3   # 風格擾動初始強度

    # --- 四階段訓練排程 ---
    stage1_steps: int = 3000        # 物理預訓練（LEA）
    stage2_steps: int = 3000        # 風格條件化蒸餾
    stage3_steps: int = 5000        # 聯合微調（7 任務不確定性加權）
    stage4_steps: int = 2000        # 對抗精修（可選）
    enable_adversarial: bool = True
    batch_size: int = 4
    peak_lr: float = 1e-3
    warmup_steps: int = 300
    weight_decay: float = 1e-4
    grad_clip: float = 1.0
    disc_lr: float = 4e-4           # 判別器學習率
    adv_weight: float = 0.1         # 對抗損失權重
    gd_ratio: int = 5               # 生成器/判別器更新比

    # --- 資料管線 ---
    grid_size: int = 16
    train_samples: int = 2000
    cache_dir: str = "brnext_output/cache"
    use_cache: bool = True
    n_fem_workers: int = 10         # AsyncBuffer 工作執行緒數
    prefetch_buffer: int = 4

    # --- 評估 ---
    eval_interval: int = 500
    n_eval_samples: int = 50

    # --- A100 最佳化 ---
    use_pmap: bool = False          # 自動偵測多 GPU
    jit_compile: bool = True

    # --- 存檔 ---
    checkpoint_dir: str = "Block Reality/experimental/reborn/checkpoints"
    save_every: int = 1000


# ---------------------------------------------------------------------------
# 預設配置快捷方式
# ---------------------------------------------------------------------------
DEFAULT_CONFIG = RebornConfig()

# 論文基準配置（用於 exp_001~005）
PAPER_CONFIG = RebornConfig(
    simp=SimPConfig(p_simp=3.0, vol_frac=0.40, max_iter=50),
    style=StyleConfig(mode="gaudi"),
    nurbs=NurbsConfig(smoothing=0.6, resolution=2),
    verbose=True,
)

# A100 訓練配置（用於 exp_006~008）
A100_TRAINING_CONFIG = TrainingConfig(
    simp=SimPConfig(p_simp=3.0, vol_frac=0.40, max_iter=50),
    style=StyleConfig(mode="gaudi"),
    verbose=True,
    grid_size=16,
    stage1_steps=3000,
    stage2_steps=3000,
    stage3_steps=5000,
    stage4_steps=2000,
)
