"""
voxel_massing.py — 第一階段：體素量體輸入處理

職責：
  1. 將 Blueprint JSON / 方塊列表轉換為標準化佔用網格
  2. 驗證輸入合法性（尺寸、錨點存在性）
  3. 填充至 2 的冪次方（FNO 相容）
  4. 生成載重場（自重 + 可選外部點載）

輸出格式：VoxelMassingResult（標準化 numpy 陣列集合）
"""
from __future__ import annotations
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
import numpy as np
from numpy.typing import NDArray

from ..utils.blueprint_io import (
    from_blueprint_json, from_block_list,
    normalize_to_fno_input, pad_to_power_of_two,
    make_cantilever, make_simply_supported_beam, make_tower,
    E_SCALE, RHO_SCALE, RC_SCALE,
)
from ..config import RebornConfig


@dataclass
class VoxelMassingResult:
    """第一階段輸出：標準化體素量體數據"""
    # 佔用遮罩（True = 固體）
    occupancy:     NDArray   # bool[Lx,Ly,Lz]
    # 固定端遮罩（True = 錨點）
    anchors:       NDArray   # bool[Lx,Ly,Lz]
    # 材料場
    E_field:       NDArray   # float32[Lx,Ly,Lz] Pa
    nu_field:      NDArray   # float32[Lx,Ly,Lz]
    density_field: NDArray   # float32[Lx,Ly,Lz] kg/m³
    rcomp_field:   NDArray   # float32[Lx,Ly,Lz] MPa
    rtens_field:   NDArray   # float32[Lx,Ly,Lz] MPa
    # 目標體積分率
    target_vf:     float     = 0.40
    # 體素大小（公尺）
    voxel_size_m:  float     = 1.0
    # 原始空間尺寸（填充前）
    original_shape: tuple    = field(default_factory=tuple)
    # FNO 輸入張量（預打包）
    fno_input:     NDArray | None = None  # float32[Lx,Ly,Lz,5]

    @property
    def shape(self) -> tuple[int, int, int]:
        return tuple(self.occupancy.shape)

    @property
    def n_solid(self) -> int:
        """固體體素數量"""
        return int(self.occupancy.sum())

    @property
    def volume_fraction(self) -> float:
        """當前體積分率"""
        total = self.occupancy.size
        return self.n_solid / total if total > 0 else 0.0


