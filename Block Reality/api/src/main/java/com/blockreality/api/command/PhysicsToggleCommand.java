package com.blockreality.api.command;

import com.blockreality.api.config.BRConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /br_physics_toggle         — 切換物理引擎開關
 * /br_physics_toggle on      — 持續啟用物理模擬
 * /br_physics_toggle off     — 停用物理模擬
 */
public class PhysicsToggleCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_physics_toggle")
                .requires(source -> source.hasPermission(2)) // op only
                .then(Commands.literal("on")
                    .executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("off")
                    .executes(ctx -> setEnabled(ctx.getSource(), false)))
                .executes(ctx -> toggle(ctx.getSource()))
        );
    }

    private static int setEnabled(CommandSourceStack source, boolean on) {
        BRConfig.setPhysicsEnabled(on);
        String status = on ? "§a持續啟用" : "§c已停用";
        source.sendSuccess(() -> Component.literal(
            "§6[BR] §f物理引擎 " + status
        ), true);
        return 1;
    }

    private static int toggle(CommandSourceStack source) {
        boolean current = BRConfig.isPhysicsEnabled();
        return setEnabled(source, !current);
    }
}
