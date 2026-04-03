package com.blockreality.fastdesign.client;

import com.blockreality.api.client.GhostBlockRenderer;
import com.blockreality.api.item.ChiselItem;
import com.blockreality.api.network.BRNetwork;
import com.blockreality.api.network.ChiselControlPacket;
import com.blockreality.api.placement.BuildMode;
import com.blockreality.api.placement.MultiBlockCalculator;
import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.build.BuildModeState;
import com.blockreality.fastdesign.network.FdActionPacket;
import com.blockreality.fastdesign.network.FdNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Fast Design 快捷鍵系統 — Level 3 + v2.0
 *
 * G 鍵: 開啟 Control Panel (保留向下相容)
 * V 鍵: 切換建造模式 (Normal → Line → Wall → Cube → Mirror X → Mirror Z)
 * Alt 鍵: 開啟 Pie Menu / 雕刻刀形狀選單
 * X 鍵: 取消選取 / 橡皮擦模式（持工具時）
 * ↑/↓ 鍵: 選取區域上下平移 / 工具選區調高度
 * ←/→ 鍵: 工具選區調寬度
 * H 鍵: 拖動選取邊界調整大小 / 工具邊長調整
 * Ctrl+V: 設定鏡像錨點 (Mirror 模式下)
 */
public class FdKeyBindings {

    public static final KeyMapping OPEN_PANEL = new KeyMapping(
        "key.fastdesign.open_panel",
        InputConstants.KEY_G,
        "key.categories.fastdesign"
    );

    public static final KeyMapping CYCLE_BUILD_MODE = new KeyMapping(
        "key.fastdesign.cycle_build_mode",
        InputConstants.KEY_V,
        "key.categories.fastdesign"
    );

    // ★ 工具選區控制鍵（雕刻刀 + 建築法杖共用 + 選取區域操作）
    public static final KeyMapping TOOL_HEIGHT_UP = new KeyMapping(
        "key.fastdesign.tool_height_up",
        InputConstants.KEY_UP,
        "key.categories.fastdesign"
    );

    public static final KeyMapping TOOL_HEIGHT_DOWN = new KeyMapping(
        "key.fastdesign.tool_height_down",
        InputConstants.KEY_DOWN,
        "key.categories.fastdesign"
    );

    public static final KeyMapping TOOL_WIDTH_RIGHT = new KeyMapping(
        "key.fastdesign.tool_width_right",
        InputConstants.KEY_RIGHT,
        "key.categories.fastdesign"
    );

    public static final KeyMapping TOOL_WIDTH_LEFT = new KeyMapping(
        "key.fastdesign.tool_width_left",
        InputConstants.KEY_LEFT,
        "key.categories.fastdesign"
    );

    /** H 鍵：工具模式調整邊長 / 非工具模式拖動選取邊界 */
    public static final KeyMapping TOOL_EDGE_LENGTH = new KeyMapping(
        "key.fastdesign.tool_edge_length",
        InputConstants.KEY_H,
        "key.categories.fastdesign"
    );

    /** X 鍵：工具模式橡皮擦 / 非工具模式取消選取 */
    public static final KeyMapping TOOL_ERASE = new KeyMapping(
        "key.fastdesign.tool_erase",
        InputConstants.KEY_X,
        "key.categories.fastdesign"
    );

    /** Alt 鍵：工具模式形狀選單 / 非工具模式 Pie Menu */
    public static final KeyMapping TOOL_MENU = new KeyMapping(
        "key.fastdesign.tool_menu",
        InputConstants.KEY_LALT,
        "key.categories.fastdesign"
    );

