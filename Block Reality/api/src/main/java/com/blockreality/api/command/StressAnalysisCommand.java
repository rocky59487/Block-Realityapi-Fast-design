package com.blockreality.api.command;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.physics.SupportPathAnalyzer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * /br_stress [radius] [collapse]
 *
 * 帶權重的應力分析指令：
 *   /br_stress 10          — 分析周圍 10 格半徑，顯示結果
 *   /br_stress 10 collapse — 分析後崩塌所有失效方塊
 *
 * 這是 SupportPathAnalyzer（偽真實力學 BFS）的前端。
 */
public class StressAnalysisCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_stress")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                    .then(Commands.literal("collapse")
                        .executes(ctx -> runAnalysis(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "radius"),
                            true
                        )))
                    .executes(ctx -> runAnalysis(
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "radius"),
                        false
                    )))
                .executes(ctx -> runAnalysis(ctx.getSource(), 8, false))
        );
    }

    private static int runAnalysis(CommandSourceStack source, int radius, boolean doCollapse) {
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());

        source.sendSuccess(() -> Component.literal(
            String.format("[BR Stress] 分析中... 中心=(%d,%d,%d) 半徑=%d",
                center.getX(), center.getY(), center.getZ(), radius)
        ), false);

        // 執行帶權重的 BFS 分析
        SupportPathAnalyzer.AnalysisResult result = SupportPathAnalyzer.analyze(level, center, radius);

        // 更新所有 RBlock 的 stressLevel（用於未來的視覺化）
        int updatedBlocks = 0;
        for (Map.Entry<BlockPos, Float> entry : result.stressMap().entrySet()) {
            BlockEntity be = level.getBlockEntity(entry.getKey());
            if (be instanceof RBlockEntity rbe) {
                rbe.setStressLevelBatch(entry.getValue());
                updatedBlocks++;
            }
        }
        // Flush all batch updates
        for (BlockPos pos : result.stressMap().keySet()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RBlockEntity rbe) {
                rbe.flushSync();
            }
        }

        // 報告結果
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[BR Stress] 分析完成 (%.1fms)\n", result.elapsedMs()));
        sb.append(String.format("  總方塊: %d | 安全: %d | 失效: %d\n",
            result.totalAnalyzed(), result.stable().size(), result.failureCount()));

        if (result.failureCount() > 0) {
            // 分類統計
            int cantilever = 0, crushing = 0, noSupport = 0;
            for (var reason : result.failures().values()) {
                switch (reason.type()) {
                    case CANTILEVER_BREAK -> cantilever++;
                    case CRUSHING -> crushing++;
                    case NO_SUPPORT -> noSupport++;
                }
            }
            sb.append(String.format("  懸臂斷裂: %d | 壓碎: %d | 無支撐: %d\n",
                cantilever, crushing, noSupport));

            // 顯示前 5 個失效點
            int shown = 0;
            for (var entry : result.failures().entrySet()) {
                if (shown >= 5) {
                    sb.append("  ... 更多省略\n");
                    break;
                }
                BlockPos pos = entry.getKey();
                var reason = entry.getValue();
                sb.append(String.format("  ✗ (%d,%d,%d) %s: %s\n",
                    pos.getX(), pos.getY(), pos.getZ(),
                    reason.type(), reason.detail()));
                shown++;
            }
        }

        sb.append(String.format("  已更新 %d 個 RBlock 的應力值", updatedBlocks));

        final String report = sb.toString();
        source.sendSuccess(() -> Component.literal(report), false);

        // 崩塌模式
        if (doCollapse && result.failureCount() > 0) {
            source.getServer().execute(() -> {
                int collapsed = 0;
                for (BlockPos pos : result.failures().keySet()) {
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        FallingBlockEntity.fall(level, pos, state);
                        collapsed++;
                    }
                }
                final int finalCollapsed = collapsed;
                source.sendSuccess(() -> Component.literal(
                    String.format("[BR Stress] 已崩塌 %d 個失效方塊", finalCollapsed)
                ), true);
            });
        }

        return 1;
    }
}
