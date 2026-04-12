"""
test_smoke.py — reborn-ml 冒煙測試

在無 GPU 的 CPU 環境中驗證包的基本功能：
  - 包可正常匯入（sys.path 自動設定）
  - TrainingConfig 可實例化
  - 10 步訓練迴圈完成不報錯
  - 輸出形狀正確 [1, 8, 8, 8, 11]
"""
import numpy as np
import pytest


def test_import():
    """包可正常匯入。"""
    import reborn_ml
    assert reborn_ml.__version__ == "0.2.0"


def test_config():
    """TrainingConfig 可實例化，預設值合理。"""
    from reborn_ml.config import TrainingConfig, A100_TRAINING_CONFIG
    cfg = TrainingConfig()
    assert cfg.grid_size == 16
    assert cfg.stage3_steps == 5000
    assert cfg.n_styles == 4
    assert A100_TRAINING_CONFIG.stage1_steps == 3000


@pytest.mark.skipif(
    not __import__("importlib").util.find_spec("jax"),
    reason="JAX not installed",
)
def test_model_forward():
    """StyleConditionedSSGO 前向傳播形狀正確。"""
    import jax
    import jax.numpy as jnp
    from reborn_ml.models.style_net import StyleConditionedSSGO

    model = StyleConditionedSSGO(hidden=8, modes=2, n_global_layers=1,
                                  n_focal_layers=1, n_backbone_layers=1)
    rng = jax.random.PRNGKey(42)
    x = jnp.zeros((1, 8, 8, 8, 6))
    sid = jnp.array([0])

    variables = model.init(rng, x, sid, update_stats=False)
    out = model.apply(variables, x, sid, update_stats=False)

    assert out.shape == (1, 8, 8, 8, 11), f"期望 (1,8,8,8,11)，得到 {out.shape}"


@pytest.mark.skipif(
    not __import__("importlib").util.find_spec("jax"),
    reason="JAX not installed",
)
def test_training_10_steps():
    """10 步訓練迴圈完成，損失為有限值。"""
    from reborn_ml.config import TrainingConfig
    from reborn_ml.training.style_trainer import RebornStyleTrainer

    cfg = TrainingConfig(
        grid_size=8,
        stage1_steps=3,
        stage2_steps=3,
        stage3_steps=4,
        stage4_steps=0,
        enable_adversarial=False,
        train_samples=10,
        verbose=False,
    )
    trainer = RebornStyleTrainer(cfg)
    params, model, history = trainer.run()

    for stage in ["stage1", "stage2", "stage3"]:
        losses = history[stage]
        assert len(losses) > 0, f"{stage} 無損失記錄"
        assert all(np.isfinite(l) for l in losses), f"{stage} 出現 NaN/Inf"


@pytest.mark.skipif(
    not __import__("importlib").util.find_spec("jax"),
    reason="JAX not installed",
)
def test_losses_no_nan():
    """reborn_total_loss 不產生 NaN。"""
    import jax
    import jax.numpy as jnp
    from reborn_ml.training.losses import reborn_total_loss

    B, L = 1, 4
    pred     = jnp.zeros((B, L, L, L, 11))
    target   = jnp.zeros((B, L, L, L, 10))
    mask     = jnp.ones((B, L, L, L))
    style_sdf = jnp.zeros((B, L, L, L))
    E   = jnp.ones((B, L, L, L)) * 200e9
    nu  = jnp.ones((B, L, L, L)) * 0.3
    rho = jnp.ones((B, L, L, L)) * 7850.0
    fem_trust = jnp.ones((B, L, L, L))
    log_sigma = jnp.zeros(7)

    total, metrics = reborn_total_loss(pred, target, mask, style_sdf, style_sdf, E, nu, rho, fem_trust, log_sigma)
    assert np.isfinite(float(total)), f"total_loss = {total}"
    assert all(np.isfinite(float(v)) for v in metrics.values())
