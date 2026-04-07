package com.blockreality.api.physics.solver;

import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nonnull;

/**
 * 物理域轉譯器介面 — 將域概念映射到通用擴散求解器。
 *
 * <p>每個物理域（流體/風/熱/電磁）實作此介面，在求解前後進行轉譯：
 * <ul>
 *   <li>{@link #populateRegion} — 域 → 求解器：將域物理量映射到 σ[], f[], φ[]</li>
 *   <li>{@link #interpretResults} — 求解器 → 域：從 φ[] 導出域物理量（壓力/溫度/電流）</li>
 * </ul>
 *
 * <h3>域映射表</h3>
 * <pre>
 * 域       | σ (conductivity)  | f (source)    | φ (phi)    | gravity
 * ─────────┼───────────────────┼───────────────┼────────────┼────────
 * Fluid    | ρ-based           | ρgh           | 流體勢能   | 1.0
 * Thermal  | k/(ρc)            | Q/(ρc)        | 溫度 T     | 0.0
 * EM       | σ_elec            | -ρ_charge/ε   | 電位 V     | 0.0
 * Wind     | 1/ρ_air           | ∇·u*          | 壓力 p     | 0.0
 * </pre>
 */
public interface DomainTranslator {

    /**
     * 將域物理量寫入求解器區域的 SoA 陣列。
     *
     * <p>實作者應設置 conductivity[]、source[]、type[]，
     * 以及 phi[] 的初始條件。
     *
     * @param region 通用擴散區域
     * @param level  Minecraft 世界（用於讀取方塊狀態）
     */
    void populateRegion(@Nonnull DiffusionRegion region, @Nonnull ServerLevel level);

    /**
     * 從求解後的 phi[] 導出域物理量。
     *
     * <p>例如：Thermal 從 φ[] 讀取溫度，計算熱應力；
     * EM 從 φ[] 計算 E = -∇φ 電場。
     *
     * @param region 已求解的擴散區域
     */
    void interpretResults(@Nonnull DiffusionRegion region);

    /**
     * 重力權重：0.0（Thermal/EM/Wind）或 1.0（Fluid）。
     * 控制求解器是否將 ρgh 加入總勢能計算。
     */
    float getGravityWeight();

    /** 域識別碼："fluid", "wind", "thermal", "em" */
    String getDomainId();

    /** 預設擴散率（各域不同：流體 0.25, 熱 0.5, EM 0.4） */
    float getDefaultDiffusionRate();

    /** 預設最大迭代次數 */
    default int getDefaultMaxIterations() { return 4; }
}
