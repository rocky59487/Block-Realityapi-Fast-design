"""
Cross-package staged-model availability test.

Verifies that models produced after each training stage are valid for forward inference.
"""
from __future__ import annotations

import sys
from pathlib import Path

# Ensure monorepo packages are importable (now under ml/)
ROOT = Path(__file__).resolve().parent.parent.parent
for pkg in ["reborn-ml/src", "HYBR", "BR-NeXT", "brml"]:
    p = str(ROOT / pkg)
    if p not in sys.path:
        sys.path.insert(0, p)

import jax
import jax.numpy as jnp
import numpy as np

# =============================================================================
# Helpers
# =============================================================================

def _dummy_fem(stress, disp):
    class _F:
        pass
    f = _F()
    f.stress = stress
    f.displacement = disp
    return f


# =============================================================================
# 1. BR-NeXT CMFD — 3 stages
# =============================================================================

def test_brnext_cmfd_stages():
    print("\n[BR-NeXT] Testing CMFD 3-stage model availability...")
    from brnext.pipeline.cmfd_trainer import CMFDTrainer, CMFDConfig
    from brnext.pipeline.structure_gen import generate_structure
    from brnext.pipeline.async_data_loader import AsyncBuffer
    import brnext.pipeline.async_data_loader as async_mod

    cfg = CMFDConfig(
        grid_size=8,
        stage1_steps=2,
        stage2_steps=2,
        stage3_steps=2,
        stage1_samples=10,
        stage2_samples=4,
        stage3_samples=4,
        batch_size=1,
        lr=1e-3,
        hidden=8,
        modes=2,
        n_global_layers=1,
        n_focal_layers=1,
        n_backbone_layers=1,
        moe_hidden=8,
        output_dir="brnext_checkpoints/_test_stages",
        cache_dir="brnext_checkpoints/_test_stages/cache",
        use_cache=False,
        augment=False,
    )
    trainer = CMFDTrainer(cfg, on_log=lambda m: None)

    L = cfg.grid_size
    dummy_x = jnp.zeros((1, L, L, L, 7))

    # ---- Stage 1 ----
    params_s1, model = trainer._stage1_lea()
    out_s1 = model.apply({"params": params_s1}, dummy_x)
    assert out_s1.shape == (1, L, L, L, 10), f"S1 shape mismatch: {out_s1.shape}"
    print("  Stage 1 (LEA) model forward: OK")

    # Mock AsyncBuffer for Stage 2 & 3
    np_rng = np.random.default_rng(cfg.seed)
    struct = generate_structure(L, np_rng, "tower")
    phi_mock = np.ones((L, L, L), dtype=np.float32) * 0.01
    fem_mock = _dummy_fem(
        np.ones((L, L, L, 6), dtype=np.float32) * 0.1,
        np.ones((L, L, L, 3), dtype=np.float32) * 0.05,
    )

    class MockBuf:
        def __init__(self, samples):
            self.buffer = samples
            self._exhausted = True
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def __len__(self): return len(self.buffer)
        def poll(self, **k): pass
        def prefetch(self, **k): pass
        def sample(self, rng, n=1):
            idxs = rng.integers(0, len(self.buffer), size=n)
            return [self.buffer[i] for i in idxs]

    original_async_buffer = async_mod.AsyncBuffer

    # ---- Stage 2 ----
    async_mod.AsyncBuffer = lambda *a, **kw: MockBuf([(struct, phi_mock)] * 4)
    try:
        params_s2, model = trainer._stage2_pfsf(params_s1, model)
        out_s2 = model.apply({"params": params_s2}, dummy_x)
        assert out_s2.shape == (1, L, L, L, 10), f"S2 shape mismatch: {out_s2.shape}"
        print("  Stage 2 (PFSF) model forward: OK")
    finally:
        async_mod.AsyncBuffer = original_async_buffer

    # ---- Stage 3 ----
    async_mod.AsyncBuffer = lambda *a, **kw: MockBuf([(struct, fem_mock, phi_mock)] * 4)
    try:
        params_s3, model, hist = trainer._stage3_fem(params_s2, model)
        out_s3 = model.apply({"params": params_s3}, dummy_x)
        assert out_s3.shape == (1, L, L, L, 10), f"S3 shape mismatch: {out_s3.shape}"
        print("  Stage 3 (FEM) model forward: OK")
    finally:
        async_mod.AsyncBuffer = original_async_buffer

    print("[BR-NeXT] All CMFD stages OK")


# =============================================================================
# 2. HYBR Meta-Trainer
# =============================================================================

