package com.blockreality.api.item;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.chisel.VoxelGrid;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.network.ChiselSyncPacket;
import com.blockreality.api.physics.StructureIslandRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * 雕刻刀 — 將 RBlock 雕刻為子方塊形狀。
 *
 * 操作方式：
 *   - 右鍵方塊（模板模式）：套用當前模板形狀
 *   - 右鍵方塊（自訂模式）：以選區框 (selWidth × selHeight) 填充子體素
 *   - X + 右鍵方塊（自訂模式）：移除單個子體素
 *   - 上下鍵：調整選區高度 (1~10)
 *   - 左右鍵：調整選區寬度 (1~10)
 *   - Shift+右鍵空中：循環切換模板形狀
 *
 * 選區大小與模式儲存在 ItemStack NBT 中。
 */
public class ChiselItem extends Item implements ChiselCompatibleTool {

    private static final String TAG_SHAPE_INDEX = "chisel_shape_idx";
    private static final String TAG_SEL_WIDTH = "chisel_sel_w";
    private static final String TAG_SEL_HEIGHT = "chisel_sel_h";
    private static final String TAG_ERASE = "chisel_erase";

    /** 選區邊長範圍 */
    private static final int MIN_SEL = 1;
    private static final int MAX_SEL = 10;

    /** 可循環的模板形狀 */
    private static final SubBlockShape[] TEMPLATE_CYCLE = {
        SubBlockShape.FULL,
        SubBlockShape.SLAB_BOTTOM,
        SubBlockShape.SLAB_TOP,
        SubBlockShape.STAIR_NORTH,
        SubBlockShape.STAIR_SOUTH,
        SubBlockShape.STAIR_EAST,
        SubBlockShape.STAIR_WEST,
        SubBlockShape.PILLAR,
        SubBlockShape.QUARTER_NE,
        SubBlockShape.QUARTER_NW,
        SubBlockShape.QUARTER_SE,
        SubBlockShape.QUARTER_SW,
        SubBlockShape.ARCH_BOTTOM,
        SubBlockShape.ARCH_TOP,
        SubBlockShape.BEAM_NS,
        SubBlockShape.BEAM_EW,
        SubBlockShape.CUSTOM,
    };

