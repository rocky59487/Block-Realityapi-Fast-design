package com.blockreality.api.command;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * /br_material [info|list|set <material>]
 *
 * 開發用指令：
 *   info — 查看準星指向方塊的材料參數
 *   list — 列出所有預設材料及其參數
 *   set  — 設定準星指向的 RBlock 之材料
 */
public class MaterialInfoCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_material")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("info")
                    .executes(ctx -> showInfo(ctx.getSource())))
                .then(Commands.literal("list")
                    .executes(ctx -> listMaterials(ctx.getSource())))
                .then(Commands.literal("set")
                    .then(Commands.argument("material", StringArgumentType.word())
                        .executes(ctx -> setMaterial(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "material")
                        ))))
                .executes(ctx -> showInfo(ctx.getSource()))
        );
    }

    private static int showInfo(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        HitResult hit = player.pick(5.0, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("[BR Material] 未指向任何方塊"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        ServerLevel level = source.getLevel();
        BlockEntity be = level.getBlockEntity(pos);

        if (be instanceof RBlockEntity rbe) {
            RMaterial mat = rbe.getMaterial();
            String msg = String.format(
                "[BR Material] (%d,%d,%d)\n" +
                "  Material: %s | Type: %s\n" +
                "  Rcomp=%.1f MPa | Rtens=%.1f MPa | Rshear=%.1f MPa\n" +
                "  Density=%.0f kg/m³ | Ductile=%b\n" +
                "  Stress=%.2f | StructID=%d | Anchored=%b",
                pos.getX(), pos.getY(), pos.getZ(),
                mat.getMaterialId(), rbe.getBlockType().getSerializedName(),
                mat.getRcomp(), mat.getRtens(), mat.getRshear(),
                mat.getDensity(), mat.isDuctile(),
                rbe.getStressLevel(), rbe.getStructureId(), rbe.isAnchored()
            );
            source.sendSuccess(() -> Component.literal(msg), false);
        } else {
            // 非 RBlock — 顯示 SnapshotBuilder 的翻譯結果
            String blockId = level.getBlockState(pos).getBlock().toString();
            source.sendSuccess(() -> Component.literal(
                String.format("[BR Material] (%d,%d,%d) 非 R-Block: %s (無物理數據)",
                    pos.getX(), pos.getY(), pos.getZ(), blockId)
            ), false);
        }
        return 1;
    }

    private static int listMaterials(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("[BR Materials]\n");
        for (DefaultMaterial mat : DefaultMaterial.values()) {
            sb.append(String.format(
                "  %-16s Rc=%7.1f Rt=%7.1f Rs=%7.1f ρ=%7.0f %s\n",
                mat.getMaterialId(),
                mat.getRcomp(), mat.getRtens(), mat.getRshear(),
                mat.getDensity(),
                mat.isDuctile() ? "DUCTILE" : "brittle"
            ));
        }
        final String result = sb.toString();
        source.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int setMaterial(CommandSourceStack source, String materialId) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        HitResult hit = player.pick(5.0, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("[BR Material] 未指向任何方塊"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        BlockEntity be = source.getLevel().getBlockEntity(pos);

        if (be instanceof RBlockEntity rbe) {
            // ★ C-2 fix: 驗證 materialId，避免靜默回退
            boolean validId = false;
            for (DefaultMaterial m : DefaultMaterial.values()) {
                if (m.getMaterialId().equals(materialId)) { validId = true; break; }
            }
            if (!validId) {
                source.sendFailure(Component.literal(
                    "[BR Material] Unknown material: " + materialId + ". Use /br_material list"));
                return 0;
            }
            DefaultMaterial mat = DefaultMaterial.fromId(materialId);
            rbe.setMaterial(mat);
            source.sendSuccess(() -> Component.literal(
                String.format("[BR Material] 已設定 (%d,%d,%d) 材料為 %s",
                    pos.getX(), pos.getY(), pos.getZ(), mat.getMaterialId())
            ), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("[BR Material] 該方塊不是 R-Block，無法設定材料"));
            return 0;
        }
    }
}
