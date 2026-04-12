"""
style_trainer.py — RebornStyleTrainer 四階段風格條件化訓練器

A100 級訓練管線，遵循 CMFD（BR-NeXT）已驗證的級聯課程方法：

  階段 1：物理預訓練（LEA — 低頻能量對齊）
    - 凍���風格嵌入，僅訓練物理骨幹
    - 損失：freq_align_loss(低頻) + physics_residual_loss

  階段 2：風格條���化蒸餾
    - 解��風格嵌入 + SDF 頭，base 降至 0.1x lr
    - 損失：style_consistency + spectral_style_fid
    - 教師：分析式 GaudiStyle/ZahaStyle（CPU ~10ms/樣本）

  階段 3：聯合微調
    - 全部參數可訓練，7 任務不確定性加權
    - 損失：reborn_total_loss（應力+位移+phi+一致性+物理+風格L1+頻譜FID）
    - optax.multi_transform 分離學習率

  階段 4：對抗���修（可選）
    - 新增 StyleDiscriminator，交替 G/D 更新（5:1）
    - 損失：7 任務 + 0.1 × hinge adversarial

重用基礎設施（匯��，不重建）：
  - HYBR/hybr/training/stability_utils.py → make_optimizer_with_warmup
  - BR-NeXT/brnext/models/losses.py → freq_align_loss, hybrid_task_loss
  - BR-NeXT/brnext/pipeline/async_data_loader.py → AsyncBuffer
  - brml/brml/train/trainer.py → pmap 支援

參考：
  BR-NeXT/brnext/pipeline/cmfd_trainer.py — 三階段級��訓練範式
  HYBR/hybr/training/meta_trainer.py — 超網路訓練模式
"""
from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Callable

import numpy as np

# Dependencies resolved by reborn_ml.__init__ bootstrap


