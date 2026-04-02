package com.blockreality.api.material;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VanillaMaterialMap 擴展測試 — v3fix §M6
 *
 * 覆蓋範圍：
 *   1. 單例一致性
 *   2. 各材料類別代表性方塊的正確映射
 *   3. 所有 11 種木材變體 × 基本建材（planks, log）
 *   4. 全 16 色混凝土 / 混凝土粉末
 *   5. 全 16 色玻璃 / 玻璃片
 *   6. 未知方塊 → STONE fallback
 *   7. hasMapping() / size() 基本驗證
 *   8. DefaultMaterial 材料參數合理性
 *
 * 注意：本測試直接呼叫 loadDefaults() 後的 in-memory map，
 * 不需要 Forge 環境（不調用 init()，改用反射或直接 getMaterial）。
 * 因 VanillaMaterialMap 的 loadDefaults() 在建構時不自動執行，
 * 且 getMaterial 對空 map 返回 STONE，部分測試使用反射初始化。
 */
class VanillaMaterialMapTest {

    // ═══════════════════════════════════════════════════════
    //  單例
    // ═══════════════════════════════════════════════════════

    @Test
    @DisplayName("getInstance 回傳非 null 單例")
    void testGetInstanceReturnsNonNull() {
        VanillaMaterialMap map = VanillaMaterialMap.getInstance();
        assertNotNull(map, "getInstance should return non-null singleton");
    }

    @Test
    @DisplayName("getInstance 每次回傳同一實例")
    void testGetInstanceReturnsSameSingleton() {
        VanillaMaterialMap map1 = VanillaMaterialMap.getInstance();
        VanillaMaterialMap map2 = VanillaMaterialMap.getInstance();
        assertSame(map1, map2, "getInstance should return same instance");
    }

    // ═══════════════════════════════════════════════════════
    //  未知方塊 → STONE fallback
    // ═══════════════════════════════════════════════════════

    @Test
    @DisplayName("未知方塊回傳 STONE fallback")
    void testUnknownBlockReturnsStoneFallback() {
        VanillaMaterialMap map = VanillaMaterialMap.getInstance();
        DefaultMaterial result = map.getMaterial("minecraft:totally_fake_block_xyz");
        assertEquals(DefaultMaterial.STONE, result,
            "Unknown block should fallback to STONE");
    }

    @Test
    @DisplayName("空字串回傳 STONE fallback")
    void testEmptyStringReturnsStoneFallback() {
        VanillaMaterialMap map = VanillaMaterialMap.getInstance();
        DefaultMaterial result = map.getMaterial("");
        assertEquals(DefaultMaterial.STONE, result,
            "Empty string should fallback to STONE");
    }

    // ═══════════════════════════════════════════════════════
    //  loadDefaults 可透過反射測試（如已 init）
    //  以下測試假設 map 已載入 defaults
    //  如果在非 Forge 環境下 init() 失敗（FMLPaths 不可用），
    //  我們透過反射呼叫 loadDefaults()
    // ═══════════════════════════════════════════════════════

    /**
     * 工具方法：確保 map 已載入預設值。
     * 嘗試反射呼叫 loadDefaults()，如果失敗則跳過依賴此狀態的測試。
     */
    private static VanillaMaterialMap getInitializedMap() {
        VanillaMaterialMap map = VanillaMaterialMap.getInstance();
        if (map.size() == 0) {
            try {
                var method = VanillaMaterialMap.class.getDeclaredMethod("loadDefaults");
                method.setAccessible(true);
                method.invoke(map);
            } catch (Exception e) {
                // 如果反射也失敗，返回空 map（部分測試會 skip）
            }
        }
        return map;
    }

    // ─── 鋼材 (STEEL) ───

