package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.startup.StartupPhase.EnvironmentInfo;
import com.blockreality.fastdesign.startup.StartupPhase.PhaseResult;
import com.blockreality.fastdesign.startup.StartupPhase.PhaseStatus;
import com.blockreality.fastdesign.startup.StartupScanPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Client-only startup loading overlay.
 *
 * Renders over Minecraft's default loading screen while the 7-phase
 * startup scan runs. Auto-dismisses with a brief fade-out on completion.
 *
 * Layout (1280x720 reference):
 *   Top banner: title + version
 *   Phase log: up to 12 visible lines, auto-scrolling
 *   Progress bar: 600px wide, centered
 *   Status line: JVM | Heap | Forge
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID, value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.FORGE)
public class StartupOverlayScreen {

    // Colors
    private static final int BG_COLOR       = 0xF01A1A1A;
    private static final int BANNER_BG      = 0xFF1A1A1A;
    private static final int TEXT_COLOR      = 0xFFECECEC;
    private static final int TEXT_DIM        = 0xFF888888;
    private static final int BAR_BG          = 0xFF333333;
    private static final int BAR_FILL        = 0xFF4A90D9;
    private static final int COLOR_OK        = 0xFF27AE60;
    private static final int COLOR_WARN      = 0xFFF39C12;
    private static final int COLOR_ERR       = 0xFFE74C3C;

    private static final int MAX_LOG_LINES   = 12;
    private static final int BAR_WIDTH       = 600;
    private static final int BAR_HEIGHT      = 14;

    // Fade-out tracking
    private static long completionTime = 0;
    private static final long FADE_DURATION_MS = 1000;

    // Animated spinner frames
    private static final String[] SPINNER = { "|", "/", "-", "\\" };

    @SubscribeEvent
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        StartupScanPipeline pipeline = StartupScanPipeline.getInstance();

        // Not started yet — don't render
        if (!pipeline.isRunning() && !pipeline.isComplete()) return;

        // Handle fade-out after completion
        if (pipeline.isComplete()) {
            if (completionTime == 0) {
                completionTime = System.currentTimeMillis();
            }
            long elapsed = System.currentTimeMillis() - completionTime;
            if (elapsed > FADE_DURATION_MS) return; // fully faded, stop rendering
        }

        GuiGraphics gui = event.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int screenW = gui.guiWidth();
        int screenH = gui.guiHeight();

        // Calculate alpha for fade-out
        float alpha = 1.0f;
        if (pipeline.isComplete() && completionTime > 0) {
            long elapsed = System.currentTimeMillis() - completionTime;
            alpha = 1.0f - Math.min(1.0f, (float) elapsed / FADE_DURATION_MS);
        }
        int alphaInt = (int) (alpha * 240); // max 0xF0
        if (alphaInt <= 0) return;

        int bgWithAlpha = (alphaInt << 24) | (BG_COLOR & 0x00FFFFFF);

        // ─── Full-screen background ───
        gui.fill(0, 0, screenW, screenH, bgWithAlpha);

        // ─── Top banner (80px) ───
        int bannerH = 40;
        gui.fill(0, 0, screenW, bannerH, BANNER_BG | (alphaInt << 24));

        // Title
        String title = "Block Reality \u2014 Fast Design";
        int titleW = font.width(title);
        gui.drawString(font, title, (screenW - titleW) / 2, 8, TEXT_COLOR);

        // Version
        String version = "v1.0.0";
        int versionW = font.width(version);
        gui.drawString(font, version, (screenW - versionW) / 2, 22, TEXT_DIM);

        // ─── Phase log area ───
        int logStartY = bannerH + 12;
        int lineHeight = 12;
        List<PhaseResult> phases = pipeline.getCompletedPhases();

        // Build log entries
        int totalEntries = phases.size();
        boolean hasActive = !pipeline.isComplete();
        if (hasActive) totalEntries++;

        // Auto-scroll: show latest MAX_LOG_LINES entries
        int startIdx = Math.max(0, phases.size() - MAX_LOG_LINES + (hasActive ? 1 : 0));

        int logX = Math.max(40, (screenW - 600) / 2);
        int y = logStartY;
        int linesDrawn = 0;

        // Draw completed phases
        for (int i = startIdx; i < phases.size() && linesDrawn < MAX_LOG_LINES; i++) {
            PhaseResult phase = phases.get(i);
            drawPhaseLogLine(gui, font, logX, y, screenW - logX - 40, phase);
            y += lineHeight;
            linesDrawn++;

            // Draw detail lines (indented) — show up to 2 detail lines per phase
            int detailLimit = Math.min(2, phase.details().size());
            for (int d = 0; d < detailLimit && linesDrawn < MAX_LOG_LINES; d++) {
                String detail = phase.details().get(d);
                gui.drawString(font, "   \u2514\u2500 " + detail, logX + 16, y, TEXT_DIM);
                y += lineHeight;
                linesDrawn++;
            }
        }

