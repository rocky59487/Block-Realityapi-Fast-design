package com.blockreality.api.material;

/**
 * 動態材料 — 在 runtime 以公式計算出的材料參數。
 *
 * 主要用途：RC 融合節點（RCFusionDetector）
 *   每個 RC 節點的強度由相鄰的鋼筋+混凝土規格決定，
 *   無法預先寫死在 enum 裡，必須動態建立。
 *
 * 不可變 record — 線程安全，可跨線程傳遞。
 *
 * ★ review-fix #13: 存取欄位有兩種方式（結果相同）：
 *   - record canonical accessor: rcomp(), rtens(), rshear(), density(), materialId()
 *   - RMaterial interface: getRcomp(), getRtens(), getRshear(), getDensity(), getMaterialId()
 *   兩者回傳相同值。建議透過 RMaterial 介面存取以保持一致性。
 *
 * 工廠方法：
 *   DynamicMaterial.ofRCFusion(concrete, rebar, phiTens, phiShear, compBoost, hasHoneycomb)
 */


public record DynamicMaterial(
    double rcomp,
    double rtens,
    double rshear,
    double density,
    String materialId
) implements RMaterial {

    @Override public double getRcomp()      { return rcomp; }
    @Override public double getRtens()      { return rtens; }
    @Override public double getRshear()     { return rshear; }
    @Override public double getDensity()    { return density; }
    @Override public String getMaterialId() { return materialId; }

    // ─── 工廠方法 ───────────────────────────────────────────

    /**
     * 建立 RC 融合材料。
     *
     * 公式（v3fix + 想法.docx）：
     *   R_RC_comp  = R_concrete_comp × compBoost
     *   R_RC_tens  = R_concrete_tens + R_rebar_tens × φ_tens
     *   R_RC_shear = R_concrete_shear + R_rebar_shear × φ_shear
     *   density    = concrete × 97% + rebar × 3% (典型鋼筋含量 2-5%)
     *
     * 蜂窩懲罰（hasHoneycomb=true）：
     *   所有強度參數 × 0.7（降低 30%，模擬澆灌品質不良）
     *
     * @param concrete    混凝土材料
     * @param rebar       鋼筋材料
     * @param phiTens     抗拉融合係數 (預設 0.8)
     * @param phiShear    抗剪融合係數 (預設 0.6)
     * @param compBoost   抗壓增幅比例 (預設 1.1)
     * @param hasHoneycomb 是否有蜂窩空洞
     * @return 融合後的動態材料
     */
    public static DynamicMaterial ofRCFusion(
            RMaterial concrete, RMaterial rebar,
            double phiTens, double phiShear, double compBoost,
            boolean hasHoneycomb) {

        // ★ Round 5 fix: 輸入驗證 — 防止 null 或負值材料滲入物理計算
        java.util.Objects.requireNonNull(concrete, "concrete material must not be null");
        java.util.Objects.requireNonNull(rebar, "rebar material must not be null");
        if (concrete.getRcomp() < 0 || rebar.getRtens() < 0) {
            throw new IllegalArgumentException(
                String.format("Invalid material properties: concrete Rcomp=%.2f, rebar Rtens=%.2f",
                    concrete.getRcomp(), rebar.getRtens()));
        }

        double rcComp  = concrete.getRcomp()  * compBoost;
        double rcTens  = concrete.getRtens()  + rebar.getRtens()  * phiTens;
        double rcShear = concrete.getRshear() + rebar.getRshear() * phiShear;
        // ★ R6-6 fix: 鋼筋體積比從 20% 修正為 3%（典型工程值 2-5%）
        // 原 80:20 嚴重高估鋼筋密度貢獻，導致 RC 節點質量偏高約 15%。
        // 參考：ACI 318 典型配筋率 ρ = 1-3%，取 3% 作為 Minecraft 簡化值
        double rebarVolumeRatio = 0.03;
        double rcDensity = concrete.getDensity() * (1.0 - rebarVolumeRatio)
                         + rebar.getDensity() * rebarVolumeRatio;

        if (hasHoneycomb) {
            rcComp  *= 0.7;
            rcTens  *= 0.7;
            rcShear *= 0.7;
        }

        // ID 格式：rc_fusion_(有無蜂窩)
        String id = hasHoneycomb ? "rc_fusion_honeycomb" : "rc_fusion";

        return new DynamicMaterial(rcComp, rcTens, rcShear, rcDensity, id);
    }

    /**
     * 建立自訂材料（給未來 CLI /br_material create 指令使用）。
     * ★ Fix #3: Reject negative strength values instead of silently clamping to 0
     */
    public static DynamicMaterial ofCustom(String id, double rcomp, double rtens,
                                            double rshear, double density) {
        // ★ Round 5 fix: 自訂材料基本驗證 — 現在拒絕負值而不是默默clamp
        java.util.Objects.requireNonNull(id, "material id must not be null");
        if (id.isEmpty()) throw new IllegalArgumentException("material id must not be empty");
        if (density <= 0) throw new IllegalArgumentException("density must be > 0, got " + density);
        if (rcomp < 0) throw new IllegalArgumentException("rcomp must be >= 0, got " + rcomp);
        if (rtens < 0) throw new IllegalArgumentException("rtens must be >= 0, got " + rtens);
        if (rshear < 0) throw new IllegalArgumentException("rshear must be >= 0, got " + rshear);

        return new DynamicMaterial(rcomp, rtens, rshear, density, id);
    }
}
