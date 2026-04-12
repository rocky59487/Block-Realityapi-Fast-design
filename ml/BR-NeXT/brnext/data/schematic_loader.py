"""Load Minecraft schematic files (.litematic, .nbt, .mcstructure) into raw block lists.

Python port of LitematicImporter.java logic with additional support for
structure block .nbt and Bedrock .mcstructure formats.
"""
from __future__ import annotations

import gzip
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np


@dataclass
class SchematicData:
    """Unified representation of a loaded schematic."""

    name: str
    size: tuple[int, int, int]  # (sx, sy, sz)
    blocks: list[dict]  # each dict: {"x": int, "y": int, "z": int, "block": str}


def _ceil_log2(n: int) -> int:
    if n <= 1:
        return 0
    return 32 - (n - 1).bit_length()


def _unpack_block_states(packed: list[int] | np.ndarray, volume: int, palette_size: int) -> np.ndarray:
    """Decode Litematica packed long array into palette indices.

    Mirrors LitematicImporter.unpackBlockStates().
    """
    packed = np.asarray(packed, dtype=np.uint64)
    bits_per_entry = max(2, _ceil_log2(palette_size))
    entries_per_long = 64 // bits_per_entry
    mask = (1 << bits_per_entry) - 1

    result = np.zeros(volume, dtype=np.int32)
    for i in range(volume):
        long_idx = i // entries_per_long
        bit_offset = (i % entries_per_long) * bits_per_entry
        if long_idx >= len(packed):
            break
        result[i] = int((packed[long_idx] >> bit_offset) & mask)
    return result


def _read_nbt(path: Path) -> dict[str, Any]:
    """Read any NBT file (gzip or raw) into plain Python structures."""
    import nbtlib

    data = nbtlib.load(str(path))
    # nbtlib 2.0 returns a File object; its root tag acts like a dict
    return _nbt_to_dict(data)


def _nbt_to_dict(tag) -> Any:
    import nbtlib

    if isinstance(tag, nbtlib.Compound):
        return {k: _nbt_to_dict(v) for k, v in tag.items()}
    if isinstance(tag, nbtlib.List):
        return [_nbt_to_dict(v) for v in tag]
    if isinstance(tag, (nbtlib.ByteArray, nbtlib.IntArray, nbtlib.LongArray)):
        return tag.tolist()
    if isinstance(tag, (nbtlib.Byte, nbtlib.Short, nbtlib.Int, nbtlib.Long)):
        return int(tag)
    if isinstance(tag, (nbtlib.Float, nbtlib.Double)):
        return float(tag)
    if isinstance(tag, nbtlib.String):
        return str(tag)
    # Fallback for plain numpy arrays or lists inside nbtlib wrappers
    if hasattr(tag, "tolist"):
        return tag.tolist()
    return tag


# ═══════════════════════════════════════════════════════════════════════════════
# Format parsers
# ═══════════════════════════════════════════════════════════════════════════════

def parse_litematic(path: Path) -> SchematicData | None:
    """Parse a .litematic file (Litematica format)."""
    try:
        root = _read_nbt(path)
    except Exception as e:
        print(f"  Failed to read litematic {path}: {e}")
        return None

    metadata = root.get("Metadata", {})
    name = metadata.get("Name", path.stem)
    enclosing = metadata.get("EnclosingSize", {})
    size = (int(enclosing.get("x", 0)), int(enclosing.get("y", 0)), int(enclosing.get("z", 0)))

    regions = root.get("Regions", {})
    if not regions:
        print(f"  No regions in {path}")
        return None

    blocks = []
    for region_name, region in regions.items():
        if not isinstance(region, dict):
            continue

        # Position offset
        pos = region.get("Position", {})
        off_x, off_y, off_z = int(pos.get("x", 0)), int(pos.get("y", 0)), int(pos.get("z", 0))

        # Size (can be negative)
        sz = region.get("Size", {})
        sx, sy, sz_val = int(sz.get("x", 1)), int(sz.get("y", 1)), int(sz.get("z", 1))
        abs_sx, abs_sy, abs_sz = abs(sx), abs(sy), abs(sz_val)
        adj_x = sx + 1 if sx < 0 else 0
        adj_y = sy + 1 if sy < 0 else 0
        adj_z = sz_val + 1 if sz_val < 0 else 0

        # Palette
        palette_tag = region.get("BlockStatePalette", [])
        palette = []
        for entry in palette_tag:
            if isinstance(entry, dict):
                block_name = entry.get("Name", "minecraft:air")
                palette.append(block_name)
            else:
                palette.append("minecraft:air")
        palette_size = len(palette)

        # BlockStates packed long array
        packed = region.get("BlockStates", [])
        volume = abs_sx * abs_sy * abs_sz
        if not packed or palette_size == 0:
            continue

        indices = _unpack_block_states(packed, volume, palette_size)

        for y in range(abs_sy):
            for z in range(abs_sz):
                for x in range(abs_sx):
                    idx = y * abs_sx * abs_sz + z * abs_sx + x
                    if idx >= len(indices):
                        continue
                    pidx = int(indices[idx])
                    if pidx < 0 or pidx >= palette_size:
                        continue
                    block_name = palette[pidx]
                    if block_name == "minecraft:air":
                        continue
                    blocks.append({
                        "x": off_x + adj_x + x,
                        "y": off_y + adj_y + y,
                        "z": off_z + adj_z + z,
                        "block": block_name,
                    })

    if not blocks:
        return None
    return SchematicData(name=name, size=size, blocks=blocks)


