package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 結構島嶼登錄 — 追蹤所有 RBlock 連通分量（「島嶼」）。
 *
 * 設計目標：
 *   1. 放置/破壞時 O(1)~O(K) 增量更新（K = 鄰居數 ≤ 6）
 *   2. 支援跨 chunk 的大型結構（突破 40³ 快照上限）
 *   3. 為並行 PhysicsExecutor 提供獨立工作單元
 *
 * 核心概念：
 *   - 每個 RBlock 屬於恰好一個 island
 *   - 相鄰的 RBlock 屬於同一個 island（6 方向連通）
 *   - 放置方塊時合併相鄰 island
 *   - 破壞方塊時可能分裂 island（使用 BFS 驗證）
 *
 * 執行緒安全：
 *   - 所有修改操作（register/unregister）應在 server thread 呼叫
 *   - 查詢操作（getIsland/getIslandId）可從任意執行緒呼叫
 */
@ThreadSafe
public class StructureIslandRegistry {

    private static final Logger LOGGER = LogManager.getLogger("BR-IslandRegistry");

    /** 島嶼 ID 生成器 */
    private static final AtomicInteger nextIslandId = new AtomicInteger(1);

    /** 方塊位置 → 島嶼 ID 映射 */
    private static final ConcurrentHashMap<BlockPos, Integer> blockToIsland = new ConcurrentHashMap<>();

    /** 島嶼 ID → 島嶼資訊 */
    private static final ConcurrentHashMap<Integer, StructureIsland> islands = new ConcurrentHashMap<>();

    /**
     * 結構島嶼 — 一組連通的 RBlock。
     */
    public static class StructureIsland {
        private final int id;
        private final Set<BlockPos> members = ConcurrentHashMap.newKeySet();
        private volatile int minX, minY, minZ, maxX, maxY, maxZ;
        private volatile long lastModifiedEpoch;

        StructureIsland(int id) {
            this.id = id;
            this.minX = Integer.MAX_VALUE;
            this.minY = Integer.MAX_VALUE;
            this.minZ = Integer.MAX_VALUE;
            this.maxX = Integer.MIN_VALUE;
            this.maxY = Integer.MIN_VALUE;
            this.maxZ = Integer.MIN_VALUE;
        }

        public int getId() { return id; }
        public Set<BlockPos> getMembers() { return Collections.unmodifiableSet(members); }
        public int getBlockCount() { return members.size(); }
        public long getLastModifiedEpoch() { return lastModifiedEpoch; }

        public BlockPos getMinCorner() { return new BlockPos(minX, minY, minZ); }
        public BlockPos getMaxCorner() { return new BlockPos(maxX, maxY, maxZ); }

