package com.blockreality.api.client;
import com.blockreality.api.physics.FailureType;

import com.blockreality.api.client.render.effect.StructuralFXRenderer;
import com.blockreality.api.network.CollapseEffectPacket.CollapseInfo;
import com.blockreality.api.physics.FailureType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ★ review-fix ICReM-5: 客戶端崩塌效果快取
 *
 * 接收伺服器端的崩塌效果資料，並分發到對應的視覺效果渲染器。
 *
 * 不同破壞模式的視覺效果：
 *   - CANTILEVER_BREAK: 大型斷裂碎片 + 斷裂線動畫 + 整段下墜
 *   - CRUSHING:         漸進式裂紋擴散 + 壓碎粉塵 + 材質碎裂
 *   - NO_SUPPORT:       快速分散掉落 + 輕量粒子
 */
@OnlyIn(Dist.CLIENT)
public class ClientCollapseCache {

    /** 待處理的崩塌效果佇列（網路線程寫入，渲染線程讀取） */
    private static final ConcurrentLinkedQueue<CollapseEffect> pendingEffects = new ConcurrentLinkedQueue<>();

    public record CollapseEffect(BlockPos pos, FailureType type, int materialId) {}

    /**
     * 處理從伺服器收到的崩塌效果封包。
     * 在網路線程上呼叫，排入佇列。
     */
    public static void processCollapseEffects(Map<BlockPos, CollapseInfo> data) {
        for (Map.Entry<BlockPos, CollapseInfo> entry : data.entrySet()) {
            pendingEffects.add(new CollapseEffect(
                entry.getKey(),
                entry.getValue().type(),
                entry.getValue().materialId()
            ));
        }
    }

    /**
     * 在渲染線程上消費崩塌效果，生成對應視覺。
     * 由 StructuralFXRenderer.render() 每幀呼叫。
     */
    public static void drainAndSpawnEffects(StructuralFXRenderer renderer) {
        CollapseEffect effect;
        while ((effect = pendingEffects.poll()) != null) {
            // 原有小碎片粒子
            renderer.spawnCollapseFX(effect.pos, effect.type, effect.materialId);

            // 電影級墜落碎塊（大型旋轉方塊）
            float[] color = getMaterialColor(effect.materialId);
            switch (effect.type) {
                case CRUSHING ->
                        renderer.spawnFallingChunks(effect.pos, color[0], color[1], color[2], 3, 0.8f);
                case CANTILEVER_BREAK ->
                        renderer.spawnFallingChunks(effect.pos, color[0], color[1], color[2], 2, 0.9f);
                case TENSION_BREAK ->
                        renderer.spawnFallingChunks(effect.pos, color[0], color[1], color[2], 2, 0.6f);
                case NO_SUPPORT ->
                        renderer.spawnFallingChunks(effect.pos, color[0], color[1], color[2], 1, 1.0f);
            }

            // 攝影機震動 + 環境粉塵
            triggerClientCollapseEffect(effect.pos, effect.type);
        }
    }

    /** 材質 ID → RGB 顏色 */
    private static float[] getMaterialColor(int materialId) {
        return switch (materialId) {
            case 0 -> new float[]{0.7f, 0.7f, 0.68f};   // concrete
            case 1 -> new float[]{0.55f, 0.6f, 0.65f};   // steel
            case 2 -> new float[]{0.6f, 0.4f, 0.2f};     // wood
            case 3 -> new float[]{0.5f, 0.5f, 0.55f};    // rebar
            case 4 -> new float[]{0.9f, 0.75f, 0.2f};    // RC node
            case 5 -> new float[]{0.4f, 0.7f, 1.0f};     // anchor pile
            default -> new float[]{0.8f, 0.8f, 0.8f};    // unknown
        };
    }

    /**
     * Fix 2: 客戶端崩塌動畫效果。
     * <ul>
     *   <li>CRUSHING: 近距離（16 格內）螢幕輕微震動，模擬衝擊波</li>
     *   <li>所有類型: 觸發 BRAnimationEngine 的 structure collapse clip</li>
     * </ul>
     */
    private static void triggerClientCollapseEffect(BlockPos pos, FailureType type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        double distSq = mc.player.blockPosition().distSqr(pos);
        if (distSq > 64 * 64) return;

        // ── 電影級攝影機震動（取代 animateHurt） ──
        var shaker = com.blockreality.api.client.render.effect.CameraShakeManager.class;
        switch (type) {
            case CRUSHING -> // 低頻強震（地面衝擊波）
                com.blockreality.api.client.render.effect.CameraShakeManager
                        .triggerShake(pos, 0.15f, 12.0f, 25);
            case CANTILEVER_BREAK -> // 中頻中震
                com.blockreality.api.client.render.effect.CameraShakeManager
                        .triggerShake(pos, 0.10f, 8.0f, 18);
            case TENSION_BREAK -> // 高頻短促（金屬斷裂振動）
                com.blockreality.api.client.render.effect.CameraShakeManager
                        .triggerShake(pos, 0.06f, 16.0f, 10);
            case NO_SUPPORT -> // 輕微震動
                com.blockreality.api.client.render.effect.CameraShakeManager
                        .triggerShake(pos, 0.04f, 6.0f, 12);
        }

        // ── 環境粉塵粒子 ──
        if (distSq < 32 * 32) {
            double px = pos.getX() + 0.5, py = pos.getY() + 0.5, pz = pos.getZ() + 0.5;
            int dustCount = type == FailureType.CRUSHING ? 8 : 4;
            for (int i = 0; i < dustCount; i++) {
                double dx = (mc.level.random.nextDouble() - 0.5) * 2.5;
                double dy = mc.level.random.nextDouble() * 0.5;
                double dz = (mc.level.random.nextDouble() - 0.5) * 2.5;
                mc.level.addParticle(
                        net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE,
                        px + dx, py + dy, pz + dz,
                        dx * 0.015, 0.03, dz * 0.015);
            }
        }
    }
}
