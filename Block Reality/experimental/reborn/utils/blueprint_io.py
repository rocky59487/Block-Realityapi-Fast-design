"""
blueprint_io.py — Blueprint JSON/NBT 轉換為 numpy 佔用網格

橋接 Java 側的 Blueprint 資料格式與 Reborn Python 管線。

支援格式：
  1. Blueprint JSON（BlueprintIO.java 導出格式）
  2. 簡單方塊列表（{pos: [x,y,z], material_id: str}）
  3. 合成測試結構（cantilever、bridge、tower）
"""
from __future__ import annotations
import json
import numpy as np
from pathlib import Path
from typing import Any
from numpy.typing import NDArray


# ---------------------------------------------------------------------------
# DefaultMaterial 物理屬性查找表
# 與 DefaultMaterial.java 數值完全一致
# ---------------------------------------------------------------------------

MATERIAL_PROPS: dict[str, dict[str, float]] = {
    "CONCRETE":        {"E": 30e9,   "nu": 0.2,  "rho": 2400.0, "rcomp": 30.0,  "rtens": 3.0},
    "REINFORCED_CONCRETE": {"E": 32e9, "nu": 0.2, "rho": 2500.0, "rcomp": 40.0, "rtens": 5.0},
    "REBAR":           {"E": 200e9,  "nu": 0.3,  "rho": 7850.0, "rcomp": 400.0, "rtens": 500.0},
    "STEEL":           {"E": 210e9,  "nu": 0.3,  "rho": 7850.0, "rcomp": 355.0, "rtens": 450.0},
    "TIMBER":          {"E": 12e9,   "nu": 0.4,  "rho": 600.0,  "rcomp": 40.0,  "rtens": 30.0},
    "BRICK":           {"E": 15e9,   "nu": 0.2,  "rho": 1900.0, "rcomp": 10.0,  "rtens": 1.0},
    "GLASS":           {"E": 70e9,   "nu": 0.2,  "rho": 2500.0, "rcomp": 800.0, "rtens": 30.0},
    "SAND":            {"E": 0.1e9,  "nu": 0.35, "rho": 1600.0, "rcomp": 0.5,   "rtens": 0.01},
    "OBSIDIAN":        {"E": 90e9,   "nu": 0.25, "rho": 2900.0, "rcomp": 200.0, "rtens": 50.0},
    "BEDROCK":         {"E": 1e12,   "nu": 0.1,  "rho": 5000.0, "rcomp": 1e9,   "rtens": 1e9},
    # 預設值（未知材料）
    "UNKNOWN":         {"E": 30e9,   "nu": 0.2,  "rho": 2400.0, "rcomp": 30.0,  "rtens": 3.0},
}

# 正規化常數（與 OnnxPFSFRuntime.java 一致）
E_SCALE   = 200e9
RHO_SCALE = 7850.0
RC_SCALE  = 250.0


def _resolve_material(material_id: str) -> dict[str, float]:
    """從 material_id 字串解析物理屬性"""
    key = material_id.upper().replace("BLOCKREALITY:", "").split(":")[0]
    # 嘗試部分匹配
    for mat_key in MATERIAL_PROPS:
        if mat_key in key:
            return MATERIAL_PROPS[mat_key]
    return MATERIAL_PROPS["UNKNOWN"]


# ---------------------------------------------------------------------------
# 從 Blueprint JSON 讀取
# ---------------------------------------------------------------------------

def from_blueprint_json(path: str | Path) -> dict[str, NDArray]:
    """
    讀取 BlueprintIO.java 導出的 JSON 文件。

    Args:
        path: Blueprint JSON 文件路徑

    Returns:
        {
          "occupancy":    bool   [Lx,Ly,Lz]
          "anchors":      bool   [Lx,Ly,Lz]
          "E_field":      float32[Lx,Ly,Lz]  — Pa
          "nu_field":     float32[Lx,Ly,Lz]
          "density_field":float32[Lx,Ly,Lz]  — kg/m³
          "rcomp_field":  float32[Lx,Ly,Lz]  — MPa
          "rtens_field":  float32[Lx,Ly,Lz]  — MPa
        }
    """
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    blocks: list[dict] = data.get("blocks", [])
    if not blocks:
        raise ValueError(f"Blueprint JSON 不含任何方塊：{path}")

    # 計算邊界框
    positions = np.array([b["pos"] for b in blocks], dtype=np.int32)
    min_pos = positions.min(axis=0)
    positions -= min_pos   # 歸一化至原點
    max_pos = positions.max(axis=0)
    Lx, Ly, Lz = int(max_pos[0]) + 1, int(max_pos[1]) + 1, int(max_pos[2]) + 1

    return _build_grids(blocks, positions, (Lx, Ly, Lz))


