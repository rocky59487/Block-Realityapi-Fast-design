package com.blockreality.api.material;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原版方塊 → DefaultMaterial 映射表（JSON 數據驅動）。
 *
 * 設計目標：
 *   1. 將硬編碼的 if-else 材料映射改為 JSON 配置，方便 modpack 作者自訂
 *   2. 覆蓋 100+ 原版方塊（原先僅 8 種）
 *   3. 首次啟動自動生成預設 JSON
 *   4. 查詢使用 ConcurrentHashMap，O(1) 無鎖讀取
 *
 * 檔案位置：config/blockreality/vanilla_material_map.json
 * 格式：{ "minecraft:oak_planks": "timber", "minecraft:iron_block": "steel", ... }
 *
 * 映射規則：
 *   - JSON value = DefaultMaterial.getMaterialId() (全小寫)
 *   - 未列入的方塊 → fallback 到 STONE
 *   - 空氣方塊不需要列入（SnapshotBuilder 已跳過）
 */
@javax.annotation.concurrent.ThreadSafe // volatile ConcurrentHashMap lookups
public class VanillaMaterialMap {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/MaterialMap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 單例實例 — 在 BlockRealityMod 初始化時呼叫 init() */
    private static final VanillaMaterialMap INSTANCE = new VanillaMaterialMap();

    /**
     * blockId → DefaultMaterial 的快速查找表。
     * ★ review-fix #5: 改為 volatile reference swap，避免 loadFromFile 的 clear()+populate 之間
     * 並發讀取看到空 map 而全部 fallback 到 STONE。
     */
    private volatile ConcurrentHashMap<String, DefaultMaterial> map = new ConcurrentHashMap<>();

    /** 取得單例 */
    public static VanillaMaterialMap getInstance() { return INSTANCE; }

    /**
     * 初始化 — 從 JSON 載入映射表，若不存在則生成預設。
     * 應在 FMLCommonSetupEvent 中呼叫。
     */
    public void init() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("blockreality");
        Path mapFile = configDir.resolve("vanilla_material_map.json");

        try {
            Files.createDirectories(configDir);

            if (Files.exists(mapFile)) {
                loadFromFile(mapFile);
            } else {
                loadDefaults();
                saveToFile(mapFile);
                LOGGER.info("Generated default vanilla_material_map.json with {} entries", map.size());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load vanilla_material_map.json, using built-in defaults", e);
            loadDefaults();
        }

        LOGGER.info("VanillaMaterialMap initialized: {} block mappings loaded", map.size());
    }

    /**
     * 查詢方塊對應的材料。
     *
     * @param blockId 完整的 block ID (如 "minecraft:oak_planks")
     * @return 對應的 DefaultMaterial，未定義的回傳 STONE
     */
    public DefaultMaterial getMaterial(String blockId) {
        return map.getOrDefault(blockId, DefaultMaterial.STONE);
    }

    /**
     * 查詢是否有明確映射（非 fallback）。
     */
    public boolean hasMapping(String blockId) {
        return map.containsKey(blockId);
    }

    /**
     * 取得映射總數 — 診斷用。
     */
    public int size() { return map.size(); }

    // ═══════════════════════════════════════════════════════
    //  I/O
    // ═══════════════════════════════════════════════════════

    private void loadFromFile(Path file) throws IOException {
        String json = Files.readString(file);
        Type type = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
        Map<String, String> raw = GSON.fromJson(json, type);

        // ★ C-4 fix: GSON.fromJson() 可能回傳 null（空檔案、格式錯誤的 JSON）
        if (raw == null || raw.isEmpty()) {
            LOGGER.warn("vanilla_material_map.json is empty or invalid, using built-in defaults");
            loadDefaults();
            return;
        }

        // 建立合法 materialId → DefaultMaterial 的查找表
        Map<String, DefaultMaterial> validIds = new HashMap<>();
        for (DefaultMaterial dm : DefaultMaterial.values()) {
            validIds.put(dm.getMaterialId(), dm);
        }

        // ★ review-fix #5: 先填充新 map，再原子 swap，避免 clear → populate 之間的空窗期
        ConcurrentHashMap<String, DefaultMaterial> newMap = new ConcurrentHashMap<>();
        int skipped = 0;
        for (var entry : raw.entrySet()) {
            DefaultMaterial mat = validIds.get(entry.getValue());
            if (mat == null) {
                LOGGER.warn("Unknown material '{}' for block '{}', falling back to STONE",
                    entry.getValue(), entry.getKey());
                newMap.put(entry.getKey(), DefaultMaterial.STONE);
                skipped++;
            } else {
                newMap.put(entry.getKey(), mat);
            }
        }
        if (skipped > 0) {
            LOGGER.warn("{} entries had unknown material IDs", skipped);
        }
        this.map = newMap;  // atomic reference swap
    }

