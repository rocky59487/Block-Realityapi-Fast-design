"""
config.py — reborn-ml 訓練超參數設定

純 ML 訓練設定，移除 Minecraft / Forge 相關參數。

使用方式：
    from reborn_ml.config import TrainingConfig, A100_TRAINING_CONFIG
"""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Literal


@dataclass
class SimPConfig:
    """SIMP 拓撲最佳化參數"""
    p_simp: float = 3.0
    vol_frac: float = 0.40
    r_min: float = 1.5
    x_min: float = 1e-3
    max_iter: int = 50
    tol: float = 0.01
    move: float = 0.2
    bisect_tol: float = 1e-4


@dataclass
class StyleConfig:
    """風格皮膚參數"""
    mode: Literal["gaudi", "zaha", "none"] = "gaudi"
    gaudi_arch_strength: float = 0.6
    gaudi_smin_k: float = 0.3
    gaudi_column_stress_thresh: float = 0.7
    zaha_flow_alpha: float = 0.4
    stress_path_min_length: int = 4


@dataclass
class TrainingConfig:
    """
    Reborn v2 訓練管線設定。

    預設值針對單卡 A100（80GB）最佳化，
    預估 ~3 小時完成 13,000 步。
    """
    # --- 內嵌子設定 ---
    simp: SimPConfig = field(default_factory=SimPConfig)
    style: StyleConfig = field(default_factory=StyleConfig)

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
    style_sdf_channels: int = 1
    style_alpha_init: float = 0.3

    # --- 四階段訓練排程 ---
    stage1_steps: int = 3000
    stage2_steps: int = 3000
    stage3_steps: int = 5000
    stage4_steps: int = 2000
    enable_adversarial: bool = True
    batch_size: int = 4
    peak_lr: float = 1e-3
    warmup_steps: int = 300
    weight_decay: float = 1e-4
    grad_clip: float = 1.0
    disc_lr: float = 4e-4
    adv_weight: float = 0.1
    gd_ratio: int = 5

    # --- 資料管線 ---
    grid_size: int = 16
    train_samples: int = 2000
    cache_dir: str = "reborn_ml_cache"
    use_cache: bool = True
    n_fem_workers: int = 10
    prefetch_buffer: int = 4

    # --- 評估 ---
    eval_interval: int = 500
    n_eval_samples: int = 50

    # --- A100 最佳化 ---
    use_pmap: bool = False
    jit_compile: bool = True

    # --- 全域 ---
    verbose: bool = False
    seed: int = 42
    checkpoint_dir: str = "reborn_ml_checkpoints"
    save_every: int = 1000


# ---------------------------------------------------------------------------
# 預設快捷方式
# ---------------------------------------------------------------------------
DEFAULT_TRAINING_CONFIG = TrainingConfig()

A100_TRAINING_CONFIG = TrainingConfig(
    verbose=True,
    grid_size=16,
    stage1_steps=3000,
    stage2_steps=3000,
    stage3_steps=5000,
    stage4_steps=2000,
    enable_adversarial=True,
    batch_size=4,
    peak_lr=1e-3,
)
