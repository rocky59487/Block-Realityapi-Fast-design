package com.blockreality.api.physics;

/**
 * 結構失效類型 — 代表物理引擎偵測到的失效模式。
 *
 * <p>由 PFSF GPU 引擎和崩塌系統共同使用。
 * 每種類型對應不同的結構工程失效機制。
 */
public enum FailureType {
    /** 懸臂力矩超過 Rtens → 從根部斷裂 */
    CANTILEVER_BREAK,
    /** 載重超過 Rcomp → 壓碎 */
    CRUSHING,
    /** 完全無支撐（孤島） */
    NO_SUPPORT,
    /** 拉力斷裂（outward flux 超過 Rtens） */
    TENSION_BREAK,

    /** ★ PFSF-Fluid: 靜水壓力超過結構承載能力 */
    HYDROSTATIC_PRESSURE,

    /** ★ PFSF-Thermal: 熱膨脹應力超過材料屈服強度 */
    THERMAL_STRESS,

    /** ★ PFSF-Thermal: 表面溫度梯度導致混凝土剝落 */
    THERMAL_SPALLING,

    /** ★ PFSF-Wind: 風壓傾覆力矩超過自重穩定力矩 */
    WIND_OVERTURNING,

    /**
     * ★ 自重重心傾覆：CoM 投影超出支撐多邊形邊緣（含 15% 死區）。
     * 與 WIND_OVERTURNING 的區別：此類型源於自重重心偏移，而非外力。
     * 觸發後產生物理正確的傾倒角速度（ω ∝ √(g×overhang/h_com)）。
     */
    OVERTURNING,

    /** ★ PFSF-EM: 閃電擊中造成結構性損傷 */
    LIGHTNING_STRIKE,

    /** 扭轉斷裂 — 不對稱荷載導致 */
    TORSION_BREAK,

    /** 疲勞裂紋 — 累積應力超過疲勞限值 */
    FATIGUE_CRACK
}
