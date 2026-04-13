package com.blockreality.api.command;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.diagnostic.BrCrashReporter;
import com.blockreality.api.physics.ConnectivityCache;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 統一指令 /br — 取代舊的多個 /br_xxx 指令。
 *
 * 子指令：
 *   /br toggle         — 開關物理引擎
 *   /br status         — 顯示物理引擎狀態
 *   /br vulkan_test    — 測試 Vulkan 可用性
 *   /br crash_test     — 測試崩潰報告器（會真的拋出例外！）
 *   /br crash_report   — 生成即時診斷報告（不崩潰）
 */
public class BrCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("br")
            .requires(src -> src.hasPermission(2))

            .then(Commands.literal("toggle")
                .executes(ctx -> {
                    boolean current = BRConfig.isPhysicsEnabled();
                    BRConfig.setPhysicsEnabled(!current);
                    String state = !current ? "ON" : "OFF";
                    ctx.getSource().sendSuccess(() ->
                        Component.literal("[BlockReality] 物理引擎: " + state)
                            .withStyle(!current ? ChatFormatting.GREEN : ChatFormatting.RED),
                        true);
                    return 1;
                })
            )

            .then(Commands.literal("status")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    boolean physicsOn = BRConfig.isPhysicsEnabled();
                    boolean pfsfOn = BRConfig.isPFSFEnabled();
                    boolean pfsfAvail = PFSFEngine.isAvailable();
                    long epoch = ConnectivityCache.getStructureEpoch();
                    String cacheStats = ConnectivityCache.getCacheStats();

                    src.sendSuccess(() -> Component.literal("=== Block Reality Status ===")
                        .withStyle(ChatFormatting.GOLD), false);
                    src.sendSuccess(() -> Component.literal("  Physics: " + (physicsOn ? "ON" : "OFF"))
                        .withStyle(physicsOn ? ChatFormatting.GREEN : ChatFormatting.RED), false);
                    src.sendSuccess(() -> Component.literal("  PFSF Config: " + (pfsfOn ? "ON" : "OFF"))
                        .withStyle(pfsfOn ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
                    src.sendSuccess(() -> Component.literal("  PFSF GPU: " + (pfsfAvail ? "Available" : "Unavailable"))
                        .withStyle(pfsfAvail ? ChatFormatting.GREEN : ChatFormatting.RED), false);
                    src.sendSuccess(() -> Component.literal("  Epoch: " + epoch), false);
                    src.sendSuccess(() -> Component.literal("  Cache: " + cacheStats)
                        .withStyle(ChatFormatting.GRAY), false);

                    return 1;
                })
            )

            .then(Commands.literal("vulkan_test")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    boolean available = PFSFEngine.isAvailable();

                    if (available) {
                        src.sendSuccess(() -> Component.literal("[Vulkan] PFSF GPU 物理引擎正常運作")
                            .withStyle(ChatFormatting.GREEN), false);
                    } else {
                        src.sendSuccess(() -> Component.literal("[Vulkan] PFSF 不可用 — 無 Vulkan 支援或初始化失敗")
                            .withStyle(ChatFormatting.RED), false);
                        src.sendSuccess(() -> Component.literal("  請確認 GPU 驅動已更新至支援 Vulkan 1.2+ 的版本")
                            .withStyle(ChatFormatting.GRAY), false);
                    }

                    return 1;
                })
            )

            // ── Crash Reporter 測試指令 ────────────────────────────────────
            .then(Commands.literal("crash_report")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal("[BR-CrashReporter] 正在生成診斷報告...")
                        .withStyle(ChatFormatting.YELLOW), false);

                    // 非致命：生成即時系統快照報告
                    Thread reportThread = new Thread(() ->
                        BrCrashReporter.generateManualReport("Manual diagnostic report via /br crash_report", null),
                        "BR-CrashReport-Thread");
                    reportThread.setDaemon(true);
                    reportThread.start();

                    src.sendSuccess(() -> Component.literal(
                        "[BR-CrashReporter] 報告已生成！查看 crashreporter/ 資料夾")
                        .withStyle(ChatFormatting.GREEN), false);
                    return 1;
                })
            )

            .then(Commands.literal("crash_test")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal(
                        "[BR-CrashReporter] 正在觸發測試崩潰（此伺服器將崩潰！）")
                        .withStyle(ChatFormatting.RED), false);

                    // 在新執行緒觸發，讓玩家看到警告訊息後再崩潰
                    Thread crashThread = new Thread(() -> {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        throw new RuntimeException(
                            "[BR-CrashReporter] Intentional test crash from /br crash_test — " +
                            "if you see a crashreporter/ file, the reporter is working correctly!");
                    }, "BR-CrashTest-Thread");
                    crashThread.setDaemon(false);  // 非 daemon 確保 UncaughtExceptionHandler 被觸發
                    crashThread.start();

                    return 1;
                })
            )

            .executes(ctx -> {
                ctx.getSource().sendSuccess(() ->
                    Component.literal("用法: /br <toggle|status|vulkan_test|crash_report|crash_test>")
                        .withStyle(ChatFormatting.YELLOW), false);
                return 1;
            })
        );
    }
}