def test_hybr_stages():
    print("\n[HYBR] Testing HYBR meta-trainer model availability...")
    from hybr.training.meta_trainer import HYBRTrainer, HYBRConfig
    from hybr.core.materialize import materialize_static_ssgo
    from brnext.pipeline.structure_gen import generate_structure

    cfg = HYBRConfig(
        grid_size=8,
        train_samples=4,
        train_steps=2,
        batch_size=2,
        peak_lr=1e-3,
        hidden=8,
        modes=2,
        n_global_layers=1,
        n_focal_layers=1,
        n_backbone_layers=1,
        moe_hidden=8,
        latent_dim=8,
        hypernet_widths=(16, 16),
        rank=2,
        output_dir="hybr_output/_test_stages",
        use_cache=False,
    )
    trainer = HYBRTrainer(cfg, on_log=lambda m: None)

    # Inject mock dataset directly
    L = cfg.grid_size
    np_rng = np.random.default_rng(cfg.seed)
    mock_ds = []
    for _ in range(cfg.train_samples):
        struct = generate_structure(L, np_rng, "tower")
        fem = _dummy_fem(
            np.zeros((L, L, L, 6), dtype=np.float32),
            np.zeros((L, L, L, 3), dtype=np.float32),
        )
        phi = np.zeros((L, L, L), dtype=np.float32)
        mock_ds.append((struct, fem, phi))
    trainer._build_dataset = lambda styles: mock_ds

    params, model, history = trainer.run()

    dummy_x = jnp.zeros((1, L, L, L, 6))
    # Adaptive forward
    out = model.apply({"params": params, "batch_stats": {}}, dummy_x, update_stats=False)
    assert out.shape == (1, L, L, L, 10), f"HYBR adaptive forward shape mismatch: {out.shape}"
    print("  HYBR adaptive model forward: OK")

    # Materialized static forward
    occ = jnp.ones((1, L, L, L))
    static_params, static_model = materialize_static_ssgo(
        model, {"params": params, "batch_stats": {}}, occ
    )
    out_static = static_model.apply({"params": static_params}, dummy_x)
    assert out_static.shape == (1, L, L, L, 10), f"HYBR static forward shape mismatch: {out_static.shape}"
    print("  HYBR materialized static model forward: OK")
    print("[HYBR] All stages OK")


# =============================================================================
# 3. reborn-ml — 4 stages
# =============================================================================

def test_reborn_stages():
    print("\n[reborn-ml] Testing RebornStyleTrainer 4-stage model availability...")
    from reborn_ml.config import TrainingConfig
    from reborn_ml.training.style_trainer import RebornStyleTrainer

    cfg = TrainingConfig(
        grid_size=8,
        stage1_steps=2,
        stage2_steps=2,
        stage3_steps=2,
        stage4_steps=2,
        enable_adversarial=True,
        train_samples=4,
        verbose=False,
        hidden_channels=8,
        fno_modes=2,
        n_global_layers=1,
        n_focal_layers=1,
        n_backbone_layers=1,
        moe_hidden=8,
        latent_dim=8,
        hypernet_widths=(16, 16),
        cp_rank=2,
    )

    class StageChecker(RebornStyleTrainer):
        def _stage1_physics(self, params, model, dataset, history):
            params = super()._stage1_physics(params, model, dataset, history)
            self._verify_forward(params, model, "Stage 1")
            return params

        def _stage2_style(self, params, model, dataset, history):
            params = super()._stage2_style(params, model, dataset, history)
            self._verify_forward(params, model, "Stage 2")
            return params

        def _stage3_joint(self, params, model, dataset, history):
            params, log_sigma = super()._stage3_joint(params, model, dataset, history)
            self._verify_forward(params, model, "Stage 3")
            return params, log_sigma

        def _stage4_adversarial(self, params, model, dataset, history, log_sigma=None):
            params = super()._stage4_adversarial(params, model, dataset, history, log_sigma)
            self._verify_forward(params, model, "Stage 4")
            return params

        def _verify_forward(self, params, model, tag):
            L = self.cfg.grid_size
            dummy_x = jnp.zeros((1, L, L, L, 6))
            dummy_sid = jnp.array([0])
            out = model.apply({"params": params}, dummy_x, dummy_sid, update_stats=False)
            assert out.shape == (1, L, L, L, 11), f"{tag} shape mismatch: {out.shape}"
            print(f"  {tag} model forward: OK")

    trainer = StageChecker(cfg, on_log=lambda m: None)
    params, model, history = trainer.run()

    # Final forward sanity check
    dummy_x = jnp.zeros((1, cfg.grid_size, cfg.grid_size, cfg.grid_size, 6))
    out = model.apply({"params": params}, dummy_x, jnp.array([0]), update_stats=False)
    assert out.shape == (1, cfg.grid_size, cfg.grid_size, cfg.grid_size, 11)
    print("[reborn-ml] All 4 stages OK")


# =============================================================================
# Main
# =============================================================================

if __name__ == "__main__":
    test_brnext_cmfd_stages()
    test_hybr_stages()
    test_reborn_stages()
    print("\n" + "=" * 60)
    print("ALL STAGED-MODEL AVAILABILITY TESTS PASSED")
    print("=" * 60)
