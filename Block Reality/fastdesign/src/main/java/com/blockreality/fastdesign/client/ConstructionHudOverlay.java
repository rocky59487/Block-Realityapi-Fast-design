package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 施工區域 HUD 疊層渲染器 — 開發手冊 §8.3
 *
 * 當玩家位於施工區域時，在畫面右上角顯示進度條和工序信息。
 */
@Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ConstructionHudOverlay {

    // Client-side cache: set by sync packet
    private static volatile String currentPhaseName = null;
    private static volatile String currentPhaseZh = null;
    private static volatile int zoneId = -1;
    private static volatile float progress = 0.0f;

    /**
     * 設定當前施工區域信息。由同步封包呼叫。
     */
    public static void setZoneInfo(String phaseName, String phaseZh, float progress) {
        ConstructionHudOverlay.currentPhaseName = phaseName;
        ConstructionHudOverlay.currentPhaseZh = phaseZh;
        ConstructionHudOverlay.progress = Math.max(0.0f, Math.min(1.0f, progress));
    }

    /**
     * 清除施工區域信息。
     */
    public static void clearZoneInfo() {
        ConstructionHudOverlay.currentPhaseName = null;
        ConstructionHudOverlay.currentPhaseZh = null;
        ConstructionHudOverlay.zoneId = -1;
        ConstructionHudOverlay.progress = 0.0f;
    }

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        // 只在 HUD 最後渲染
        if (event.getOverlay() == null) {
            return; // 跳過無效事件
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || currentPhaseName == null) {
            return;
        }

        GuiGraphics gui = event.getGuiGraphics();
        int screenWidth = gui.guiWidth();
        int screenHeight = gui.guiHeight();

        // 右上角位置
        int x = screenWidth - 220;
        int y = 10;

        // 背景框大小
        int width = 200;
        int height = 60;

        // 繪製半透明黑色背景
        gui.fill(x, y, x + width, y + height, 0x80000000);

        // 繪製標題
        gui.drawString(
            Minecraft.getInstance().font,
            "§b[FD-CI] 施工區域",
            x + 8,
            y + 6,
            0xFFFFFF
        );

        // 繪製工序信息
        String phaseLabel = "工序: " + (currentPhaseZh != null ? currentPhaseZh : "Unknown");
        gui.drawString(
            Minecraft.getInstance().font,
            "§e" + phaseLabel,
            x + 8,
            y + 18,
            0xFFFFFF
        );

        // 繪製進度條
        int barWidth = width - 16;
        int barX = x + 8;
        int barY = y + 32;
        int barHeight = 8;

        // 背景
        gui.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);

        // 填充部分（綠色）
        int filledWidth = (int) (barWidth * progress);
        gui.fill(barX, barY, barX + filledWidth, barY + barHeight, 0xFF00CC44);

        // 進度百分比文字
        String progressStr = String.format("%.0f%%", progress * 100);
        int textWidth = Minecraft.getInstance().font.width(progressStr);
        gui.drawString(
            Minecraft.getInstance().font,
            progressStr,
            barX + (barWidth - textWidth) / 2,
            barY + 1,
            0xFFFFFF
        );
    }
}
