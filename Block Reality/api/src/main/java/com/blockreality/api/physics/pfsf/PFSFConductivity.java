package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.Direction;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 傳導率計算器。
 *
 * σ_ij 決定應力如何在體素之間流動：
 * <ul>
 *   <li>垂直邊：取兩側較弱材料的 Rcomp（荷載沿重力傳遞）</li>
 *   <li>水平邊：加上抗拉修正 + 距離衰減（力矩放大效應）</li>
 *   <li>空氣邊：σ = 0（絕緣）</li>
 * </ul>
 *
 * 參考：PFSF 手冊 §5.3
 */
public final class PFSFConductivity {

    private PFSFConductivity() {}

    /**
     * 計算兩相鄰體素之間的傳導率 σ_ij。
     *
     * @param mi   體素 i 的材料（null 表示空氣）
     * @param mj   體素 j 的材料（null 表示空氣）
     * @param dir  從 i 到 j 的方向
     * @param armI 體素 i 的水平力臂（到最近錨點的水平 Manhattan 距離）
     * @param armJ 體素 j 的水平力臂
     * @return 傳導率 σ_ij（≥ 0）
     */
    public static float sigma(RMaterial mi, RMaterial mj, Direction dir,
                               int armI, int armJ) {
        // 空氣邊 = 絕緣
        if (mi == null || mj == null) return 0.0f;

        // 不可破壞材料視為極高傳導（接地效果）
        double rcompI = mi.isIndestructible() ? 1e6 : mi.getRcomp();
        double rcompJ = mj.isIndestructible() ? 1e6 : mj.getRcomp();

        // 基礎傳導：取兩側較弱材料的抗壓強度（短板效應）
        float base = (float) Math.min(rcompI, rcompJ);
        if (base <= 0) return 0.0f;

        // 垂直邊（UP / DOWN）：全傳導，不受力臂影響
        if (dir == Direction.UP || dir == Direction.DOWN) {
            return base;
        }

        // ─── 水平邊計算 ───

        // 1. 抗拉修正：水平傳遞受抗拉強度限制
        double rtensI = mi.isIndestructible() ? 1e6 : mi.getRtens();
        double rtensJ = mj.isIndestructible() ? 1e6 : mj.getRtens();
        double avgRtens = (rtensI + rtensJ) / 2.0;
        float tensionRatio = (float) Math.min(1.0, avgRtens / Math.max(base, 1.0));
        float sigmaH = base * tensionRatio;

        // 2. 距離衰減（§2.4 力矩修正）：力臂越大 → 水平傳導率越低
        //    迫使遠端荷載回流至垂直支撐路徑
        double avgArm = (armI + armJ) / 2.0;
        float decay = (float) (1.0 / (1.0 + MOMENT_BETA * avgArm));

        float result = sigmaH * decay;
        // H5-fix: NaN/Inf 防護
        if (Float.isNaN(result) || Float.isInfinite(result)) return 0.0f;
        return result;
    }

    /**
     * 計算傳導率（不含距離衰減，用於無力臂資訊的場景）。
     */
    public static float sigmaNoDecay(RMaterial mi, RMaterial mj, Direction dir) {
        return sigma(mi, mj, dir, 0, 0);
    }

    /**
     * 將 Minecraft Direction 轉換為 conductivity 陣列中的方向索引。
     * 對應 GPU shader 中的 dir: 0=-X 1=+X 2=-Y 3=+Y 4=-Z 5=+Z
     */
    public static int dirToIndex(Direction dir) {
        return switch (dir) {
            case WEST -> DIR_NEG_X;   // -X
            case EAST -> DIR_POS_X;   // +X
            case DOWN -> DIR_NEG_Y;   // -Y
            case UP -> DIR_POS_Y;     // +Y
            case NORTH -> DIR_NEG_Z;  // -Z
            case SOUTH -> DIR_POS_Z;  // +Z
        };
    }
}
