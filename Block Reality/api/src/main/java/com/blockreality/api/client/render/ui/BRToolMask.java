package com.blockreality.api.client.render.ui;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * 工具遮罩系統 — Axiom 風格選取過濾器。
 *
 * 提供多種預設遮罩和可組合的遮罩建構器。
 * 遮罩用於限制 BRSelectionEngine 和 BRQuickPlacer 的操作範圍。
 *
 * 組合方式：
 * - AND：所有條件都必須通過
 * - OR：任一條件通過即可
 * - NOT：反轉結果
 * - 鏈式 Builder 模式
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRToolMask {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRToolMask.class);

    // ========================= 遮罩類型 =========================

    /** 遮罩邏輯運算 */
    public enum CombineMode {
        AND, OR
    }

    // ========================= 世界查詢 =========================

    /** 世界方塊查詢介面（由外部注入） */
    public interface BlockInfoProvider {
        String getBlockId(int x, int y, int z);
        boolean isAir(int x, int y, int z);
        boolean isSolid(int x, int y, int z);
        boolean isLiquid(int x, int y, int z);
        boolean hasTag(int x, int y, int z, String tagId);
        int getLightLevel(int x, int y, int z);
        /** 取得 Y 高度對應的 biome id */
        String getBiomeId(int x, int y, int z);
    }

    private static BlockInfoProvider provider = null;

    /** 注入世界查詢（在遊戲啟動時設定） */
    public static void setBlockInfoProvider(BlockInfoProvider p) {
        provider = p;
    }

    // ========================= 預設遮罩工廠 =========================

    /** 只選取指定方塊 ID */
    public static Predicate<BlockPos> blockId(String... ids) {
        Set<String> idSet = new HashSet<>(Arrays.asList(ids));
        return pos -> provider != null && idSet.contains(
            provider.getBlockId(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** 只選取非空氣方塊 */
    public static Predicate<BlockPos> nonAir() {
        return pos -> provider != null && !provider.isAir(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 只選取空氣方塊 */
    public static Predicate<BlockPos> airOnly() {
        return pos -> provider != null && provider.isAir(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 只選取固體方塊 */
    public static Predicate<BlockPos> solidOnly() {
        return pos -> provider != null && provider.isSolid(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 只選取液體方塊 */
    public static Predicate<BlockPos> liquidOnly() {
        return pos -> provider != null && provider.isLiquid(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 只選取有指定 tag 的方塊（如 "minecraft:logs"） */
    public static Predicate<BlockPos> hasTag(String tagId) {
        return pos -> provider != null && provider.hasTag(pos.getX(), pos.getY(), pos.getZ(), tagId);
    }

    /** Y 座標範圍過濾 */
    public static Predicate<BlockPos> yRange(int minY, int maxY) {
        return pos -> pos.getY() >= minY && pos.getY() <= maxY;
    }

    /** 球形範圍過濾 */
    public static Predicate<BlockPos> withinSphere(BlockPos center, double radius) {
        double r2 = radius * radius;
        return pos -> {
            double dx = pos.getX() - center.getX();
            double dy = pos.getY() - center.getY();
            double dz = pos.getZ() - center.getZ();
            return (dx * dx + dy * dy + dz * dz) <= r2;
        };
    }

    /** 柱形範圍過濾（Y 軸無限） */
    public static Predicate<BlockPos> withinCylinder(int centerX, int centerZ, double radius) {
        double r2 = radius * radius;
        return pos -> {
            double dx = pos.getX() - centerX;
            double dz = pos.getZ() - centerZ;
            return (dx * dx + dz * dz) <= r2;
        };
    }

    /** 光照等級過濾 */
    public static Predicate<BlockPos> lightLevel(int min, int max) {
        return pos -> {
            if (provider == null) return false;
            int light = provider.getLightLevel(pos.getX(), pos.getY(), pos.getZ());
            return light >= min && light <= max;
        };
    }

    /** 生態系過濾 */
    public static Predicate<BlockPos> biome(String... biomeIds) {
        Set<String> biomeSet = new HashSet<>(Arrays.asList(biomeIds));
        return pos -> provider != null && biomeSet.contains(
            provider.getBiomeId(pos.getX(), pos.getY(), pos.getZ()));
    }

    /** 表面方塊過濾（上方為空氣） */
    public static Predicate<BlockPos> surfaceBlocks() {
        return pos -> {
            if (provider == null) return false;
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            return !provider.isAir(x, y, z) && provider.isAir(x, y + 1, z);
        };
    }

    /** 排除指定方塊 ID */
    public static Predicate<BlockPos> excludeBlockId(String... ids) {
        Set<String> idSet = new HashSet<>(Arrays.asList(ids));
        return pos -> provider == null || !idSet.contains(
            provider.getBlockId(pos.getX(), pos.getY(), pos.getZ()));
    }

    // ========================= 遮罩組合 =========================

    /** 反轉遮罩 */
    public static Predicate<BlockPos> not(Predicate<BlockPos> mask) {
        return mask.negate();
    }

    /** AND 組合多個遮罩 */
    @SafeVarargs
    public static Predicate<BlockPos> allOf(Predicate<BlockPos>... masks) {
        return pos -> {
            for (Predicate<BlockPos> m : masks) {
                if (!m.test(pos)) return false;
            }
            return true;
        };
    }

    /** OR 組合多個遮罩 */
    @SafeVarargs
    public static Predicate<BlockPos> anyOf(Predicate<BlockPos>... masks) {
        return pos -> {
            for (Predicate<BlockPos> m : masks) {
                if (m.test(pos)) return true;
            }
            return false;
        };
    }

    // ========================= Builder =========================

    /**
     * 鏈式遮罩建構器。
     *
     * 用法範例：
     * <pre>
     * Predicate<BlockPos> mask = BRToolMask.builder()
     *     .nonAir()
     *     .yRange(60, 120)
     *     .excludeBlockId("minecraft:bedrock")
     *     .surfaceOnly()
     *     .build();
     * </pre>
     */
    public static MaskBuilder builder() {
        return new MaskBuilder();
    }

    public static class MaskBuilder {
        private final List<Predicate<BlockPos>> conditions = new ArrayList<>();
        private CombineMode mode = CombineMode.AND;

        public MaskBuilder combineMode(CombineMode mode) {
            this.mode = mode;
            return this;
        }

        public MaskBuilder nonAir() {
            conditions.add(BRToolMask.nonAir());
            return this;
        }

        public MaskBuilder airOnly() {
            conditions.add(BRToolMask.airOnly());
            return this;
        }

        public MaskBuilder solidOnly() {
            conditions.add(BRToolMask.solidOnly());
            return this;
        }

        public MaskBuilder liquidOnly() {
            conditions.add(BRToolMask.liquidOnly());
            return this;
        }

        public MaskBuilder blockId(String... ids) {
            conditions.add(BRToolMask.blockId(ids));
            return this;
        }

        public MaskBuilder excludeBlockId(String... ids) {
            conditions.add(BRToolMask.excludeBlockId(ids));
            return this;
        }

        public MaskBuilder hasTag(String tagId) {
            conditions.add(BRToolMask.hasTag(tagId));
            return this;
        }

        public MaskBuilder yRange(int minY, int maxY) {
            conditions.add(BRToolMask.yRange(minY, maxY));
            return this;
        }

        public MaskBuilder withinSphere(BlockPos center, double radius) {
            conditions.add(BRToolMask.withinSphere(center, radius));
            return this;
        }

        public MaskBuilder withinCylinder(int centerX, int centerZ, double radius) {
            conditions.add(BRToolMask.withinCylinder(centerX, centerZ, radius));
            return this;
        }

        public MaskBuilder lightLevel(int min, int max) {
            conditions.add(BRToolMask.lightLevel(min, max));
            return this;
        }

        public MaskBuilder biome(String... biomeIds) {
            conditions.add(BRToolMask.biome(biomeIds));
            return this;
        }

        public MaskBuilder surfaceOnly() {
            conditions.add(BRToolMask.surfaceBlocks());
            return this;
        }

        public MaskBuilder custom(Predicate<BlockPos> predicate) {
            conditions.add(predicate);
            return this;
        }

        public Predicate<BlockPos> build() {
            if (conditions.isEmpty()) return pos -> true;
            if (conditions.size() == 1) return conditions.get(0);

            Predicate<BlockPos>[] arr = conditions.toArray(new Predicate[0]);
            return mode == CombineMode.AND ? allOf(arr) : anyOf(arr);
        }
    }

    // ========================= 預設遮罩組合 =========================

    /** 「只選地面」— 非空氣 + 表面方塊 + 非液體 */
    public static Predicate<BlockPos> presetGround() {
        return allOf(nonAir(), surfaceBlocks(), not(liquidOnly()));
    }

    /** 「只選建築」— 非天然方塊（排除石頭/泥土/沙子等） */
    public static Predicate<BlockPos> presetBuilding() {
        return allOf(
            nonAir(),
            excludeBlockId(
                "minecraft:stone", "minecraft:dirt", "minecraft:grass_block",
                "minecraft:sand", "minecraft:gravel", "minecraft:bedrock",
                "minecraft:deepslate", "minecraft:water", "minecraft:lava"
            )
        );
    }

    /** 「只選木材」 */
    public static Predicate<BlockPos> presetWood() {
        return anyOf(
            hasTag("minecraft:logs"),
            hasTag("minecraft:planks")
        );
    }

    /** 「只選礦石」 */
    public static Predicate<BlockPos> presetOres() {
        return anyOf(
            hasTag("minecraft:coal_ores"),
            hasTag("minecraft:iron_ores"),
            hasTag("minecraft:gold_ores"),
            hasTag("minecraft:diamond_ores"),
            hasTag("minecraft:emerald_ores"),
            hasTag("minecraft:copper_ores"),
            hasTag("minecraft:lapis_ores"),
            hasTag("minecraft:redstone_ores")
        );
    }

    // ========================= 遮罩持久化（Save/Load） =========================

    /**
     * 遮罩設定的可序列化表示。
     * 將 Predicate<BlockPos>（不可序列化）轉換為描述性配置。
     */
    public static class MaskConfig {
        public String name;
        public final List<String> includedBlockIds = new java.util.ArrayList<>();
        public final List<String> excludedBlockIds = new java.util.ArrayList<>();
        public final List<String> requiredTags = new java.util.ArrayList<>();
        public boolean nonAirOnly = false;
        public boolean solidOnly = false;
        public boolean surfaceOnly = false;
        public int minY = Integer.MIN_VALUE;
        public int maxY = Integer.MAX_VALUE;

        public MaskConfig(String name) {
            this.name = name;
        }
    }

    /**
     * 將遮罩配置儲存為 NBT。
     *
     * @param config 遮罩配置
     * @return NBT CompoundTag
     */
    public static net.minecraft.nbt.CompoundTag saveMaskToNBT(MaskConfig config) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("name", config.name);
        tag.putBoolean("nonAirOnly", config.nonAirOnly);
        tag.putBoolean("solidOnly", config.solidOnly);
        tag.putBoolean("surfaceOnly", config.surfaceOnly);
        tag.putInt("minY", config.minY);
        tag.putInt("maxY", config.maxY);

        net.minecraft.nbt.ListTag includeList = new net.minecraft.nbt.ListTag();
        for (String id : config.includedBlockIds) {
            includeList.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put("includedBlocks", includeList);

        net.minecraft.nbt.ListTag excludeList = new net.minecraft.nbt.ListTag();
        for (String id : config.excludedBlockIds) {
            excludeList.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put("excludedBlocks", excludeList);

        net.minecraft.nbt.ListTag tagList = new net.minecraft.nbt.ListTag();
        for (String t : config.requiredTags) {
            tagList.add(net.minecraft.nbt.StringTag.valueOf(t));
        }
        tag.put("requiredTags", tagList);

        tag.putInt("version", 1);
        return tag;
    }

    /**
     * 從 NBT 載入遮罩配置。
     *
     * @param tag NBT 標籤
     * @return 遮罩配置，或 null 如果格式不正確
     */
    public static MaskConfig loadMaskFromNBT(net.minecraft.nbt.CompoundTag tag) {
        if (tag == null || !tag.contains("name")) return null;

        MaskConfig config = new MaskConfig(tag.getString("name"));
        config.nonAirOnly = tag.getBoolean("nonAirOnly");
        config.solidOnly = tag.getBoolean("solidOnly");
        config.surfaceOnly = tag.getBoolean("surfaceOnly");
        config.minY = tag.contains("minY") ? tag.getInt("minY") : Integer.MIN_VALUE;
        config.maxY = tag.contains("maxY") ? tag.getInt("maxY") : Integer.MAX_VALUE;

        if (tag.contains("includedBlocks")) {
            net.minecraft.nbt.ListTag list = tag.getList("includedBlocks", 8); // 8 = StringTag
            for (int i = 0; i < list.size(); i++) {
                config.includedBlockIds.add(list.getString(i));
            }
        }
        if (tag.contains("excludedBlocks")) {
            net.minecraft.nbt.ListTag list = tag.getList("excludedBlocks", 8);
            for (int i = 0; i < list.size(); i++) {
                config.excludedBlockIds.add(list.getString(i));
            }
        }
        if (tag.contains("requiredTags")) {
            net.minecraft.nbt.ListTag list = tag.getList("requiredTags", 8);
            for (int i = 0; i < list.size(); i++) {
                config.requiredTags.add(list.getString(i));
            }
        }

        return config;
    }

    /**
     * 從 MaskConfig 建構可執行的 Predicate。
     *
     * @param config 遮罩配置
     * @return 組合後的遮罩謂詞
     */
    public static java.util.function.Predicate<net.minecraft.core.BlockPos> buildFromConfig(MaskConfig config) {
        MaskBuilder builder = new MaskBuilder();

        if (config.nonAirOnly) builder.nonAir();
        if (config.solidOnly) builder.solidOnly();
        if (config.surfaceOnly) builder.surfaceOnly();
        if (config.minY != Integer.MIN_VALUE || config.maxY != Integer.MAX_VALUE) {
            builder.yRange(config.minY, config.maxY);
        }
        for (String id : config.includedBlockIds) {
            builder.blockId(id);
        }
        for (String id : config.excludedBlockIds) {
            builder.excludeBlockId(id);
        }
        for (String tag : config.requiredTags) {
            builder.hasTag(tag);
        }

        return builder.build();
    }
}
