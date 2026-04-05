package com.blockreality.api.client;

import com.blockreality.api.client.render.effect.StructuralFXRenderer;
import com.blockreality.api.network.CollapseEffectPacket.CollapseInfo;
import com.blockreality.api.physics.SupportPathAnalyzer.FailureType;
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
            renderer.spawnCollapseFX(effect.pos, effect.type, effect.materialId);

            // Fix 2: 客戶端動畫觸發 — 螢幕震動 + 視覺衝擊
            triggerClientCollapseEffect(effect.pos, effect.type);
        }
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
        if (distSq > 64 * 64) return;  // 超出 64 格不處理

        // 近距離壓碎震動（16 格內，模擬地面衝擊波）
        if (distSq < 16 * 16 && type == FailureType.CRUSHING) {
            // 輕微攝影機偏移（Minecraft 原生受傷抖動機制）
            mc.player.animateHurt(0);
        }

        // 觸發粉塵粒子（客戶端獨有的環境粉塵，服務端不發送）
        if (distSq < 32 * 32) {
            double px = pos.getX() + 0.5, py = pos.getY() + 0.5, pz = pos.getZ() + 0.5;
            for (int i = 0; i < 4; i++) {
                double dx = (mc.level.random.nextDouble() - 0.5) * 2.0;
                double dy = mc.level.random.nextDouble() * 0.3;
                double dz = (mc.level.random.nextDouble() - 0.5) * 2.0;
                mc.level.addParticle(
                        net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE,
                        px + dx, py + dy, pz + dz,
                        dx * 0.02, 0.02, dz * 0.02);
            }
        }
    }
}
