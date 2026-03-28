package com.blockreality.api.command;

import com.blockreality.api.client.render.BRRenderSettings;
import com.blockreality.api.client.render.BRRenderSettings.RenderStyle;
import com.blockreality.api.client.render.pipeline.BRRenderPipeline;
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
 * /br_render tier <0-3>         — 手動設定渲染層級
 * /br_render effect <name> <on|off> — 切換單一效果
 * /br_render effects            — 列出所有效果及狀態
 * /br_render shadow <resolution> — 設定陰影解析度
 * /br_render ssao <samples>     — 設定 SSAO 取樣數
 */
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
        BRRenderPipeline.setEnabled(on);
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f光影管線 " + (on ? "§a已啟用" : "§c已停用")), true);
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
        BRRenderTier.Tier[] tiers = BRRenderTier.Tier.values();
        if (level >= tiers.length) {
            source.sendFailure(Component.literal("§c[BR] 無效層級: " + level));
            return 0;
        }
        BRRenderTier.Tier maxTier = BRRenderTier.getMaxSupportedTier();
        BRRenderTier.Tier target = tiers[level];

        if (target.ordinal() > maxTier.ordinal()) {
            source.sendSuccess(() -> Component.literal(
                "§6[BR] §e警告: GPU 最高支援 Tier " + maxTier.ordinal() +
                " (" + maxTier.name + ")，已自動降級"), false);
        }

        BRRenderTier.setTier(target);
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f渲染層級: §b" + BRRenderTier.getCurrentTier().name +
            " §7(" + BRRenderTier.getCurrentTier().glRequirement + ")"), true);
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
        boolean pipeOn = BRRenderPipeline.isEnabled();
        boolean init = BRRenderPipeline.isInitialized();
        long frames = BRRenderPipeline.getFrameCount();

        String header = String.format(
            "§6[BR] §f管線: %s §7| 幀數: %d\n",
            !init ? "§7未初始化" : (pipeOn ? "§a運行中" : "§c已停用"),
            frames);

        source.sendSuccess(() -> Component.literal(
            header + BRRenderSettings.getStatusSummary()), false);
        return 1;
    }
}
