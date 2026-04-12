"""
test_voxel_massing.py — VoxelMassing 單元測試

pytest 執行：
    cd "Block Reality/experimental"
    python -m pytest reborn/tests/test_voxel_massing.py -v
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import numpy as np
import pytest
from reborn import RebornConfig
from reborn.stages.voxel_massing import VoxelMassing
from reborn.utils.blueprint_io import make_cantilever, make_tower, MATERIAL_PROPS


@pytest.fixture
def massing():
    return VoxelMassing(RebornConfig())


class TestSyntheticStructures:
    def test_cantilever_shape(self, massing):
        r = massing.from_test_structure("cantilever", size=8)
        assert r.shape == (16, 8, 1), f"期望 (16,8,1)，得到 {r.shape}"

    def test_beam_shape(self, massing):
        r = massing.from_test_structure("beam", size=8)
        assert r.shape == (16, 4, 1)

    def test_tower_shape(self, massing):
        r = massing.from_test_structure("tower", size=8)
        assert r.shape == (4, 8, 4)

    def test_has_anchors(self, massing):
        r = massing.from_test_structure("cantilever", size=8)
        assert r.anchors.any(), "懸臂樑應有固定端"

    def test_solid_voxels(self, massing):
        r = massing.from_test_structure("cantilever", size=8)
        assert r.n_solid > 0

    def test_volume_fraction(self, massing):
        r = massing.from_test_structure("cantilever", size=8)
        # 懸臂樑：全部固體
        assert r.volume_fraction > 0.0
        assert r.volume_fraction <= 1.0

    def test_fno_input_shape(self, massing):
        r = massing.from_test_structure("cantilever", size=8)
        assert r.fno_input is not None
        assert r.fno_input.shape[-1] == 5, "FNO 輸入應有 5 個通道"

    def test_fno_input_normalized(self, massing):
        """驗證 FNO 輸入正規化值域"""
        r = massing.from_test_structure("cantilever", size=8)
        inp = r.fno_input
        assert inp[..., 0].max() <= 1.0, "佔用通道應 ≤ 1"
        assert inp[..., 1].max() <= 1.1, "E 正規化應接近 [0,1]"
        assert inp[..., 2].max() <= 0.5, "泊松比應 ≤ 0.5"

    def test_unknown_structure(self, massing):
        with pytest.raises(ValueError, match="未知測試結構"):
            massing.from_test_structure("nonexistent_structure")


class TestMaterialProps:
    def test_all_materials_have_required_keys(self):
        required_keys = {"E", "nu", "rho", "rcomp", "rtens"}
        for mat, props in MATERIAL_PROPS.items():
            assert required_keys.issubset(set(props)), f"{mat} 缺少屬性"

    def test_concrete_properties(self):
        mat = MATERIAL_PROPS["CONCRETE"]
        assert 20e9 <= mat["E"] <= 50e9, "混凝土楊氏模量應在 20-50 GPa"
        assert mat["rcomp"] > 0
        assert mat["rho"] > 1000


class TestFromBlockList:
    def test_single_block(self, massing):
        blocks = [{"pos": [0, 0, 0], "material_id": "CONCRETE", "is_anchor": True}]
        r = massing.from_block_list(blocks)
        assert r.n_solid == 1
        assert r.anchors.any()

    def test_auto_anchor(self, massing):
        """若無錨點，自動加入底部"""
        blocks = [
            {"pos": [0, 1, 0], "material_id": "STEEL"},
            {"pos": [0, 2, 0], "material_id": "STEEL"},
        ]
        r = massing.from_block_list(blocks)
        # 自動加入底部 y=0 的錨點（即使沒有方塊）
        # 或，若底部有方塊，則自動設為錨點
        assert r is not None  # 至少不崩潰

    def test_different_materials(self, massing):
        blocks = [
            {"pos": [0, 0, 0], "material_id": "STEEL"},
            {"pos": [1, 0, 0], "material_id": "CONCRETE"},
        ]
        r = massing.from_block_list(blocks)
        E_steel    = MATERIAL_PROPS["STEEL"]["E"]
        E_concrete = MATERIAL_PROPS["CONCRETE"]["E"]
        assert r.E_field[0, 0, 0] == pytest.approx(E_steel, rel=0.01)
        assert r.E_field[1, 0, 0] == pytest.approx(E_concrete, rel=0.01)


class TestSelfWeightLoads:
    def test_loads_shape(self, massing):
        r = massing.from_test_structure("cantilever", size=8)
        loads = massing.compute_self_weight_loads(r)
        assert loads.shape == r.shape + (3,)

    def test_downward_direction(self, massing):
        r = massing.from_test_structure("cantilever", size=8)
        loads = massing.compute_self_weight_loads(r)
        # Y 方向（索引 1）應為負（向下）
        assert loads[..., 1].max() <= 0.0, "自重應向下（-Y）"
