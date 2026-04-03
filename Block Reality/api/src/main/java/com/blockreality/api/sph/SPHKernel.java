package com.blockreality.api.sph;

/**
 * SPH 核心函數（Kernel Functions）— Monaghan (1992) Cubic Spline.
 *
 * <p>核心函數 W(r, h) 是 SPH 方法的基礎，決定粒子間如何平滑地分享物理量。
 * 本類別實作最常用的三維立方樣條核心及其梯度。
 *
 * <h3>立方樣條核心（3D 正規化）</h3>
 * <pre>
 *   q = r / h
 *   σ₃ = 1 / (π h³)      （3D 正規化常數）
 *
 *   W(q) = σ₃ × { 1 - 1.5q² + 0.75q³    if 0 ≤ q ≤ 1
 *                { 0.25(2 - q)³            if 1 < q ≤ 2
 *                { 0                        if q > 2
 * </pre>
 *
 * <h3>性質</h3>
 * <ul>
 *   <li>正規化：∫W(r,h) dV = 1（在 3D 中）</li>
 *   <li>對稱性：W(r,h) = W(-r,h)</li>
 *   <li>緊支撐：W = 0 when r > 2h（有限影響半徑）</li>
 *   <li>Delta 性質：lim_{h→0} W(r,h) = δ(r)</li>
 * </ul>
 *
 * <h3>參考文獻</h3>
 * <ul>
 *   <li>Monaghan, J.J. (1992). "Smoothed Particle Hydrodynamics".
 *       Annual Review of Astronomy and Astrophysics, 30, 543-574.</li>
 *   <li>Price, D.J. (2012). "Smoothed particle hydrodynamics and
 *       magnetohydrodynamics". J. Comput. Phys., 231(3), 759-794.</li>
 * </ul>
 *
 * @see SPHStressEngine
 * @see SpatialHashGrid
 */
public final class SPHKernel {

    private SPHKernel() {} // 工具類別，禁止實例化

    /**
     * 三維立方樣條核心 W(r, h)。
     *
     * @param r 粒子間距離（≥ 0）
     * @param h 平滑長度（smoothing length, > 0）
     * @return 核心值 W(r, h)，保證 ≥ 0
     */
    public static double cubicSpline(double r, double h) {
        if (h <= 0) throw new IllegalArgumentException("Smoothing length h must be > 0, got " + h);
        double q = r / h;
        double sigma = 1.0 / (Math.PI * h * h * h); // 3D 正規化常數

        if (q <= 1.0) {
            // 內區：1 - 1.5q² + 0.75q³
            return sigma * (1.0 - 1.5 * q * q + 0.75 * q * q * q);
        } else if (q <= 2.0) {
            // 外區：0.25(2 - q)³
            double t = 2.0 - q;
            return sigma * 0.25 * t * t * t;
        }
        return 0.0; // 緊支撐：超過 2h 為零
    }

    /**
     * 立方樣條核心的梯度模量 dW/dr（純量，沿徑向方向）。
     *
     * <p>用於 SPH 動量方程中的壓力梯度計算：
     * <pre>
     *   fᵢ = -Σⱼ mⱼ (Pᵢ/ρᵢ² + Pⱼ/ρⱼ²) ∇W(rᵢⱼ, h)
     *   ∇W = (dW/dr) × (rᵢⱼ / |rᵢⱼ|)
     * </pre>
     *
     * @param r 粒子間距離（≥ 0）
     * @param h 平滑長度（> 0）
     * @return dW/dr 值（通常 ≤ 0，因核心單調遞減）
     */
    public static double cubicSplineGradient(double r, double h) {
        if (h <= 0) throw new IllegalArgumentException("Smoothing length h must be > 0, got " + h);
        double q = r / h;
        // 梯度的 σ 多除一個 h（鏈式法則 dW/dr = (1/h) dW/dq）
        double sigma = 1.0 / (Math.PI * h * h * h * h);

        if (q < 1e-10) return 0.0; // 避免 r=0 的奇異點

        if (q <= 1.0) {
            // dW/dq = -3q + 2.25q²
            return sigma * (-3.0 * q + 2.25 * q * q);
        } else if (q <= 2.0) {
            // dW/dq = -0.75(2-q)²
            double t = 2.0 - q;
            return sigma * (-0.75 * t * t);
        }
        return 0.0;
    }

    /**
     * 核心支撐半徑 = 2h。
     * 超過此距離的粒子互不影響。
     *
     * @param h 平滑長度
     * @return 支撐半徑
     */
    public static double supportRadius(double h) {
        return 2.0 * h;
    }
}
