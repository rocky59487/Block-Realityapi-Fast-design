"""
data_pipeline.py — Reborn v2 風格條件化訓練資料管線

負責：
  1. 從 AsyncBuffer 收集 FEM+PFSF 解算結果
  2. 為每個樣本生成多風格 SDF 教師目標（Gaudi / Zaha / Hybrid / Raw）
  3. 組裝 (input_6ch, physics_target_10ch, style_sdf_target, style_id) 訓練元組
  4. 支援 DuckDB+Zarr 快取（重用 BR-NeXT 快取基礎設施）

資料格式：
  input_6ch:          float32[L,L,L,6]  — [occ, E/E_SCALE, nu, rho/RHO_SCALE, rc/RC_SCALE, rt/RT_SCALE]
  physics_target_10ch: float32[L,L,L,10] — [σ_xx..τ_xz(6), u_x..u_z(3), φ(1)]
  style_sdf_target:   float32[L,L,L]    — 風格化 SDF（由解析模型生成）
  style_id:           int                — 風格代碼 (0=raw, 1=gaudi, 2=zaha, 3=hybrid)

參考：
  HYBR/hybr/training/meta_trainer.py — DuckDB+Zarr 快取模式
  BR-NeXT/brnext/pipeline/async_data_loader.py — AsyncBuffer 非同步資料管線
"""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path
from typing import Any

import numpy as np

# ---------------------------------------------------------------------------
# 設定依賴套件路徑（HYBR / BR-NeXT / brml）
# ---------------------------------------------------------------------------
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
for _pkg in ("HYBR", "BR-NeXT", "brml"):
    _p = str(_REPO_ROOT / _pkg)
    if _p not in sys.path:
        sys.path.insert(0, _p)

from brnext.pipeline.async_data_loader import (
    AsyncBuffer,
    CurriculumSampler,
    augmented_fem_worker,
    compute_sample_id,
)
from brnext.pipeline.structure_gen import generate_structure
from brnext.config import load_norm_constants

from reborn.models.gaudi_style import GaudiStyle
from reborn.models.zaha_style import ZahaStyle
from reborn.utils.density_to_sdf import density_to_sdf_smooth
from reborn.config import TrainingConfig

# 正規化常數（全域載入一次）
_NORM = load_norm_constants()
E_SCALE = _NORM["E_SCALE"]
RHO_SCALE = _NORM["RHO_SCALE"]
RC_SCALE = _NORM["RC_SCALE"]
RT_SCALE = _NORM["RT_SCALE"]

# 風格代碼表（與 HYBRProxy.STYLE_TOKENS 一致）
STYLE_IDS: dict[str, int] = {
    "raw":    0,
    "gaudi":  1,
    "zaha":   2,
    "hybrid": 3,
}


