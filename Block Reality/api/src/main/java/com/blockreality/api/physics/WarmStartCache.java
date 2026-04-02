package com.blockreality.api.physics;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Warm-start 快取 — 儲存上次收斂結果供下次求解加速。
 *
 * <h3>策略</h3>
 * <p>當結構僅有 1-2 個方塊變動時，warm-start 可將初始猜測從自重
 * 改為前次收斂力值，使迭代次數從 ~40 降至 ~5-10 次。
 *
 * <h3>快取鍵設計</h3>
 * <p>使用 64-bit FNV-1a rolling hash 代替 {@code Set.hashCode()}（32-bit），
 * 大幅降低碰撞率。Hash 同時納入 BlockPos 與材料強度，
 * 確保形狀相同但材料不同的結構不會命中相同快取。
 *
 * <h3>驅逐策略</h3>
 * <p>LRU（Least Recently Used），容量上限 {@value #MAX_ENTRIES} 條目。
 * 使用 {@link LinkedHashMap}(accessOrder=true) + {@code removeEldestEntry} 實現。
 *
 * @see ForceEquilibriumSolver
 * @see SORSolverCore
 */
// M1-fix: 從 ForceEquilibriumSolver 提取，封裝快取邏輯與 fingerprint 演算法
public class WarmStartCache {

    // ─── 快取容量 ────────────────────────────────────────────────────────────

    /** LRU 快取最大條目數（超過後驅逐最久未存取的條目）。 */
    static final int MAX_ENTRIES = 64;

    // ─── FNV-1a 64-bit Hash 常數 ─────────────────────────────────────────────

    /**
     * FNV-1a 64-bit offset basis。
     * 來源：<a href="http://www.isthe.com/chongo/tech/comp/fnv/">Fowler–Noll–Vo hash function</a>
     */
    private static final long FNV1A_OFFSET_BASIS = 0xcbf29ce484222325L;

    /**
     * FNV-1a 64-bit prime。
     * XOR-then-multiply 策略對任意輸入都有良好的 avalanche 效果，
     * 避免相鄰結構的系統性碰撞（base-31 的常見缺點）。
     */
    static final long FNV1A_PRIME = 0x100000001b3L;

    // ─── LRU 快取（synchronized 保護多執行緒存取）────────────────────────────

    @SuppressWarnings("serial")
    private static final Map<Long, Map<BlockPos, Double>> CACHE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Map<BlockPos, Double>> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    // ─── 存取 API ────────────────────────────────────────────────────────────

    /**
     * 查詢 warm-start 快取。
     *
     * @param fingerprint 由 {@link #computeFingerprint(Set, Map)} 產生的結構指紋
     * @return 前次收斂力值映射（BlockPos → totalForce），快取缺失時返回 null
     */
    static Map<BlockPos, Double> get(long fingerprint) {
        return CACHE.get(fingerprint);
    }

    /**
     * 將已收斂的求解結果寫入快取，供下次求解作為 warm-start 初始值。
     * LRU 驅逐由 {@link LinkedHashMap#removeEldestEntry} 自動處理。
     *
     * @param blocks      結構中所有方塊位置
     * @param materials   各位置材料映射
     * @param nodeStates  本次收斂後的節點狀態映射
     */
    static void put(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Map<BlockPos, NodeState> nodeStates
    ) {
        long fp = computeFingerprint(blocks, materials);
        Map<BlockPos, Double> forceMap = new HashMap<>(nodeStates.size());
        for (NodeState ns : nodeStates.values()) {
            forceMap.put(ns.pos, ns.totalForce);
        }
        CACHE.put(fp, forceMap);
    }

    /**
     * 計算結構指紋（64-bit FNV-1a）。
     *
     * <p>指紋同時覆蓋 BlockPos 座標與材料強度，
     * 確保形狀相同但材料不同的結構對應不同指紋（無假命中）。
     *
     * <p>排序後 hash 確保方塊插入順序不影響指紋值（交換律）。
     *
     * @param blocks    結構中所有方塊位置
     * @param materials 各位置材料映射（value 可為 null，視為空氣）
     * @return 64-bit 結構指紋
     */
    static long computeFingerprint(Set<BlockPos> blocks, Map<BlockPos, RMaterial> materials) {
        return blocks.stream()
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .mapToLong(pos -> blockFingerprint(pos, materials.get(pos)))
            .reduce(FNV1A_OFFSET_BASIS, (hash, val) -> (hash ^ val) * FNV1A_PRIME);
    }

    /**
     * 計算單一方塊對指紋的貢獻值（BlockPos + 材料強度 → long）。
     *
     * <p>供 {@link #computeFingerprint(Set, Map)} 使用。
     * 因具備確定性且計算廉價，可用於任何需要識別方塊身份的場景。
     *
     * @param pos 方塊座標
     * @param mat 方塊材料（null 代表空氣，貢獻 0）
     * @return 此方塊的 64-bit fingerprint 貢獻值
     */
    static long blockFingerprint(BlockPos pos, RMaterial mat) {
        long posHash = pos.asLong();
        long matBits = (mat != null)
            ? Double.doubleToRawLongBits(mat.getCombinedStrength())
            : 0L;
        return posHash ^ (matBits * FNV1A_PRIME);
    }

    // ─── 測試輔助 ────────────────────────────────────────────────────────────

    /** 清空快取（測試用）。生產環境不應直接呼叫。 */
    static void clearForTest() {
        CACHE.clear();
    }

    /** @return 目前快取條目數量（測試用） */
    static int sizeForTest() {
        return CACHE.size();
    }

    // 工具類別，禁止實例化
    private WarmStartCache() {}
}
