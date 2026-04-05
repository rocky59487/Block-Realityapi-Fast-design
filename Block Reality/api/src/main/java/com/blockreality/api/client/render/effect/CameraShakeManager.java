package com.blockreality.api.client.render.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 電影級攝影機震動管理器。
 *
 * <h2>設計</h2>
 * <ul>
 *   <li>多震源疊加 — 多處同時崩塌時自然合成</li>
 *   <li>三頻率正弦波疊加 — 主頻 + 2 倍頻 + 噪聲，產生非週期性自然震動</li>
 *   <li>距離平方反比衰減 — 越遠越輕</li>
 *   <li>時間衰退 — 強度隨 tick 線性衰退歸零</li>
 *   <li>失敗類型差異 — CRUSHING 低頻強震 vs TENSION 高頻短促</li>
 * </ul>
 *
 * <h2>接入方式</h2>
 * 在 {@code ClientSetup.onRenderLevel(RenderLevelStageEvent)} 的
 * {@code AFTER_SKY} stage 呼叫 {@link #applyShake(com.mojang.blaze3d.vertex.PoseStack)}。
 */
@OnlyIn(Dist.CLIENT)
public final class CameraShakeManager {

    private CameraShakeManager() {}

    /** 活躍的震動源 */
    private static final List<ShakeSource> activeSources = new ArrayList<>();

    /** 當前幀的合成偏移（世界座標） */
    private static double offsetX, offsetY, offsetZ;

    // ═════════════════════���══════════════════════════════���══════════
    //  震動源定義
    // ═══════════════════════════════════════════════════════════════

    private static class ShakeSource {
        final double worldX, worldY, worldZ;
        final float magnitude;       // 初始強度（方塊單位）
        final float frequency;       // 主頻率（Hz）
        final int totalDuration;     // 總 tick 數
        int remaining;               // 剩餘 tick
        final long startTimeMs;      // System.currentTimeMillis at creation

        ShakeSource(BlockPos pos, float magnitude, float frequency, int duration) {
            this.worldX = pos.getX() + 0.5;
            this.worldY = pos.getY() + 0.5;
            this.worldZ = pos.getZ() + 0.5;
            this.magnitude = magnitude;
            this.frequency = frequency;
            this.totalDuration = duration;
            this.remaining = duration;
            this.startTimeMs = System.currentTimeMillis();
        }
    }

    // ═════════════════════════════════════════════════���═════════════
    //  公開 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * 觸發一次攝影機震動。可多次呼叫（震源疊加）。
     *
     * @param pos       崩塌位置
     * @param magnitude 強度（方塊單位），建議 0.03-0.20
     * @param frequency 主頻率（Hz），建議 6-16
     * @param duration  持續 tick 數，建議 10-30
     */
    public static void triggerShake(BlockPos pos, float magnitude, float frequency, int duration) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 距離衰減（32 格內有效）
        double distSq = mc.player.blockPosition().distSqr(pos);
        if (distSq > 32.0 * 32.0) return;
        float distFactor = (float) (1.0 - distSq / (32.0 * 32.0));

        activeSources.add(new ShakeSource(pos, magnitude * distFactor, frequency, duration));
    }

    /**
     * 每渲染幀呼叫一次。計算所有活躍震源的合成偏移。
     */
    public static void tick() {
        offsetX = offsetY = offsetZ = 0;

        if (activeSources.isEmpty()) return;

        long now = System.currentTimeMillis();

        Iterator<ShakeSource> it = activeSources.iterator();
        while (it.hasNext()) {
            ShakeSource src = it.next();
            src.remaining--;
            if (src.remaining <= 0) {
                it.remove();
                continue;
            }

            // 時間衰退：線性從 1.0 → 0.0
            float decay = (float) src.remaining / src.totalDuration;
            // 二次衰退更自然（先快後慢）
            decay = decay * decay;

            float mag = src.magnitude * decay;
            float elapsed = (now - src.startTimeMs) / 1000.0f;

            // 三頻率正弦波疊加（模擬非週期自然震動）
            // 主頻 + 1.7× 倍頻 + 2.3× 噪聲頻
            double f = src.frequency;
            double phase = elapsed * Math.PI * 2.0;

            double sx = Math.sin(phase * f) * 0.6
                    + Math.sin(phase * f * 1.7 + 1.3) * 0.3
                    + Math.sin(phase * f * 2.3 + 2.7) * 0.1;

            double sy = Math.cos(phase * f * 0.7 + 0.5) * 0.4
                    + Math.sin(phase * f * 1.3 + 3.1) * 0.2;

            double sz = Math.sin(phase * f * 0.9 + 1.7) * 0.5
                    + Math.cos(phase * f * 1.9 + 0.3) * 0.25;

            offsetX += sx * mag;
            offsetY += sy * mag * 0.6;  // Y 軸震幅較小（上下跳動不自然）
            offsetZ += sz * mag;
        }

        // 全域上限（防止多震源疊加時過度搖晃）
        double maxOffset = 0.25;
        offsetX = clamp(offsetX, -maxOffset, maxOffset);
        offsetY = clamp(offsetY, -maxOffset, maxOffset);
        offsetZ = clamp(offsetZ, -maxOffset, maxOffset);
    }

    /**
     * 將震動偏移套用到 PoseStack。
     * 在 {@code RenderLevelStageEvent.Stage.AFTER_SKY} 呼叫。
     */
    public static void applyShake(com.mojang.blaze3d.vertex.PoseStack poseStack) {
        if (offsetX == 0 && offsetY == 0 && offsetZ == 0) return;
        poseStack.translate(offsetX, offsetY, offsetZ);
    }

    /** 是否有活躍震動 */
    public static boolean isShaking() {
        return !activeSources.isEmpty();
    }

    /** 清除所有震動（切換維度、暫停等） */
    public static void clear() {
        activeSources.clear();
        offsetX = offsetY = offsetZ = 0;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