    public ChiselItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @org.jetbrains.annotations.Nullable Level level,
                                 @NotNull java.util.List<Component> tooltip,
                                 @NotNull net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(Component.literal("右鍵方塊: 套用形狀 / 選區填充").withStyle(net.minecraft.ChatFormatting.AQUA));
        tooltip.add(Component.literal("Alt: 開啟形狀選單").withStyle(net.minecraft.ChatFormatting.YELLOW));
        tooltip.add(Component.literal("X+右鍵: 橡皮擦（移除子體素）").withStyle(net.minecraft.ChatFormatting.RED));
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("↑↓ 調高度  ←→ 調寬度  H 邊長").withStyle(net.minecraft.ChatFormatting.GRAY));
        String shape = getCurrentShapeName(stack);
        int w = getSelectionWidth(stack);
        int h = getSelectionHeight(stack);
        tooltip.add(Component.literal("形狀: " + shape + "  選區: " + w + "×" + h)
            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    // ═══════════════════════════════════════════════════
    //  右鍵方塊：套用形狀 / 選區填充 / 橡皮擦
    // ═══════════════════════════════════════════════════

    @Override
    @NotNull
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockPos pos = context.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity rbe)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        SubBlockShape currentShape = getCurrentShape(stack);

        if (currentShape == SubBlockShape.CUSTOM) {
            return handleCustomMode(context, rbe, stack);
        } else {
            // 模板模式：直接套用
            ChiselState newState = ChiselState.ofShape(currentShape);
            rbe.setChiselState(newState);
        }

        syncAndMarkDirty(level, pos, rbe);
        return InteractionResult.CONSUME;
    }

    /**
     * 自訂模式處理：
     *   - 橡皮擦模式 (X 按住)：移除 hit 位置的單個子體素
     *   - 正常模式：以選區框填充子體素
     */
    private InteractionResult handleCustomMode(UseOnContext context, RBlockEntity rbe, ItemStack stack) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Vec3 hit = context.getClickLocation();
        Direction face = context.getClickedFace();

        // 計算 hit 位置對應的子體素座標
        int hitVx = Mth.clamp((int) ((hit.x - pos.getX()) * 10), 0, 9);
        int hitVy = Mth.clamp((int) ((hit.y - pos.getY()) * 10), 0, 9);
        int hitVz = Mth.clamp((int) ((hit.z - pos.getZ()) * 10), 0, 9);

        ChiselState oldState = rbe.getChiselState();
        VoxelGrid oldGrid = oldState.isCustom() ? oldState.voxelGrid() : VoxelGrid.full();

        if (isEraseMode(stack)) {
            // ★ 橡皮擦模式：X + 右鍵 → 移除單個子體素
            if (!oldGrid.get(hitVx, hitVy, hitVz)) {
                return InteractionResult.PASS; // 已經是空的
            }
            VoxelGrid newGrid = new VoxelGrid.Builder(oldGrid)
                .set(hitVx, hitVy, hitVz, false)
                .build();
            if (newGrid.isEmpty()) {
                return InteractionResult.FAIL; // 不允許完全移除
            }
            rbe.setChiselState(ChiselState.ofCustom(newGrid));
        } else {
            // ★ 選區填充模式：以 selWidth × selHeight 框在 hit 位置填充
            int selW = getSelectionWidth(stack);
            int selH = getSelectionHeight(stack);

            VoxelGrid.Builder builder = new VoxelGrid.Builder(oldGrid);
            applySelectionBox(builder, hitVx, hitVy, hitVz, face, selW, selH, true);
            VoxelGrid newGrid = builder.build();
            rbe.setChiselState(ChiselState.ofCustom(newGrid));
        }

        syncAndMarkDirty(level, pos, rbe);
        return InteractionResult.CONSUME;
    }

    /**
     * 在子體素網格上套用選區框操作。
     *
     * 選區框以 hit 體素為起點，沿著點擊面的兩個平面軸展開。
     * 面法線方向不展開（深度固定 1 層）。
     *
     * @param builder   VoxelGrid 建造器
     * @param hitX      hit 體素 X
     * @param hitY      hit 體素 Y
     * @param hitZ      hit 體素 Z
     * @param face      點擊的面方向（決定展開軸）
     * @param selWidth  選區寬度（左右鍵調整）
     * @param selHeight 選區高度（上下鍵調整）
     * @param value     true = 填充, false = 清除
     */
    private static void applySelectionBox(VoxelGrid.Builder builder,
                                           int hitX, int hitY, int hitZ,
                                           Direction face,
                                           int selWidth, int selHeight,
                                           boolean value) {
        // 根據面方向決定展開軸
        // 寬度 (selWidth) → 水平方向, 高度 (selHeight) → 垂直方向
        // 面法線方向固定不展開
        int halfW = (selWidth - 1) / 2;
        int halfH = (selHeight - 1) / 2;

        switch (face.getAxis()) {
            case Y -> {
                // 點擊頂/底面：選區在 XZ 平面展開
                // 寬度 → X 軸, 高度 → Z 軸
                for (int dx = -halfW; dx <= halfW + (selWidth - 1) % 2; dx++) {
                    for (int dz = -halfH; dz <= halfH + (selHeight - 1) % 2; dz++) {
                        int vx = Mth.clamp(hitX + dx, 0, 9);
                        int vz = Mth.clamp(hitZ + dz, 0, 9);
                        builder.set(vx, hitY, vz, value);
                    }
                }
            }
            case X -> {
                // 點擊東/西面：選區在 ZY 平面展開
                // 寬度 → Z 軸, 高度 → Y 軸
                for (int dz = -halfW; dz <= halfW + (selWidth - 1) % 2; dz++) {
                    for (int dy = -halfH; dy <= halfH + (selHeight - 1) % 2; dy++) {
                        int vz = Mth.clamp(hitZ + dz, 0, 9);
                        int vy = Mth.clamp(hitY + dy, 0, 9);
                        builder.set(hitX, vy, vz, value);
                    }
                }
            }
            case Z -> {
                // 點擊南/北面：選區在 XY 平面展開
                // 寬度 → X 軸, 高度 → Y 軸
                for (int dx = -halfW; dx <= halfW + (selWidth - 1) % 2; dx++) {
                    for (int dy = -halfH; dy <= halfH + (selHeight - 1) % 2; dy++) {
                        int vx = Mth.clamp(hitX + dx, 0, 9);
                        int vy = Mth.clamp(hitY + dy, 0, 9);
                        builder.set(vx, vy, hitZ, value);
                    }
                }
            }
        }
    }

    /**
     * 同步雕刻狀態到附近玩家並觸發物理重算。
     */
    private void syncAndMarkDirty(Level level, BlockPos pos, RBlockEntity rbe) {
        BRNetwork.CHANNEL.send(
            PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(pos)),
            new ChiselSyncPacket(pos, rbe.getChiselState())
        );

        // Mark the structure island as dirty so physics is recalculated
        int islandId = StructureIslandRegistry.getIslandId(pos);
        if (islandId >= 0) {
            StructureIslandRegistry.markDirty(islandId);
        }
    }

    // ═══════════════════════════════════════════════════
    //  右鍵空中：不再使用 Shift 循環（改為 Alt 彈出選單）
    // ═══════════════════════════════════════════════════

    @Override
    @NotNull
    public InteractionResultHolder<ItemStack> use(
            @NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        // 形狀選擇已改為 Alt 長按彈出選單（客戶端 FdKeyBindings 處理）
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    // ═══════════════════════════════════════════════════
    //  NBT 輔助 — 形狀索引
    // ═══════════════════════════════════════════════════

    private SubBlockShape getCurrentShape(ItemStack stack) {
        int idx = getShapeIndex(stack);
        if (idx < 0 || idx >= TEMPLATE_CYCLE.length) return SubBlockShape.FULL;
        return TEMPLATE_CYCLE[idx];
    }

    private static int getShapeIndex(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_SHAPE_INDEX)) return 0;
        return tag.getInt(TAG_SHAPE_INDEX);
    }

    private static void setShapeIndex(ItemStack stack, int idx) {
        stack.getOrCreateTag().putInt(TAG_SHAPE_INDEX, idx);
    }

    /** 透過形狀名稱設定（從選單選擇） */
    public static void setShapeByName(ItemStack stack, SubBlockShape shape) {
        for (int i = 0; i < TEMPLATE_CYCLE.length; i++) {
            if (TEMPLATE_CYCLE[i] == shape) {
                setShapeIndex(stack, i);
                return;
            }
        }
        setShapeIndex(stack, 0); // fallback to FULL
    }

    /** 取得當前形狀名稱（用於 ActionBar 顯示） */
    public static String getCurrentShapeName(ItemStack stack) {
        int idx = getShapeIndex(stack);
        if (idx < 0 || idx >= TEMPLATE_CYCLE.length) return "full";
        return TEMPLATE_CYCLE[idx].getSerializedName();
    }

    // ═══════════════════════════════════════════════════
    //  Static NBT API — 供 ChiselControlPacket 呼叫
    // ═══════════════════════════════════════════════════

    /** 取得選區寬度 (預設 1) */
    public static int getSelectionWidth(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_SEL_WIDTH)) return 1;
        return Mth.clamp(tag.getInt(TAG_SEL_WIDTH), MIN_SEL, MAX_SEL);
    }

    /** 取得選區高度 (預設 1) */
    public static int getSelectionHeight(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_SEL_HEIGHT)) return 1;
        return Mth.clamp(tag.getInt(TAG_SEL_HEIGHT), MIN_SEL, MAX_SEL);
    }

    /** 調整選區寬度 (±delta, clamped to 1~10) */
    public static void adjustSelectionWidth(ItemStack stack, int delta) {
        int current = getSelectionWidth(stack);
        int next = Mth.clamp(current + delta, MIN_SEL, MAX_SEL);
        stack.getOrCreateTag().putInt(TAG_SEL_WIDTH, next);
    }

    /** 調整選區高度 (±delta, clamped to 1~10) */
    public static void adjustSelectionHeight(ItemStack stack, int delta) {
        int current = getSelectionHeight(stack);
        int next = Mth.clamp(current + delta, MIN_SEL, MAX_SEL);
        stack.getOrCreateTag().putInt(TAG_SEL_HEIGHT, next);
    }

    /** 是否為橡皮擦模式 (X 按住) */
    public static boolean isEraseMode(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_ERASE);
    }

    /** 設定橡皮擦模式 */
    public static void setEraseMode(ItemStack stack, boolean erase) {
        stack.getOrCreateTag().putBoolean(TAG_ERASE, erase);
    }
}
