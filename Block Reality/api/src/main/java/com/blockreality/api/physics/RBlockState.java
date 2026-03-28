package com.blockreality.api.physics;

/**
 * 物理引擎專用的唯讀方塊狀態。
 * 與 Forge BlockState 完全解耦 — 不持有任何 net.minecraft 參照。
 *
 * @param blockId              方塊 ID (如 "minecraft:stone")
 * @param mass                 質量 (kg/m³ 密度 × fillRatio)
 * @param compressiveStrength  抗壓強度 (MPa)
 * @param tensileStrength      抗拉強度 (MPa)
 * @param isAnchor             是否為錨定點 (基岩、屏障等不可破壞的支撐)
 * @param crossSectionArea     有效截面積 (m²)，預設 1.0
 * @param momentOfInertia      X 軸截面慣性矩 (m⁴)，預設 1/12
 * @param sectionModulus       X 軸截面模數 (m³)，預設 1/6
 * @param momentOfInertiaY     Y 軸截面慣性矩 (m⁴)，預設 1/12 — ★ audit-fix M-6
 * @param sectionModulusY      Y 軸截面模數 (m³)，預設 1/6 — ★ audit-fix M-6
 */
public record RBlockState(
    String blockId,
    float mass,
    float compressiveStrength,
    float tensileStrength,
    boolean isAnchor,
    float crossSectionArea,
    float momentOfInertia,
    float sectionModulus,
    float momentOfInertiaY,
    float sectionModulusY
) {
    public static final RBlockState AIR = new RBlockState("minecraft:air", 0f, 0f, 0f, false, 0f, 0f, 0f, 0f, 0f);

    /**
     * 向後相容建構子 — 現有程式碼使用 5 參數版本，自動填入全塊截面預設值。
     */
    public RBlockState(String blockId, float mass, float compressiveStrength,
                       float tensileStrength, boolean isAnchor) {
        this(blockId, mass, compressiveStrength, tensileStrength, isAnchor,
            (float) PhysicsConstants.BLOCK_AREA,
            (float) PhysicsConstants.FULL_MOMENT_OF_INERTIA,
            (float) PhysicsConstants.FULL_SECTION_MODULUS,
            (float) PhysicsConstants.FULL_MOMENT_OF_INERTIA,
            (float) PhysicsConstants.FULL_SECTION_MODULUS);
    }

    /**
     * 8-arg 相容建構子 — 雕刻系統使用，X/Y 軸使用相同值。
     */
    public RBlockState(String blockId, float mass, float compressiveStrength,
                       float tensileStrength, boolean isAnchor,
                       float crossSectionArea, float momentOfInertia, float sectionModulus) {
        this(blockId, mass, compressiveStrength, tensileStrength, isAnchor,
            crossSectionArea, momentOfInertia, sectionModulus,
            momentOfInertia, sectionModulus);
    }
}
