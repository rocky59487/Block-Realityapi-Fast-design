package com.blockreality.api.block;

import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.chisel.VoxelGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Block Reality 結構方塊。
 *
 * 繼承 BaseEntityBlock 以搭載 RBlockEntity，
 * 提供材料參數存儲、NBT 持久化、Client 同步等完整物理數據管線。
 *
 * 渲染使用 MODEL 模式（標準方塊外觀），
 * 未來可依 stressLevel 改變外觀（裂紋紋理等）。
 */
public class RBlock extends BaseEntityBlock {

    public RBlock() {
        super(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(2.0f, 6.0f)      // hardness=2, resistance=6 (同石頭)
            .sound(SoundType.STONE)
            .requiresCorrectToolForDrops()
        );
    }

    /**
     * 自訂 Properties 的建構子 — 給不同材質的子類使用。
     */
    public RBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new RBlockEntity(pos, state);
    }

    /**
     * 目前不需要 tick — 未來養護計時 (RC curing) 可在此掛載 ticker。
     */
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            @NotNull Level level,
            @NotNull BlockState state,
            @NotNull BlockEntityType<T> type) {
        return null;
    }

    /**
     * 使用標準方塊模型渲染。
     * BaseEntityBlock 預設是 INVISIBLE，必須覆寫。
     */
    @Override
    @NotNull
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    // ─── 雕刻碰撞箱 ───

    /** 模板形狀的 VoxelShape 快取（lazy init） */
    private static final ConcurrentHashMap<SubBlockShape, VoxelShape> SHAPE_CACHE = new ConcurrentHashMap<>();

    @Override
    @NotNull
    public VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
                               @NotNull BlockPos pos, @NotNull CollisionContext context) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe) {
            ChiselState cs = rbe.getChiselState();
            if (!cs.isFull()) {
                return getChiselShape(cs, rbe);
            }
        }
        return Shapes.block();
    }

    private static VoxelShape getChiselShape(ChiselState cs, RBlockEntity rbe) {
        if (cs.isTemplate()) {
            // 模板形狀使用快取
            return SHAPE_CACHE.computeIfAbsent(cs.shape(), RBlock::buildTemplateShape);
        }
        // ★ audit-fix M-4: 自訂形狀使用 RBlockEntity 上的快取，
        // 避免每次 getShape() 呼叫重新生成（getShape 每 tick 每方塊可能呼叫多次）
        VoxelShape cached = rbe.getCachedCustomShape();
        if (cached != null) return cached;
        VoxelShape built = buildVoxelShape(cs.voxelGrid());
        rbe.setCachedCustomShape(built);
        return built;
    }

    private static VoxelShape buildTemplateShape(SubBlockShape shape) {
        return buildVoxelShape(VoxelGrid.fromShape(shape));
    }

    /**
     * 將 10×10×10 體素網格轉換為 Minecraft VoxelShape。
     * 使用 column-merge 策略減少 AABB 數量。
     */
    private static VoxelShape buildVoxelShape(VoxelGrid grid) {
        if (grid.isFull()) return Shapes.block();
        if (grid.isEmpty()) return Shapes.empty();

        VoxelShape shape = Shapes.empty();
        double step = 1.0 / VoxelGrid.SIZE; // 0.1

        for (int z = 0; z < VoxelGrid.SIZE; z++) {
            for (int x = 0; x < VoxelGrid.SIZE; x++) {
                // 合併同一 column 的連續 Y 段
                int yStart = -1;
                for (int y = 0; y <= VoxelGrid.SIZE; y++) {
                    boolean filled = y < VoxelGrid.SIZE && grid.get(x, y, z);
                    if (filled && yStart < 0) {
                        yStart = y;
                    } else if (!filled && yStart >= 0) {
                        shape = Shapes.or(shape, Block.box(
                            x * step * 16, yStart * step * 16, z * step * 16,
                            (x + 1) * step * 16, y * step * 16, (z + 1) * step * 16
                        ));
                        yStart = -1;
                    }
                }
            }
        }
        return shape.optimize();
    }
}
