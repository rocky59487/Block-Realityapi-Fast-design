package com.blockreality.fastdesign.client.node;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 節點註冊表 — 設計報告 §12.1 N1-12
 *
 * 靜態註冊所有 144 個節點型別，提供：
 *   - 工廠方法建立節點實例
 *   - 搜尋（英文/中文模糊匹配）
 *   - 按類別分組
 *
 * 註冊在 FastDesignMod 初始化時執行。
 */
public final class NodeRegistry {

    private static final Logger LOGGER = LogManager.getLogger("NodeRegistry");

    private static final Map<String, NodeEntry> REGISTRY = new LinkedHashMap<>();
    private static boolean initialized = false;

    private NodeRegistry() {}

    // ─── 註冊 ───

    /**
     * 註冊一個節點型別。
     *
     * @param typeId  型別 ID，如 "render.preset.QualityPreset"
     * @param factory 工廠函數（如 QualityPresetNode::new）
     * @param displayNameEN 英文名稱
     * @param displayNameCN 中文名稱
     * @param category 類別
     */
    public static void register(String typeId, Supplier<BRNode> factory,
                                 String displayNameEN, String displayNameCN,
                                 String category) {
        if (REGISTRY.containsKey(typeId)) {
            LOGGER.warn("重複註冊節點型別：{}", typeId);
            return;
        }
        REGISTRY.put(typeId, new NodeEntry(typeId, factory, displayNameEN, displayNameCN, category));
    }

    /**
     * 建立節點實例。
     */
    @Nullable
    public static BRNode create(String typeId) {
        NodeEntry entry = REGISTRY.get(typeId);
        if (entry == null) {
            LOGGER.warn("未註冊的節點型別：{}", typeId);
            return null;
        }
        return entry.factory.get();
    }

    // ─── 查詢 ───

    public static Set<String> allTypeIds() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    public static Collection<NodeEntry> allEntries() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static int registeredCount() {
        return REGISTRY.size();
    }

    /**
     * 按類別分組。
     */
    public static Map<String, List<NodeEntry>> byCategory() {
        return REGISTRY.values().stream()
                .collect(Collectors.groupingBy(NodeEntry::category, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 模糊搜尋（支援英文/中文名稱）。
     */
    public static List<NodeEntry> search(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(REGISTRY.values());
        }

        String lower = query.toLowerCase(Locale.ROOT).trim();

        // 類別前綴搜尋（如 "render:bloom"）
        String categoryFilter = null;
        String nameFilter = lower;
        int colonIdx = lower.indexOf(':');
        if (colonIdx > 0) {
            categoryFilter = lower.substring(0, colonIdx);
            nameFilter = lower.substring(colonIdx + 1);
        }

        List<NodeEntry> results = new ArrayList<>();
        String finalCategoryFilter = categoryFilter;
        String finalNameFilter = nameFilter;

        for (NodeEntry entry : REGISTRY.values()) {
            // 類別過濾
            if (finalCategoryFilter != null &&
                    !entry.category.toLowerCase(Locale.ROOT).contains(finalCategoryFilter)) {
                continue;
            }

            // 名稱匹配（英文 + 中文）
            if (finalNameFilter.isEmpty()
                    || entry.displayNameEN.toLowerCase(Locale.ROOT).contains(finalNameFilter)
                    || entry.displayNameCN.contains(finalNameFilter)
                    || entry.typeId.toLowerCase(Locale.ROOT).contains(finalNameFilter)) {
                results.add(entry);
            }
        }

        return results;
    }

    // ─── 批量註冊 ───

    /**
     * 註冊所有 144 個節點（由 FastDesignMod 在初始化時呼叫）。
     * 各 Phase N4 實作檔案提供具體節點的靜態 register 方法。
     */
    public static void registerAll() {
        if (initialized) return;
        initialized = true;

        // 各類別節點的批量註冊會在對應 impl 包的初始化類中呼叫
        // 這裡預留入口，Phase N4 實作時會填入具體註冊邏輯

        LOGGER.info("[NodeRegistry] 節點註冊完成，共 {} 種型別", REGISTRY.size());
    }

    // ─── 資料結構 ───

    public static final class NodeEntry {
        private final String typeId;
        private final Supplier<BRNode> factory;
        private final String displayNameEN;
        private final String displayNameCN;
        private final String category;

        NodeEntry(String typeId, Supplier<BRNode> factory,
                  String displayNameEN, String displayNameCN, String category) {
            this.typeId = typeId;
            this.factory = factory;
            this.displayNameEN = displayNameEN;
            this.displayNameCN = displayNameCN;
            this.category = category;
        }

        public String typeId()        { return typeId; }
        public String displayNameEN() { return displayNameEN; }
        public String displayNameCN() { return displayNameCN; }
        public String category()      { return category; }

        public BRNode createInstance() { return factory.get(); }
    }
}