def parse_nbt_structure(path: Path) -> SchematicData | None:
    """Parse a vanilla Minecraft .nbt structure file."""
    try:
        root = _read_nbt(path)
    except Exception as e:
        print(f"  Failed to read nbt {path}: {e}")
        return None

    size = root.get("size", [0, 0, 0])
    sx, sy, sz = int(size[0]), int(size[1]), int(size[2])
    palette = root.get("palette", [])
    blocks_tag = root.get("blocks", [])

    block_list = []
    for b in blocks_tag:
        pos = b.get("pos", [0, 0, 0])
        state_idx = int(b.get("state", 0))
        block_name = "minecraft:air"
        if 0 <= state_idx < len(palette):
            block_name = palette[state_idx].get("Name", "minecraft:air")
        if block_name == "minecraft:air":
            continue
        block_list.append({
            "x": int(pos[0]),
            "y": int(pos[1]),
            "z": int(pos[2]),
            "block": block_name,
        })

    if not block_list:
        return None
    return SchematicData(name=path.stem, size=(sx, sy, sz), blocks=block_list)


def parse_mcstructure(path: Path) -> SchematicData | None:
    """Parse a Bedrock .mcstructure file."""
    try:
        root = _read_nbt(path)
    except Exception as e:
        print(f"  Failed to read mcstructure {path}: {e}")
        return None

    structure = root.get("structure", {})
    size = structure.get("size", [0, 0, 0])
    sx, sy, sz = int(size[0]), int(size[1]), int(size[2])

    palette = structure.get("palette", {}).get("default", {}).get("block_palette", [])
    indices_tag = structure.get("block_indices", [])
    if not indices_tag or not palette:
        return None

    # Bedrock stores indices as a list of arrays; use the first one
    indices = list(indices_tag[0])
    blocks = []
    for i, idx in enumerate(indices):
        idx = int(idx)
        if idx < 0 or idx >= len(palette):
            continue
        block_name = palette[idx].get("name", "minecraft:air")
        if block_name == "minecraft:air":
            continue
        x = i % sx
        y = (i // sx) % sy
        z = i // (sx * sy)
        blocks.append({"x": x, "y": y, "z": z, "block": block_name})

    if not blocks:
        return None
    return SchematicData(name=path.stem, size=(sx, sy, sz), blocks=blocks)


# ═══════════════════════════════════════════════════════════════════════════════
# Public API
# ═══════════════════════════════════════════════════════════════════════════════

class SchematicLoader:
    """Load .litematic / .nbt / .mcstructure files into SchematicData."""

    SUPPORTED_SUFFIXES = {".litematic", ".nbt", ".mcstructure"}

    def __init__(self, schematic_dir: str | Path):
        self.schematic_dir = Path(schematic_dir)

    def list_files(self) -> list[Path]:
        files = []
        for suffix in self.SUPPORTED_SUFFIXES:
            files.extend(self.schematic_dir.rglob(f"*{suffix}"))
        return sorted(set(files))

    def load(self, path: Path) -> SchematicData | None:
        suffix = path.suffix.lower()
        if suffix == ".litematic":
            return parse_litematic(path)
        if suffix == ".nbt":
            return parse_nbt_structure(path)
        if suffix == ".mcstructure":
            return parse_mcstructure(path)
        return None