    /**
     * MOD 事件匯流排 — 註冊快捷鍵
     */
    @Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModEvents {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_PANEL);
            event.register(CYCLE_BUILD_MODE);
            event.register(TOOL_HEIGHT_UP);
            event.register(TOOL_HEIGHT_DOWN);
            event.register(TOOL_WIDTH_RIGHT);
            event.register(TOOL_WIDTH_LEFT);
            event.register(TOOL_EDGE_LENGTH);
            event.register(TOOL_ERASE);
            event.register(TOOL_MENU);
            // P3-B: 結構安全 HUD 切換鍵（J 鍵）— 由 StructuralSafetyHud 自行在其 ModEvents 中註冊，
            // 此處無需重複，但保留說明供索引用途。
        }
    }

    /**
     * FORGE 事件匯流排 — 監聽按鍵觸發 + 幽靈方塊預覽更新 + Hologram 渲染
     */
    @Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeEvents {

        /** H鍵拖動邊界：記錄抓取的面和上次發送的值，避免重複封包 */
        private static String grabbedFace = null;
        private static int lastSentValue = Integer.MIN_VALUE;

        /** X 鍵上一 tick 狀態 — 用於偵測按下/放開邊緣 */
        private static boolean wasEraseKeyDown = false;

        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            HologramRenderer.onRenderLevelStage(event);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft mc = Minecraft.getInstance();

            // ─── G 鍵: 開啟控制面板 ───
            while (OPEN_PANEL.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new ControlPanelScreen());
                }
            }

            // ─── V 鍵: 切換建造模式 ───
            while (CYCLE_BUILD_MODE.consumeClick()) {
                if (mc.screen == null && mc.player != null) {
                    BuildMode newMode = BuildModeState.cycleMode();
                    mc.player.displayClientMessage(
                        Component.literal("§6[FD] §f建造模式: §a" +
                            newMode.getDisplayName() + " §7(" + newMode.getDescription() + ")"),
                        true
                    );
                    GhostBlockRenderer.clearPreview();
                }
            }

            // ─── 根據是否持有工具分流按鍵邏輯 ───
            if (mc.screen == null && mc.player != null && isHoldingTool(mc)) {
                handleChiselKeys(mc);
            } else {
                handleSelectionKeys(mc);
            }

            // ─── 每 tick 更新幽靈方塊預覽 ───
            updateGhostPreview(mc);
        }

        /** 判斷是否持有雕刻刀或法杖 */
        private static boolean isHoldingTool(Minecraft mc) {
            if (mc.player == null) return false;
            boolean holdingChisel =
                mc.player.getMainHandItem().getItem() instanceof ChiselItem ||
                mc.player.getOffhandItem().getItem() instanceof ChiselItem;
            boolean holdingWand =
                mc.player.getMainHandItem().getItem() instanceof
                    com.blockreality.fastdesign.item.FdWandItem ||
                mc.player.getOffhandItem().getItem() instanceof
                    com.blockreality.fastdesign.item.FdWandItem;
            return holdingChisel || holdingWand;
        }

        /**
         * 非工具模式：選取區域操作
         * Alt = Pie Menu, X = 取消選取, Shift+X = 個別排除方塊
         * ↑↓ = 上下平移, ←→ = 水平平移（依玩家朝向）
         * H(按住) = 拖動邊界調整大小
         */
        private static void handleSelectionKeys(Minecraft mc) {
            // 清除橡皮擦狀態（放下工具時）
            if (wasEraseKeyDown) {
                wasEraseKeyDown = false;
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.ERASE_OFF));
            }

            if (mc.screen != null || mc.player == null) {
                grabbedFace = null;
                lastSentValue = Integer.MIN_VALUE;
                return;
            }

            // ─── Alt 鍵: 開啟 Pie Menu 快捷輪盤 ───
            while (TOOL_MENU.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new PieMenuScreen());
                }
            }

            // ─── X 鍵: Shift+X = 個別排除方塊, X = 取消選取 ───
            while (TOOL_ERASE.consumeClick()) {
                if (net.minecraft.client.gui.screens.Screen.hasShiftDown()
                        && ClientSelectionHolder.hasSelection()) {
                    // Shift+X: 排除準心指向的方塊
                    HitResult hit = mc.hitResult;
                    if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                        BlockPos target = ((BlockHitResult) hit).getBlockPos();
                        FdNetwork.CHANNEL.sendToServer(
                            new FdActionPacket(FdActionPacket.Action.EXCLUDE_BLOCK,
                                target.getX() + "," + target.getY() + "," + target.getZ()));
                    }
                } else {
                    // X: 取消整個選取
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.DESELECT));
                }
            }

            // ─── ↑ 鍵: 選取區域上移一格 ───
            while (TOOL_HEIGHT_UP.consumeClick()) {
                if (ClientSelectionHolder.hasSelection()) {
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.SHIFT_SELECTION, "up"));
                }
            }

            // ─── ↓ 鍵: 選取區域下移一格 ───
            while (TOOL_HEIGHT_DOWN.consumeClick()) {
                if (ClientSelectionHolder.hasSelection()) {
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.SHIFT_SELECTION, "down"));
                }
            }

            // ─── → 鍵: 選取區域向右平移（依玩家朝向） ───
            while (TOOL_WIDTH_RIGHT.consumeClick()) {
                if (ClientSelectionHolder.hasSelection()) {
                    String dir = getPlayerRightDirection(mc);
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.SHIFT_SELECTION, dir));
                }
            }

            // ─── ← 鍵: 選取區域向左平移（依玩家朝向） ───
            while (TOOL_WIDTH_LEFT.consumeClick()) {
                if (ClientSelectionHolder.hasSelection()) {
                    String dir = getPlayerLeftDirection(mc);
                    FdNetwork.CHANNEL.sendToServer(
                        new FdActionPacket(FdActionPacket.Action.SHIFT_SELECTION, dir));
                }
            }

            // ─── H 鍵(按住): 對準選取邊界拖動調整大小 ───
            if (TOOL_EDGE_LENGTH.isDown() && ClientSelectionHolder.hasSelection()) {
                HitResult hit = mc.hitResult;
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos target = ((BlockHitResult) hit).getBlockPos();
                    var sel = ClientSelectionHolder.get();
                    if (sel != null) {
                        if (grabbedFace == null) {
                            grabbedFace = detectNearestFace(target, sel);
                        }
                        if (grabbedFace != null) {
                            int value = getFaceValue(target, grabbedFace);
                            if (value != lastSentValue) {
                                lastSentValue = value;
                                FdNetwork.CHANNEL.sendToServer(
                                    new FdActionPacket(FdActionPacket.Action.RESIZE_SELECTION,
                                        grabbedFace + "," + value));
                            }
                        }
                    }
                }
            } else {
                grabbedFace = null;
                lastSentValue = Integer.MIN_VALUE;
            }
        }

        /**
         * 依玩家水平朝向取得「右方」的方位名稱
         */
        private static String getPlayerRightDirection(Minecraft mc) {
            Direction facing = mc.player.getDirection(); // 水平朝向
            return switch (facing) {
                case NORTH -> "east";
                case SOUTH -> "west";
                case EAST  -> "south";
                case WEST  -> "north";
                default    -> "east";
            };
        }

        /**
         * 依玩家水平朝向取得「左方」的方位名稱
         */
        private static String getPlayerLeftDirection(Minecraft mc) {
            Direction facing = mc.player.getDirection();
            return switch (facing) {
                case NORTH -> "west";
                case SOUTH -> "east";
                case EAST  -> "north";
                case WEST  -> "south";
                default    -> "west";
            };
        }

        /**
         * 處理工具快捷鍵 — 雕刻刀 + 建築法杖共用。
         */
        private static void handleChiselKeys(Minecraft mc) {
            if (mc.player == null || mc.screen != null) return;

            boolean holdingChisel =
                mc.player.getMainHandItem().getItem() instanceof ChiselItem ||
                mc.player.getOffhandItem().getItem() instanceof ChiselItem;

            // ─── 輔助方法：獲取當前手持的雕刻刀並更新客戶端 NBT ───
            net.minecraft.world.item.ItemStack chiselStack = null;
            if (mc.player.getMainHandItem().getItem() instanceof ChiselItem) {
                chiselStack = mc.player.getMainHandItem();
            } else if (mc.player.getOffhandItem().getItem() instanceof ChiselItem) {
                chiselStack = mc.player.getOffhandItem();
            }

            // ─── 上下鍵：調高度 ───
            while (TOOL_HEIGHT_UP.consumeClick()) {
                if (chiselStack != null) ChiselItem.adjustSelectionHeight(chiselStack, 1);
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_HEIGHT_INC));
            }
            while (TOOL_HEIGHT_DOWN.consumeClick()) {
                if (chiselStack != null) ChiselItem.adjustSelectionHeight(chiselStack, -1);
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_HEIGHT_DEC));
            }

            // ─── 左右鍵：調寬度 ───
            while (TOOL_WIDTH_RIGHT.consumeClick()) {
                if (chiselStack != null) ChiselItem.adjustSelectionWidth(chiselStack, 1);
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_WIDTH_INC));
            }
            while (TOOL_WIDTH_LEFT.consumeClick()) {
                if (chiselStack != null) ChiselItem.adjustSelectionWidth(chiselStack, -1);
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.SEL_WIDTH_DEC));
            }

            // ─── H 鍵：邊長（寬高同時調整），Shift+H 縮小 ───
            while (TOOL_EDGE_LENGTH.consumeClick()) {
                boolean shrink = net.minecraft.client.gui.screens.Screen.hasShiftDown();
                if (chiselStack != null) {
                    int delta = shrink ? -1 : 1;
                    ChiselItem.adjustSelectionWidth(chiselStack, delta);
                    ChiselItem.adjustSelectionHeight(chiselStack, delta);
                }
                ChiselControlPacket.Action edgeAction = shrink
                    ? ChiselControlPacket.Action.EDGE_LENGTH_DEC
                    : ChiselControlPacket.Action.EDGE_LENGTH_INC;
                BRNetwork.CHANNEL.sendToServer(new ChiselControlPacket(edgeAction));
            }

            // ─── X 鍵：按住 = 橡皮擦模式（邊緣觸發） ───
            boolean isEraseDown = TOOL_ERASE.isDown();
            if (isEraseDown && !wasEraseKeyDown) {
                if (chiselStack != null) ChiselItem.setEraseMode(chiselStack, true);
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.ERASE_ON));
            } else if (!isEraseDown && wasEraseKeyDown) {
                if (chiselStack != null) ChiselItem.setEraseMode(chiselStack, false);
                BRNetwork.CHANNEL.sendToServer(
                    new ChiselControlPacket(ChiselControlPacket.Action.ERASE_OFF));
            }
            wasEraseKeyDown = isEraseDown;

            // ─── Alt 鍵：長按彈出工具選單 ───
            while (TOOL_MENU.consumeClick()) {
                if (holdingChisel) {
                    mc.setScreen(new ChiselToolScreen());
                } else {
                    mc.setScreen(new PieMenuScreen());
                }
            }
        }

        /**
         * 偵測準心位置最接近選取框的哪一面。
         */
        private static String detectNearestFace(BlockPos target, ClientSelectionHolder.SelectionData sel) {
            int tx = target.getX(), ty = target.getY(), tz = target.getZ();
            int minX = sel.min().getX(), minY = sel.min().getY(), minZ = sel.min().getZ();
            int maxX = sel.max().getX(), maxY = sel.max().getY(), maxZ = sel.max().getZ();

            String closest = null;
            int minDist = Integer.MAX_VALUE;

            int[][] faces = {
                {Math.abs(tx - minX), 0},  // x-
                {Math.abs(tx - maxX), 1},  // x+
                {Math.abs(ty - minY), 2},  // y-
                {Math.abs(ty - maxY), 3},  // y+
                {Math.abs(tz - minZ), 4},  // z-
                {Math.abs(tz - maxZ), 5},  // z+
            };
            String[] names = {"x-", "x+", "y-", "y+", "z-", "z+"};

            for (int[] f : faces) {
                if (f[0] < minDist) {
                    minDist = f[0];
                    closest = names[f[1]];
                }
            }
            return closest;
        }

        /**
         * 根據抓取的面，從目標方塊取得對應軸的座標值。
         */
        private static int getFaceValue(BlockPos target, String face) {
            return switch (face) {
                case "x-", "x+" -> target.getX();
                case "y-", "y+" -> target.getY();
                case "z-", "z+" -> target.getZ();
                default -> 0;
            };
        }

        /**
         * 每個客戶端 tick 更新幽靈方塊預覽。
         */
        private static void updateGhostPreview(Minecraft mc) {
            if (mc.player == null || mc.level == null) {
                GhostBlockRenderer.clearPreview();
                return;
            }

            if (!(mc.player.getMainHandItem().getItem() instanceof
                    com.blockreality.fastdesign.item.FdWandItem)) {
                if (GhostBlockRenderer.hasPreview()) {
                    GhostBlockRenderer.clearPreview();
                }
                return;
            }

            if (!BuildModeState.isMultiBlockMode()) {
                if (GhostBlockRenderer.hasPreview()) {
                    GhostBlockRenderer.clearPreview();
                }
                return;
            }

            BlockPos anchor = BuildModeState.getAnchor();
            if (anchor == null) {
                if (GhostBlockRenderer.hasPreview()) {
                    GhostBlockRenderer.clearPreview();
                }
                return;
            }

            HitResult hit = mc.hitResult;
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                GhostBlockRenderer.clearPreview();
                return;
            }

            BlockPos target = ((BlockHitResult) hit).getBlockPos();
            BuildModeState.setPreviewTarget(target);

            List<BlockPos> positions = MultiBlockCalculator.calculate(
                BuildModeState.getMode(),
                anchor,
                target,
                BuildModeState.getMirrorAnchor()
            );

            GhostBlockRenderer.setPreview(positions);
        }
    }
}
