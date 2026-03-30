package com.blockreality.api.network;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.item.ChiselCompatibleTool;
import com.blockreality.api.item.ChiselItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C→S 工具控制封包 — 雕刻刀 + 建築法杖 共用。
 *
 * 統一操作邏輯：
 *   - SEL_WIDTH_INC / SEL_WIDTH_DEC：左右鍵調整選區寬度 (1~10)
 *   - SEL_HEIGHT_INC / SEL_HEIGHT_DEC：上下鍵調整選區高度 (1~10)
 *   - EDGE_LENGTH_INC / EDGE_LENGTH_DEC：H 鍵調整邊長（寬高同時 ±1）
 *   - ERASE_ON / ERASE_OFF：X 鍵按住/放開 → 橡皮擦模式
 *   - SELECT_SHAPE：從選單選擇雕刻形狀（payload = shape serializedName）
 */
public class ChiselControlPacket {

    public enum Action {
        SEL_WIDTH_INC,
        SEL_WIDTH_DEC,
        SEL_HEIGHT_INC,
        SEL_HEIGHT_DEC,
        EDGE_LENGTH_INC,
        EDGE_LENGTH_DEC,
        ERASE_ON,
        ERASE_OFF,
        SELECT_SHAPE
    }

    private final Action action;
    private final String payload; // SELECT_SHAPE 用

    public ChiselControlPacket(Action action) {
        this(action, "");
    }

    public ChiselControlPacket(Action action, String payload) {
        this.action = action;
        this.payload = payload != null ? payload : "";
    }

    public static void encode(ChiselControlPacket pkt, FriendlyByteBuf buf) {
        buf.writeInt(pkt.action.ordinal());
        buf.writeUtf(pkt.payload, 64);
    }

    public static ChiselControlPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readInt();
        String payload = buf.readUtf(256);  // Bounds check: reasonable max string length
        Action[] actions = Action.values();
        // Bounds check: enum ordinal must be within valid range
        if (ordinal < 0 || ordinal >= actions.length) {
            return new ChiselControlPacket(Action.SEL_WIDTH_INC);
        }
        return new ChiselControlPacket(actions[ordinal], payload);
    }

    public static void handle(ChiselControlPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 尋找手持的雕刻刀或法杖（共用選區 NBT tag）
            ItemStack heldItem = findToolInHand(player);
            if (heldItem == null) return;

            boolean isChisel = heldItem.getItem() instanceof ChiselItem;

            switch (pkt.action) {
                case SEL_WIDTH_INC ->
                    ChiselItem.adjustSelectionWidth(heldItem, 1);
                case SEL_WIDTH_DEC ->
                    ChiselItem.adjustSelectionWidth(heldItem, -1);
                case SEL_HEIGHT_INC ->
                    ChiselItem.adjustSelectionHeight(heldItem, 1);
                case SEL_HEIGHT_DEC ->
                    ChiselItem.adjustSelectionHeight(heldItem, -1);
                case EDGE_LENGTH_INC -> {
                    // H 鍵：寬高同時 +1（正方形邊長調整）
                    ChiselItem.adjustSelectionWidth(heldItem, 1);
                    ChiselItem.adjustSelectionHeight(heldItem, 1);
                }
                case EDGE_LENGTH_DEC -> {
                    // H 鍵：寬高同時 -1
                    ChiselItem.adjustSelectionWidth(heldItem, -1);
                    ChiselItem.adjustSelectionHeight(heldItem, -1);
                }
                case ERASE_ON ->
                    ChiselItem.setEraseMode(heldItem, true);
                case ERASE_OFF ->
                    ChiselItem.setEraseMode(heldItem, false);
                case SELECT_SHAPE -> {
                    if (isChisel && !pkt.payload.isEmpty()) {
                        SubBlockShape shape = SubBlockShape.fromString(pkt.payload);
                        ChiselItem.setShapeByName(heldItem, shape);
                    }
                }
            }

            // ActionBar 反饋
            int w = ChiselItem.getSelectionWidth(heldItem);
            int h = ChiselItem.getSelectionHeight(heldItem);
            boolean erase = ChiselItem.isEraseMode(heldItem);
            String mode = erase ? "§c橡皮擦" : "§a填充";
            String toolName = isChisel ? "雕刻刀" : "法杖";
            String shapeInfo = isChisel
                ? " §7形狀: " + ChiselItem.getCurrentShapeName(heldItem)
                : "";
            player.displayClientMessage(
                Component.literal(
                    "§b[" + toolName + "] §f選區: " + w + "×" + h + " " + mode + shapeInfo),
                true
            );
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 在玩家手中尋找雕刻刀或法杖。
     * 兩種工具共用相同的選區 NBT tag（chisel_sel_w, chisel_sel_h, chisel_erase）。
     */
    private static ItemStack findToolInHand(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (isCompatibleTool(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (isCompatibleTool(off)) return off;
        return null;
    }

    /**
     * 判斷是否為支援選區控制的工具。
     * 雕刻刀（ChiselItem）和法杖（FdWandItem）都實作 ChiselCompatibleTool 標記介面。
     */
    private static boolean isCompatibleTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof ChiselCompatibleTool;
    }
}
