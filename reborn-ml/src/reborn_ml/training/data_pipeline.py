"""
data_pipeline.py — Reborn ML 訓練資料管線

負責組裝 (input_6ch, physics_target_10ch, style_sdf_target, style_id) 訓練元組。

資料格式：
  input_6ch:            float32[L,L,L,6]  — [occ, E/E_SCALE, nu, rho/RHO_SCALE, rc/RC_SCALE, rt/RT_SCALE]
  physics_target_10ch:  float32[L,L,L,10] — [σ_xx..τ_xz(6), u_x..u_z(3), φ(1)]
  style_sdf_target:     float32[L,L,L]    — 風格化 SDF
  style_id:             int               — 風格代碼 (0=raw, 1=gaudi, 2=zaha, 3=hybrid)

FEM 管線不可用時自動降級為 Mock 資料集（程序化結構 + 解析自重應力）。
"""
from __future__ import annotations

import numpy as np

from reborn_ml.config import TrainingConfig

# 正規化常數
try:
    from brnext.config import load_norm_constants
    _NORM = load_norm_constants()
except Exception:
    _NORM = {}
E_SCALE  = float(_NORM.get("E_SCALE",  200e9))
RHO_SCALE = float(_NORM.get("RHO_SCALE", 7850.0))
RC_SCALE  = float(_NORM.get("RC_SCALE",  250.0))
RT_SCALE  = float(_NORM.get("RT_SCALE",  500.0))

STYLE_IDS: dict[str, int] = {"raw": 0, "gaudi": 1, "zaha": 2, "hybrid": 3}


