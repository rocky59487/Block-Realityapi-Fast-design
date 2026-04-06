package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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
     * @param mi        體素 i 的材料（null 表示空氣）
     * @param mj        體素 j 的材料（null 表示空氣）
     * @param dir       從 i 到 j 的方向
     * @param armI      體素 i 的水平力臂（到最近錨點的水平 Manhattan 距離）
     * @param armJ      體素 j 的水平力臂
     * @param windVec   全域風向向量（可為 null，不施加風壓偏置）
     * @return 傳導率 σ_ij（≥ 0）
     */
    public static float sigma(RMaterial mi, RMaterial mj, Direction dir,
                               int armI, int armJ, @Nullable Vec3 windVec) {
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
        float decay = 1.0f; // Deprecated MOMENT_BETA, removing decay impact.

        // ─── v2.1: 上風向傳導率偏置（Upwind Wind Conductivity）───
        // 取代舊 WIND_CONDUCTIVITY_DECAY 硬截斷，改用一階迎風格式。
        // 上風向（體素面朝向風源）→ 傳導率增強：σ' = σ × (1 + k_wind)
        // 下風向（背風面）→ 傳導率衰減：σ' = σ / (1 + k_wind)
        // 參考：Anderson 2010 Potential Flow Theory §5；k_wind=0.30f ≈ Eurocode 1 Cp 比值
        if (windVec != null) {
            float dx = (float) windVec.x;
            float dz = (float) windVec.z;
            // 方向向量在水平面的投影內積（忽略 Y 分量，風壓只影響水平方向）
            float dot = dir.getStepX() * dx + dir.getStepZ() * dz;
            if (dot > 0.0f) {
                sigmaH *= (1.0f + WIND_UPWIND_FACTOR);   // 順風方向：增強傳導
            } else if (dot < 0.0f) {
                sigmaH /= (1.0f + WIND_UPWIND_FACTOR);   // 逆風方向：衰減傳導
            }
            // dot == 0.0f（側風）：不改變
        }

        float result = sigmaH * decay;
        // H5-fix: NaN/Inf 防護
        if (Float.isNaN(result) || Float.isInfinite(result)) return 0.0f;
        return result;
    }

    /**
     * 計算傳導率（不含距離衰減、不含風壓偏置，用於無力臂資訊的場景）。
     */
    public static float sigmaNoDecay(RMaterial mi, RMaterial mj, Direction dir) {
        return sigma(mi, mj, dir, 0, 0, null);
    }

    /**
     * 向下相容：不含風壓偏置的舊 API（呼叫五參數版本）。
     */
    public static float sigma(RMaterial mi, RMaterial mj, Direction dir,
                               int armI, int armJ) {
        return sigma(mi, mj, dir, armI, armJ, null);
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