class RebornStyleTrainer:
    """四階段風格條件化訓練器。

    使用方式：
        from reborn_ml.config import TrainingConfig
        from reborn_ml.training.style_trainer import RebornStyleTrainer

        config = TrainingConfig(grid_size=16, stage1_steps=3000)
        trainer = RebornStyleTrainer(config)
        params, model, history = trainer.run()

    Attributes:
        cfg:      TrainingConfig 訓練設定
        on_log:   日誌回調函式
        on_step:  每步回調函式（step, loss, metrics）
        tracker:  MLflow 實驗追蹤器（可選）
    """

    def __init__(
        self,
        cfg,
        on_log: Callable[[str], None] | None = None,
        on_step: Callable[[int, float, dict], None] | None = None,
        tracker=None,
    ):
        from reborn_ml.config import TrainingConfig
        self.cfg = cfg if isinstance(cfg, TrainingConfig) else TrainingConfig()
        self.on_log = on_log or print
        self.on_step = on_step or (lambda step, loss, metrics: None)
        self.tracker = tracker
        self._stop = False

        self.output_dir = Path(self.cfg.checkpoint_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def stop(self):
        """外部停止信號。"""
        self._stop = True

    def _log(self, msg: str):
        self.on_log(msg)

    # -----------------------------------------------------------------------
    # 主入口
    # -----------------------------------------------------------------------

    def run(self) -> tuple:
        """執行四階段訓練。回傳 (params, model, history)。"""
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state

        from reborn_ml.models.style_net import StyleConditionedSSGO, StyleDiscriminator
        from hybr.training.stability_utils import make_optimizer_with_warmup

        self._log("═══ Reborn StyleTrainer ═══")
        self._log(f"設定：grid={self.cfg.grid_size}, "
                  f"steps={self.cfg.stage1_steps}+{self.cfg.stage2_steps}"
                  f"+{self.cfg.stage3_steps}+{self.cfg.stage4_steps}")

        if self.tracker:
            self.tracker.start_run(run_name="reborn_style")
            self.tracker.log_params(self.cfg.__dict__)

        # ── 建立資料集 ──
        dataset = self._build_dataset()

        # ── 建立模型 ──
        model = self._build_model()
        rng = jax.random.PRNGKey(self.cfg.seed)
        L = self.cfg.grid_size
        dummy_x = jnp.zeros((1, L, L, L, 6))
        dummy_sid = jnp.array([0])
        variables = model.init(rng, dummy_x, dummy_sid, update_stats=False)
        params = variables["params"]

        history = {"stage1": [], "stage2": [], "stage3": [], "stage4": []}

        # ── 階段 1：物理預訓練 ──
        t0 = time.time()
        params = self._stage1_physics(params, model, dataset, history)
        if self._stop:
            return params, model, history
        self._log(f"  階段 1 完成，��時 {time.time()-t0:.0f}s")

        # ── 階段 2：風格條件化 ──
        t0 = time.time()
        params = self._stage2_style(params, model, dataset, history)
        if self._stop:
            return params, model, history
        self._log(f"  階段 2 完成，耗時 {time.time()-t0:.0f}s")

        # ── 階段 3：聯合微調 ──
        t0 = time.time()
        params, log_sigma_s3 = self._stage3_joint(params, model, dataset, history)
        if self._stop:
            return params, model, history
        self._log(f"  階段 3 完成，耗時 {time.time()-t0:.0f}s")

        # ── 階段 4：對抗精修 ──
        if self.cfg.enable_adversarial and self.cfg.stage4_steps > 0:
            t0 = time.time()
            params = self._stage4_adversarial(params, model, dataset, history, log_sigma_s3)
            self._log(f"  階段 4 完成，耗時 {time.time()-t0:.0f}s")

        self._log("═══ 訓練完成 ═══")
        if self.tracker:
            self.tracker.end_run()

        # 存檔最終模型
        self._save_checkpoint(params, "final")

        return params, model, history

    # -----------------------------------------------------------------------
    # 模型與資料建構
    # -----------------------------------------------------------------------

    def _build_model(self):
        """從設定建構 StyleConditionedSSGO。"""
        from reborn_ml.models.style_net import StyleConditionedSSGO
        return StyleConditionedSSGO(
            hidden=self.cfg.hidden_channels,
            modes=self.cfg.fno_modes,
            n_global_layers=self.cfg.n_global_layers,
            n_focal_layers=self.cfg.n_focal_layers,
            n_backbone_layers=self.cfg.n_backbone_layers,
            moe_hidden=self.cfg.moe_hidden,
            latent_dim=self.cfg.latent_dim,
            hypernet_widths=self.cfg.hypernet_widths,
            rank=self.cfg.cp_rank,
            n_styles=self.cfg.n_styles,
            encoder_type=self.cfg.encoder_type,
            style_alpha_init=self.cfg.style_alpha_init,
        )

    def _build_dataset(self) -> list:
        """建構訓練資料集。"""
        self._log("\n--- 建構訓練資料集 ---")
        try:
            from reborn_ml.training.data_pipeline import RebornDataPipeline
            pipeline = RebornDataPipeline(self.cfg)
            dataset = pipeline.build_dataset()
            self._log(f"  資料集大小：{len(dataset)}")
            return dataset
        except Exception as e:
            self._log(f"  資料管線失敗 ({e})，使用 Mock 資料集")
            return self._make_mock_dataset()

    def _make_mock_dataset(self) -> list:
        """Mock 資料集（CPU 測試用）。"""
        import jax.numpy as jnp
        L = self.cfg.grid_size
        rng = np.random.default_rng(self.cfg.seed)
        dataset = []
        for i in range(min(self.cfg.train_samples, 50)):
            occ = (rng.random((L, L, L)) > 0.4).astype(np.float32)
            occ[:, 0, :] = 1.0  # 底部固體
            x = np.stack([
                occ,
                np.full((L, L, L), 0.15, dtype=np.float32),  # E_norm
                np.full((L, L, L), 0.2, dtype=np.float32),   # nu
                np.full((L, L, L), 0.3, dtype=np.float32),   # rho_norm
                np.full((L, L, L), 0.12, dtype=np.float32),  # rc_norm
                np.full((L, L, L), 0.05, dtype=np.float32),  # rt_norm
            ], axis=-1)
            target = np.zeros((L, L, L, 10), dtype=np.float32)
            target[..., 1] = -np.cumsum(occ, axis=1) * 0.01
            style_sdf = np.zeros((L, L, L), dtype=np.float32)
            sid = rng.integers(0, self.cfg.n_styles)
            dataset.append((x, target, style_sdf, int(sid)))
        return dataset

    # -----------------------------------------------------------------------
    # 參數標記（用於 multi_transform）
    # -----------------------------------------------------------------------

    @staticmethod
    def _label_params(params: dict) -> dict:
        """標記參數群組：base / hyper / style。"""
        import jax
        def _label_leaf(path, _):
            path_str = "/".join(str(p.key) for p in path)
            if "StyleEmbedding" in path_str or "style_alpha" in path_str:
                return "style"
            elif "HyperMLP" in path_str or "SpectralWeightHead" in path_str:
                return "hyper"
            else:
                return "base"
        return jax.tree_util.tree_map_with_path(_label_leaf, params)

    # -----------------------------------------------------------------------
    # 階段 1：物理預訓練
    # -----------------------------------------------------------------------

    def _stage1_physics(self, params, model, dataset, history):
        """物理預訓練：freq_align_loss + physics_residual。"""
        self._log("\n═══ 階段 1：物理預訓練 ═══")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state
        from brnext.models.losses import freq_align_loss, physics_residual_loss
        from hybr.training.stability_utils import make_optimizer_with_warmup

        # 凍結風格參數（lr=0）
        mask = self._label_params(params)
        tx = optax.multi_transform(
            {
                "base": make_optimizer_with_warmup(
                    peak_lr=self.cfg.peak_lr,
                    warmup_steps=self.cfg.warmup_steps,
                    total_steps=self.cfg.stage1_steps,
                    weight_decay=self.cfg.weight_decay,
                ),
                "hyper": make_optimizer_with_warmup(
                    peak_lr=self.cfg.peak_lr,
                    warmup_steps=self.cfg.warmup_steps,
                    total_steps=self.cfg.stage1_steps,
                    weight_decay=self.cfg.weight_decay,
                ),
                "style": optax.set_to_zero(),  # 凍結
            },
            mask,
        )
        state = train_state.TrainState.create(
            apply_fn=lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False),
            params=params,
            tx=tx,
        )

        @jax.jit
        def train_step(state, xb, sid, target):
            def loss_fn(p):
                pred = state.apply_fn(p, xb, sid)
                mask = xb[..., 0]  # [B,L,L,L]
                E = xb[..., 1] * 200e9   # E_norm → Pa
                nu = xb[..., 2]

                # LEA：低頻頻譜對齊（主損失）
                l_freq = freq_align_loss(
                    pred[..., :6], target[..., :6], mask, band="low"
                )
                # 物理殘差（平衡 + 相容）— 小權重避免早期主導
                # 等效於 Navier-Cauchy 方程的弱形式
                l_phys = physics_residual_loss(
                    pred[..., :6], pred[..., 6:9], E, nu, mask
                )
                return l_freq + 0.05 * l_phys
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        n = len(dataset)
        for step in range(1, self.cfg.stage1_steps + 1):
            if self._stop:
                break
            idx = step % n
            x, target, _, sid = dataset[idx]
            xb = jnp.array(x)[None]
            tb = jnp.array(target)[None]
            sid_b = jnp.array([sid])
            state, loss = train_step(state, xb, sid_b, tb)
            loss_val = float(loss)
            history["stage1"].append(loss_val)
            self.on_step(step, loss_val, {"stage": 1})
            if step % 500 == 0:
                self._log(f"  [S1 {step}/{self.cfg.stage1_steps}] loss={loss_val:.6f}")
                if self.tracker:
                    self.tracker.log_metrics({"S1_loss": loss_val}, step=step)

        return state.params

    # -----------------------------------------------------------------------
    # 階段 2：風格條件化
    # -----------------------------------------------------------------------

    def _stage2_style(self, params, model, dataset, history):
        """風格蒸餾：style_consistency + spectral_style_fid。"""
        self._log("\n═══ 階段 2：風格條件化蒸餾 ═══")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state
        from hybr.training.stability_utils import make_optimizer_with_warmup
        from reborn_ml.training.losses import style_consistency_loss, spectral_style_fid

        mask = self._label_params(params)
        tx = optax.multi_transform(
            {
                "base": make_optimizer_with_warmup(
                    peak_lr=self.cfg.peak_lr * 0.1,  # base 降低
                    warmup_steps=self.cfg.warmup_steps,
                    total_steps=self.cfg.stage2_steps,
                    weight_decay=self.cfg.weight_decay,
                ),
                "hyper": make_optimizer_with_warmup(
                    peak_lr=self.cfg.peak_lr * 0.1,
                    warmup_steps=self.cfg.warmup_steps,
                    total_steps=self.cfg.stage2_steps,
                    weight_decay=self.cfg.weight_decay,
                ),
                "style": make_optimizer_with_warmup(
                    peak_lr=self.cfg.peak_lr,  # ���格全速
                    warmup_steps=self.cfg.warmup_steps,
                    total_steps=self.cfg.stage2_steps,
                    weight_decay=self.cfg.weight_decay,
                ),
            },
            mask,
        )
        state = train_state.TrainState.create(
            apply_fn=lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False),
            params=params,
            tx=tx,
        )

        @jax.jit
        def train_step(state, xb, sid, style_target):
            def loss_fn(p):
                pred = state.apply_fn(p, xb, sid)
                pred_sdf = pred[..., 10]  # [B,L,L,L]
                occ_mask = xb[..., 0]
                l_con = style_consistency_loss(
                    pred_sdf, style_target, occ_mask
                )
                l_fid = spectral_style_fid(
                    pred_sdf[None] if pred_sdf.ndim == 3 else pred_sdf,
                    style_target[None] if style_target.ndim == 3 else style_target,
                )
                return l_con + 0.5 * l_fid
            loss, grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=grads), loss

        n = len(dataset)
        for step in range(1, self.cfg.stage2_steps + 1):
            if self._stop:
                break
            idx = step % n
            x, _, style_target, sid = dataset[idx]
            xb = jnp.array(x)[None]
            st = jnp.array(style_target)[None] if np.ndim(style_target) == 3 else jnp.array(style_target)
            sid_b = jnp.array([sid])
            state, loss = train_step(state, xb, sid_b, st)
            loss_val = float(loss)
            history["stage2"].append(loss_val)
            self.on_step(step, loss_val, {"stage": 2})
            if step % 500 == 0:
                self._log(f"  [S2 {step}/{self.cfg.stage2_steps}] loss={loss_val:.6f}")
                if self.tracker:
                    self.tracker.log_metrics({"S2_loss": loss_val}, step=step)

        return state.params

    # -----------------------------------------------------------------------
    # 階段 3：聯合微調
    # -----------------------------------------------------------------------

    def _stage3_joint(self, params, model, dataset, history):
        """7 任務不確定性加權聯合微調。"""
        self._log("\n═══ 階段 3：聯合微調 ═══")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state
        from hybr.training.stability_utils import make_optimizer_with_warmup
        from reborn_ml.training.losses import reborn_total_loss

        # 7 任務不確定性加權
        all_params = {
            "model": params,
            "log_sigma": jnp.zeros(7),
        }

        schedule = optax.warmup_cosine_decay_schedule(
            0.0, self.cfg.peak_lr,
            warmup_steps=min(500, self.cfg.stage3_steps // 10),
            decay_steps=self.cfg.stage3_steps,
            end_value=self.cfg.peak_lr * 0.01,
        )

        # 為 log_sigma 分配獨立優化器：
        #   - 無 weight_decay（weight_decay 對 log_sigma 的 L2 懲罰 wd·σ
        #     會把 σ 偏壓回 1，限制 Kendall 自適應範圍，違反理論）
        #   - 較低學習率（log_sigma 只有 7 個純量，收斂比模型快）
        def _param_labels(all_p):
            def _label(path, _):
                top = str(path[0].key) if hasattr(path[0], "key") else str(path[0])
                return "log_sigma" if top == "log_sigma" else "model"
            return jax.tree_util.tree_map_with_path(_label, all_p)

        param_labels = _param_labels(all_params)
        tx = optax.multi_transform(
            {
                "model": optax.chain(
                    optax.clip_by_global_norm(self.cfg.grad_clip),
                    optax.adamw(schedule, weight_decay=self.cfg.weight_decay),
                ),
                # log_sigma：純 Adam，無 weight_decay，LR = 0.1x model
                "log_sigma": optax.chain(
                    optax.clip_by_global_norm(1.0),
                    optax.adam(self.cfg.peak_lr * 0.1),
                ),
            },
            param_labels,
        )
        state = train_state.TrainState.create(
            apply_fn=lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False),
            params=all_params,
            tx=tx,
        )

        @jax.jit
        def train_step(state, xb, sid, target, style_target):
            # 對 all_params = {"model": ..., "log_sigma": ...} 聯合求梯度
            # 這樣 log_sigma 的 Kendall 不確定性梯度才能正確反向傳播：
            #   d/dσ_i [ L_i·exp(-2σ_i)/2 + σ_i ] = 1 - L_i·exp(-2σ_i)
            def loss_fn(all_p):
                mp_ = all_p["model"]
                log_s = all_p["log_sigma"]
                pred = model.apply({"params": mp_}, xb, sid, update_stats=False)
                mask = xb[..., 0]
                E = xb[..., 1] * 200e9
                nu = xb[..., 2]
                rho = xb[..., 3] * 7850.0
                fem_trust = jnp.ones_like(mask)

                total, _ = reborn_total_loss(
                    pred, target, mask,
                    pred[..., 10], style_target,
                    E, nu, rho, fem_trust, log_s,
                )
                return total

            total, all_grads = jax.value_and_grad(loss_fn)(state.params)
            return state.apply_gradients(grads=all_grads), total

        n = len(dataset)
        for step in range(1, self.cfg.stage3_steps + 1):
            if self._stop:
                break
            idx = step % n
            x, target, style_target, sid = dataset[idx]
            xb = jnp.array(x)[None]
            tb = jnp.array(target)[None]
            st = jnp.array(style_target)[None] if np.ndim(style_target) == 3 else jnp.array(style_target)
            sid_b = jnp.array([sid])
            state, loss = train_step(state, xb, sid_b, tb, st)
            loss_val = float(loss)
            history["stage3"].append(loss_val)
            self.on_step(step, loss_val, {"stage": 3})
            if step % 500 == 0:
                self._log(f"  [S3 {step}/{self.cfg.stage3_steps}] loss={loss_val:.6f}")
                if self.tracker:
                    self.tracker.log_metrics({"S3_loss": loss_val}, step=step)

        return state.params["model"], state.params["log_sigma"]

    # -----------------------------------------------------------------------
    # 階段 4：對抗精修
    # -----------------------------------------------------------------------

    def _stage4_adversarial(self, params, model, dataset, history, log_sigma=None):
        """對抗精修：StyleDiscriminator + hinge loss + 物理/風格聯合損失。

        生成器損失 = reborn_total_loss（7 任務，以 Stage 3 的 log_sigma 固定）
                    + adv_weight * hinge_G_loss
        固定 log_sigma 防止 Stage 4 的 GAN 梯度重新拉偏不確定性估計。
        """
        self._log("\n═══ 階段 4：對抗精修 ═══")
        import jax
        import jax.numpy as jnp
        import optax
        from flax.training import train_state
        from reborn_ml.models.style_net import StyleDiscriminator
        from reborn_ml.training.losses import adversarial_loss, reborn_total_loss

        # 若無 Stage 3 log_sigma，預設全零（σ=1，各任務等權）
        import numpy as _np
        _log_sigma_fixed = jnp.array(log_sigma if log_sigma is not None else _np.zeros(7))

        # 建構判別器
        L = self.cfg.grid_size
        disc = StyleDiscriminator(hidden=32, n_styles=self.cfg.n_styles)
        rng = jax.random.PRNGKey(self.cfg.seed + 100)
        dummy_sdf = jnp.zeros((1, L, L, L, 1))
        dummy_sid = jnp.array([0])
        disc_vars = disc.init(rng, dummy_sdf, dummy_sid)
        disc_params = disc_vars["params"]

        # 分離優化器
        g_tx = optax.chain(
            optax.clip_by_global_norm(self.cfg.grad_clip),
            optax.adam(self.cfg.peak_lr * 0.2),
        )
        d_tx = optax.chain(
            optax.clip_by_global_norm(self.cfg.grad_clip),
            optax.adam(self.cfg.disc_lr),
        )
        g_state = train_state.TrainState.create(
            apply_fn=lambda p, x, s: model.apply({"params": p}, x, s, update_stats=False),
            params=params,
            tx=g_tx,
        )
        d_state = train_state.TrainState.create(
            apply_fn=lambda p, sdf, s: disc.apply({"params": p}, sdf, s),
            params=disc_params,
            tx=d_tx,
        )

        @jax.jit
        def d_step(d_state, g_params, xb, sid, style_target):
            pred = model.apply({"params": g_params}, xb, sid, update_stats=False)
            fake_sdf = pred[..., 10:11]  # [B,L,L,L,1]
            real_sdf = style_target[..., None] if style_target.ndim == 4 else style_target

            def d_loss_fn(dp):
                d_real = disc.apply({"params": dp}, real_sdf, sid)
                d_fake = disc.apply({"params": dp}, fake_sdf, sid)
                d_loss, _ = adversarial_loss(d_real, d_fake)
                return d_loss

            loss, grads = jax.value_and_grad(d_loss_fn)(d_state.params)
            return d_state.apply_gradients(grads=grads), loss

        adv_weight = self.cfg.adv_weight

        @jax.jit
        def g_step(g_state, d_params, xb, sid, target, style_target, log_sigma_fixed):
            """生成器更新：7 任務物理/風格損失 + adv_weight * hinge_G。

            Stage 3 的 log_sigma 作為固定常數傳入（非可訓練），
            防止 GAN 梯度重新拉偏不確定性估計（catastrophic forgetting）。
            """
            def g_loss_fn(gp):
                pred = model.apply({"params": gp}, xb, sid, update_stats=False)
                fake_sdf = pred[..., 10:11]

                # 7 任務物理 + 風格損失（使用 Stage 3 固定 log_sigma）
                mask = xb[..., 0]
                E = xb[..., 1] * 200e9
                nu = xb[..., 2]
                rho = xb[..., 3] * 7850.0
                fem_trust = jnp.ones_like(mask)
                physics_loss, _ = reborn_total_loss(
                    pred, target, mask,
                    pred[..., 10], style_target,
                    E, nu, rho, fem_trust, log_sigma_fixed,
                )

                # Hinge 生成器損失：-E[D(fake)]
                d_fake = disc.apply({"params": d_params}, fake_sdf, sid)
                _, g_adv = adversarial_loss(jnp.zeros_like(d_fake), d_fake)

                return physics_loss + adv_weight * g_adv

            loss, grads = jax.value_and_grad(g_loss_fn)(g_state.params)
            return g_state.apply_gradients(grads=grads), loss

        n = len(dataset)
        for step in range(1, self.cfg.stage4_steps + 1):
            if self._stop:
                break
            idx = step % n
            x, target, style_target, sid = dataset[idx]  # 包含 target（物理標籤）
            xb = jnp.array(x)[None]
            tb = jnp.array(target)[None]
            st = jnp.array(style_target)[None] if np.ndim(style_target) == 3 else jnp.array(style_target)
            sid_b = jnp.array([sid])

            # 判別器更新
            d_state, d_loss = d_step(d_state, g_state.params, xb, sid_b, st)

            # 生成器更新（每 gd_ratio 步一次）
            g_loss_val = 0.0
            if step % self.cfg.gd_ratio == 0:
                g_state, g_loss = g_step(
                    g_state, d_state.params, xb, sid_b, tb, st, _log_sigma_fixed
                )
                g_loss_val = float(g_loss)

            history["stage4"].append(float(d_loss))
            self.on_step(step, float(d_loss), {"stage": 4, "g_loss": g_loss_val})
            if step % 500 == 0:
                self._log(f"  [S4 {step}/{self.cfg.stage4_steps}] D={float(d_loss):.4f} G={g_loss_val:.4f}")
                if self.tracker:
                    self.tracker.log_metrics(
                        {"S4_d_loss": float(d_loss), "S4_g_loss": g_loss_val}, step=step
                    )

        return g_state.params

    # -----------------------------------------------------------------------
    # 存檔
    # -----------------------------------------------------------------------

    def _save_checkpoint(self, params, tag: str = "latest"):
        """存檔模型參數為 numpy .npz（以 JAX path 作為鍵）。"""
        import jax
        # tree_leaves_with_path 回傳 [(KeyPath, leaf), ...] 列表
        path_leaf_pairs = jax.tree_util.tree_leaves_with_path(params)
        save_dict = {}
        for kpath, leaf in path_leaf_pairs:
            # 每個 key entry 有 .key 屬性（DictKey）或 .idx（SequenceKey）
            name = "/".join(
                str(k.key) if hasattr(k, "key") else str(k.idx)
                for k in kpath
            )
            # npz 不允許 '/' 作為鍵，改用 '.'
            name = name.replace("/", ".")
            save_dict[name] = np.array(leaf)
        out_path = self.output_dir / f"reborn_style_{tag}.npz"
        np.savez(out_path, **save_dict)
        self._log(f"  存檔：{out_path}")
