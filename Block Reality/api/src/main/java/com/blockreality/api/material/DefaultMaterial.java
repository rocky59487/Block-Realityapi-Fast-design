package com.blockreality.api.material;

import java.util.HashMap;
import java.util.Map;

/**
 * 預設材料定義 — 對應 Minecraft 原版方塊的工程參數。
 *
 * 數值來源：
 *   - PLAIN_CONCRETE: C25 混凝土 (無筋)
 *   - REBAR: HRB400 鋼筋
 *   - CONCRETE: C30 混凝土
 *   - RC_NODE: RC 融合節點 (由 RCFusionDetector 計算)
 *   - BRICK: 標準紅磚
 *   - TIMBER: 杉木/橡木結構材
 *   - STEEL: Q345 結構鋼
 *   - STONE: 花崗岩 (Minecraft 預設石材)
 *   - GLASS: 普通平板玻璃
 *   - SAND: 鬆散砂土
 *   - OBSIDIAN: 火山岩 (黑曜石)
 *   - BEDROCK: 不可破壞 (無限強度)
 */
/**
 * ★ 材料參數來源：
 *   - 楊氏模量 E：Engineering Toolbox / Eurocode 2 (EN 1992) / AISC Steel Manual
 *   - 泊松比 ν：Eurocode / CES EduPack materials database
 *   - 降伏強度：GB 50010 (混凝土) / GB 50017 (鋼結構) / AS 1720 (木材)
 *   - Rcomp/Rtens/Rshear：原有值保留（遊戲內平衡參數）
 */
public enum DefaultMaterial implements RMaterial {

    //                          Rcomp    Rtens    Rshear   Density   ID              E(GPa)  ν     Fy(MPa)
    PLAIN_CONCRETE(              25.0,    2.5,     3.5,    2400.0,  "plain_concrete", 25.0, 0.18,   25.0),
    REBAR(                      250.0,  400.0,   150.0,    7850.0,  "rebar",         200.0, 0.29,  400.0),
    CONCRETE(                    30.0,    3.0,     4.0,    2350.0,  "concrete",       30.0, 0.20,   30.0),
    RC_NODE(                     33.0,    5.9,     5.0,    2500.0,  "rc_node",        32.0, 0.20,   33.0),
    BRICK(                       10.0,    0.5,     1.5,    1800.0,  "brick",           5.0, 0.15,   10.0),
    TIMBER(                       5.0,    8.0,     2.0,     600.0,  "timber",         11.0, 0.35,    5.0),
    STEEL(                      350.0,  500.0,   200.0,    7850.0,  "steel",         200.0, 0.29,  345.0),
    STONE(                       30.0,    3.0,     4.0,    2400.0,  "stone",          50.0, 0.25,   30.0),
    // ★ P2-fix (2025-04): Rtens 0.5 → 30.0 MPa（GB/T 11944 普通平板玻璃最低抗拉 30 MPa，
    //   原值 0.5 MPa 低估約 60 倍，導致玻璃結構幾乎在任何拉力下即告失效）
    GLASS(                      100.0,   30.0,     1.0,    2500.0,  "glass",          70.0, 0.22,  100.0),
    SAND(                         0.1,    0.0,     0.05,   1600.0,  "sand",            0.01, 0.30,   0.1),
    OBSIDIAN(                   200.0,    5.0,    20.0,    2600.0,  "obsidian",       70.0, 0.20,  200.0),
    // ★ P3-fix (2025-04): 1e15 → 1e9 MPa（= 1 TPa，仍遠超任何實際材料），
    //   並透過 isIndestructible() = true 讓求解器短路，完全繞開浮點運算。
    //   這樣即使外部程式碼直接使用強度值，也不會觸發 double 運算的精度問題。
    BEDROCK(                      1e9,    1e9,      1e9,    3000.0, "bedrock",        1e6,  0.10,   1e9);

    private final double rcomp;
    private final double rtens;
    private final double rshear;
    private final double density;
    private final String materialId;
    private final double elasticModulusGPa;   // 楊氏模量 (GPa)
    private final double poissonsRatio;        // 泊松比
    private final double yieldStrengthMPa;     // 降伏強度 (MPa)

    /**
     * ★ new-fix N8: 靜態 HashMap 快取，使 fromId() 由 O(N) 線性掃描變為 O(1) 查找。
     * enum 只有 12 個值，但若 fromId() 在每個方塊 tick 的熱路徑呼叫，仍值得優化。
     */
    private static final Map<String, DefaultMaterial> BY_ID = new HashMap<>();
    static {
        for (DefaultMaterial m : values()) {
            BY_ID.put(m.materialId, m);
        }
    }

    DefaultMaterial(double rcomp, double rtens, double rshear, double density, String materialId,
                    double elasticModulusGPa, double poissonsRatio, double yieldStrengthMPa) {
        this.rcomp = rcomp;
        this.rtens = rtens;
        this.rshear = rshear;
        this.density = density;
        this.materialId = materialId;
        this.elasticModulusGPa = elasticModulusGPa;
        this.poissonsRatio = poissonsRatio;
        this.yieldStrengthMPa = yieldStrengthMPa;
    }

    @Override
    public double getRcomp() {
        return rcomp;
    }

    @Override
    public double getRtens() {
        return rtens;
    }

    @Override
    public double getRshear() {
        return rshear;
    }

    @Override
    public double getDensity() {
        return density;
    }

    @Override
    public String getMaterialId() {
        return materialId;
    }

    /**
     * ★ 覆寫 RMaterial 預設方法 — 使用真實工程數據替代經驗近似。
     * 數據來源：Eurocode 2, AISC Steel Manual, Engineering Toolbox
     */
    @Override
    public double getYoungsModulusPa() {
        return elasticModulusGPa * 1e9;  // GPa → Pa
    }

    @Override
    public double getPoissonsRatio() {
        return poissonsRatio;
    }

    @Override
    public double getYieldStrength() {
        return yieldStrengthMPa;
    }

    /**
     * 是否為不可破壞材料。
     * ★ P3-fix (2025-04): BEDROCK 回傳 true，讓物理求解器跳過強度利用率計算，
     *   避免 1e9 MPa 在浮點運算鏈中累積的精度問題。
     */
    @Override
    public boolean isIndestructible() {
        return this == BEDROCK;
    }

    /**
     * 依 ID 查找預設材料。
     * ★ review-fix #16: Javadoc 修正 — 原先寫 STONE 但實際回傳 CONCRETE。
     * ★ new-fix N8: 改用靜態 HashMap，O(1) 查找替代 O(N) 線性掃描。
     * @return 找不到時回傳 CONCRETE（RBlock 的合理預設值）
     */
    public static DefaultMaterial fromId(String id) {
        DefaultMaterial result = BY_ID.get(id);
        return result != null ? result : CONCRETE; // DEV-4 fix: fallback 改為 CONCRETE
    }
}
