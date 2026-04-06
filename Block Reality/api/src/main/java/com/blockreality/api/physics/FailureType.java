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
    HYDROSTATIC_PRESSURE
}
