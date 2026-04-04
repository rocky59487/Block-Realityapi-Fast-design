package com.blockreality.api.physics.pfsf;

import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.physics.SupportPathAnalyzer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 斷裂結果應用器 — 讀取 GPU fail_flags[]，轉換為 FailureType，觸發 CollapseManager。
 *
 * <p>安全機制：</p>
 * <ul>
 *   <li>MAX_FAILURE_PER_TICK 限制每 tick 最大斷裂數</li>
 *   <li>MAX_CASCADE_RADIUS 限制單次蔓延半徑</li>
 *   <li>保守重啟 Chebyshev（有新斷裂時）</li>
 * </ul>
 *
 * 參考：PFSF 手冊 §5.5, §6.1
 */
public final class PFSFFailureApplicator {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Failure");

    private PFSFFailureApplicator() {}

    /**
     * 處理 GPU 讀回的 fail_flags[]，觸發崩塌。
     *
     * @param failFlags GPU 讀回的斷裂標記陣列
     * @param buf       對應的 island buffer（用於座標轉換）
     * @param level     Minecraft 世界
     * @return 實際觸發的斷裂數量
     */
    public static int apply(byte[] failFlags, PFSFIslandBuffer buf, ServerLevel level) {
        if (failFlags == null || failFlags.length == 0) return 0;

        Map<BlockPos, SupportPathAnalyzer.FailureType> failures = new LinkedHashMap<>();
        int failCount = 0;

        for (int i = 0; i < failFlags.length; i++) {
            byte flag = failFlags[i];
            if (flag == FAIL_OK) continue;
            if (failCount >= MAX_FAILURE_PER_TICK) {
                LOGGER.debug("[PFSF] Hit MAX_FAILURE_PER_TICK ({}), deferring remaining to next tick",
                        MAX_FAILURE_PER_TICK);
                break;
            }

            BlockPos pos = buf.fromFlatIndex(i);
            SupportPathAnalyzer.FailureType type = mapFailureType(flag);

            if (type != null) {
                failures.put(pos, type);
                failCount++;
            }
        }

        if (failures.isEmpty()) return 0;

        // 觸發崩塌
        LOGGER.debug("[PFSF] Island {} — {} failures detected (cantilever={}, crush={}, orphan={})",
                buf.getIslandId(), failures.size(),
                countType(failures, SupportPathAnalyzer.FailureType.CANTILEVER_BREAK),
                countType(failures, SupportPathAnalyzer.FailureType.CRUSHING),
                countType(failures, SupportPathAnalyzer.FailureType.NO_SUPPORT));

        for (var entry : failures.entrySet()) {
            CollapseManager.triggerPFSFCollapse(level, entry.getKey(), entry.getValue());
        }

        // 標記 buffer dirty → 下一 tick 重算
        buf.markDirty();

        // 保守重啟 Chebyshev
        PFSFScheduler.onCollapseTriggered(buf);

        return failures.size();
    }

    /**
     * 將 GPU fail flag → SupportPathAnalyzer.FailureType。
     */
    private static SupportPathAnalyzer.FailureType mapFailureType(byte flag) {
        return switch (flag) {
            case FAIL_CANTILEVER -> SupportPathAnalyzer.FailureType.CANTILEVER_BREAK;
            case FAIL_CRUSHING -> SupportPathAnalyzer.FailureType.CRUSHING;
            case FAIL_NO_SUPPORT -> SupportPathAnalyzer.FailureType.NO_SUPPORT;
            default -> null;
        };
    }

    private static long countType(Map<BlockPos, SupportPathAnalyzer.FailureType> map,
                                   SupportPathAnalyzer.FailureType type) {
        return map.values().stream().filter(t -> t == type).count();
    }
}
