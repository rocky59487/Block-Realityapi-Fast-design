"""
test_style_training.py — StyleConditionedSSGO 訓練管線單元測試

pytest 執行：
    cd "Block Reality/experimental"
    python -m pytest reborn/tests/test_style_training.py -v

注意：需要 JAX/Flax 環境。測試使用最小網格（4³~8³）和極少步數。
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
import pytest

# JAX 可能不可用 — 跳過測試
jax = pytest.importorskip("jax")
jnp = pytest.importorskip("jax.numpy")
nn = pytest.importorskip("flax.linen")


# -----------------------------------------------------------------------
# diff_sdf_ops 測試
# -----------------------------------------------------------------------

class TestDiffSDFOps:
    def test_density_to_sdf_interior(self):
        """密實體素應產生負 SDF"""
        from reborn.models.diff_sdf_ops import density_to_sdf_diff
        density = jnp.ones((8, 8, 8))
        sdf = density_to_sdf_diff(density, iso=0.5)
        assert sdf[4, 4, 4] < 0, f"內部 SDF 應 < 0，得 {sdf[4,4,4]}"

    def test_smooth_union_commutative(self):
        """平滑聯集應近似交換律"""
        from reborn.models.diff_sdf_ops import smooth_union
        key = jax.random.PRNGKey(0)
        a = jax.random.normal(key, (6, 6, 6))
        b = jax.random.normal(jax.random.PRNGKey(1), (6, 6, 6))
        u_ab = smooth_union(a, b, k=0.3)
        u_ba = smooth_union(b, a, k=0.3)
        assert jnp.allclose(u_ab, u_ba, atol=1e-5)

    def test_sdf_differentiable(self):
        """density_to_sdf_diff 應可通過 jax.grad"""
        from reborn.models.diff_sdf_ops import density_to_sdf_diff
        def loss_fn(d):
            sdf = density_to_sdf_diff(d, iso=0.5, sigma=0.5)
            return jnp.mean(sdf ** 2)
        density = jnp.ones((4, 4, 4)) * 0.7
        grad = jax.grad(loss_fn)(density)
        assert not jnp.isnan(grad).any(), "梯度含 NaN"
        assert grad.shape == density.shape


# -----------------------------------------------------------------------
# StyleConditionedSSGO 測試
# -----------------------------------------------------------------------

class TestStyleConditionedSSGO:
    @pytest.fixture
    def small_model(self):
        from reborn.models.style_net import StyleConditionedSSGO
        return StyleConditionedSSGO(
            hidden=8, modes=2, n_global_layers=1,
            n_focal_layers=1, n_backbone_layers=1,
            moe_hidden=8, latent_dim=8,
            hypernet_widths=(16,), rank=1, n_styles=4,
        )

    def test_output_shape(self, small_model):
        """輸出應為 [B, L, L, L, 11]"""
        L = 4
        x = jnp.zeros((1, L, L, L, 6))
        sid = jnp.array([1])
        params = small_model.init(jax.random.PRNGKey(0), x, sid, update_stats=False)
        out = small_model.apply(params, x, sid, update_stats=False)
        assert out.shape == (1, L, L, L, 11), f"期望 (1,4,4,4,11)，得 {out.shape}"

    def test_different_styles_produce_different_outputs(self, small_model):
        """不同風格代碼應產生不同輸出"""
        L = 4
        x = jnp.ones((1, L, L, L, 6)) * 0.5
        x = x.at[..., 0].set(1.0)  # 佔用 = 1
        params = small_model.init(jax.random.PRNGKey(0), x, jnp.array([0]), update_stats=False)

        out_gaudi = small_model.apply(params, x, jnp.array([1]), update_stats=False)
        out_zaha = small_model.apply(params, x, jnp.array([2]), update_stats=False)
        # 至少 style_sdf 通道（第 11）應不同
        diff = jnp.abs(out_gaudi[..., 10] - out_zaha[..., 10]).mean()
        assert diff > 0, "不同風格的 SDF 輸出應不同"

    def test_no_nan_in_output(self, small_model):
        """輸出不應有 NaN"""
        L = 4
        x = jnp.ones((1, L, L, L, 6)) * 0.5
        x = x.at[..., 0].set(1.0)
        sid = jnp.array([0])
        params = small_model.init(jax.random.PRNGKey(42), x, sid, update_stats=False)
        out = small_model.apply(params, x, sid, update_stats=False)
        assert not jnp.isnan(out).any(), "輸出含 NaN"

    def test_gradient_flows(self, small_model):
        """梯度應能通過模型反向傳播"""
        L = 4
        x = jnp.ones((1, L, L, L, 6)) * 0.5
        x = x.at[..., 0].set(1.0)
        sid = jnp.array([1])
        params = small_model.init(jax.random.PRNGKey(0), x, sid, update_stats=False)

        def loss_fn(p):
            out = small_model.apply({"params": p}, x, sid, update_stats=False)
            return jnp.mean(out ** 2)

        grads = jax.grad(loss_fn)(params["params"])
        # 檢查至少一個梯度不為零
        leaves = jax.tree_util.tree_leaves(grads)
        has_nonzero = any(jnp.abs(g).max() > 0 for g in leaves)
        assert has_nonzero, "所有梯度為零"
        # 檢查無 NaN
        has_nan = any(jnp.isnan(g).any() for g in leaves)
        assert not has_nan, "梯度含 NaN"


# -----------------------------------------------------------------------
# StyleDiscriminator 測試
# -----------------------------------------------------------------------

class TestStyleDiscriminator:
    def test_output_shape(self):
        from reborn.models.style_net import StyleDiscriminator
        disc = StyleDiscriminator(hidden=8, n_styles=4, latent_dim=8)
        sdf = jnp.zeros((2, 4, 4, 4, 1))
        sid = jnp.array([0, 1])
        params = disc.init(jax.random.PRNGKey(0), sdf, sid)
        out = disc.apply(params, sdf, sid)
        assert out.shape == (2, 1), f"判別器輸出應為 (2,1)，得 {out.shape}"


# -----------------------------------------------------------------------
# 損失函數測試
# -----------------------------------------------------------------------

class TestLosses:
    def test_style_consistency_loss(self):
        from reborn.training.losses import style_consistency_loss
        pred = jnp.ones((4, 4, 4)) * 0.5
        teacher = jnp.ones((4, 4, 4)) * 0.3
        mask = jnp.ones((4, 4, 4))
        loss = style_consistency_loss(pred, teacher, mask)
        expected = 0.2  # |0.5 - 0.3|
        assert abs(float(loss) - expected) < 0.01

    def test_adversarial_loss(self):
        from reborn.training.losses import adversarial_loss
        real = jnp.ones((4, 1)) * 2.0
        fake = jnp.ones((4, 1)) * -2.0
        d_loss, g_loss = adversarial_loss(real, fake)
        assert float(d_loss) >= 0, "判別器損失應 >= 0"

    def test_reborn_total_loss_no_nan(self):
        """reborn_total_loss 不應回傳 NaN"""
        from reborn.training.losses import reborn_total_loss
        B, L = 1, 4
        pred = jnp.zeros((B, L, L, L, 11))
        target = jnp.zeros((B, L, L, L, 10))
        mask = jnp.ones((B, L, L, L))
        style_pred = jnp.zeros((B, L, L, L))
        style_teacher = jnp.zeros((B, L, L, L))
        E = jnp.ones((B, L, L, L)) * 30e9
        nu = jnp.ones((B, L, L, L)) * 0.2
        rho = jnp.ones((B, L, L, L)) * 2400
        fem_trust = jnp.ones((B, L, L, L))
        log_sigma = jnp.zeros(7)

        total, metrics = reborn_total_loss(
            pred, target, mask, style_pred, style_teacher,
            E, nu, rho, fem_trust, log_sigma,
        )
        assert not jnp.isnan(total), "總損失為 NaN"
        assert jnp.isfinite(total), "總損失不有限"


# -----------------------------------------------------------------------
# DiffGaudiStyle / DiffZahaStyle 測試
# -----------------------------------------------------------------------

class TestDiffStyles:
    def test_diff_gaudi_output_shape(self):
        from reborn.models.diff_gaudi import DiffGaudiStyle
        model = DiffGaudiStyle()
        B, L = 1, 6
        density = jnp.ones((B, L, L, L)) * 0.7
        stress = jnp.zeros((B, L, L, L, 6))
        stress = stress.at[..., 1].set(-10.0)
        style_sdf = jnp.zeros((B, L, L, L, 1))
        params = model.init(jax.random.PRNGKey(0), density, stress, style_sdf)
        out = model.apply(params, density, stress, style_sdf)
        assert out.shape == (B, L, L, L)

    def test_diff_zaha_output_shape(self):
        from reborn.models.diff_zaha import DiffZahaStyle
        model = DiffZahaStyle()
        B, L = 1, 6
        density = jnp.ones((B, L, L, L)) * 0.7
        stress = jnp.zeros((B, L, L, L, 6))
        style_sdf = jnp.zeros((B, L, L, L, 1))
        params = model.init(jax.random.PRNGKey(0), density, stress, style_sdf)
        out = model.apply(params, density, stress, style_sdf)
        assert out.shape == (B, L, L, L)


# -----------------------------------------------------------------------
# Evaluator 測試
# -----------------------------------------------------------------------

class TestEvaluator:
    def test_pareto_score_non_negative(self):
        from reborn.training.evaluator import RebornEvaluator
        evaluator = RebornEvaluator()
        metrics = {
            "compliance_ratio": 0.5,
            "physics_residual": 0.1,
            "style_consistency": 0.2,
            "spectral_fid": 0.05,
            "sdf_smoothness": 0.01,
        }
        score = evaluator._compute_pareto(metrics)
        assert score >= 0, "Pareto 分數應 >= 0"