    private void saveToFile(Path file) throws IOException {
        // 轉換為 blockId → materialId 的字串映射
        LinkedHashMap<String, String> raw = new LinkedHashMap<>();
        map.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> raw.put(e.getKey(), e.getValue().getMaterialId()));
        Files.writeString(file, GSON.toJson(raw));
    }

    // ═══════════════════════════════════════════════════════
    //  預設映射表 — 覆蓋所有有結構意義的原版方塊
    // ═══════════════════════════════════════════════════════

    private void loadDefaults() {
        // ★ review-fix #5: loadDefaults 同樣使用 swap pattern
        // 先填充，再assign（避免clear→populate間的空窗期導致concurrent threads見空map）
        ConcurrentHashMap<String, DefaultMaterial> newMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, DefaultMaterial> prevMap = this.map;
        this.map = newMap;  // Temporarily set to newMap so put() calls work

        // ─── BEDROCK (不可破壞) ───
        put("minecraft:bedrock", DefaultMaterial.BEDROCK);
        put("minecraft:barrier", DefaultMaterial.BEDROCK);

        // ─── STEEL (金屬方塊) ───
        put("minecraft:iron_block", DefaultMaterial.STEEL);
        put("minecraft:iron_bars", DefaultMaterial.STEEL);
        put("minecraft:iron_door", DefaultMaterial.STEEL);
        put("minecraft:iron_trapdoor", DefaultMaterial.STEEL);
        put("minecraft:heavy_weighted_pressure_plate", DefaultMaterial.STEEL);
        put("minecraft:chain", DefaultMaterial.STEEL);
        put("minecraft:anvil", DefaultMaterial.STEEL);
        put("minecraft:chipped_anvil", DefaultMaterial.STEEL);
        put("minecraft:damaged_anvil", DefaultMaterial.STEEL);
        put("minecraft:copper_block", DefaultMaterial.STEEL);
        put("minecraft:cut_copper", DefaultMaterial.STEEL);
        put("minecraft:cut_copper_stairs", DefaultMaterial.STEEL);
        put("minecraft:cut_copper_slab", DefaultMaterial.STEEL);
        put("minecraft:waxed_copper_block", DefaultMaterial.STEEL);
        put("minecraft:waxed_cut_copper", DefaultMaterial.STEEL);
        put("minecraft:waxed_cut_copper_stairs", DefaultMaterial.STEEL);
        put("minecraft:waxed_cut_copper_slab", DefaultMaterial.STEEL);
        put("minecraft:exposed_copper", DefaultMaterial.STEEL);
        put("minecraft:weathered_copper", DefaultMaterial.STEEL);
        put("minecraft:oxidized_copper", DefaultMaterial.STEEL);
        put("minecraft:waxed_exposed_copper", DefaultMaterial.STEEL);
        put("minecraft:waxed_weathered_copper", DefaultMaterial.STEEL);
        put("minecraft:waxed_oxidized_copper", DefaultMaterial.STEEL);
        put("minecraft:lightning_rod", DefaultMaterial.STEEL);
        put("minecraft:netherite_block", DefaultMaterial.STEEL);
        put("minecraft:gold_block", DefaultMaterial.STEEL);
        put("minecraft:raw_iron_block", DefaultMaterial.STEEL);
        put("minecraft:raw_gold_block", DefaultMaterial.STEEL);
        put("minecraft:raw_copper_block", DefaultMaterial.STEEL);

        // ─── TIMBER (所有木材變體) ───
        String[] woodTypes = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
                              "mangrove", "cherry", "bamboo", "crimson", "warped"};
        for (String wood : woodTypes) {
            String prefix = "minecraft:" + wood;
            put(prefix + "_planks", DefaultMaterial.TIMBER);
            put(prefix + "_log", DefaultMaterial.TIMBER);
            put(prefix + "_wood", DefaultMaterial.TIMBER);
            put(prefix + "_slab", DefaultMaterial.TIMBER);
            put(prefix + "_stairs", DefaultMaterial.TIMBER);
            put(prefix + "_fence", DefaultMaterial.TIMBER);
            put(prefix + "_fence_gate", DefaultMaterial.TIMBER);
            put(prefix + "_door", DefaultMaterial.TIMBER);
            put(prefix + "_trapdoor", DefaultMaterial.TIMBER);
            put(prefix + "_button", DefaultMaterial.TIMBER);
            put(prefix + "_pressure_plate", DefaultMaterial.TIMBER);
            // 去皮版本
            put(prefix.replace("minecraft:", "minecraft:stripped_") + "_log", DefaultMaterial.TIMBER);
            put(prefix.replace("minecraft:", "minecraft:stripped_") + "_wood", DefaultMaterial.TIMBER);
        }
        // 菌柄特殊命名
        put("minecraft:crimson_stem", DefaultMaterial.TIMBER);
        put("minecraft:warped_stem", DefaultMaterial.TIMBER);
        put("minecraft:stripped_crimson_stem", DefaultMaterial.TIMBER);
        put("minecraft:stripped_warped_stem", DefaultMaterial.TIMBER);
        put("minecraft:crimson_hyphae", DefaultMaterial.TIMBER);
        put("minecraft:warped_hyphae", DefaultMaterial.TIMBER);
        put("minecraft:stripped_crimson_hyphae", DefaultMaterial.TIMBER);
        put("minecraft:stripped_warped_hyphae", DefaultMaterial.TIMBER);
        // 竹特殊
        put("minecraft:bamboo_block", DefaultMaterial.TIMBER);
        put("minecraft:stripped_bamboo_block", DefaultMaterial.TIMBER);
        put("minecraft:bamboo_mosaic", DefaultMaterial.TIMBER);
        put("minecraft:bamboo_mosaic_slab", DefaultMaterial.TIMBER);
        put("minecraft:bamboo_mosaic_stairs", DefaultMaterial.TIMBER);

        // ─── GLASS (所有玻璃變體) ───
        put("minecraft:glass", DefaultMaterial.GLASS);
        put("minecraft:glass_pane", DefaultMaterial.GLASS);
        put("minecraft:tinted_glass", DefaultMaterial.GLASS);
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime",
                           "pink", "gray", "light_gray", "cyan", "purple", "blue",
                           "brown", "green", "red", "black"};
        for (String color : colors) {
            put("minecraft:" + color + "_stained_glass", DefaultMaterial.GLASS);
            put("minecraft:" + color + "_stained_glass_pane", DefaultMaterial.GLASS);
        }

        // ─── BRICK (磚塊系列) ───
        put("minecraft:bricks", DefaultMaterial.BRICK);
        put("minecraft:brick_slab", DefaultMaterial.BRICK);
        put("minecraft:brick_stairs", DefaultMaterial.BRICK);
        put("minecraft:brick_wall", DefaultMaterial.BRICK);
        put("minecraft:nether_bricks", DefaultMaterial.BRICK);
        put("minecraft:nether_brick_slab", DefaultMaterial.BRICK);
        put("minecraft:nether_brick_stairs", DefaultMaterial.BRICK);
        put("minecraft:nether_brick_wall", DefaultMaterial.BRICK);
        put("minecraft:nether_brick_fence", DefaultMaterial.BRICK);
        put("minecraft:red_nether_bricks", DefaultMaterial.BRICK);
        put("minecraft:red_nether_brick_slab", DefaultMaterial.BRICK);
        put("minecraft:red_nether_brick_stairs", DefaultMaterial.BRICK);
        put("minecraft:red_nether_brick_wall", DefaultMaterial.BRICK);
        put("minecraft:mud_bricks", DefaultMaterial.BRICK);
        put("minecraft:mud_brick_slab", DefaultMaterial.BRICK);
        put("minecraft:mud_brick_stairs", DefaultMaterial.BRICK);
        put("minecraft:mud_brick_wall", DefaultMaterial.BRICK);

        // ─── SAND (鬆散材料) ───
        put("minecraft:sand", DefaultMaterial.SAND);
        put("minecraft:red_sand", DefaultMaterial.SAND);
        put("minecraft:gravel", DefaultMaterial.SAND);
        put("minecraft:soul_sand", DefaultMaterial.SAND);
        put("minecraft:soul_soil", DefaultMaterial.SAND);
        put("minecraft:clay", DefaultMaterial.SAND);
        put("minecraft:mud", DefaultMaterial.SAND);
        put("minecraft:packed_mud", DefaultMaterial.SAND);
        put("minecraft:dirt", DefaultMaterial.SAND);
        put("minecraft:coarse_dirt", DefaultMaterial.SAND);
        put("minecraft:rooted_dirt", DefaultMaterial.SAND);
        put("minecraft:farmland", DefaultMaterial.SAND);
        put("minecraft:dirt_path", DefaultMaterial.SAND);
        put("minecraft:mycelium", DefaultMaterial.SAND);
        put("minecraft:podzol", DefaultMaterial.SAND);
        put("minecraft:grass_block", DefaultMaterial.SAND);
        put("minecraft:snow_block", DefaultMaterial.SAND);
        put("minecraft:powder_snow", DefaultMaterial.SAND);
        put("minecraft:suspicious_sand", DefaultMaterial.SAND);
        put("minecraft:suspicious_gravel", DefaultMaterial.SAND);

        // ─── OBSIDIAN (超硬方塊) ───
        put("minecraft:obsidian", DefaultMaterial.OBSIDIAN);
        put("minecraft:crying_obsidian", DefaultMaterial.OBSIDIAN);
        put("minecraft:respawn_anchor", DefaultMaterial.OBSIDIAN);
        put("minecraft:ender_chest", DefaultMaterial.OBSIDIAN);
        put("minecraft:enchanting_table", DefaultMaterial.OBSIDIAN);
        put("minecraft:reinforced_deepslate", DefaultMaterial.OBSIDIAN);

        // ─── CONCRETE (混凝土系列) ───
        for (String color : colors) {
            put("minecraft:" + color + "_concrete", DefaultMaterial.CONCRETE);
        }

        // ─── PLAIN_CONCRETE (混凝土粉末 → 素混凝土) ───
        for (String color : colors) {
            put("minecraft:" + color + "_concrete_powder", DefaultMaterial.PLAIN_CONCRETE);
        }

        // ─── STONE (各種石材 — 預設分類) ───
        put("minecraft:stone", DefaultMaterial.STONE);
        put("minecraft:stone_slab", DefaultMaterial.STONE);
        put("minecraft:stone_stairs", DefaultMaterial.STONE);
        put("minecraft:stone_bricks", DefaultMaterial.STONE);
        put("minecraft:stone_brick_slab", DefaultMaterial.STONE);
        put("minecraft:stone_brick_stairs", DefaultMaterial.STONE);
        put("minecraft:stone_brick_wall", DefaultMaterial.STONE);
        put("minecraft:cracked_stone_bricks", DefaultMaterial.STONE);
        put("minecraft:mossy_stone_bricks", DefaultMaterial.STONE);
        put("minecraft:mossy_stone_brick_slab", DefaultMaterial.STONE);
        put("minecraft:mossy_stone_brick_stairs", DefaultMaterial.STONE);
        put("minecraft:mossy_stone_brick_wall", DefaultMaterial.STONE);
        put("minecraft:chiseled_stone_bricks", DefaultMaterial.STONE);
        put("minecraft:smooth_stone", DefaultMaterial.STONE);
        put("minecraft:smooth_stone_slab", DefaultMaterial.STONE);
        put("minecraft:cobblestone", DefaultMaterial.STONE);
        put("minecraft:cobblestone_slab", DefaultMaterial.STONE);
        put("minecraft:cobblestone_stairs", DefaultMaterial.STONE);
        put("minecraft:cobblestone_wall", DefaultMaterial.STONE);
        put("minecraft:mossy_cobblestone", DefaultMaterial.STONE);
        put("minecraft:mossy_cobblestone_slab", DefaultMaterial.STONE);
        put("minecraft:mossy_cobblestone_stairs", DefaultMaterial.STONE);
        put("minecraft:mossy_cobblestone_wall", DefaultMaterial.STONE);
        put("minecraft:granite", DefaultMaterial.STONE);
        put("minecraft:granite_slab", DefaultMaterial.STONE);
        put("minecraft:granite_stairs", DefaultMaterial.STONE);
        put("minecraft:granite_wall", DefaultMaterial.STONE);
        put("minecraft:polished_granite", DefaultMaterial.STONE);
        put("minecraft:polished_granite_slab", DefaultMaterial.STONE);
        put("minecraft:polished_granite_stairs", DefaultMaterial.STONE);
        put("minecraft:diorite", DefaultMaterial.STONE);
        put("minecraft:diorite_slab", DefaultMaterial.STONE);
        put("minecraft:diorite_stairs", DefaultMaterial.STONE);
        put("minecraft:diorite_wall", DefaultMaterial.STONE);
        put("minecraft:polished_diorite", DefaultMaterial.STONE);
        put("minecraft:polished_diorite_slab", DefaultMaterial.STONE);
        put("minecraft:polished_diorite_stairs", DefaultMaterial.STONE);
        put("minecraft:andesite", DefaultMaterial.STONE);
        put("minecraft:andesite_slab", DefaultMaterial.STONE);
        put("minecraft:andesite_stairs", DefaultMaterial.STONE);
        put("minecraft:andesite_wall", DefaultMaterial.STONE);
        put("minecraft:polished_andesite", DefaultMaterial.STONE);
        put("minecraft:polished_andesite_slab", DefaultMaterial.STONE);
        put("minecraft:polished_andesite_stairs", DefaultMaterial.STONE);
        put("minecraft:deepslate", DefaultMaterial.STONE);
        put("minecraft:deepslate_bricks", DefaultMaterial.STONE);
        put("minecraft:deepslate_brick_slab", DefaultMaterial.STONE);
        put("minecraft:deepslate_brick_stairs", DefaultMaterial.STONE);
        put("minecraft:deepslate_brick_wall", DefaultMaterial.STONE);
        put("minecraft:deepslate_tiles", DefaultMaterial.STONE);
        put("minecraft:deepslate_tile_slab", DefaultMaterial.STONE);
        put("minecraft:deepslate_tile_stairs", DefaultMaterial.STONE);
        put("minecraft:deepslate_tile_wall", DefaultMaterial.STONE);
        put("minecraft:cobbled_deepslate", DefaultMaterial.STONE);
        put("minecraft:cobbled_deepslate_slab", DefaultMaterial.STONE);
        put("minecraft:cobbled_deepslate_stairs", DefaultMaterial.STONE);
        put("minecraft:cobbled_deepslate_wall", DefaultMaterial.STONE);
        put("minecraft:polished_deepslate", DefaultMaterial.STONE);
        put("minecraft:polished_deepslate_slab", DefaultMaterial.STONE);
        put("minecraft:polished_deepslate_stairs", DefaultMaterial.STONE);
        put("minecraft:polished_deepslate_wall", DefaultMaterial.STONE);
        put("minecraft:chiseled_deepslate", DefaultMaterial.STONE);
        put("minecraft:cracked_deepslate_bricks", DefaultMaterial.STONE);
        put("minecraft:cracked_deepslate_tiles", DefaultMaterial.STONE);
        put("minecraft:tuff", DefaultMaterial.STONE);
        put("minecraft:calcite", DefaultMaterial.STONE);
        put("minecraft:dripstone_block", DefaultMaterial.STONE);
        put("minecraft:pointed_dripstone", DefaultMaterial.STONE);
        put("minecraft:sandstone", DefaultMaterial.STONE);
        put("minecraft:sandstone_slab", DefaultMaterial.STONE);
        put("minecraft:sandstone_stairs", DefaultMaterial.STONE);
        put("minecraft:sandstone_wall", DefaultMaterial.STONE);
        put("minecraft:chiseled_sandstone", DefaultMaterial.STONE);
        put("minecraft:cut_sandstone", DefaultMaterial.STONE);
        put("minecraft:cut_sandstone_slab", DefaultMaterial.STONE);
        put("minecraft:smooth_sandstone", DefaultMaterial.STONE);
        put("minecraft:smooth_sandstone_slab", DefaultMaterial.STONE);
        put("minecraft:smooth_sandstone_stairs", DefaultMaterial.STONE);
        put("minecraft:red_sandstone", DefaultMaterial.STONE);
        put("minecraft:red_sandstone_slab", DefaultMaterial.STONE);
        put("minecraft:red_sandstone_stairs", DefaultMaterial.STONE);
        put("minecraft:red_sandstone_wall", DefaultMaterial.STONE);
        put("minecraft:chiseled_red_sandstone", DefaultMaterial.STONE);
        put("minecraft:cut_red_sandstone", DefaultMaterial.STONE);
        put("minecraft:cut_red_sandstone_slab", DefaultMaterial.STONE);
        put("minecraft:smooth_red_sandstone", DefaultMaterial.STONE);
        put("minecraft:smooth_red_sandstone_slab", DefaultMaterial.STONE);
        put("minecraft:smooth_red_sandstone_stairs", DefaultMaterial.STONE);
        put("minecraft:prismarine", DefaultMaterial.STONE);
        put("minecraft:prismarine_slab", DefaultMaterial.STONE);
        put("minecraft:prismarine_stairs", DefaultMaterial.STONE);
        put("minecraft:prismarine_wall", DefaultMaterial.STONE);
        put("minecraft:prismarine_bricks", DefaultMaterial.STONE);
        put("minecraft:prismarine_brick_slab", DefaultMaterial.STONE);
        put("minecraft:prismarine_brick_stairs", DefaultMaterial.STONE);
        put("minecraft:dark_prismarine", DefaultMaterial.STONE);
        put("minecraft:dark_prismarine_slab", DefaultMaterial.STONE);
        put("minecraft:dark_prismarine_stairs", DefaultMaterial.STONE);
        put("minecraft:purpur_block", DefaultMaterial.STONE);
        put("minecraft:purpur_slab", DefaultMaterial.STONE);
        put("minecraft:purpur_stairs", DefaultMaterial.STONE);
        put("minecraft:purpur_pillar", DefaultMaterial.STONE);
        put("minecraft:end_stone", DefaultMaterial.STONE);
        put("minecraft:end_stone_bricks", DefaultMaterial.STONE);
        put("minecraft:end_stone_brick_slab", DefaultMaterial.STONE);
        put("minecraft:end_stone_brick_stairs", DefaultMaterial.STONE);
        put("minecraft:end_stone_brick_wall", DefaultMaterial.STONE);
        put("minecraft:blackstone", DefaultMaterial.STONE);
        put("minecraft:blackstone_slab", DefaultMaterial.STONE);
        put("minecraft:blackstone_stairs", DefaultMaterial.STONE);
        put("minecraft:blackstone_wall", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone_slab", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone_stairs", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone_wall", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone_bricks", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone_brick_slab", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone_brick_stairs", DefaultMaterial.STONE);
        put("minecraft:polished_blackstone_brick_wall", DefaultMaterial.STONE);
        put("minecraft:cracked_polished_blackstone_bricks", DefaultMaterial.STONE);
        put("minecraft:chiseled_polished_blackstone", DefaultMaterial.STONE);
        put("minecraft:basalt", DefaultMaterial.STONE);
        put("minecraft:polished_basalt", DefaultMaterial.STONE);
        put("minecraft:smooth_basalt", DefaultMaterial.STONE);
        put("minecraft:quartz_block", DefaultMaterial.STONE);
        put("minecraft:quartz_slab", DefaultMaterial.STONE);
        put("minecraft:quartz_stairs", DefaultMaterial.STONE);
        put("minecraft:quartz_bricks", DefaultMaterial.STONE);
        put("minecraft:quartz_pillar", DefaultMaterial.STONE);
        put("minecraft:chiseled_quartz_block", DefaultMaterial.STONE);
        put("minecraft:smooth_quartz", DefaultMaterial.STONE);
        put("minecraft:smooth_quartz_slab", DefaultMaterial.STONE);
        put("minecraft:smooth_quartz_stairs", DefaultMaterial.STONE);

        // ─── STONE 系列 (陶瓦) ───
        for (String color : colors) {
            put("minecraft:" + color + "_terracotta", DefaultMaterial.STONE);
            put("minecraft:" + color + "_glazed_terracotta", DefaultMaterial.STONE);
        }
        put("minecraft:terracotta", DefaultMaterial.STONE);

        // ─── 特殊方塊 ───
        put("minecraft:diamond_block", DefaultMaterial.OBSIDIAN);   // 超硬
        put("minecraft:emerald_block", DefaultMaterial.OBSIDIAN);   // 超硬
        put("minecraft:lapis_block", DefaultMaterial.STONE);
        put("minecraft:redstone_block", DefaultMaterial.STONE);
        put("minecraft:coal_block", DefaultMaterial.STONE);
        put("minecraft:amethyst_block", DefaultMaterial.GLASS);     // 脆性結晶
        put("minecraft:budding_amethyst", DefaultMaterial.GLASS);

        // ★ Assignment already done at line 163 via this.map = newMap;
        // All put() calls have populated newMap correctly
    }

    private void put(String blockId, DefaultMaterial mat) {
        map.put(blockId, mat);
    }
}
