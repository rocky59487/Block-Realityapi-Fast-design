package com.blockreality.fastdesign.config;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * FastDesign 模組的 Forge 1.20.1 配置。
 * 將常用的硬編碼數值公開為可配置選項。
 */
@Mod.EventBusSubscriber(modid = "fastdesign", bus = Mod.EventBusSubscriber.Bus.MOD)
public class FastDesignConfig {

    // ============================================================================
    // 通用配置（伺服器 + 客戶端同步）
    // ============================================================================

    public static final ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec COMMON_SPEC;

    // 選取與復原
    public static ForgeConfigSpec.IntValue MAX_SELECTION_VOLUME;
    public static ForgeConfigSpec.IntValue UNDO_STACK_SIZE;

    // 匯出
    public static ForgeConfigSpec.IntValue EXPORT_MAX_BLOCKS;
    public static ForgeConfigSpec.IntValue EXPORT_TIMEOUT_SECONDS;

    // 鋼筋
    public static ForgeConfigSpec.IntValue REBAR_SPACING_MAX;

    // 全息圖
    public static ForgeConfigSpec.IntValue HOLOGRAM_MAX_BLOCKS;
    public static ForgeConfigSpec.DoubleValue HOLOGRAM_GHOST_ALPHA;
    public static ForgeConfigSpec.DoubleValue HOLOGRAM_CULL_DISTANCE;

    // 功能開關
    public static ForgeConfigSpec.BooleanValue WAND_ENABLED;
    public static ForgeConfigSpec.BooleanValue CAD_AUTO_OPEN;
    public static ForgeConfigSpec.BooleanValue ALWAYS_SHOW_SELECTION;

    static {
        // ==== 選取與復原 ====
        COMMON_BUILDER.comment("選取與復原設定")
                .push("selection");

        MAX_SELECTION_VOLUME = COMMON_BUILDER
                .comment("單次選取允許的最大方塊數量")
                .defineInRange("maxSelectionVolume", 125_000, 1, 1_000_000);

        UNDO_STACK_SIZE = COMMON_BUILDER
                .comment("每位玩家的最大復原深度")
                .defineInRange("undoStackSize", 10, 1, 50);

        COMMON_BUILDER.pop();

        // ==== 匯出 ====
        COMMON_BUILDER.comment("匯出設定")
                .push("export");

        EXPORT_MAX_BLOCKS = COMMON_BUILDER
                .comment("NURBS 匯出允許的最大方塊數量")
                .defineInRange("exportMaxBlocks", 5000, 100, 50_000);

        EXPORT_TIMEOUT_SECONDS = COMMON_BUILDER
                .comment("附屬匯出操作的逾時秒數")
                .defineInRange("exportTimeoutSeconds", 30, 5, 300);

        COMMON_BUILDER.pop();

        // ==== 鋼筋 ====
        COMMON_BUILDER.comment("鋼筋設定")
                .push("rebar");

        REBAR_SPACING_MAX = COMMON_BUILDER
                .comment("鋼筋網格最大間距")
                .defineInRange("rebarSpacingMax", 8, 1, 16);

        COMMON_BUILDER.pop();

        // ==== 全息圖 ====
        COMMON_BUILDER.comment("全息圖設定")
                .push("hologram");

        HOLOGRAM_MAX_BLOCKS = COMMON_BUILDER
                .comment("全息圖顯示中渲染的最大方塊數量")
                .defineInRange("hologramMaxBlocks", 10_000, 100, 100_000);

        HOLOGRAM_GHOST_ALPHA = COMMON_BUILDER
                .comment("全息圖幽靈渲染的透明度等級（0.1 = 非常透明，1.0 = 不透明）")
                .defineInRange("hologramGhostAlpha", 0.4, 0.1, 1.0);

        HOLOGRAM_CULL_DISTANCE = COMMON_BUILDER
                .comment("全息圖停止渲染的距離（方塊為單位，剔除距離）")
                .defineInRange("hologramCullDistance", 128.0, 16.0, 512.0);

        COMMON_BUILDER.pop();

        // ==== 功能開關 ====
        COMMON_BUILDER.comment("功能開關")
                .push("features");

        WAND_ENABLED = COMMON_BUILDER
                .comment("啟用選取魔杖物品")
                .define("wandEnabled", true);

        CAD_AUTO_OPEN = COMMON_BUILDER
                .comment("載入藍圖後自動開啟 CAD 介面")
                .define("cadAutoOpen", false);

        ALWAYS_SHOW_SELECTION = COMMON_BUILDER
                .comment("始終顯示選取邊界框，即使未手持魔杖")
                .define("alwaysShowSelection", false);

        COMMON_BUILDER.pop();

        COMMON_SPEC = COMMON_BUILDER.build();
    }

    // ============================================================================
    // 靜態取值方法
    // ============================================================================

    public static int getMaxSelectionVolume() {
        return MAX_SELECTION_VOLUME.get();
    }

    public static int getUndoStackSize() {
        return UNDO_STACK_SIZE.get();
    }

    public static int getExportMaxBlocks() {
        return EXPORT_MAX_BLOCKS.get();
    }

    public static int getExportTimeoutSeconds() {
        return EXPORT_TIMEOUT_SECONDS.get();
    }

    public static int getRebarSpacingMax() {
        return REBAR_SPACING_MAX.get();
    }

    public static int getHologramMaxBlocks() {
        return HOLOGRAM_MAX_BLOCKS.get();
    }

    public static double getHologramGhostAlpha() {
        return HOLOGRAM_GHOST_ALPHA.get();
    }

    public static double getHologramCullDistance() {
        return HOLOGRAM_CULL_DISTANCE.get();
    }

    public static boolean isWandEnabled() {
        return WAND_ENABLED.get();
    }

    public static boolean isCadAutoOpen() {
        return CAD_AUTO_OPEN.get();
    }

    public static boolean isAlwaysShowSelection() {
        return ALWAYS_SHOW_SELECTION.get();
    }
}
