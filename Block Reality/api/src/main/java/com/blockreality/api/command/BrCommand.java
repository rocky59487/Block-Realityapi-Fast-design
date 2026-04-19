package com.blockreality.api.command;

import com.blockreality.api.collapse.CollapseJournal;
import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.diagnostic.BrCrashReporter;
import com.blockreality.api.physics.ConnectivityCache;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.pfsf.PFSFBufferManager;
import com.blockreality.api.physics.pfsf.PFSFConstants;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import com.blockreality.api.physics.pfsf.PFSFFixtureWriter;
import com.blockreality.api.physics.pfsf.PFSFIslandBuffer;
import com.blockreality.api.physics.pfsf.PFSFScheduler;
import com.blockreality.api.spi.IVS2Bridge;
import com.blockreality.api.spi.ModuleRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private static final Logger LOGGER = LogManager.getLogger("BR-Command");

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

            // ── 崩塌日誌指令 ─────────────────────────────────────────────
            .then(Commands.literal("journal")
                // /br journal         → 最近 10 筆
                .executes(ctx -> execJournal(ctx.getSource(), 10))
                // /br journal <count> → 最近 N 筆
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                    .executes(ctx -> execJournal(
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "count"))))
            )

            // ── 崩塌回滾指令 ─────────────────────────────────────────────
            .then(Commands.literal("undo")
                // /br undo         → 回滾最近一條因果鏈
                .executes(ctx -> execUndo(ctx.getSource(), 1))
                // /br undo <count> → 回滾最近 N 條因果鏈
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 10))
                    .executes(ctx -> execUndo(
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "count"))))
            )

            // ── 指定鏈 ID 復原 ────────────────────────────────────────────
            .then(Commands.literal("restore")
                .then(Commands.argument("chainId", LongArgumentType.longArg(0))
                    .executes(ctx -> execRestore(
                        ctx.getSource(),
                        LongArgumentType.getLong(ctx, "chainId"))))
            )

            // ── PFSF 現場擷取 ────────────────────────────────────────────
            // /br pfsf dump <islandId>  — 擷取單個 island 成 fixture JSON
            // /br pfsf dumpAll          — 擷取所有 island
            .then(Commands.literal("pfsf")
                .then(Commands.literal("dump")
                    .then(Commands.argument("islandId", IntegerArgumentType.integer(1))
                        .executes(ctx -> execPfsfDump(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "islandId"))))
                )
                .then(Commands.literal("dumpAll")
                    .executes(ctx -> execPfsfDumpAll(ctx.getSource()))
                )
            )

            // ── 開發者診斷指令 ───────────────────────────────────────────
            .then(Commands.literal("debug")

                // /br debug islands [count]  — 列出最大的 N 個 island
                .then(Commands.literal("islands")
                    .executes(ctx -> execDebugIslands(ctx.getSource(), 20))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> execDebugIslands(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "count"))))
                )

                // /br debug pfsf  — PFSF GPU 引擎詳細狀態
                .then(Commands.literal("pfsf")
                    .executes(ctx -> execDebugPFSF(ctx.getSource()))
                )

                // /br debug dump  — 全量轉儲到 LOGGER（不列印到聊天室，避免刷屏）
                .then(Commands.literal("dump")
                    .executes(ctx -> execDebugDump(ctx.getSource()))
                )

                // /br debug vs2  — VS2 橋接器詳細狀態
                .then(Commands.literal("vs2")
                    .executes(ctx -> execDebugVS2(ctx.getSource()))
                )

                // /br debug — 無子指令時顯示概覽
                .executes(ctx -> {
                    execDebugIslands(ctx.getSource(), 5);
                    execDebugPFSF(ctx.getSource());
                    return 1;
                })
            )

            .executes(ctx -> {
                ctx.getSource().sendSuccess(() ->
                    Component.literal("用法: /br <toggle|status|vulkan_test|crash_report|crash_test|journal|undo|restore|pfsf [dump <id>|dumpAll]|debug [islands|pfsf|vs2|dump]>")
                        .withStyle(ChatFormatting.YELLOW), false);
                return 1;
            })
        );
    }

    // ─── /br journal ───────────────────────────────────────────────────────

    private static int execJournal(CommandSourceStack src, int count) {
        CollapseJournal journal = CollapseManager.getJournal();
        List<CollapseJournal.Entry> recent = journal.recent(count);

        if (recent.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[BR-Journal] 日誌為空（無已記錄的崩塌）")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
            String.format("[BR-Journal] 最近 %d / %d 筆記錄（共 %d 筆）：",
                recent.size(), count, journal.size()))
            .withStyle(ChatFormatting.GOLD), false);

        for (CollapseJournal.Entry e : recent) {
            src.sendSuccess(() -> Component.literal(String.format(
                "  [%d] chain=%d pos=(%d,%d,%d) type=%-16s tick=%d",
                e.id(), e.chainId(),
                e.pos().getX(), e.pos().getY(), e.pos().getZ(),
                e.failureType().name(),
                e.tickStamp()))
                .withStyle(ChatFormatting.GRAY), false);
        }

        // 統計摘要
        var counts = journal.getFailureCounts();
        if (!counts.isEmpty()) {
            StringBuilder sb = new StringBuilder("[BR-Journal] 統計：");
            counts.forEach((type, n) -> sb.append(type.name()).append('=').append(n).append(' '));
            src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.AQUA), false);
        }
        return 1;
    }

    // ─── /br undo ──────────────────────────────────────────────────────────

    private static int execUndo(CommandSourceStack src, int chainCount) {
        ServerLevel level = src.getLevel();
        CollapseJournal journal = CollapseManager.getJournal();
        int totalRestored = 0;

        for (int i = 0; i < chainCount; i++) {
            List<CollapseJournal.Entry> chain = journal.undo();
            if (chain.isEmpty()) break;

            for (CollapseJournal.Entry e : chain) {
                restoreBlock(level, e);
                totalRestored++;
            }
        }

        if (totalRestored == 0) {
            src.sendSuccess(() -> Component.literal("[BR-Undo] 無可回滾的崩塌記錄")
                .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        final int count = totalRestored;
        src.sendSuccess(() -> Component.literal(
            String.format("[BR-Undo] 已還原 %d 個方塊（%d 條因果鏈）", count, chainCount))
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ─── /br restore <chainId> ─────────────────────────────────────────────

    private static int execRestore(CommandSourceStack src, long chainId) {
        ServerLevel level = src.getLevel();
        CollapseJournal journal = CollapseManager.getJournal();
        List<CollapseJournal.Entry> chain = journal.getChain(chainId);

        if (chain.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                "[BR-Restore] 找不到 chainId=" + chainId + " 的記錄")
                .withStyle(ChatFormatting.RED), false);
            return 0;
        }

        for (CollapseJournal.Entry e : chain) {
            restoreBlock(level, e);
        }

        src.sendSuccess(() -> Component.literal(
            String.format("[BR-Restore] 已還原 chainId=%d 的 %d 個方塊", chainId, chain.size()))
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // ─── 共用：還原單一方塊 ────────────────────────────────────────────────

    private static void restoreBlock(ServerLevel level, CollapseJournal.Entry entry) {
        BlockPos pos = entry.pos();
        BlockState state = entry.prevState();
        if (state == null || state.isAir()) return;

        // 僅在目標位置為空氣時還原，避免覆蓋玩家已放置的新方塊
        if (!level.getBlockState(pos).isAir()) return;

        level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
    }

    // ─── /br debug islands ────────────────────────────────────────────────

    private static int execDebugIslands(CommandSourceStack src, int topN) {
        Map<Integer, StructureIslandRegistry.StructureIsland> islands =
                StructureIslandRegistry.getAllIslands();
        int total = islands.size();
        long totalBlocks = StructureIslandRegistry.getTotalRegisteredBlocks();
        long epoch = ConnectivityCache.getStructureEpoch();

        src.sendSuccess(() -> Component.literal(String.format(
                "§6[BR-Debug Islands] §ftotal=%d  totalBlocks=%d  epoch=%d",
                total, totalBlocks, epoch))
            .withStyle(ChatFormatting.GOLD), false);

        if (islands.isEmpty()) return 1;

        // 顯示最大的 topN 個 island
        islands.values().stream()
            .sorted((a, b) -> Integer.compare(b.getBlockCount(), a.getBlockCount()))
            .limit(topN)
            .forEach(island -> {
                BlockPos mn = island.getMinCorner();
                BlockPos mx = island.getMaxCorner();
                src.sendSuccess(() -> Component.literal(String.format(
                        "  id=%-6d blocks=%-8d AABB=(%d,%d,%d)→(%d,%d,%d) epoch=%d",
                        island.getId(), island.getBlockCount(),
                        mn.getX(), mn.getY(), mn.getZ(),
                        mx.getX(), mx.getY(), mx.getZ(),
                        island.getLastModifiedEpoch()))
                    .withStyle(ChatFormatting.GRAY), false);
            });
        return 1;
    }

    // ─── /br debug pfsf ───────────────────────────────────────────────────

    private static int execDebugPFSF(CommandSourceStack src) {
        boolean pfsfCfgOn = BRConfig.isPFSFEnabled();
        boolean pfsfAvail = PFSFEngine.isAvailable();

        src.sendSuccess(() -> Component.literal("§6[BR-Debug PFSF]").withStyle(ChatFormatting.GOLD), false);
        src.sendSuccess(() -> Component.literal(
                "  Config: " + (pfsfCfgOn ? "§aON" : "§cOFF")
                + "  GPU: " + (pfsfAvail ? "§aAvailable" : "§cUnavailable")), false);

        if (pfsfAvail) {
            String stats = PFSFEngine.getStats();
            // Split long stats line on " | " for readability
            for (String part : stats.split(" \\| ")) {
                src.sendSuccess(() -> Component.literal("  " + part).withStyle(ChatFormatting.GRAY), false);
            }
        } else {
            src.sendSuccess(() -> Component.literal(
                    "  tickBudgetMs=" + BRConfig.getPFSFTickBudgetMs()
                    + "  maxIslandSize=" + BRConfig.getPFSFMaxIslandSize()
                    + "  vram=" + BRConfig.getVramUsagePercent() + "%")
                .withStyle(ChatFormatting.GRAY), false);
        }

        // Cache stats
        String cacheStats = ConnectivityCache.getCacheStats();
        src.sendSuccess(() -> Component.literal("  Cache: " + cacheStats).withStyle(ChatFormatting.GRAY), false);

        // v0.4 M3g: per-island LOD / macro-residual table. Skipped when no
        // buffer has been allocated yet — avoids printing a bare header in
        // fresh worlds or when PFSF is disabled.
        appendLodTable(src);

        return 1;
    }

    private static void appendLodTable(CommandSourceStack src) {
        Map<Integer, StructureIslandRegistry.StructureIsland> islands =
                StructureIslandRegistry.getAllIslands();
        if (islands.isEmpty()) return;

        List<int[]> rows = new ArrayList<>(islands.size());  // [id, lod, stable, osc, active‰, residE9]
        List<String> residualText = new ArrayList<>();
        for (Integer id : islands.keySet()) {
            PFSFIslandBuffer buf = PFSFBufferManager.getBuffer(id);
            if (buf == null) continue;  // island present in registry but not yet solved
            float[] residuals = buf.getCachedMacroResidualsView();
            float activeRatio = residuals != null ? PFSFScheduler.getActiveRatio(residuals) : 0f;
            int activePermille = Math.round(activeRatio * 1000f);
            float prevRes = buf.getPrevMaxMacroResidual();
            rows.add(new int[]{id, buf.getLodLevel(), buf.getStableTickCount(),
                    buf.getOscillationCount(), activePermille});
            residualText.add(String.format("%.2e", prevRes));
        }
        if (rows.isEmpty()) return;

        src.sendSuccess(() -> Component.literal("§6[BR-Debug PFSF LOD]").withStyle(ChatFormatting.GOLD), false);
        src.sendSuccess(() -> Component.literal(
                String.format("  %6s %-8s %6s %4s %7s %10s",
                        "id", "lod", "stable", "osc", "active", "prevMacRes"))
                .withStyle(ChatFormatting.YELLOW), false);
        for (int i = 0; i < rows.size(); i++) {
            int[] r = rows.get(i);
            String lodName = lodLabel(r[1]);
            String line = String.format("  %6d %-8s %6d %4d %6.1f%% %10s",
                    r[0], lodName, r[2], r[3], r[4] / 10.0f, residualText.get(i));
            src.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static String lodLabel(int lod) {
        switch (lod) {
            case PFSFConstants.LOD_FULL:     return "FULL";
            case PFSFConstants.LOD_STANDARD: return "STANDARD";
            case PFSFConstants.LOD_COARSE:   return "COARSE";
            case PFSFConstants.LOD_DORMANT:  return "DORMANT";
            default:                         return "LOD?" + lod;
        }
    }

    // ─── /br debug vs2 ────────────────────────────────────────────────────

    private static int execDebugVS2(CommandSourceStack src) {
        IVS2Bridge bridge = ModuleRegistry.getVS2Bridge();

        // Header
        src.sendSuccess(() -> Component.literal("=== VS2 Bridge Debug ===")
            .withStyle(ChatFormatting.GOLD), false);

        // Bridge health / circuit breaker
        boolean avail = bridge.isAvailable();
        String diag  = bridge.getBridgeDiagnostics();
        src.sendSuccess(() -> Component.literal(
            "  Status: " + diag)
            .withStyle(avail ? ChatFormatting.GREEN : ChatFormatting.RED), false);

        int shipCount = bridge.getActiveShipCount();
        src.sendSuccess(() -> Component.literal(
            "  Active ships: " + shipCount)
            .withStyle(shipCount > 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY), false);

        if (shipCount == 0) {
            src.sendSuccess(() -> Component.literal("  (no active VS2 ships)")
                .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        // Per-ship detail
        List<IVS2Bridge.ShipDataSnapshot> snapshots = bridge.getAllShipSnapshots();
        for (int i = 0; i < snapshots.size(); i++) {
            IVS2Bridge.ShipDataSnapshot snap = snapshots.get(i);
            final int idx = i + 1;
            src.sendSuccess(() -> Component.literal(
                String.format("  [%d] %s", idx, snap.toDebugLine()))
                .withStyle(ChatFormatting.GRAY), false);
        }

        return 1;
    }

    // ─── /br pfsf dump / dumpAll ──────────────────────────────────────────

    private static int execPfsfDump(CommandSourceStack src, int islandId) {
        ServerLevel level = src.getLevel();
        StructureIslandRegistry.StructureIsland island =
                StructureIslandRegistry.getIsland(islandId);
        if (island == null) {
            src.sendFailure(Component.literal(
                "[BR-PFSF] island id=" + islandId + " 不存在")
                .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (island.getBlockCount() == 0) {
            src.sendFailure(Component.literal(
                "[BR-PFSF] island id=" + islandId + " 為空，略過")
                .withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        Path outDir = fixtureOutputDir(src);
        try {
            Path written = PFSFFixtureWriter.dump(level, island, outDir);
            src.sendSuccess(() -> Component.literal(
                "[BR-PFSF] 已寫入 fixture: " + written + " (" + island.getBlockCount() + " 方塊)")
                .withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[BR-PFSF] dump island {} failed", islandId, e);
            src.sendFailure(Component.literal(
                "[BR-PFSF] 擷取失敗: " + e.getMessage())
                .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static int execPfsfDumpAll(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        Map<Integer, StructureIslandRegistry.StructureIsland> all =
                StructureIslandRegistry.getAllIslands();
        if (all.isEmpty()) {
            src.sendSuccess(() -> Component.literal("[BR-PFSF] 無可擷取的 island")
                .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        Path outDir = fixtureOutputDir(src);
        List<Path> written = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        for (StructureIslandRegistry.StructureIsland island : all.values()) {
            if (island.getBlockCount() == 0) continue;
            try {
                written.add(PFSFFixtureWriter.dump(level, island, outDir));
            } catch (Exception e) {
                LOGGER.error("[BR-PFSF] dump island {} failed", island.getId(), e);
                failed.add(island.getId());
            }
        }
        final int okCount = written.size();
        final int failCount = failed.size();
        src.sendSuccess(() -> Component.literal(String.format(
                "[BR-PFSF] 已擷取 %d 個 fixture → %s (失敗 %d)",
                okCount, outDir, failCount))
            .withStyle(failCount == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return okCount;
    }

    /** 擷取輸出目錄：世界存檔根目錄下的 {@code pfsf-fixtures/}。 */
    private static Path fixtureOutputDir(CommandSourceStack src) {
        return src.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("pfsf-fixtures");
    }

    // ─── /br debug dump ───────────────────────────────────────────────────

    private static int execDebugDump(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal(
                "§6[BR-Debug Dump] §f診斷資訊已輸出至伺服器日誌（br-debug.log）")
            .withStyle(ChatFormatting.YELLOW), false);

        // 轉儲到 LOGGER（日誌檔，不刷聊天室）
        LOGGER.info("=== /br debug dump ===");
        LOGGER.info("Islands: {}", StructureIslandRegistry.getStats());
        LOGGER.info("Cache: {}", ConnectivityCache.getCacheStats());

        if (PFSFEngine.isAvailable()) {
            LOGGER.info("PFSF: {}", PFSFEngine.getStats());
        } else {
            LOGGER.info("PFSF: unavailable");
        }

        // CollapseJournal 統計
        CollapseJournal journal = CollapseManager.getJournal();
        LOGGER.info("CollapseJournal: size={}", journal.size());
        journal.getFailureCounts().forEach((type, count) ->
            LOGGER.info("  FailureType.{} = {}", type.name(), count));

        // BRConfig 可調參數快照
        LOGGER.info("BRConfig snapshot:");
        LOGGER.info("  physicsEnabled={} pfsfEnabled={} pcgEnabled={}",
                BRConfig.isPhysicsEnabled(), BRConfig.isPFSFEnabled(), BRConfig.isPFSFPCGEnabled());
        LOGGER.info("  pfsfTickBudget={}ms  maxIslandSize={}  vram={}%",
                BRConfig.getPFSFTickBudgetMs(), BRConfig.getPFSFMaxIslandSize(), BRConfig.getVramUsagePercent());
        LOGGER.info("  fluidEnabled={}  thermalEnabled={}  windEnabled={}  emEnabled={}",
                BRConfig.isFluidEnabled(), BRConfig.isThermalEnabled(),
                BRConfig.isWindEnabled(), BRConfig.isEmEnabled());
        LOGGER.info("  maxCollapsePerTick={}  maxIslandsPerTick={}  evictorMinAge={}",
                BRConfig.getMaxCollapsePerTick(), BRConfig.getMaxIslandsPerTick(),
                BRConfig.getEvictorMinAgeTicks());
        // VS2 bridge snapshot
        IVS2Bridge vs2 = ModuleRegistry.getVS2Bridge();
        LOGGER.info("VS2Bridge: {}", vs2.getBridgeDiagnostics());
        List<IVS2Bridge.ShipDataSnapshot> vs2Ships = vs2.getAllShipSnapshots();
        if (vs2Ships.isEmpty()) {
            LOGGER.info("VS2Ships: (none)");
        } else {
            for (IVS2Bridge.ShipDataSnapshot snap : vs2Ships) {
                LOGGER.info("  {}", snap.toDebugLine());
            }
        }

        LOGGER.info("=== end dump ===");

        return 1;
    }
}