def from_block_list(
    blocks: list[dict[str, Any]],
    bbox: tuple[int, int, int] | None = None,
) -> dict[str, NDArray]:
    """
    從方塊列表建立佔用網格。

    Args:
        blocks: [{"pos": [x,y,z], "material_id": "CONCRETE", "is_anchor": false}, ...]
        bbox:   (Lx,Ly,Lz)，若 None 則自動計算

    Returns:
        同 from_blueprint_json 的回傳格式
    """
    if not blocks:
        raise ValueError("方塊列表為空")

    positions = np.array([b["pos"] for b in blocks], dtype=np.int32)
    min_pos = positions.min(axis=0)
    positions -= min_pos

    if bbox is None:
        max_pos = positions.max(axis=0)
        bbox = (int(max_pos[0]) + 1, int(max_pos[1]) + 1, int(max_pos[2]) + 1)

    return _build_grids(blocks, positions, bbox)


def _build_grids(
    blocks: list[dict],
    positions: NDArray,
    shape: tuple[int, int, int],
) -> dict[str, NDArray]:
    """將方塊列表轉換為網格張量"""
    Lx, Ly, Lz = shape
    occupancy  = np.zeros(shape, dtype=bool)
    anchors    = np.zeros(shape, dtype=bool)
    E_field    = np.zeros(shape, dtype=np.float32)
    nu_field   = np.zeros(shape, dtype=np.float32)
    rho_field  = np.zeros(shape, dtype=np.float32)
    rcomp_field= np.zeros(shape, dtype=np.float32)
    rtens_field= np.zeros(shape, dtype=np.float32)

    for b, pos in zip(blocks, positions):
        x, y, z = int(pos[0]), int(pos[1]), int(pos[2])
        if not (0 <= x < Lx and 0 <= y < Ly and 0 <= z < Lz):
            continue
        mat_id = b.get("material_id", "CONCRETE")
        props = _resolve_material(mat_id)
        occupancy [x, y, z] = True
        anchors   [x, y, z] = bool(b.get("is_anchor", False)) or (y == 0)
        E_field   [x, y, z] = props["E"]
        nu_field  [x, y, z] = props["nu"]
        rho_field [x, y, z] = props["rho"]
        rcomp_field[x, y, z]= props["rcomp"]
        rtens_field[x, y, z]= props["rtens"]

    return {
        "occupancy":     occupancy,
        "anchors":       anchors,
        "E_field":       E_field,
        "nu_field":      nu_field,
        "density_field": rho_field,
        "rcomp_field":   rcomp_field,
        "rtens_field":   rtens_field,
    }


# ---------------------------------------------------------------------------
# 正規化工具
# ---------------------------------------------------------------------------

def normalize_to_fno_input(
    grids: dict[str, NDArray],
    density_override: NDArray | None = None,
) -> NDArray:
    """
    將網格字典轉換為 FNO 輸入張量 [Lx,Ly,Lz,5]。

    通道：(occ, E_norm, nu, rho_norm, rcomp_norm)
    與 OnnxPFSFRuntime.java 的正規化常數完全一致。

    Args:
        grids:            from_blueprint_json() 的回傳值
        density_override: 若提供，用此密度場替換 occupancy（用於 SIMP）

    Returns:
        float32[Lx,Ly,Lz,5]
    """
    occ = density_override if density_override is not None else grids["occupancy"].astype(np.float32)
    return np.stack([
        occ,
        grids["E_field"].astype(np.float32) / E_SCALE,
        grids["nu_field"].astype(np.float32),
        grids["density_field"].astype(np.float32) / RHO_SCALE,
        grids["rcomp_field"].astype(np.float32) / RC_SCALE,
    ], axis=-1)


def pad_to_power_of_two(grid: NDArray, mode: str = "constant") -> tuple[NDArray, tuple]:
    """
    將任意形狀網格填充至最近的 2 的冪次方立方體。

    Args:
        grid: shape (..., [Lx,Ly,Lz], [...])
        mode: numpy.pad mode

    Returns:
        padded_grid: 填充後的網格
        original_shape: (Lx, Ly, Lz) 原始空間形狀（用於裁切還原）
    """
    spatial_shape = grid.shape[:3]
    L = int(2 ** np.ceil(np.log2(max(spatial_shape))))
    pads = [(0, L - s) for s in spatial_shape]
    # 若有額外維度（如通道），不填充
    pads += [(0, 0)] * (grid.ndim - 3)
    return np.pad(grid, pads, mode=mode), spatial_shape


