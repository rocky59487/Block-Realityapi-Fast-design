package com.blockreality.fastdesign.command;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 選取排除管理器 — 管理大選取框內個別排除的方塊位置
 *
 * 玩家可以在選取框內排除特定方塊，使得 fill/replace/clear 等操作跳過這些位置。
 * 排除集會在取消選取或重設選取時自動清除。
 */
public class SelectionExclusionManager {

    /** 每位玩家的排除方塊集合 */
    private static final Map<UUID, Set<BlockPos>> exclusionMap = new ConcurrentHashMap<>();

    /**
     * 切換指定位置的排除狀態（已排除→取消排除，未排除→加入排除）
     * @return true 表示該位置現在被排除，false 表示該位置已恢復選取
     */
    public static boolean toggle(UUID playerId, BlockPos pos) {
        Set<BlockPos> set = exclusionMap.computeIfAbsent(playerId,
            k -> ConcurrentHashMap.newKeySet());
        BlockPos immutable = pos.immutable();
        if (set.contains(immutable)) {
            set.remove(immutable);
            return false;
        } else {
            set.add(immutable);
            return true;
        }
    }

    /**
     * 檢查指定位置是否被排除
     */
    public static boolean isExcluded(UUID playerId, BlockPos pos) {
        Set<BlockPos> set = exclusionMap.get(playerId);
        if (set == null || set.isEmpty()) return false;
        return set.contains(pos.immutable());
    }

    /**
     * 取得玩家的排除集合（唯讀）
     */
    public static Set<BlockPos> getExcluded(UUID playerId) {
        Set<BlockPos> set = exclusionMap.get(playerId);
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    /**
     * 取得排除數量
     */
    public static int getExcludedCount(UUID playerId) {
        Set<BlockPos> set = exclusionMap.get(playerId);
        return set != null ? set.size() : 0;
    }

    /**
     * 清除玩家的所有排除（取消選取時呼叫）
     */
    public static void clear(UUID playerId) {
        exclusionMap.remove(playerId);
    }
}
