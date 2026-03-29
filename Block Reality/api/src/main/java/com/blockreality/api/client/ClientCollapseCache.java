package com.blockreality.api.client;

import com.blockreality.api.client.render.effect.StructuralFXRenderer;
import com.blockreality.api.network.CollapseEffectPacket.CollapseInfo;
import com.blockreality.api.physics.SupportPathAnalyzer.FailureType;
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
        }
    }
}
