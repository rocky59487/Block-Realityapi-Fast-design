package com.blockreality.api.command;

import com.blockreality.api.client.render.BRRenderSettings;
import com.blockreality.api.client.render.BRRenderSettings.RenderStyle;
import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * /br_render                    — 顯示完整渲染狀態
 * /br_render on|off             — 啟用/停用管線
 * /br_render style <name>       — 套用渲染風格（cinema/balanced/performance/minimal）
 * /br_render tier <0-3>         — 設定渲染開關（0=停用，1-3=啟用）
 * /br_render effect <name> <on|off> — 切換單一效果
 * /br_render effects            — 列出所有效果及狀態
 * /br_render shadow <resolution> — 設定陰影解析度
 * /br_render ssao <samples>     — 設定 SSAO 取樣數
 */
@SuppressWarnings("deprecation") // Phase 4-F: uses deprecated old-pipeline classes pending removal
public class RenderToggleCommand {

    private static final String[] STYLE_NAMES = {"cinema", "balanced", "performance", "minimal", "custom"};

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_render")
                .requires(source -> source.hasPermission(0))

                // /br_render on|off
                .then(Commands.literal("on")
                    .executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("off")
                    .executes(ctx -> setEnabled(ctx.getSource(), false)))

                // /br_render style <name>
                .then(Commands.literal("style")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                            SharedSuggestionProvider.suggest(STYLE_NAMES, builder))
                        .executes(ctx -> setStyle(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))

                // /br_render tier <0-3>
                .then(Commands.literal("tier")
                    .then(Commands.argument("level", IntegerArgumentType.integer(0, 3))
                        .executes(ctx -> setTier(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "level")))))

                // /br_render effect <name> <true|false>
                .then(Commands.literal("effect")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) ->
                            SharedSuggestionProvider.suggest(
                                BRRenderSettings.getAllEffectNames(), builder))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> setEffect(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"),
                                BoolArgumentType.getBool(ctx, "enabled"))))
                        .executes(ctx -> queryEffect(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))

                // /br_render effects — 列出所有
                .then(Commands.literal("effects")
                    .executes(ctx -> listEffects(ctx.getSource())))

                // /br_render shadow <resolution>
                .then(Commands.literal("shadow")
                    .then(Commands.argument("resolution", IntegerArgumentType.integer(256, 8192))
                        .executes(ctx -> setShadowRes(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "resolution")))))

                // /br_render ssao <samples>
                .then(Commands.literal("ssao")
                    .then(Commands.argument("samples", IntegerArgumentType.integer(4, 128))
                        .executes(ctx -> setSSAOSamples(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "samples")))))

                // /br_render（無參數）— 顯示狀態
                .executes(ctx -> showStatus(ctx.getSource()))
        );
    }

    private static int setEnabled(CommandSourceStack source, boolean on) {
        // Phase 4-F: BRRenderPipeline removed — pipeline toggle routes to informational message
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f光影管線已停用（已棄用，請使用新渲染系統）"), true);
        return 1;
    }

    private static int setStyle(CommandSourceStack source, String styleName) {
        try {
            RenderStyle style = RenderStyle.valueOf(styleName.toUpperCase());
            BRRenderSettings.applyStyle(style);
            source.sendSuccess(() -> Component.literal(
                "§6[BR] §f已套用渲染風格: §b" + style.displayName +
                " §7(" + style.description + ")"), true);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                "§c[BR] 未知風格: " + styleName +
                "。可用: cinema, balanced, performance, minimal, custom"));
        }
        return 1;
    }

    private static int setTier(CommandSourceStack source, int level) {
        // Phase 4-F: 層級系統已簡化為開/關切換
        // level 0 → 停用；level 1~3 → 啟用（RT 層級已移除，統一對應 ENABLED）
        boolean on = (level > 0);
        BRRenderTier.setEnabled(on);
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f渲染: §b" + BRRenderTier.getCurrentTier().name +
            " §7(" + BRRenderTier.getCurrentTier().glRequirement + ")" +
            (level >= 3 ? " §e[注意: RT 層級已移除]" : "")), true);
        return 1;
    }

    private static int setEffect(CommandSourceStack source, String name, boolean enabled) {
        BRRenderSettings.setEffect(name, enabled);
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f" + name + " " + (enabled ? "§a已啟用" : "§c已停用")), true);
        return 1;
    }

    private static int queryEffect(CommandSourceStack source, String name) {
        boolean on = BRRenderSettings.isEffectEnabled(name);
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f" + name + ": " + (on ? "§a啟用" : "§c停用")), false);
        return 1;
    }

    private static int listEffects(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
            BRRenderSettings.getStatusSummary()), false);
        return 1;
    }

    private static int setShadowRes(CommandSourceStack source, int res) {
        BRRenderSettings.setShadowResolution(res);
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f陰影解析度: §b" + res + "x" + res), true);
        return 1;
    }

    private static int setSSAOSamples(CommandSourceStack source, int samples) {
        BRRenderSettings.setSSAOSamples(samples);
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §fSSAO 取樣數: §b" + samples), true);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        // Phase 4-F: BRRenderPipeline removed; show RT Compositor status
        String header = String.format(
            "§6[BR] §f管線: §aRT Compositor（Phase 4-F）\n");

        source.sendSuccess(() -> Component.literal(
            header + BRRenderSettings.getStatusSummary()), false);
        return 1;
    }
}