        // Draw active phase with animated spinner
        if (hasActive && linesDrawn < MAX_LOG_LINES) {
            long tick = (System.currentTimeMillis() / 200) % SPINNER.length;
            String spinnerChar = SPINNER[(int) tick];
            String activeLine = "\u25B6 " + pipeline.getCurrentPhaseName() + " " + spinnerChar;
            gui.drawString(font, activeLine, logX, y, BAR_FILL);
        }

        // ─── Progress bar ───
        int barX = (screenW - BAR_WIDTH) / 2;
        int barY = screenH - 70;

        // Clamp bar width to screen
        int actualBarW = Math.min(BAR_WIDTH, screenW - 40);
        barX = (screenW - actualBarW) / 2;

        // Bar background
        gui.fill(barX, barY, barX + actualBarW, barY + BAR_HEIGHT, BAR_BG);

        // Bar fill with animated pulse
        float progress = pipeline.getCurrentProgress();
        int fillW = (int) (actualBarW * Math.max(0, Math.min(1, progress)));

        if (fillW > 0) {
            // Pulse effect on the active segment
            float pulse = 0.85f + 0.15f * (float) Math.sin(System.currentTimeMillis() * 0.005);
            int r = (int) (((BAR_FILL >> 16) & 0xFF) * pulse);
            int g = (int) (((BAR_FILL >> 8) & 0xFF) * pulse);
            int b = (int) ((BAR_FILL & 0xFF) * pulse);
            int pulsedColor = 0xFF000000 | (r << 16) | (g << 8) | b;
            gui.fill(barX, barY, barX + fillW, barY + BAR_HEIGHT, pulsedColor);
        }

        // Bar border
        gui.fill(barX, barY, barX + actualBarW, barY + 1, 0x40FFFFFF);
        gui.fill(barX, barY + BAR_HEIGHT - 1, barX + actualBarW, barY + BAR_HEIGHT, 0x40FFFFFF);

        // Percentage text centered on bar
        String pctText = String.format("%.0f%%", progress * 100);
        int pctW = font.width(pctText);
        gui.drawString(font, pctText, barX + (actualBarW - pctW) / 2, barY + 3, TEXT_COLOR);

        // Phase counter below bar
        int phaseNum = Math.min(phases.size() + 1, 7);
        String phaseInfo = "Phase " + phaseNum + " / 7  \u2014  " + pipeline.getCurrentPhaseName();
        int phaseInfoW = font.width(phaseInfo);
        gui.drawString(font, phaseInfo, (screenW - phaseInfoW) / 2, barY + BAR_HEIGHT + 4, TEXT_DIM);

        // ─── Status line at bottom ───
        EnvironmentInfo env = pipeline.getEnvironmentInfo();
        if (env != null) {
            String statusLine = "JVM " + env.jvmVersion()
                + "  |  Heap " + env.heapMb() + "MB"
                + "  |  Forge " + env.forgeVersion();
            int statusW = font.width(statusLine);
            gui.drawString(font, statusLine, (screenW - statusW) / 2, screenH - 20, TEXT_DIM);
        }
    }

    private static void drawPhaseLogLine(GuiGraphics gui, Font font,
                                          int x, int y, int maxWidth,
                                          PhaseResult phase) {
        // Status badge
        String badge;
        int badgeColor;
        switch (phase.status()) {
            case WARN:
                badge = "[WARN]";
                badgeColor = COLOR_WARN;
                break;
            case ERROR:
                badge = "[ERR]";
                badgeColor = COLOR_ERR;
                break;
            default:
                badge = "[OK]";
                badgeColor = COLOR_OK;
                break;
        }

        // Phase name with bullet
        String name = "\u25CF " + phase.name();
        gui.drawString(font, name, x, y, TEXT_COLOR);

        // Dotted line filler
        int nameW = font.width(name);
        int badgeW = font.width(badge);
        int dotsSpace = maxWidth - nameW - badgeW - 16;
        if (dotsSpace > 20) {
            int dotCount = dotsSpace / font.width(".");
            String dots = ".".repeat(Math.max(0, dotCount));
            gui.drawString(font, dots, x + nameW + 4, y, 0xFF555555);
        }

        // Badge at right
        int badgeX = x + maxWidth - badgeW;
        gui.drawString(font, badge, badgeX, y, badgeColor);
    }
}
