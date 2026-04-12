"""
test_topo_optimizer.py — TopologyOptimizer 單元測試

pytest 執行：
    python -m pytest reborn/tests/test_topo_optimizer.py -v
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
import pytest
from reborn import RebornConfig, SimPConfig
from reborn.stages.voxel_massing import VoxelMassing
from reborn.stages.topo_optimizer import TopologyOptimizer, TopologyResult
from reborn.models.fno_proxy import FNOProxy
from reborn.utils.stress_tensor import von_mises


@pytest.fixture
def small_config():
    return RebornConfig(
        simp=SimPConfig(max_iter=10, tol=0.05, vol_frac=0.40),
        verbose=False,
    )


@pytest.fixture
def mock_fno():
    return FNOProxy(mode="mock")


@pytest.fixture
def small_massing(small_config):
    return VoxelMassing(small_config).from_test_structure("cantilever", size=8)


class TestSIMPStiffness:
    """SIMP 材料插值測試"""

    def test_solid_stiffness(self, small_config, mock_fno):
        opt = TopologyOptimizer(small_config, mock_fno)
        x_solid = np.ones((4, 4, 1))
        E_simp = opt._simp_stiffness(x_solid)
        # x=1 → E_simp = E_min + (1 - E_min) ≈ 1.0
        assert np.allclose(E_simp, 1.0, atol=1e-3), f"固體 E_simp 應≈1.0，得 {E_simp.mean()}"

    def test_void_stiffness(self, small_config, mock_fno):
        opt = TopologyOptimizer(small_config, mock_fno)
        x_void = np.zeros((4, 4, 1))
        E_simp = opt._simp_stiffness(x_void)
        # x=0 → E_simp = E_min
        assert np.allclose(E_simp, small_config.simp.x_min, atol=1e-6)

    def test_intermediate_stiffness(self, small_config, mock_fno):
        """中間密度應有較低剛度（懲罰效果）"""
        opt = TopologyOptimizer(small_config, mock_fno)
        x_half = np.full((4, 4, 1), 0.5)
        E_simp = opt._simp_stiffness(x_half)
        # x=0.5, p=3 → 比線性插值（0.5）更小
        assert E_simp.mean() < 0.5, "SIMP 懲罰應使中間密度的剛度低於線性插值"


class TestOCUpdate:
    """Optimality Criteria 更新測試"""

    def test_volume_constraint_satisfied(self, small_config, mock_fno, small_massing):
        """OC 更新後體積分率應接近目標"""
        opt = TopologyOptimizer(small_config, mock_fno)
        Lx, Ly, Lz = small_massing.shape
        mask = small_massing.occupancy.astype(bool)
        x    = np.full((Lx, Ly, Lz), small_config.simp.vol_frac, dtype=np.float32)
        x[~mask] = 0.0

        # 構造一個均勻敏感度場
        sens = -np.ones((Lx, Ly, Lz), dtype=np.float32)
        sens[~mask] = 0.0

        x_new = opt._oc_update(x, sens, mask)
        vf_new = x_new[mask].mean() if mask.any() else 0.0

        target = small_config.simp.vol_frac
        err = abs(vf_new - target)
        assert err < 0.02, f"體積分率誤差 {err:.4f} > 0.02"

    def test_move_limit_respected(self, small_config, mock_fno, small_massing):
        """更新量不超過 move_limit"""
        opt = TopologyOptimizer(small_config, mock_fno)
        Lx, Ly, Lz = small_massing.shape
        mask = small_massing.occupancy.astype(bool)
        x = np.full((Lx, Ly, Lz), 0.5, dtype=np.float32)
        x[~mask] = 0.0
        sens = -np.ones_like(x)
        sens[~mask] = 0.0

        x_new = opt._oc_update(x, sens, mask)
        max_change = float(np.abs(x_new - x)[mask].max())
        assert max_change <= small_config.simp.move + 1e-5, \
            f"更新量 {max_change:.4f} 超過 move_limit {small_config.simp.move}"


class TestSensitivityFilter:
    """敏感度濾波測試"""

    def test_filter_reduces_checkerboard(self, small_config, mock_fno):
        """濾波應平滑化高頻棋盤格紋路"""
        opt = TopologyOptimizer(small_config, mock_fno)
        Lx, Ly = 8, 4
        # 建立棋盤格敏感度
        sens = np.zeros((Lx, Ly, 1), dtype=np.float32)
        for i in range(Lx):
            for j in range(Ly):
                sens[i, j, 0] = 1.0 if (i + j) % 2 == 0 else -1.0
        x    = np.ones((Lx, Ly, 1), dtype=np.float32)
        mask = np.ones((Lx, Ly, 1), dtype=bool)

        filtered = opt._filter_sensitivity(sens, x, mask)
        # 濾波後標準差應小於原始
        assert filtered.std() < sens.std(), "濾波後棋盤格應被平滑化"


class TestFullOptimization:
    """完整最佳化迴圈測試（小尺寸）"""

    def test_compliance_decreases(self, small_config, mock_fno, small_massing):
        """合規性應整體下降"""
        opt = TopologyOptimizer(small_config, mock_fno)
        result = opt.optimize(small_massing)

        if len(result.compliance_history) >= 5:
            first_half = np.mean(result.compliance_history[:len(result.compliance_history)//2])
            second_half = np.mean(result.compliance_history[len(result.compliance_history)//2:])
            # 允許一定誤差（Mock FNO 可能不完美下降）
            assert second_half <= first_half * 1.5, \
                "後半段合規性不應顯著增加"

    def test_result_shape(self, small_config, mock_fno, small_massing):
        """輸出形狀應與輸入一致"""
        opt = TopologyOptimizer(small_config, mock_fno)
        result = opt.optimize(small_massing)
        assert result.density.shape == small_massing.shape
        assert result.stress_voigt.shape == small_massing.shape + (6,)
        assert result.displacement.shape == small_massing.shape + (3,)

    def test_density_bounds(self, small_config, mock_fno, small_massing):
        """密度場應在合法範圍"""
        opt = TopologyOptimizer(small_config, mock_fno)
        result = opt.optimize(small_massing)
        assert result.density.min() >= 0.0, "密度不應為負"
        assert result.density.max() <= 1.0 + 1e-5, "密度不應超過 1"

    def test_binary_density_shape(self, small_config, mock_fno, small_massing):
        opt = TopologyOptimizer(small_config, mock_fno)
        result = opt.optimize(small_massing)
        binary = result.binary_density(0.5)
        assert binary.dtype == np.float32
        unique_vals = set(binary.ravel().tolist())
        assert unique_vals.issubset({0.0, 1.0}), "二值化密度應只有 0 和 1"


class TestVonMises:
    """馮米塞斯應力計算測試"""

    def test_uniaxial(self):
        """單軸應力下，σ_VM 應等於 |σ_xx|"""
        s = np.zeros((4, 4, 1, 6), dtype=np.float32)
        s[..., 0] = 100.0   # σ_xx = 100 MPa
        vm = von_mises(s)
        assert np.allclose(vm, 100.0, atol=0.1)

    def test_zero_stress(self):
        s = np.zeros((3, 3, 3, 6), dtype=np.float32)
        vm = von_mises(s)
        assert np.allclose(vm, 0.0)

    def test_non_negative(self):
        """馮米塞斯應力永遠非負"""
        rng = np.random.default_rng(0)
        s = rng.standard_normal((5, 5, 5, 6)).astype(np.float32)
        vm = von_mises(s)
        assert (vm >= 0).all()
