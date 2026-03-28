package com.blockreality.api.material;

/**
 * Block Reality 方塊類型（核心 enum）。
 *
 * 類型決定方塊在結構中的角色：
 *   PLAIN        — 素材 (原版方塊預設)           factor=1.0
 *   REBAR        — 鋼筋 (提供抗拉)               factor=1.2
 *   CONCRETE     — 混凝土 (提供抗壓)             factor=0.8
 *   RC_NODE      — RC 融合節點 (鋼筋+混凝土)     factor=0.7
 *   ANCHOR_PILE  — 錨定樁 (手動放置的地基錨定點)  factor=0.5
 *
 * structuralFactor 是 SPHStressEngine 和 BlockTypeRegistry 共用的
 * 唯一數據來源（Single Source of Truth），避免兩處維護不同步。
 *
 * RC_NODE 由 RCFusionDetector 在偵測到相鄰鋼筋+混凝土時自動生成，
 * 融合公式：R_RC_tens = R_concrete_tens + R_rebar_tens × φ_tens
 *
 * ANCHOR_PILE 用於手動標記地基錨定點，
 * AnchorContinuityChecker.isNaturalAnchor() 會將其視為天然錨定。
 *
 * 擴展類型（CI 模組自定義）請使用 {@link BlockTypeRegistry#register}。
 *
 * @see BlockTypeRegistry
 */
public enum BlockType {

    PLAIN("plain", 1.0f),
    REBAR("rebar", 1.2f),
    CONCRETE("concrete", 0.8f),
    RC_NODE("rc_node", 0.7f),
    ANCHOR_PILE("anchor_pile", 0.5f);

    // ★ R3-1 fix: 靜態 HashMap 快取，fromString() O(1)（與 DefaultMaterial N8 修復一致）
    private static final java.util.Map<String, BlockType> BY_NAME = new java.util.HashMap<>();
    static {
        for (BlockType t : values()) { BY_NAME.put(t.serializedName, t); }
    }

    private final String serializedName;

    /**
     * 結構係數 — SPHStressEngine materialFactor 的唯一來源。
     * < 1.0 = 更強（混凝土、RC），> 1.0 = 更弱（鋼筋獨立時），= 1.0 = 基準。
     */
    private final float structuralFactor;

    BlockType(String serializedName, float structuralFactor) {
        this.serializedName = serializedName;
        this.structuralFactor = structuralFactor;
    }

    public String getSerializedName() {
        return serializedName;
    }

    /**
     * 取得此類型的結構係數。
     *
     * @return structural factor（SPHStressEngine 和 BlockTypeRegistry 共用）
     */
    public float getStructuralFactor() {
        return structuralFactor;
    }

    /**
     * 從序列化名稱查找核心 BlockType。
     * 僅搜尋核心 enum，不含擴展類型。
     *
     * @param name 序列化名稱
     * @return 對應的 BlockType，找不到時回傳 PLAIN
     */
    public static BlockType fromString(String name) {
        // ★ R3-1 fix: O(1) HashMap 查找取代 O(N) 線性掃描
        return BY_NAME.getOrDefault(name, PLAIN);
    }

    /**
     * 判斷名稱是否為已知類型（核心 enum 或擴展註冊表）。
     *
     * @param name 序列化名稱
     * @return true 如果是核心類型或已註冊的擴展類型
     */
    public static boolean isKnownType(String name) {
        return BlockTypeRegistry.isRegistered(name);
    }
}