        /** AABB 體積（用於判斷是否超過快照上限） */
        public int getAABBVolume() {
            if (members.isEmpty()) return 0;
            return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        void addMember(BlockPos pos) {
            members.add(pos);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        void removeMember(BlockPos pos) {
            members.remove(pos);
        }

        /** 重新計算 AABB（在成員變動後） */
        void recalculateBounds() {
            if (members.isEmpty()) {
                minX = minY = minZ = 0;
                maxX = maxY = maxZ = 0;
                return;
            }
            int mnX = Integer.MAX_VALUE, mnY = Integer.MAX_VALUE, mnZ = Integer.MAX_VALUE;
            int mxX = Integer.MIN_VALUE, mxY = Integer.MIN_VALUE, mxZ = Integer.MIN_VALUE;
            for (BlockPos p : members) {
                mnX = Math.min(mnX, p.getX());
                mnY = Math.min(mnY, p.getY());
                mnZ = Math.min(mnZ, p.getZ());
                mxX = Math.max(mxX, p.getX());
                mxY = Math.max(mxY, p.getY());
                mxZ = Math.max(mxZ, p.getZ());
            }
            minX = mnX; minY = mnY; minZ = mnZ;
            maxX = mxX; maxY = mxY; maxZ = mxZ;
        }

        void touch(long epoch) { this.lastModifiedEpoch = epoch; }
    }

    // ═══════════════════════════════════════════════════════
    //  放置：登錄方塊 + 合併相鄰 island
    // ═══════════════════════════════════════════════════════

    /**
     * 登錄新放置的 RBlock。
     * 檢查 6 鄰居，找到所有相鄰 island 並合併。
     *
     * @param pos   新方塊位置
     * @param epoch 當前結構 epoch
     * @return 此方塊所屬的 island ID
     */
    public static int registerBlock(BlockPos pos, long epoch) {
        Set<Integer> neighborIslands = new HashSet<>();
        for (Direction dir : Direction.values()) {
            Integer id = blockToIsland.get(pos.relative(dir));
            if (id != null) {
                neighborIslands.add(id);
            }
        }

        if (neighborIslands.isEmpty()) {
            // 孤立方塊 → 建立新 island
            int newId = nextIslandId.getAndIncrement();
            StructureIsland island = new StructureIsland(newId);
            island.addMember(pos);
            island.touch(epoch);
            islands.put(newId, island);
            blockToIsland.put(pos, newId);
            LOGGER.debug("[IslandRegistry] New island {} at {}", newId, pos.toShortString());
            return newId;
        }

        if (neighborIslands.size() == 1) {
            // 只有一個相鄰 island → 直接加入
            int targetId = neighborIslands.iterator().next();
            StructureIsland island = islands.get(targetId);
            if (island != null) {
                island.addMember(pos);
                island.touch(epoch);
                blockToIsland.put(pos, targetId);
            }
            return targetId;
        }

        // 多個相鄰 island → 合併到最大的 island 中
        int targetId = -1;
        int maxSize = -1;
        for (int id : neighborIslands) {
            StructureIsland island = islands.get(id);
            if (island != null && island.getBlockCount() > maxSize) {
                maxSize = island.getBlockCount();
                targetId = id;
            }
        }

        StructureIsland target = islands.get(targetId);
        if (target == null) return -1;

        // 合併其他 island 到 target
        for (int id : neighborIslands) {
            if (id == targetId) continue;
            StructureIsland other = islands.remove(id);
            if (other != null) {
                for (BlockPos member : other.members) {
                    target.addMember(member);
                    blockToIsland.put(member, targetId);
                }
                LOGGER.debug("[IslandRegistry] Merged island {} ({} blocks) into {} at {}",
                    id, other.getBlockCount(), targetId, pos.toShortString());
            }
        }

        target.addMember(pos);
        target.touch(epoch);
        blockToIsland.put(pos, targetId);
        return targetId;
    }

    // ═══════════════════════════════════════════════════════
    //  破壞：移除方塊 + 可能分裂 island
    // ═══════════════════════════════════════════════════════

    /**
     * 註銷被破壞的 RBlock。
     * 移除後檢查鄰居是否仍然連通，必要時分裂 island。
     *
     * @param level 伺服器世界（用於 BFS 驗證連通性）
     * @param pos   被破壞方塊位置
     * @param epoch 當前結構 epoch
     */
    public static void unregisterBlock(ServerLevel level, BlockPos pos, long epoch) {
        Integer removedIslandId = blockToIsland.remove(pos);
        if (removedIslandId == null) return;

        StructureIsland island = islands.get(removedIslandId);
        if (island == null) return;

        island.removeMember(pos);
        island.touch(epoch);

        if (island.getBlockCount() == 0) {
            islands.remove(removedIslandId);
            LOGGER.debug("[IslandRegistry] Island {} removed (empty)", removedIslandId);
            return;
        }

        // 收集仍在 island 中的鄰居
        List<BlockPos> rblockNeighbors = new java.util.ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (island.members.contains(neighbor)) {
                rblockNeighbors.add(neighbor);
            }
        }

        if (rblockNeighbors.size() <= 1) {
            // 0 或 1 個鄰居 → 不可能分裂
            island.recalculateBounds();
            return;
        }

        // 多個鄰居 → BFS 檢查是否仍然連通
        // 從第一個鄰居做 BFS，看能否到達所有其他鄰居
        Set<BlockPos> reachable = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos seed = rblockNeighbors.get(0);
        reachable.add(seed);
        queue.add(seed);

        // ★ audit-fix C-3: BFS 預算設為 island 實際大小（無人為截斷）。
        // 原先 min(size, 65536) 在大型結構中截斷 BFS，導致未遍歷到的鄰居
        // 被誤判為不可達，觸發錯誤分裂。island.members 已在記憶體中，
        // O(N) BFS 不會造成效能問題。
        int budget = island.getBlockCount();
        int visited = 0;

        while (!queue.isEmpty() && visited < budget) {
            BlockPos current = queue.poll();
            visited++;
            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (!reachable.contains(next) && island.members.contains(next)) {
                    reachable.add(next);
                    queue.add(next);
                }
            }
        }