def crop_to_original(grid: NDArray, original_shape: tuple) -> NDArray:
    """從填充後的網格裁切回原始形狀"""
    Lx, Ly, Lz = original_shape
    return grid[:Lx, :Ly, :Lz]


# ---------------------------------------------------------------------------
# 合成測試結構生成器（用於實驗，不需要真實 Minecraft 資料）
# ---------------------------------------------------------------------------

def make_cantilever(
    Lx: int = 32, Ly: int = 16, Lz: int = 1,
    material_id: str = "CONCRETE",
) -> dict[str, NDArray]:
    """
    MBB 懸臂樑（2.5D）— 拓撲最佳化標準基準問題。
    邊界條件：左側固定支撐，右下角施加向下點載。
    """
    shape = (Lx, Ly, max(Lz, 1))
    props = _resolve_material(material_id)
    occupancy = np.ones(shape, dtype=bool)
    anchors   = np.zeros(shape, dtype=bool)
    anchors[0, :, :] = True   # 左側固定端

    return {
        "occupancy":     occupancy,
        "anchors":       anchors,
        "E_field":       np.full(shape, props["E"],   dtype=np.float32),
        "nu_field":      np.full(shape, props["nu"],  dtype=np.float32),
        "density_field": np.full(shape, props["rho"], dtype=np.float32),
        "rcomp_field":   np.full(shape, props["rcomp"], dtype=np.float32),
        "rtens_field":   np.full(shape, props["rtens"], dtype=np.float32),
        # 載重點：右下角，向下（-Y 方向）
        "load_pos":      np.array([[Lx - 1, 0, 0]]),
        "load_vec":      np.array([[0.0, -1.0, 0.0]]),
    }


def make_simply_supported_beam(
    Lx: int = 40, Ly: int = 10, Lz: int = 1,
    material_id: str = "CONCRETE",
) -> dict[str, NDArray]:
    """
    簡支樑 — 兩端支撐，中央點載。
    """
    shape = (Lx, Ly, max(Lz, 1))
    props = _resolve_material(material_id)
    occupancy = np.ones(shape, dtype=bool)
    anchors   = np.zeros(shape, dtype=bool)
    anchors[0,  0, :] = True   # 左支撐
    anchors[-1, 0, :] = True   # 右支撐

    return {
        "occupancy":     occupancy,
        "anchors":       anchors,
        "E_field":       np.full(shape, props["E"],   dtype=np.float32),
        "nu_field":      np.full(shape, props["nu"],  dtype=np.float32),
        "density_field": np.full(shape, props["rho"], dtype=np.float32),
        "rcomp_field":   np.full(shape, props["rcomp"], dtype=np.float32),
        "rtens_field":   np.full(shape, props["rtens"], dtype=np.float32),
        "load_pos":      np.array([[Lx // 2, Ly - 1, 0]]),
        "load_vec":      np.array([[0.0, -1.0, 0.0]]),
    }


def make_tower(
    Lx: int = 8, Ly: int = 24, Lz: int = 8,
    hollow: bool = True,
    material_id: str = "CONCRETE",
) -> dict[str, NDArray]:
    """
    高塔結構 — 底部固定，頂部自由（適合風載測試）。
    hollow=True：空心正方形截面
    """
    shape = (Lx, Ly, Lz)
    props = _resolve_material(material_id)
    occupancy = np.zeros(shape, dtype=bool)

    if hollow:
        # 正方形截面外殼（厚度 1 體素）
        occupancy[0, :, :] = True
        occupancy[-1, :, :] = True
        occupancy[:, :, 0] = True
        occupancy[:, :, -1] = True
    else:
        occupancy[:] = True

    anchors = np.zeros(shape, dtype=bool)
    anchors[:, 0, :] = occupancy[:, 0, :]   # 底部固定

    return {
        "occupancy":     occupancy,
        "anchors":       anchors,
        "E_field":       np.where(occupancy, props["E"],   0).astype(np.float32),
        "nu_field":      np.where(occupancy, props["nu"],  0).astype(np.float32),
        "density_field": np.where(occupancy, props["rho"], 0).astype(np.float32),
        "rcomp_field":   np.where(occupancy, props["rcomp"], 0).astype(np.float32),
        "rtens_field":   np.where(occupancy, props["rtens"], 0).astype(np.float32),
    }
