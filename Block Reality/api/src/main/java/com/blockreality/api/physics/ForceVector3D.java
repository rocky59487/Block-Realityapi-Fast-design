package com.blockreality.api.physics;

import javax.annotation.concurrent.Immutable;

/**
 * 三維力/力矩向量 — 結構力學完整內力描述。
 *
 * <h3>座標系</h3>
 * 採用 Minecraft 右手座標系：
 * <ul>
 *   <li>X — 東（East, +X）/ 西（West, -X）</li>
 *   <li>Y — 上（Up, +Y）/ 下（Down, -Y）— 重力方向 = -Y</li>
 *   <li>Z — 南（South, +Z）/ 北（North, -Z）</li>
 * </ul>
 *
 * <h3>六個自由度</h3>
 * <ul>
 *   <li>Fx, Fy, Fz — 力分量 (N)</li>
 *   <li>Mx, My, Mz — 力矩分量 (N·m)
 *     <ul>
 *       <li>Mx — 繞 X 軸的力矩（前後傾斜）</li>
 *       <li>My — 繞 Y 軸的力矩（水平扭轉）</li>
 *       <li>Mz — 繞 Z 軸的力矩（左右傾斜）</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>符號慣例</h3>
 * <ul>
 *   <li>Fy 正值 = 向上（壓力為負、重力為負）</li>
 *   <li>力矩遵循右手定則</li>
 * </ul>
 *
 * <h3>不可變性</h3>
 * 此 record 為不可變值物件，所有運算返回新實例。
 * 適合多執行緒共用、作為 Map key、快取等場景。
 *
 * @param fx X 方向力 (N)
 * @param fy Y 方向力 (N) — 正值向上，重力為負
 * @param fz Z 方向力 (N)
 * @param mx 繞 X 軸力矩 (N·m)
 * @param my 繞 Y 軸力矩 (N·m) — 水平扭矩
 * @param mz 繞 Z 軸力矩 (N·m)
 *
 * @see LoadCombination
 * @see NodeState
 * @since 1.1.0
 */
@Immutable
public record ForceVector3D(
    double fx, double fy, double fz,
    double mx, double my, double mz
) {

    /** 零向量 — 無力、無力矩。 */
    public static final ForceVector3D ZERO = new ForceVector3D(0, 0, 0, 0, 0, 0);

    // ─── 工廠方法 ──────────────────────────────────────────────────────

    /**
     * 僅力的向量（無力矩）。
     */
    public static ForceVector3D ofForce(double fx, double fy, double fz) {
        return new ForceVector3D(fx, fy, fz, 0, 0, 0);
    }

    /**
     * 僅力矩的向量（無力）。
     */
    public static ForceVector3D ofMoment(double mx, double my, double mz) {
        return new ForceVector3D(0, 0, 0, mx, my, mz);
    }

    /**
     * 純重力向量（-Y 方向）。
     * @param weight 重力大小 (N)，正值
     * @return 向量 (0, -weight, 0, 0, 0, 0)
     */
    public static ForceVector3D gravity(double weight) {
        return new ForceVector3D(0, -weight, 0, 0, 0, 0);
    }

    /**
     * 純水平力（風荷載典型方向）。
     * @param dirX X 方向分量
     * @param dirZ Z 方向分量
     * @param magnitude 力大小 (N)
     */
    public static ForceVector3D horizontal(double dirX, double dirZ, double magnitude) {
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1e-10) return ZERO;
        double scale = magnitude / len;
        return new ForceVector3D(dirX * scale, 0, dirZ * scale, 0, 0, 0);
    }

    // ─── 向量運算 ──────────────────────────────────────────────────────

    /**
     * 向量加法。
     */
    public ForceVector3D add(ForceVector3D other) {
        return new ForceVector3D(
            fx + other.fx, fy + other.fy, fz + other.fz,
            mx + other.mx, my + other.my, mz + other.mz
        );
    }

    /**
     * 向量減法。
     */
    public ForceVector3D subtract(ForceVector3D other) {
        return new ForceVector3D(
            fx - other.fx, fy - other.fy, fz - other.fz,
            mx - other.mx, my - other.my, mz - other.mz
        );
    }

    /**
     * 純量乘法。
     */
    public ForceVector3D scale(double factor) {
        return new ForceVector3D(
            fx * factor, fy * factor, fz * factor,
            mx * factor, my * factor, mz * factor
        );
    }

    /**
     * 取反向量。
     */
    public ForceVector3D negate() {
        return new ForceVector3D(-fx, -fy, -fz, -mx, -my, -mz);
    }

    // ─── 分量存取 ──────────────────────────────────────────────────────

    /**
     * 力的合成大小（L2 範數）。
     * @return |F| = sqrt(Fx² + Fy² + Fz²)
     */
    public double forceMagnitude() {
        return Math.sqrt(fx * fx + fy * fy + fz * fz);
    }

    /**
     * 力矩的合成大小。
     * @return |M| = sqrt(Mx² + My² + Mz²)
     */
    public double momentMagnitude() {
        return Math.sqrt(mx * mx + my * my + mz * mz);
    }

    /**
     * 水平力分量大小（X-Z 平面）。
     * @return sqrt(Fx² + Fz²)
     */
    public double horizontalForceMagnitude() {
        return Math.sqrt(fx * fx + fz * fz);
    }

    /**
     * 垂直力分量（正值向上）。
     * @return Fy
     */
    public double verticalForce() {
        return fy;
    }

    /**
     * 傾覆力矩大小（繞 X 軸和 Z 軸的合力矩）。
     * 用於穩定性檢查：傾覆力矩 > 抵抗力矩 → 傾覆。
     * @return sqrt(Mx² + Mz²)
     */
    public double overturningMoment() {
        return Math.sqrt(mx * mx + mz * mz);
    }

    /**
     * 扭矩大小（繞 Y 軸）。
     * @return |My|
     */
    public double torsion() {
        return Math.abs(my);
    }

    // ─── 力矩計算 ──────────────────────────────────────────────────────

    /**
     * 計算此力在給定力臂下產生的力矩（M = r × F）。
     *
     * <p>使用向量外積：
     * <pre>
     * M = r × F = (ry·Fz − rz·Fy, rz·Fx − rx·Fz, rx·Fy − ry·Fx)
     * </pre>
     *
     * @param armX 力臂 X 分量 (m)
     * @param armY 力臂 Y 分量 (m)
     * @param armZ 力臂 Z 分量 (m)
     * @return 力矩向量（僅力矩分量，力分量歸零）
     */
    public ForceVector3D momentAbout(double armX, double armY, double armZ) {
        double newMx = armY * fz - armZ * fy;
        double newMy = armZ * fx - armX * fz;
        double newMz = armX * fy - armY * fx;
        return ForceVector3D.ofMoment(newMx, newMy, newMz);
    }

    // ─── 工程判定 ──────────────────────────────────────────────────────

    /**
     * 是否為零向量（所有分量均為零或極小）。
     */
    public boolean isZero() {
        return forceMagnitude() < 1e-10 && momentMagnitude() < 1e-10;
    }

    /**
     * 是否僅有垂直力分量（傳統標量分析的退化情況）。
     */
    public boolean isVerticalOnly() {
        return Math.abs(fx) < 1e-10 && Math.abs(fz) < 1e-10
            && momentMagnitude() < 1e-10;
    }

    @Override
    public String toString() {
        return String.format(
            "F(%.1f, %.1f, %.1f)N  M(%.1f, %.1f, %.1f)N·m",
            fx, fy, fz, mx, my, mz
        );
    }
}