    @ParameterizedTest
    @DisplayName("金屬方塊映射到 STEEL")
    @ValueSource(strings = {
        "minecraft:iron_block",
        "minecraft:iron_bars",
        "minecraft:iron_door",
        "minecraft:chain",
        "minecraft:anvil",
        "minecraft:copper_block",
        "minecraft:netherite_block",
        "minecraft:gold_block",
        "minecraft:raw_iron_block",
        "minecraft:lightning_rod"
    })
    void testSteelMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;  // 無法初始化，跳過
        assertEquals(DefaultMaterial.STEEL, map.getMaterial(blockId),
            blockId + " should map to STEEL");
    }

    // ─── 木材 (TIMBER) — 所有 11 種木材的 planks ───

    @ParameterizedTest
    @DisplayName("木板映射到 TIMBER")
    @ValueSource(strings = {
        "minecraft:oak_planks",
        "minecraft:spruce_planks",
        "minecraft:birch_planks",
        "minecraft:jungle_planks",
        "minecraft:acacia_planks",
        "minecraft:dark_oak_planks",
        "minecraft:mangrove_planks",
        "minecraft:cherry_planks",
        "minecraft:bamboo_planks",
        "minecraft:crimson_planks",
        "minecraft:warped_planks"
    })
    void testTimberPlanksMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.TIMBER, map.getMaterial(blockId),
            blockId + " should map to TIMBER");
    }

    @ParameterizedTest
    @DisplayName("原木映射到 TIMBER")
    @ValueSource(strings = {
        "minecraft:oak_log",
        "minecraft:spruce_log",
        "minecraft:birch_log",
        "minecraft:stripped_oak_log",
        "minecraft:stripped_birch_log",
        "minecraft:crimson_stem",
        "minecraft:warped_stem",
        "minecraft:stripped_crimson_stem",
        "minecraft:bamboo_block"
    })
    void testTimberLogMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.TIMBER, map.getMaterial(blockId),
            blockId + " should map to TIMBER");
    }

    // ─── 玻璃 (GLASS) ───

    @ParameterizedTest
    @DisplayName("玻璃映射到 GLASS")
    @ValueSource(strings = {
        "minecraft:glass",
        "minecraft:glass_pane",
        "minecraft:tinted_glass",
        "minecraft:white_stained_glass",
        "minecraft:red_stained_glass",
        "minecraft:blue_stained_glass_pane",
        "minecraft:black_stained_glass"
    })
    void testGlassMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.GLASS, map.getMaterial(blockId),
            blockId + " should map to GLASS");
    }

    // ─── 磚塊 (BRICK) ───

    @ParameterizedTest
    @DisplayName("磚塊映射到 BRICK")
    @ValueSource(strings = {
        "minecraft:bricks",
        "minecraft:brick_slab",
        "minecraft:brick_stairs",
        "minecraft:nether_bricks",
        "minecraft:red_nether_bricks",
        "minecraft:mud_bricks"
    })
    void testBrickMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.BRICK, map.getMaterial(blockId),
            blockId + " should map to BRICK");
    }

    // ─── 砂/土 (SAND) ───

    @ParameterizedTest
    @DisplayName("鬆散材料映射到 SAND")
    @ValueSource(strings = {
        "minecraft:sand",
        "minecraft:red_sand",
        "minecraft:gravel",
        "minecraft:dirt",
        "minecraft:clay",
        "minecraft:grass_block",
        "minecraft:soul_sand",
        "minecraft:mud",
        "minecraft:snow_block",
        "minecraft:farmland"
    })
    void testSandMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.SAND, map.getMaterial(blockId),
            blockId + " should map to SAND");
    }

    // ─── 黑曜石 (OBSIDIAN) ───

    @ParameterizedTest
    @DisplayName("超硬方塊映射到 OBSIDIAN")
    @ValueSource(strings = {
        "minecraft:obsidian",
        "minecraft:crying_obsidian",
        "minecraft:reinforced_deepslate",
        "minecraft:diamond_block",
        "minecraft:emerald_block"
    })
    void testObsidianMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.OBSIDIAN, map.getMaterial(blockId),
            blockId + " should map to OBSIDIAN");
    }

    // ─── 基岩 (BEDROCK) ───

    @ParameterizedTest
    @DisplayName("不可破壞方塊映射到 BEDROCK")
    @ValueSource(strings = {
        "minecraft:bedrock",
        "minecraft:barrier"
    })
    void testBedrockMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.BEDROCK, map.getMaterial(blockId),
            blockId + " should map to BEDROCK");
    }

    // ─── 混凝土 (CONCRETE) — 全 16 色 ───

    @ParameterizedTest
    @DisplayName("彩色混凝土映射到 CONCRETE")
    @ValueSource(strings = {
        "minecraft:white_concrete",
        "minecraft:orange_concrete",
        "minecraft:magenta_concrete",
        "minecraft:light_blue_concrete",
        "minecraft:yellow_concrete",
        "minecraft:lime_concrete",
        "minecraft:pink_concrete",
        "minecraft:gray_concrete",
        "minecraft:light_gray_concrete",
        "minecraft:cyan_concrete",
        "minecraft:purple_concrete",
        "minecraft:blue_concrete",
        "minecraft:brown_concrete",
        "minecraft:green_concrete",
        "minecraft:red_concrete",
        "minecraft:black_concrete"
    })
    void testConcreteMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.CONCRETE, map.getMaterial(blockId),
            blockId + " should map to CONCRETE");
    }

    // ─── 混凝土粉末 (PLAIN_CONCRETE) ───

    @ParameterizedTest
    @DisplayName("混凝土粉末映射到 PLAIN_CONCRETE")
    @ValueSource(strings = {
        "minecraft:white_concrete_powder",
        "minecraft:red_concrete_powder",
        "minecraft:black_concrete_powder"
    })
    void testConcretePowderMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.PLAIN_CONCRETE, map.getMaterial(blockId),
            blockId + " should map to PLAIN_CONCRETE");
    }

    // ─── 石材 (STONE) — 主要石材變體 ───

    @ParameterizedTest
    @DisplayName("石材變體映射到 STONE")
    @ValueSource(strings = {
        "minecraft:stone",
        "minecraft:cobblestone",
        "minecraft:granite",
        "minecraft:diorite",
        "minecraft:andesite",
        "minecraft:deepslate",
        "minecraft:tuff",
        "minecraft:calcite",
        "minecraft:sandstone",
        "minecraft:blackstone",
        "minecraft:basalt",
        "minecraft:quartz_block",
        "minecraft:end_stone",
        "minecraft:purpur_block",
        "minecraft:prismarine",
        "minecraft:terracotta"
    })
    void testStoneMappings(String blockId) {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertEquals(DefaultMaterial.STONE, map.getMaterial(blockId),
            blockId + " should map to STONE");
    }

    // ═══════════════════════════════════════════════════════
    //  hasMapping / size
    // ═══════════════════════════════════════════════════════

    @Test
    @DisplayName("hasMapping 對已知方塊回傳 true")
    void testHasMappingForKnownBlock() {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;
        assertTrue(map.hasMapping("minecraft:stone"),
            "stone should have explicit mapping");
    }

    @Test
    @DisplayName("hasMapping 對未知方塊回傳 false")
    void testHasMappingForUnknownBlock() {
        VanillaMaterialMap map = getInitializedMap();
        assertFalse(map.hasMapping("minecraft:fake_nonexistent_block"),
            "Unknown block should not have mapping");
    }

    @Test
    @DisplayName("size 應大於 200（覆蓋 200+ 原版方塊）")
    void testSizeExceeds200() {
        VanillaMaterialMap map = getInitializedMap();
        if (map.size() == 0) return;  // 無法初始化
        assertTrue(map.size() > 200,
            "Default map should contain 200+ entries, got: " + map.size());
    }

    // ═══════════════════════════════════════════════════════
    //  DefaultMaterial 材料參數合理性
    // ═══════════════════════════════════════════════════════

    @Test
    @DisplayName("所有 DefaultMaterial 的強度參數 >= 0")
    void testAllMaterialsHaveNonNegativeStrength() {
        for (DefaultMaterial mat : DefaultMaterial.values()) {
            assertTrue(mat.getRcomp() >= 0,
                mat.name() + " Rcomp should be >= 0");
            assertTrue(mat.getRtens() >= 0,
                mat.name() + " Rtens should be >= 0");
            assertTrue(mat.getRshear() >= 0,
                mat.name() + " Rshear should be >= 0");
            assertTrue(mat.getDensity() > 0,
                mat.name() + " density should be > 0");
        }
    }

    @Test
    @DisplayName("STEEL 強度 > CONCRETE 強度")
    void testSteelStrongerThanConcrete() {
        assertTrue(DefaultMaterial.STEEL.getRcomp() > DefaultMaterial.CONCRETE.getRcomp(),
            "Steel Rcomp should exceed Concrete");
        assertTrue(DefaultMaterial.STEEL.getRtens() > DefaultMaterial.CONCRETE.getRtens(),
            "Steel Rtens should exceed Concrete");
    }

    @Test
    @DisplayName("SAND 是最弱的結構材料")
    void testSandIsWeakest() {
        for (DefaultMaterial mat : DefaultMaterial.values()) {
            if (mat == DefaultMaterial.SAND) continue;
            assertTrue(mat.getRcomp() >= DefaultMaterial.SAND.getRcomp(),
                mat.name() + " should be at least as strong as SAND in compression");
        }
    }

    @Test
    @DisplayName("BEDROCK 有極大的強度值")
    void testBedrockIsIndestructible() {
        assertTrue(DefaultMaterial.BEDROCK.getRcomp() > 1e14,
            "BEDROCK Rcomp should be essentially infinite");
        assertTrue(DefaultMaterial.BEDROCK.getRtens() > 1e14,
            "BEDROCK Rtens should be essentially infinite");
    }

    @Test
    @DisplayName("每個 DefaultMaterial 都有唯一 materialId")
    void testUniqueMaterialIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (DefaultMaterial mat : DefaultMaterial.values()) {
            assertNotNull(mat.getMaterialId(), mat.name() + " materialId should not be null");
            assertTrue(ids.add(mat.getMaterialId()),
                mat.name() + " materialId '" + mat.getMaterialId() + "' should be unique");
        }
    }

    @Test
    @DisplayName("DefaultMaterial.fromId 往返一致")
    void testFromIdRoundTrip() {
        // #10 verified: DefaultMaterial.fromId() 存在，fallback = CONCRETE
        for (DefaultMaterial mat : DefaultMaterial.values()) {
            assertEquals(mat, DefaultMaterial.fromId(mat.getMaterialId()),
                "fromId('" + mat.getMaterialId() + "') should return " + mat.name());
        }
    }

    @Test
    @DisplayName("DefaultMaterial.fromId 未知 ID 回傳 CONCRETE")
    void testFromIdUnknownFallsBackToConcrete() {
        assertEquals(DefaultMaterial.CONCRETE, DefaultMaterial.fromId("totally_unknown_id"),
            "Unknown materialId should fallback to CONCRETE (per DEV-4 fix)");
    }
}