class RebornDataPipeline:
    """
    Reborn v2 風格條件化訓練資料管線。

    使用方式：
        cfg = TrainingConfig(train_samples=500, grid_size=16)
        pipeline = RebornDataPipeline(cfg)
        dataset = pipeline.build_dataset()
        # dataset: list[(input_6ch, target_10ch, style_sdf, style_id), ...]

    當 FEM 工作執行緒不可用時，自動降級為 Mock 資料集。
    """

    def __init__(self, config: TrainingConfig):
        self.config = config
        self._verbose = config.verbose

        # 解析風格模型（不需要 GPU，純 numpy/scipy）
        self._gaudi = GaudiStyle(verbose=False)
        self._zaha = ZahaStyle(verbose=False)

        # DuckDB + Zarr 快取（與 HYBRTrainer 相同模式）
        self.registry = None
        self.zarr_store = None
        self.config_hash = hashlib.sha256(
            repr((config.grid_size, config.seed, "reborn_v2")).encode()
        ).hexdigest()[:16]

        if config.use_cache:
            try:
                from brnext.data import DatasetRegistry, ZarrDatasetStore
                cache_dir = Path(config.cache_dir)
                cache_dir.mkdir(parents=True, exist_ok=True)
                self.registry = DatasetRegistry(cache_dir / "dataset_registry.duckdb")
                self.zarr_store = ZarrDatasetStore(cache_dir / "zarr_store")
                if self._verbose:
                    print("[RebornDataPipeline] DuckDB+Zarr 快取已啟用")
            except Exception as e:
                if self._verbose:
                    print(f"[RebornDataPipeline] 快取初始化失敗 ({e})，將不使用快取")
                self.registry = None
                self.zarr_store = None

    # ===================================================================
    # 主入口
    # ===================================================================

    def build_dataset(self) -> list[tuple]:
        """
        建構完整訓練資料集。

        流程：
          1. 使用 AsyncBuffer 收集 FEM+PFSF 解算結果
          2. 為每個樣本生成 4 種風格的 SDF 教師目標
          3. 組裝訓練元組

        Returns:
            list[(input_6ch, target_10ch, style_sdf, style_id)]
            每個原始 FEM 樣本會展開為 4 個訓練樣本（每種風格各一）
        """
        # 先嘗試真實 FEM 管線
        try:
            raw_samples = self._collect_fem_samples()
        except Exception as e:
            if self._verbose:
                print(f"[RebornDataPipeline] FEM 管線失敗 ({e})，切換至 Mock")
            raw_samples = []

        if len(raw_samples) == 0:
            if self._verbose:
                print("[RebornDataPipeline] 使用 Mock 資料集作為退回方案")
            return self._make_mock_dataset(self.config.train_samples)

        # 展開：每個 FEM 樣本 × 4 風格
        dataset: list[tuple] = []
        styles = list(STYLE_IDS.keys())

        for sample in raw_samples:
            struct = sample[0]
            # FEM 結果可能是 (struct, fem, phi) 或 (struct, phi)
            if len(sample) == 3:
                fem, phi = sample[1], sample[2]
            else:
                fem, phi = None, sample[1]

            # 建構 6 通道輸入
            input_6ch = self._build_input(struct)

            # 建構 10 通道物理目標
            target_10ch = self._build_target(struct, fem, phi)

            # 從 FEM 結果提取 SIMP-like 密度和應力
            # 若真實密度不可用，用 φ 場作為代理
            density = getattr(struct, "density_field", None)
            if density is None or np.max(density) < 1e-6:
                density = (phi / (np.max(np.abs(phi)) + 1e-8) + 1.0) * 0.5
                density = np.clip(density, 0.0, 1.0)

            # 從目標提取 Voigt 應力（前 6 通道）
            stress_voigt = target_10ch[..., :6]

            # 為每種風格生成 SDF 教師目標
            for style_name in styles:
                style_sdf = self._generate_style_target(
                    density, stress_voigt, style_name
                )
                style_id = STYLE_IDS[style_name]
                dataset.append((input_6ch, target_10ch, style_sdf, style_id))

        if self._verbose:
            print(
                f"[RebornDataPipeline] 資料集建構完成：{len(raw_samples)} 個 FEM 樣本 "
                f"× {len(styles)} 風格 = {len(dataset)} 個訓練樣本"
            )
        return dataset

    # ===================================================================
    # 輸入/目標建構
    # ===================================================================

    def _build_input(self, struct) -> np.ndarray:
        """
        建構 6 通道正規化輸入。

        通道排列（與 HYBRTrainer._build_input 一致）：
          [occ, E/E_SCALE, nu, rho/RHO_SCALE, rc/RC_SCALE, rt/RT_SCALE]

        Args:
            struct: VoxelStructure — 含 occupancy, E_field, nu_field 等欄位

        Returns:
            float32[L, L, L, 6]
        """
        occ = struct.occupancy.astype(np.float32)
        E = struct.E_field.astype(np.float32) / E_SCALE
        nu = struct.nu_field.astype(np.float32)
        rho = struct.density_field.astype(np.float32) / RHO_SCALE
        rc = struct.rcomp_field.astype(np.float32) / RC_SCALE
        rt = struct.rtens_field.astype(np.float32) / RT_SCALE
        return np.stack([occ, E, nu, rho, rc, rt], axis=-1)

    def _build_target(self, struct, fem, phi) -> np.ndarray:
        """
        建構 10 通道物理目標。

        通道排列：
          [σ_xx, σ_yy, σ_zz, τ_xy, τ_yz, τ_xz, u_x, u_y, u_z, φ]

        Args:
            struct: VoxelStructure
            fem:    FEM 解算結果（可能為 None）
            phi:    PFSF φ 場

        Returns:
            float32[L, L, L, 10]
        """
        shape = struct.occupancy.shape
        target = np.zeros((*shape, 10), dtype=np.float32)

        if fem is not None:
            # FEM 有完整應力和位移資料
            if hasattr(fem, "stress_voigt"):
                target[..., :6] = fem.stress_voigt.astype(np.float32)
            if hasattr(fem, "displacement"):
                target[..., 6:9] = fem.displacement.astype(np.float32)

        # φ 場（PFSF 勢場）
        if phi is not None:
            target[..., 9] = phi.astype(np.float32)

        return target

    # ===================================================================
    # 風格 SDF 教師目標生成
    # ===================================================================

    def _generate_style_target(
        self,
        density: np.ndarray,
        stress_voigt: np.ndarray,
        style_name: str,
    ) -> np.ndarray:
        """
        生成指定風格的 SDF 教師目標。

        使用解析風格模型（無需 GPU）為訓練提供監督信號。

        Args:
            density:      float32[L, L, L] — SIMP 密度場 ∈ [0, 1]
            stress_voigt: float32[L, L, L, 6] — Voigt 應力
            style_name:   風格名稱 ("gaudi" / "zaha" / "hybrid" / "raw")

        Returns:
            float32[L, L, L] — 風格化 SDF
        """
        if style_name == "gaudi":
            return self._gaudi.apply(density, stress_voigt)
        elif style_name == "zaha":
            return self._zaha.apply(density, stress_voigt)
        elif style_name == "hybrid":
            # 高第 50% + 札哈 50% 的混合風格
            sdf_gaudi = self._gaudi.apply(density, stress_voigt)
            sdf_zaha = self._zaha.apply(density, stress_voigt)
            return (0.5 * sdf_gaudi + 0.5 * sdf_zaha).astype(np.float32)
        elif style_name == "raw":
            # 無風格 — 直接從密度場轉換為平滑 SDF
            return density_to_sdf_smooth(density, iso=0.5)
        else:
            raise ValueError(f"未知風格：{style_name}，可用：{list(STYLE_IDS)}")

    # ===================================================================
    # FEM 樣本收集（AsyncBuffer）
    # ===================================================================

    def _collect_fem_samples(self) -> list[tuple]:
        """
        使用 AsyncBuffer 收集 FEM+PFSF 解算結果。

        模式與 HYBRTrainer._build_dataset 一致：
          1. 嘗試載入預計算快取
          2. 不足時啟動 AsyncBuffer 生成

        Returns:
            list[(struct, fem, phi)] 或 list[(struct, phi)]
        """
        np_rng = np.random.default_rng(self.config.seed)
        dataset: list[tuple] = []
        seen: set[str] = set()

        # ── 1. 載入快取 ──
        try:
            from precompute.feeder import load_precomputed_samples
            precomputed = load_precomputed_samples(
                self.config.cache_dir, self.config.grid_size, seed=self.config.seed
            )
            for item in precomputed:
                sid = compute_sample_id(item[0])
                if sid not in seen:
                    seen.add(sid)
                    dataset.append(item)
            if self._verbose and dataset:
                print(f"[RebornDataPipeline] 從快取載入 {len(dataset)} 個樣本")
        except Exception as e:
            if self._verbose:
                print(f"[RebornDataPipeline] 快取載入跳過：{e}")

        if len(dataset) >= self.config.train_samples:
            return dataset[:self.config.train_samples]

        # ── 2. AsyncBuffer 即時生成 ──
        styles = ["tower", "bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
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
            registry=self.registry,
            zarr_store=self.zarr_store,
            config_hash=self.config_hash,
            grid_size=self.config.grid_size,
            target_samples=self.config.train_samples,
        ) as buf:
            buf.prefetch(min_buffer=min(20, self.config.train_samples))
            if self._verbose:
                print(f"[RebornDataPipeline] FEM buffer ready: {len(buf)}")

            while len(dataset) < self.config.train_samples:
                buf.poll(max_size=self.config.train_samples)
                if len(buf) == 0:
                    if buf._exhausted:
                        break
                    buf.prefetch(min_buffer=1, timeout=5.0)
                    if len(buf) == 0:
                        break
                # 去重取樣
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

        if self._verbose:
            print(f"[RebornDataPipeline] 收集到 {len(dataset)} 個唯一 FEM 樣本")
        return dataset

    # ===================================================================
    # Mock 資料集（CPU 退回方案）
    # ===================================================================

    def _make_mock_dataset(self, n_samples: int) -> list[tuple]:
        """
        生成 Mock 訓練資料集，用於 FEM 工作執行緒不可用時的 CPU 測試。

        使用 blueprint_io 的合成結構生成器 + 解析自重應力作為替代。
        風格 SDF 仍由真實解析模型（GaudiStyle / ZahaStyle）生成。

        Args:
            n_samples: 目標樣本數（每個基礎樣本展開為 4 風格）

        Returns:
            list[(input_6ch, target_10ch, style_sdf, style_id)]
        """
        from reborn.utils.blueprint_io import make_cantilever, make_tower

        if self._verbose:
            print(f"[RebornDataPipeline] 生成 {n_samples} 個 Mock 樣本...")

        L = self.config.grid_size
        np_rng = np.random.default_rng(self.config.seed)
        dataset: list[tuple] = []
        styles = list(STYLE_IDS.keys())

        # 每種結構類型平均分配
        n_base = max(1, n_samples // len(styles))
        generators = [make_cantilever, make_tower]

        for i in range(n_base):
            # 隨機選擇結構生成器
            gen_fn = generators[i % len(generators)]
            struct = gen_fn(L)

            # 建構輸入
            input_6ch = self._build_input(struct)

            # Mock 物理目標：解析自重應力
            target_10ch = self._make_mock_target(struct)

            # 密度場（佔用網格作為代理）
            density = struct.occupancy.astype(np.float32)

            # Mock Voigt 應力（只有 σ_yy 非零）
            stress_voigt = target_10ch[..., :6]

            # 為每種風格生成 SDF 教師目標
            for style_name in styles:
                try:
                    style_sdf = self._generate_style_target(
                        density, stress_voigt, style_name
                    )
                except Exception:
                    # 風格模型可能因應力場品質不足而失敗
                    style_sdf = density_to_sdf_smooth(density, iso=0.5)

                style_id = STYLE_IDS[style_name]
                dataset.append((input_6ch, target_10ch, style_sdf, style_id))

        if self._verbose:
            print(
                f"[RebornDataPipeline] Mock 資料集完成：{n_base} 結構 "
                f"× {len(styles)} 風格 = {len(dataset)} 個訓練樣本"
            )
        return dataset

    def _make_mock_target(self, struct) -> np.ndarray:
        """
        生成 Mock 10 通道物理目標。

        使用簡化的解析自重應力模型：
          σ_yy = -ρ·g·Σ(occ_above) / 1e6  (MPa)
          其他分量為零或小擾動

        Args:
            struct: VoxelStructure

        Returns:
            float32[L, L, L, 10]
        """
        shape = struct.occupancy.shape
        target = np.zeros((*shape, 10), dtype=np.float32)
        occ = struct.occupancy.astype(np.float32)

        # 自重應力（沿 y 軸累積）
        rho = getattr(struct, "density_field", np.full(shape, 2400.0, dtype=np.float32))
        cum_weight = np.cumsum(occ * rho, axis=1) * 9.81 / 1e6  # MPa
        target[..., 1] = -cum_weight  # σ_yy（壓縮為負）

        # φ 場（使用 σ_yy 的 von Mises 近似）
        target[..., 9] = np.abs(target[..., 1]) * occ

        return target
