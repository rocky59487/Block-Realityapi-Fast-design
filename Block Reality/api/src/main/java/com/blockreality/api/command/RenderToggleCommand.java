package com.blockreality.api.command;

import com.blockreality.api.client.render.pipeline.BRRenderPipeline;
import com.blockreality.api.client.render.BRRenderConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /br_render         — 顯示目前光影狀態
 * /br_render on      — 啟用 Block Reality 光影管線
 * /br_render off     — 停用（回到原版渲染）
 */
public class RenderToggleCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_render")
                .requires(source -> source.hasPermission(0)) // all players
                .then(Commands.literal("on")
                    .executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("off")
                    .executes(ctx -> setEnabled(ctx.getSource(), false)))
                .executes(ctx -> showStatus(ctx.getSource()))
        );
    }

    private static int setEnabled(CommandSourceStack source, boolean on) {
        BRRenderPipeline.setEnabled(on);
        String status = on ? "§a已啟用" : "§c已停用";
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f光影管線 " + status
        ), true);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        boolean pipeOn = BRRenderPipeline.isEnabled();
        boolean init = BRRenderPipeline.isInitialized();
        long frames = BRRenderPipeline.getFrameCount();

        String stateStr = !init ? "§7未初始化" : (pipeOn ? "§a運行中" : "§c已停用");

        source.sendSuccess(() -> Component.literal(String.format(
            "§6[BR] §f光影管線: %s §7| 已渲染 %d 幀\n" +
            "§6[BR] §7HDR=%s SSAO=%s TAA=%s CSM=%s Bloom=%s",
            stateStr, frames,
            bool(BRRenderConfig.HDR_ENABLED),
            bool(BRRenderConfig.SSAO_ENABLED),
            bool(BRRenderConfig.TAA_ENABLED),
            bool(BRRenderConfig.CSM_ENABLED),
            bool(BRRenderConfig.BLOOM_INTENSITY > 0)
        )), false);
        return 1;
    }

    private static String bool(boolean v) { return v ? "§a✓" : "§c✗"; }
}