        // 檢查是否所有鄰居都可達
        boolean allReachable = true;
        for (BlockPos neighbor : rblockNeighbors) {
            if (!reachable.contains(neighbor)) {
                allReachable = false;
                break;
            }
        }

        if (allReachable) {
            // 仍然連通 → 只需更新 AABB
            island.recalculateBounds();
            return;
        }

        // 需要分裂！
        // 將 reachable 集合保留在原 island，其餘方塊建立新 island
        Set<BlockPos> remaining = new HashSet<>(island.members);
        remaining.removeAll(reachable);

        // ★ audit-fix M-1: 先加入新成員再移除舊成員（避免 clear→addAll 的中間空狀態）。
        // 雖然 @ThreadSafe 標記要求所有修改在 server thread，但防禦性編碼更安全。
        // retainAll 等效於 clear+addAll 但對 ConcurrentHashMap.KeySetView 也不是原子的，
        // 因此改為：先加所有 reachable（大多已存在，addAll 是冪等的），再 retainAll 移除非 reachable。
        island.members.retainAll(reachable);
        island.recalculateBounds();
        island.touch(epoch);

        // 對剩餘方塊做 BFS 分群（可能分裂成多個 island）
        Set<BlockPos> unassigned = new HashSet<>(remaining);
        while (!unassigned.isEmpty()) {
            BlockPos start = unassigned.iterator().next();
            int newId = nextIslandId.getAndIncrement();
            StructureIsland newIsland = new StructureIsland(newId);

            Deque<BlockPos> splitQueue = new ArrayDeque<>();
            splitQueue.add(start);
            unassigned.remove(start);

            while (!splitQueue.isEmpty()) {
                BlockPos current = splitQueue.poll();
                newIsland.addMember(current);
                blockToIsland.put(current, newId);

                for (Direction dir : Direction.values()) {
                    BlockPos next = current.relative(dir);
                    if (unassigned.remove(next)) {
                        splitQueue.add(next);
                    }
                }
            }

            newIsland.touch(epoch);
            islands.put(newId, newIsland);
            // ★ audit-fix C-5: 新分裂的 island 必須標記 dirty，否則物理不會重算
            PhysicsScheduler.markDirty(newId, epoch);
            LOGGER.info("[IslandRegistry] Split: new island {} with {} blocks from island {}",
                newId, newIsland.getBlockCount(), removedIslandId);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  查詢
    // ═══════════════════════════════════════════════════════

    /** 取得方塊所屬的 island ID（不存在回傳 -1） */
    public static int getIslandId(BlockPos pos) {
        Integer id = blockToIsland.get(pos);
        return id != null ? id : -1;
    }

    /** 取得 island 資訊 */
    public static StructureIsland getIsland(int islandId) {
        return islands.get(islandId);
    }

    /** 取得所有 island */
    public static Map<Integer, StructureIsland> getAllIslands() {
        return Collections.unmodifiableMap(new HashMap<>(islands));
    }

    /** 取得已登錄的方塊總數 */
    public static int getTotalRegisteredBlocks() {
        return blockToIsland.size();
    }

    /** 取得 island 數量 */
    public static int getIslandCount() {
        return islands.size();
    }

    // ═══════════════════════════════════════════════════════
    //  生命週期
    // ═══════════════════════════════════════════════════════

    /** 清除所有登錄（世界卸載時呼叫） */
    public static void clear() {
        blockToIsland.clear();
        islands.clear();
        LOGGER.info("[IslandRegistry] Cleared all islands");
    }

    /** 診斷用統計資訊 */
    public static String getStats() {
        return String.format("islands=%d, totalBlocks=%d",
            islands.size(), blockToIsland.size());
    }
}
