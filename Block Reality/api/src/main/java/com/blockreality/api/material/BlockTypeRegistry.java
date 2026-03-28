package com.blockreality.api.material;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 動態方塊類型註冊表 — v3fix §M5
 *
 * 允許 Construction Intern (CI) 模組在不修改 BlockType 枚舉的前提下
 * 註冊新的方塊類型（如 PRESTRESSED, COMPOSITE, GEOPOLYMER 等）。
 *
 * 設計原則：
 *   1. 核心 5 種 BlockType（PLAIN, REBAR, CONCRETE, RC_NODE, ANCHOR_PILE）
 *      仍使用 enum，確保 switch 語句和序列化穩定。
 *   2. 第三方/CI 模組透過此 Registry 註冊擴展類型。
 *   3. 查詢優先檢查 enum，再查 registry。
 *   4. ConcurrentHashMap 保證執行緒安全。
 *
 * 使用方式：
 *   // 模組初始化時
 *   BlockTypeRegistry.register("prestressed", 0.6f);
 *
 *   // 查詢時
 *   BlockTypeEntry entry = BlockTypeRegistry.resolve("prestressed");
 *
 * @see BlockType
 */
@ThreadSafe
public final class BlockTypeRegistry {

    private static final Logger LOGGER = LogManager.getLogger("BR-BlockTypeRegistry");

    /**
     * 擴展方塊類型條目。
     *
     * @param serializedName 序列化名稱（全小寫，用於 NBT/JSON）
     * @param structuralFactor 結構係數（對應 SPHStressEngine 的 materialFactor）
     *                         1.0 = 與 PLAIN 相同，< 1.0 = 更強，> 1.0 = 更弱
     */
    public record BlockTypeEntry(
        String serializedName,
        float structuralFactor
    ) {
        public BlockTypeEntry {
            if (serializedName == null || serializedName.isBlank()) {
                throw new IllegalArgumentException("serializedName must not be null or blank");
            }
            if (structuralFactor <= 0) {
                throw new IllegalArgumentException("structuralFactor must be positive, got: " + structuralFactor);
            }
        }
    }

    /** 擴展類型存儲 */
    private static final Map<String, BlockTypeEntry> EXTENSIONS = new ConcurrentHashMap<>();

    /**
     * 核心 enum 類型的快速查表（避免每次遍歷 values()）。
     * ★ review-fix #14: 改用 HashMap — unmodifiableMap 已保證不可變，
     * 不需要 ConcurrentHashMap 的 volatile read 開銷。
     */
    private static final Map<String, BlockType> CORE_TYPES;
    static {
        Map<String, BlockType> map = new java.util.HashMap<>();
        for (BlockType type : BlockType.values()) {
            map.put(type.getSerializedName(), type);
        }
        CORE_TYPES = Collections.unmodifiableMap(map);
    }

    private BlockTypeRegistry() {} // 不可實例化

    // ═══════════════════════════════════════════════════════
    //  Registration
    // ═══════════════════════════════════════════════════════

    /**
     * 註冊一個擴展方塊類型。
     *
     * @param serializedName   類型名稱（全小寫，不可與核心 enum 衝突）
     * @param structuralFactor 結構係數
     * @return 建立的 BlockTypeEntry
     * @throws IllegalArgumentException 若名稱與核心 BlockType 衝突
     */
    public static BlockTypeEntry register(String serializedName, float structuralFactor) {
        if (CORE_TYPES.containsKey(serializedName)) {
            throw new IllegalArgumentException(
                "Cannot register extension type '" + serializedName +
                "': conflicts with core BlockType enum");
        }

        BlockTypeEntry entry = new BlockTypeEntry(serializedName, structuralFactor);
        BlockTypeEntry previous = EXTENSIONS.put(serializedName, entry);

        if (previous != null) {
            LOGGER.warn("[BlockTypeRegistry] Overwritten existing extension type: {} (old factor={}, new factor={})",
                serializedName, previous.structuralFactor(), structuralFactor);
        } else {
            LOGGER.info("[BlockTypeRegistry] Registered extension type: {} (factor={})",
                serializedName, structuralFactor);
        }

        return entry;
    }

    /**
     * 取消註冊一個擴展類型。
     *
     * @param serializedName 要移除的類型名稱
     * @return true 如果成功移除，false 如果不存在
     */
    public static boolean unregister(String serializedName) {
        BlockTypeEntry removed = EXTENSIONS.remove(serializedName);
        if (removed != null) {
            LOGGER.info("[BlockTypeRegistry] Unregistered extension type: {}", serializedName);
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════
    //  Query
    // ═══════════════════════════════════════════════════════

    /**
     * 判斷名稱是否為核心 BlockType enum。
     */
    public static boolean isCoreType(String serializedName) {
        return CORE_TYPES.containsKey(serializedName);
    }

    /**
     * 判斷名稱是否已註冊（核心或擴展）。
     */
    public static boolean isRegistered(String serializedName) {
        return CORE_TYPES.containsKey(serializedName) || EXTENSIONS.containsKey(serializedName);
    }

    /**
     * 查詢擴展類型條目。
     *
     * @param serializedName 類型名稱
     * @return 擴展條目，若不存在回傳 null
     */
    public static BlockTypeEntry getExtension(String serializedName) {
        return EXTENSIONS.get(serializedName);
    }

    /**
     * 解析類型名稱 → BlockType enum 或擴展條目的結構係數。
     *
     * 優先順序：
     *   1. 核心 BlockType enum → 回傳對應 SPH materialFactor
     *   2. 擴展註冊表 → 回傳 BlockTypeEntry.structuralFactor
     *   3. 未找到 → 回傳 1.0f (PLAIN 等效)
     *
     * @param serializedName 類型名稱
     * @return 結構係數
     */
    public static float resolveStructuralFactor(String serializedName) {
        // 1. 核心類型
        BlockType coreType = CORE_TYPES.get(serializedName);
        if (coreType != null) {
            return getCoreStructuralFactor(coreType);
        }

        // 2. 擴展類型
        BlockTypeEntry ext = EXTENSIONS.get(serializedName);
        if (ext != null) {
            return ext.structuralFactor();
        }

        // 3. Fallback
        return 1.0f;
    }

    /**
     * 取得核心 BlockType 的結構係數。
     * #7 fix: 直接從 BlockType enum 讀取，確保與 SPHStressEngine 共用同一數據來源。
     */
    private static float getCoreStructuralFactor(BlockType type) {
        return type.getStructuralFactor();
    }

    /**
     * 取得所有已註冊的擴展類型（不含核心 enum）。
     */
    public static Collection<BlockTypeEntry> getAllExtensions() {
        return Collections.unmodifiableCollection(EXTENSIONS.values());
    }

    /**
     * 擴展類型數量。
     */
    public static int extensionCount() {
        return EXTENSIONS.size();
    }

    /**
     * 清除所有擴展類型（測試用）。
     */
    public static void clearExtensions() {
        EXTENSIONS.clear();
        LOGGER.debug("[BlockTypeRegistry] All extension types cleared");
    }
}
