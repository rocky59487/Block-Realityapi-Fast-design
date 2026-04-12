"""Map Minecraft block IDs to Block Reality material names.

This is a heuristic mapping table; users can override or extend it.
"""
from __future__ import annotations

# Material property table mirroring structure_gen.py MATERIALS
MATERIAL_PROPS: dict[str, dict[str, float]] = {
    "plain_concrete": {"E_pa": 25e9, "nu": 0.18, "density": 2400.0, "rcomp": 25.0, "rtens": 2.5},
    "concrete":       {"E_pa": 30e9, "nu": 0.20, "density": 2350.0, "rcomp": 30.0, "rtens": 3.0},
    "rebar":          {"E_pa": 200e9, "nu": 0.29, "density": 7850.0, "rcomp": 250.0, "rtens": 400.0},
    "brick":          {"E_pa": 5e9, "nu": 0.15, "density": 1800.0, "rcomp": 10.0, "rtens": 0.5},
    "timber":         {"E_pa": 11e9, "nu": 0.35, "density": 600.0, "rcomp": 5.0, "rtens": 8.0},
    "steel":          {"E_pa": 200e9, "nu": 0.29, "density": 7850.0, "rcomp": 350.0, "rtens": 500.0},
    "stone":          {"E_pa": 50e9, "nu": 0.25, "density": 2400.0, "rcomp": 30.0, "rtens": 3.0},
    "glass":          {"E_pa": 70e9, "nu": 0.22, "density": 2500.0, "rcomp": 100.0, "rtens": 30.0},
    "sand":           {"E_pa": 0.01e9, "nu": 0.30, "density": 1600.0, "rcomp": 0.1, "rtens": 0.0},
    "obsidian":       {"E_pa": 70e9, "nu": 0.20, "density": 2600.0, "rcomp": 200.0, "rtens": 5.0},
    "bedrock":        {"E_pa": 1e12, "nu": 0.10, "density": 3000.0, "rcomp": 1e6, "rtens": 1e6},
}

# Keyword-based block ID -> material mapping (lower-cased matching)
BLOCK_MATERIAL_KEYWORDS: list[tuple[tuple[str, ...], str]] = [
    # Concrete
    (("concrete",), "concrete"),
    # Stone-like
    (("stone", "granite", "diorite", "andesite", "cobblestone", "deepslate", "tuff",
      "calcite", "dripstone", "basalt", "blackstone", "netherrack", "end_stone",
      "prismarine", "quartz_block", "smooth_quartz"), "stone"),
    # Wood / timber
    (("planks", "log", "wood", "stripped_", "fence", "door", "trapdoor",
      "sign", "hanging_sign", "beehive", "bookshelf", "chest", "barrel",
      "crafting_table", "smithing_table", "fletching_table", "cartography_table",
      "loom", "composter", "note_block", "jukebox", "piston", "sticky_piston"), "timber"),
    # Metals -> steel (simplified)
    (("iron_", "gold_", "diamond_", "emerald_", "netherite_", "copper_",
      "cut_copper", "exposed_copper", "weathered_copper", "oxidized_copper",
      "lantern", "soul_lantern", "hopper", "cauldron", "anvil", "chipped_anvil",
      "damaged_anvil", "rail", "detector_rail", "activator_rail", "powered_rail"), "steel"),
    # Glass
    (("glass",), "glass"),
    # Brick
    (("bricks", "brick_", "mud_bricks", "nether_bricks", "red_nether_bricks"), "brick"),
    # Obsidian
    (("obsidian",), "obsidian"),
    # Loose / soil -> sand (low strength)
    (("sand", "gravel", "dirt", "clay", "mud", "podzol", "mycelium",
      "grass_block", "farmland", "snow_block", "snow"), "sand"),
    # Bedrock
    (("bedrock", "command_block", "barrier", "structure_block", "jigsaw"), "bedrock"),
]

# Explicit overrides for exact block IDs (takes precedence over keywords)
BLOCK_MATERIAL_EXACT: dict[str, str] = {
    "minecraft:air": None,
    "minecraft:cave_air": None,
    "minecraft:void_air": None,
    "minecraft:water": None,
    "minecraft:lava": None,
    "minecraft:fire": None,
    "minecraft:soul_fire": None,
    "minecraft:grass": None,
    "minecraft:tall_grass": None,
    "minecraft:fern": None,
    "minecraft:large_fern": None,
    "minecraft:leaves": None,
    "minecraft:azalea_leaves": None,
    "minecraft:flowering_azalea_leaves": None,
    "minecraft:mangrove_leaves": None,
    "minecraft:cherry_leaves": None,
    "minecraft:oak_leaves": None,
    "minecraft:spruce_leaves": None,
    "minecraft:birch_leaves": None,
    "minecraft:jungle_leaves": None,
    "minecraft:acacia_leaves": None,
    "minecraft:dark_oak_leaves": None,
    "minecraft:pale_oak_leaves": None,
}


def guess_material(block_id: str) -> str | None:
    """Guess Block Reality material from a Minecraft block ID.

    Returns None for air / fluids / plants (should be skipped).
    Falls back to 'stone' if no keyword matches.
    """
    block_id = block_id.lower().strip()

    # 1. Exact override
    if block_id in BLOCK_MATERIAL_EXACT:
        return BLOCK_MATERIAL_EXACT[block_id]

    # 2. Keyword matching
    for keywords, material in BLOCK_MATERIAL_KEYWORDS:
        for kw in keywords:
            if kw in block_id:
                return material

    # 3. Default fallback
    return "stone"
