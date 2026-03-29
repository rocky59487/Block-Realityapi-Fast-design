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
 * ★ review-fix ICReM: 增強節點啟動體驗
 *   - 新增節點狀態指示器（RC 融合進度、養護時間）
 *   - 應力等級即時顯示
 *   - 動態顏色回饋（安全/警告/危險）
 *   - 施工進度平滑動畫
 */
@Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ConstructionHudOverlay {

    // Client-side cache: set by sync packet
    private static volatile String currentPhaseName = null;
    private static volatile String currentPhaseZh = null;
    private static volatile int zoneId = -1;
    private static volatile float progress = 0.0f;

    // ★ review-fix ICReM: 節點狀態信息
    private static volatile String nodeStatus = null;     // RC_NODE / ANCHOR_PILE / null
    private static volatile float nodeStress = 0.0f;      // 0.0~1.5
    private static volatile float curingProgress = 1.0f;  // 0.0~1.0 (1.0 = 養護完成)
    private static volatile int connectedNodes = 0;       // 相鄰節點數

    // ★ 平滑動畫用
    private static float displayProgress = 0.0f;
    private static float displayStress = 0.0f;
    private static long lastRenderTime = 0;

    /**
     * 設定當前施工區域信息。由同步封包呼叫。
     */
    public static void setZoneInfo(String phaseName, String phaseZh, float progress) {
        ConstructionHudOverlay.currentPhaseName = phaseName;
        ConstructionHudOverlay.currentPhaseZh = phaseZh;
        ConstructionHudOverlay.progress = Math.max(0.0f, Math.min(1.0f, progress));
    }

    /**
     * ★ review-fix ICReM: 設定節點狀態信息。
     */
    public static void setNodeInfo(String status, float stress, float curing, int connected) {
        ConstructionHudOverlay.nodeStatus = status;
        ConstructionHudOverlay.nodeStress = stress;
        ConstructionHudOverlay.curingProgress = Math.max(0.0f, Math.min(1.0f, curing));
        ConstructionHudOverlay.connectedNodes = connected;
    }

    /**
     * 清除施工區域信息。
     */
    public static void clearZoneInfo() {
        ConstructionHudOverlay.currentPhaseName = null;
        ConstructionHudOverlay.currentPhaseZh = null;
        ConstructionHudOverlay.zoneId = -1;
        ConstructionHudOverlay.progress = 0.0f;
        ConstructionHudOverlay.nodeStatus = null;
        ConstructionHudOverlay.nodeStress = 0.0f;
        ConstructionHudOverlay.curingProgress = 1.0f;
        ConstructionHudOverlay.connectedNodes = 0;
    }

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() == null) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // 至少需要施工區域或節點狀態之一
        boolean hasZone = currentPhaseName != null;
        boolean hasNode = nodeStatus != null;
        if (!hasZone && !hasNode) return;

        // ★ 平滑動畫插值
        long now = System.currentTimeMillis();
        float dt = lastRenderTime > 0 ? Math.min((now - lastRenderTime) / 1000.0f, 0.1f) : 0.016f;
        lastRenderTime = now;
        float lerpSpeed = 5.0f; // 每秒追趕 5 倍差值
        displayProgress += (progress - displayProgress) * Math.min(1.0f, dt * lerpSpeed);
        displayStress += (nodeStress - displayStress) * Math.min(1.0f, dt * lerpSpeed);

        GuiGraphics gui = event.getGuiGraphics();
        int screenWidth = gui.guiWidth();

        // 動態計算高度
        int height = 10; // 上邊距
        if (hasZone) height += 46;
        if (hasNode) height += 52;

        int x = screenWidth - 230;
        int y = 10;
        int width = 210;
        int totalHeight = height;

        // ─── 繪製背景 ───
        gui.fill(x, y, x + width, y + totalHeight, 0x90000000);
        // 左邊框線（動態顏色）
        int borderColor = getStressBorderColor(displayStress);
        gui.fill(x, y, x + 2, y + totalHeight, borderColor);

        int yOffset = y + 6;

        // ═══ 施工區域信息 ═══
        if (hasZone) {
            gui.drawString(Minecraft.getInstance().font,
                "§b⚙ 施工區域", x + 8, yOffset, 0xFFFFFF);
            yOffset += 12;

            String phaseLabel = "工序: " + (currentPhaseZh != null ? currentPhaseZh : currentPhaseName);
            gui.drawString(Minecraft.getInstance().font,
                "§e" + phaseLabel, x + 8, yOffset, 0xFFFFFF);
            yOffset += 12;

            // 進度條
            renderProgressBar(gui, x + 8, yOffset, width - 16, 8,
                displayProgress, 0xFF00CC44, "§f" + String.format("%.0f%%", displayProgress * 100));
            yOffset += 14;
        }

        // ═══ 節點狀態信息 ═══
        if (hasNode) {
            if (hasZone) {
                // 分隔線
                gui.fill(x + 8, yOffset, x + width - 8, yOffset + 1, 0x40FFFFFF);
                yOffset += 4;
            }

            // 節點類型圖示
            String nodeIcon = "RC_NODE".equals(nodeStatus) ? "§6◆ RC 節點" : "§3◆ 錨樁";
            gui.drawString(Minecraft.getInstance().font, nodeIcon, x + 8, yOffset, 0xFFFFFF);
            // 連接數
            String connStr = "§7[" + connectedNodes + " 連接]";
            gui.drawString(Minecraft.getInstance().font, connStr,
                x + width - 8 - Minecraft.getInstance().font.width(connStr), yOffset, 0xFFFFFF);
            yOffset += 12;

            // 應力指示器
            String stressLabel = displayStress < 0.3f ? "§a安全" :
                                 displayStress < 0.7f ? "§e警告" : "§c危險";
            gui.drawString(Minecraft.getInstance().font,
                "應力: " + stressLabel + " §7(" + String.format("%.0f%%", displayStress * 100) + ")",
                x + 8, yOffset, 0xFFFFFF);
            yOffset += 12;

            // 養護進度條（未完成時顯示）
            if (curingProgress < 1.0f) {
                int curingColor = curingProgress < 0.5f ? 0xFFFF8800 : 0xFF44AAFF;
                renderProgressBar(gui, x + 8, yOffset, width - 16, 6,
                    curingProgress, curingColor,
                    "§7養護 " + String.format("%.0f%%", curingProgress * 100));
                yOffset += 10;
            } else {
                gui.drawString(Minecraft.getInstance().font,
                    "§a✓ 養護完成", x + 8, yOffset, 0xFFFFFF);
                yOffset += 10;
            }
        }
    }

    // ─── 輔助方法 ───

    /**
     * 繪製進度條。
     */
    private static void renderProgressBar(GuiGraphics gui, int x, int y, int w, int h,
                                           float ratio, int fillColor, String label) {
        // 背景
        gui.fill(x, y, x + w, y + h, 0xFF222222);
        // 填充
        int filled = (int) (w * Math.max(0, Math.min(1, ratio)));
        gui.fill(x, y, x + filled, y + h, fillColor);
        // 邊框
        gui.fill(x, y, x + w, y + 1, 0x40FFFFFF);
        gui.fill(x, y + h - 1, x + w, y + h, 0x40FFFFFF);
        // 文字（置中）
        int textW = Minecraft.getInstance().font.width(label);
        gui.drawString(Minecraft.getInstance().font, label,
            x + (w - textW) / 2, y + (h > 8 ? 1 : 0), 0xFFFFFF);
    }

    /**
     * ★ review-fix ICReM: 根據應力等級返回左邊框顏色。
     */
    private static int getStressBorderColor(float stress) {
        if (stress < 0.3f) return 0xFF00CC44;   // 綠色：安全
        if (stress < 0.7f) return 0xFFFFAA00;   // 黃色：警告
        // 危險時閃爍
        float flash = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() * 0.008);
        int r = (int) (255 * flash);
        return 0xFF000000 | (r << 16) | (20 << 8);
    }
}