class RebornDataPipeline:
    """
    Reborn v2 風格條件化訓練資料管線。

    使用方式：
        cfg = TrainingConfig(train_samples=500, grid_size=16)
        pipeline = RebornDataPipeline(cfg)
        dataset = pipeline.build_dataset()
    """

    def __init__(self, config: TrainingConfig):
        self.config = config
        self._verbose = config.verbose

    def build_dataset(self) -> list[tuple]:
        """建構訓練資料集。"""
        # 嘗試真實 FEM 管線
        try:
            raw_samples = self._collect_fem_samples()
        except Exception as e:
            if self._verbose:
                print(f"[DataPipeline] FEM 管線失敗 ({e})，切換至 Mock")
            raw_samples = []

        if len(raw_samples) == 0:
            if self._verbose:
                print("[DataPipeline] 使用 Mock 資料集")
            return self._make_mock_dataset(self.config.train_samples)

        dataset: list[tuple] = []
        for sample in raw_samples:
            struct = sample[0]
            fem = sample[1] if len(sample) > 2 else None
            phi = sample[-1]

            input_6ch = self._build_input(struct)
            target_10ch = self._build_target(struct, fem, phi)
            density = getattr(struct, "density_field", struct.occupancy.astype(np.float32))
            stress_voigt = target_10ch[..., :6]

            for style_name, style_id in STYLE_IDS.items():
                style_sdf = self._generate_style_target(density, stress_voigt, style_name)
                dataset.append((input_6ch, target_10ch, style_sdf, style_id))

        return dataset

    def _build_input(self, struct) -> np.ndarray:
        occ = struct.occupancy.astype(np.float32)
        E = struct.E_field.astype(np.float32) / E_SCALE
        nu = struct.nu_field.astype(np.float32)
        rho = struct.density_field.astype(np.float32) / RHO_SCALE
        rc = struct.rcomp_field.astype(np.float32) / RC_SCALE
        rt = struct.rtens_field.astype(np.float32) / RT_SCALE
        return np.stack([occ, E, nu, rho, rc, rt], axis=-1)

    def _build_target(self, struct, fem, phi) -> np.ndarray:
        shape = struct.occupancy.shape
        target = np.zeros((*shape, 10), dtype=np.float32)
        if fem is not None:
            if hasattr(fem, "stress_voigt"):
                target[..., :6] = fem.stress_voigt.astype(np.float32)
            if hasattr(fem, "displacement"):
                target[..., 6:9] = fem.displacement.astype(np.float32)
        if phi is not None:
            target[..., 9] = phi.astype(np.float32)
        return target

    def _generate_style_target(
        self,
        density: np.ndarray,
        stress_voigt: np.ndarray,
        style_name: str,
    ) -> np.ndarray:
        """生成風格 SDF 教師目標。優先使用解析風格模型，失敗則退為原始 SDF。"""
        try:
            if style_name == "gaudi":
                from reborn.models.gaudi_style import GaudiStyle
                return GaudiStyle(verbose=False).apply(density, stress_voigt)
            elif style_name == "zaha":
                from reborn.models.zaha_style import ZahaStyle
                return ZahaStyle(verbose=False).apply(density, stress_voigt)
            elif style_name == "hybrid":
                from reborn.models.gaudi_style import GaudiStyle
                from reborn.models.zaha_style import ZahaStyle
                sg = GaudiStyle(verbose=False).apply(density, stress_voigt)
                sz = ZahaStyle(verbose=False).apply(density, stress_voigt)
                return (0.5 * sg + 0.5 * sz).astype(np.float32)
        except ImportError:
            pass
        # 退回：gaussian smoothed density as raw SDF proxy
        return _density_to_raw_sdf(density)

    def _collect_fem_samples(self) -> list[tuple]:
        """使用 AsyncBuffer 收集 FEM+PFSF 解算結果。"""
        from brnext.pipeline.async_data_loader import (
            AsyncBuffer, CurriculumSampler, augmented_fem_worker, compute_sample_id,
        )
        from brnext.pipeline.structure_gen import generate_structure

        np_rng = np.random.default_rng(self.config.seed)
        dataset: list[tuple] = []
        seen: set[str] = set()

        styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree"]
        sampler = CurriculumSampler(styles, self.config.grid_size, np_rng)
        n_attempts = self.config.train_samples * 3
        progresses = np.linspace(0.0, 1.0, n_attempts)
        style_list = [sampler.sample_styles(1, p)[0] for p in progresses]

        gen = (
            (self.config.grid_size, self.config.seed + i, style_list[i], True)
            for i in range(n_attempts)
        )

        with AsyncBuffer(
            gen, augmented_fem_worker,
            n_workers=self.config.n_fem_workers,
            chunksize=2,
            target_samples=self.config.train_samples,
        ) as buf:
            buf.prefetch(min_buffer=min(20, self.config.train_samples))
            while len(dataset) < self.config.train_samples:
                buf.poll(max_size=self.config.train_samples)
                if len(buf) == 0:
                    break
                for _ in range(20):
                    item = buf.sample(np_rng, n=1)[0]
                    sid = compute_sample_id(item[0])
                    if sid not in seen:
                        seen.add(sid)
                        dataset.append(item)
                        break
                else:
                    if len(buf) < 2:
                        break

        return dataset

    def _make_mock_dataset(self, n_samples: int) -> list[tuple]:
        """生成 Mock 訓練資料集（CPU 無 FEM 時的退回方案）。"""
        L = self.config.grid_size
        np_rng = np.random.default_rng(self.config.seed)
        dataset: list[tuple] = []

        n_base = max(1, n_samples // len(STYLE_IDS))
        for i in range(n_base):
            # 程序化結構：懸臂 or 塔型
            if i % 2 == 0:
                occ = _make_cantilever(L)
            else:
                occ = _make_tower(L)

            occ[:, 0, :] = 1.0  # 底部固定

            # 材料場（均勻鋼材）
            E_norm  = np.full((L, L, L), 200e9 / E_SCALE, dtype=np.float32)
            nu      = np.full((L, L, L), 0.3, dtype=np.float32)
            rho_n   = np.full((L, L, L), 7850.0 / RHO_SCALE, dtype=np.float32)
            rc_n    = np.full((L, L, L), 250.0 / RC_SCALE, dtype=np.float32)
            rt_n    = np.full((L, L, L), 500.0 / RT_SCALE, dtype=np.float32)
            input_6ch = np.stack([occ, E_norm, nu, rho_n, rc_n, rt_n], axis=-1)

            # 解析自重應力 target
            target_10ch = np.zeros((L, L, L, 10), dtype=np.float32)
            rho_mat = 7850.0
            cum_weight = np.cumsum(occ * rho_mat, axis=1) * 9.81 / 1e6
            target_10ch[..., 1] = -cum_weight
            target_10ch[..., 9] = np.abs(cum_weight) * occ

            density = occ.astype(np.float32)
            stress_voigt = target_10ch[..., :6]

            for style_name, style_id in STYLE_IDS.items():
                style_sdf = self._generate_style_target(density, stress_voigt, style_name)
                dataset.append((input_6ch, target_10ch, style_sdf, style_id))

        return dataset


# ---------------------------------------------------------------------------
# 輔助函式（程序化結構生成）
# ---------------------------------------------------------------------------

def _make_cantilever(L: int) -> np.ndarray:
    """生成懸臂樑結構。"""
    occ = np.zeros((L, L, L), dtype=np.float32)
    mid = L // 2
    occ[mid - 1:mid + 1, :, mid - 1:mid + 1] = 1.0  # 主樑
    occ[mid - 1:mid + 1, :L // 2, :] = 1.0           # 水平延伸
    return occ


def _make_tower(L: int) -> np.ndarray:
    """生成塔型結構。"""
    occ = np.zeros((L, L, L), dtype=np.float32)
    mid = L // 2
    # 四個角柱
    for ci, cj in [(1, 1), (1, -2), (-2, 1), (-2, -2)]:
        occ[mid + ci:mid + ci + 1, :, mid + cj:mid + cj + 1] = 1.0
    # 水平樓板（每 L//4 層）
    step = max(1, L // 4)
    for y in range(0, L, step):
        occ[mid - 1:mid + 2, y, mid - 1:mid + 2] = 1.0
    return occ


def _density_to_raw_sdf(density: np.ndarray, iso: float = 0.5, sigma: float = 1.0) -> np.ndarray:
    """簡易密度 → SDF 轉換（numpy 版，無 JAX 依賴）。"""
    from scipy.ndimage import gaussian_filter
    smoothed = gaussian_filter(density.astype(np.float32), sigma=sigma)
    k = 4.0 / (sigma + 0.5)
    sdf = -np.tanh(k * (smoothed - iso))
    return sdf.astype(np.float32)