class VoxelMassing:
    """
    第一階段：體素量體輸入處理器。

    職責：讀取各種來源的 Blueprint 資料，轉換為標準化 numpy 張量。
    """

    def __init__(self, config: RebornConfig | None = None):
        self.config = config or RebornConfig()

    def from_blueprint_json(self, path: str | Path) -> VoxelMassingResult:
        """從 Blueprint JSON 文件建立量體"""
        grids = from_blueprint_json(path)
        return self._build_result(grids)

    def from_block_list(
        self,
        blocks: list[dict[str, Any]],
        bbox: tuple[int, int, int] | None = None,
    ) -> VoxelMassingResult:
        """從方塊列表建立量體"""
        grids = from_block_list(blocks, bbox)
        return self._build_result(grids)

    def from_test_structure(
        self,
        name: str = "cantilever",
        size: int = 16,
        material_id: str = "CONCRETE",
    ) -> VoxelMassingResult:
        """
        建立合成測試結構（用於實驗）。

        支援：
          "cantilever"    — MBB 懸臂樑（2.5D 拓撲最佳化標準問題）
          "beam"          — 簡支樑（中央點載）
          "tower"         — 高塔（空心正方形截面）
        """
        generators = {
            "cantilever": lambda: make_cantilever(Lx=size*2, Ly=size, Lz=1, material_id=material_id),
            "beam":       lambda: make_simply_supported_beam(Lx=size*2, Ly=size//2, Lz=1, material_id=material_id),
            "tower":      lambda: make_tower(Lx=size//2, Ly=size, Lz=size//2, material_id=material_id),
        }
        if name not in generators:
            raise ValueError(f"未知測試結構：{name}，可用：{list(generators)}")

        grids = generators[name]()
        return self._build_result(grids)

    def _build_result(self, grids: dict, pad: bool = False) -> VoxelMassingResult:
        """
        將原始網格字典轉換為 VoxelMassingResult。

        驗證步驟：
          1. 至少有一個固體體素
          2. 至少有一個錨點（否則結構會飛走）
          3. 材料場值域合理
        """
        occ   = grids["occupancy"].astype(bool)
        anch  = grids["anchors"].astype(bool)
        E     = grids["E_field"].astype(np.float32)
        nu    = grids["nu_field"].astype(np.float32)
        rho   = grids["density_field"].astype(np.float32)
        rcomp = grids["rcomp_field"].astype(np.float32)
        rtens = grids["rtens_field"].astype(np.float32)

        # 驗證
        if not occ.any():
            raise ValueError("佔用網格全空，請確認 Blueprint 資料")
        if not anch.any():
            # 自動加入底部錨點
            anch[:, 0, :] = occ[:, 0, :]
            if self.config.verbose:
                print("[VoxelMassing] 警告：未找到錨點，自動設定底部為固定端")

        # 確保材料場在有效範圍
        E     = np.where(occ, np.clip(E, 1e6, 1e15), 0.0)
        nu    = np.where(occ, np.clip(nu, 0.0, 0.49), 0.0)
        rho   = np.where(occ, np.clip(rho, 100.0, 10000.0), 0.0)
        rcomp = np.where(occ, np.clip(rcomp, 0.1, 1e9), 0.0)
        rtens = np.where(occ, np.clip(rtens, 0.01, 1e9), 0.0)

        original_shape = occ.shape

        # 可選：填充至 2 的冪次方
        if pad:
            occ,   _ = pad_to_power_of_two(occ)
            anch,  _ = pad_to_power_of_two(anch)
            E,     _ = pad_to_power_of_two(E)
            nu,    _ = pad_to_power_of_two(nu)
            rho,   _ = pad_to_power_of_two(rho)
            rcomp, _ = pad_to_power_of_two(rcomp)
            rtens, _ = pad_to_power_of_two(rtens)

        # 預打包 FNO 輸入
        temp_grids = {
            "occupancy": occ, "E_field": E, "nu_field": nu,
            "density_field": rho, "rcomp_field": rcomp,
        }
        fno_input = normalize_to_fno_input(temp_grids)

        return VoxelMassingResult(
            occupancy=occ, anchors=anch,
            E_field=E, nu_field=nu, density_field=rho,
            rcomp_field=rcomp, rtens_field=rtens,
            target_vf=self.config.simp.vol_frac,
            original_shape=original_shape,
            fno_input=fno_input,
        )

    def compute_self_weight_loads(self, result: VoxelMassingResult) -> NDArray:
        """
        計算自重載荷場（向量場）。

        公式：f = ρ·g·V_voxel（向下 -Y 方向）

        Returns:
            float32[Lx,Ly,Lz,3] — 體積力（N/m³）
        """
        g = 9.81
        Lx, Ly, Lz = result.shape
        loads = np.zeros((Lx, Ly, Lz, 3), dtype=np.float32)
        loads[..., 1] = -(result.density_field * g * result.occupancy).astype(np.float32)
        return loads

    def get_summary(self, result: VoxelMassingResult) -> dict:
        """輸出量體摘要統計"""
        Lx, Ly, Lz = result.shape
        return {
            "grid_shape":    (Lx, Ly, Lz),
            "n_solid":       result.n_solid,
            "n_anchor":      int(result.anchors.sum()),
            "volume_frac":   f"{result.volume_fraction:.3f}",
            "E_range_GPa":   f"{result.E_field[result.occupancy].min()/1e9:.1f} ~ {result.E_field[result.occupancy].max()/1e9:.1f}",
            "rho_range":     f"{result.density_field[result.occupancy].min():.0f} ~ {result.density_field[result.occupancy].max():.0f} kg/m³",
            "target_vf":     result.target_vf,
        }
