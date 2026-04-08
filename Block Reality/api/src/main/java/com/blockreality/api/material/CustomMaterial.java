package com.blockreality.api.material;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 自訂材料 — v3fix 合規的 Builder Pattern 實現。
 *
 * 特色：
 *   - Builder Pattern 提供流暢的 API：CustomMaterial.builder("my_mat")
 *       .rcomp(50).rtens(25).rshear(15).density(2400).build()
 *   - 私有建構子，確保只能透過 Builder 建立
 *   - 完整的參數驗證（非負數、合理範圍檢查）
 *   - 不可變（immutable），線程安全
 *
 * 用途：
 *   - 取代 DynamicMaterial.ofCustom() 的更優雅方式
 *   - CLI 指令 /br_material create 使用
 *   - 動態材料融合計算結果的包裝
 */
public class CustomMaterial implements RMaterial {

    private static final Logger LOGGER = LogManager.getLogger("BR-Material");

    private final String id;
    private final double rcomp;
    private final double rtens;
    private final double rshear;
    private final double density;

    /**
     * 私有建構子 — 只允許透過 Builder 建立。
     */
    private CustomMaterial(String id, double rcomp, double rtens, double rshear, double density) {
        this.id = id;
        this.rcomp = rcomp;
        this.rtens = rtens;
        this.rshear = rshear;
        this.density = density;
    }

    // ═══════════════════════════════════════════════════════
    //  RMaterial 介面實現
    // ═══════════════════════════════════════════════════════

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
        return id;
    }

    // ═══════════════════════════════════════════════════════
    //  Builder 工廠方法
    // ═══════════════════════════════════════════════════════

    /**
     * 建立新的 Builder 實例。
     *
     * @param id 材料識別 ID（必須）
     * @return Builder 實例
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    // ═══════════════════════════════════════════════════════
    //  Builder 內部類
    // ═══════════════════════════════════════════════════════

    /**
     * 鏈式 Builder — 提供流暢 API 設置材料參數。
     */
    public static class Builder {
        private final String id;
        private double rcomp = 0;
        private double rtens = 0;
        private double rshear = 0;
        private double density = 1000; // 預設密度 (kg/m³)

        /**
         * 建立 Builder。
         *
         * @param id 材料識別 ID
         */
        public Builder(String id) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("Material ID cannot be null or empty");
            }
            this.id = id;
        }

        /**
         * 設置抗壓強度 (MPa)。
         *
         * @param rcomp 抗壓強度
         * @return this（鏈式呼叫）
         */
        public Builder rcomp(double rcomp) {
            if (rcomp < 0) {
                throw new IllegalArgumentException("rcomp cannot be negative: " + rcomp);
            }
            this.rcomp = rcomp;
            return this;
        }

        /**
         * 設置抗拉強度 (MPa)。
         *
         * @param rtens 抗拉強度
         * @return this（鏈式呼叫）
         */
        public Builder rtens(double rtens) {
            if (rtens < 0) {
                throw new IllegalArgumentException("rtens cannot be negative: " + rtens);
            }
            this.rtens = rtens;
            return this;
        }

        /**
         * 設置抗剪強度 (MPa)。
         *
         * @param rshear 抗剪強度
         * @return this（鏈式呼叫）
         */
        public Builder rshear(double rshear) {
            if (rshear < 0) {
                throw new IllegalArgumentException("rshear cannot be negative: " + rshear);
            }
            this.rshear = rshear;
            return this;
        }

        /**
         * 設置密度 (kg/m³)。
         *
         * @param density 密度
         * @return this（鏈式呼叫）
         */
        public Builder density(double density) {
            if (density <= 0) {
                throw new IllegalArgumentException("density must be positive: " + density);
            }
            this.density = density;
            return this;
        }

        /**
         * 驗證並建立 CustomMaterial 實例。
         *
         * 驗證規則：
         *   - ID 非空（建構時已檢查）
         *   - 所有強度非負（setter 已檢查）
         *   - 密度正數（setter 已檢查）
         *   - 至少有一項強度 > 0（否則材料無意義）
         *
         * @return 新的 CustomMaterial 實例
         * @throws IllegalStateException 若驗證失敗
         */
        // ═══════════════════════════════════════════════════════
        //  物理合理性上限 — WARN + clamp（不拋例外）
        // ═══════════════════════════════════════════════════════
        private static final double MAX_RCOMP   = 10000.0;  // MPa (ultra-high-strength concrete)
        private static final double MAX_RTENS   = 5000.0;   // MPa
        private static final double MAX_RSHEAR  = 5000.0;   // MPa
        private static final double MIN_DENSITY = 100.0;    // kg/m³ (aerogel)
        private static final double MAX_DENSITY = 25000.0;  // kg/m³ (osmium)

        public CustomMaterial build() {
            // 驗證：至少有一項強度定義
            if (rcomp <= 0 && rtens <= 0 && rshear <= 0) {
                throw new IllegalStateException(
                    "CustomMaterial must have at least one strength parameter > 0 " +
                    "(rcomp=" + rcomp + ", rtens=" + rtens + ", rshear=" + rshear + ")"
                );
            }

            // 物理合理性檢查：超出工程合理範圍時 WARN + clamp
            if (rcomp > MAX_RCOMP) {
                LOGGER.warn("[Material] Rcomp {} exceeds physical maximum {}, clamping", rcomp, MAX_RCOMP);
                rcomp = MAX_RCOMP;
            }
            if (rtens > MAX_RTENS) {
                LOGGER.warn("[Material] Rtens {} exceeds physical maximum {}, clamping", rtens, MAX_RTENS);
                rtens = MAX_RTENS;
            }
            if (rshear > MAX_RSHEAR) {
                LOGGER.warn("[Material] Rshear {} exceeds physical maximum {}, clamping", rshear, MAX_RSHEAR);
                rshear = MAX_RSHEAR;
            }
            if (density < MIN_DENSITY) {
                LOGGER.warn("[Material] Density {} below physical minimum {}, clamping", density, MIN_DENSITY);
                density = MIN_DENSITY;
            }
            if (density > MAX_DENSITY) {
                LOGGER.warn("[Material] Density {} exceeds physical maximum {}, clamping", density, MAX_DENSITY);
                density = MAX_DENSITY;
            }

            return new CustomMaterial(id, rcomp, rtens, rshear, density);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Object 方法
    // ═══════════════════════════════════════════════════════

    @Override
    public String toString() {
        return String.format(
            "CustomMaterial{id='%s', rcomp=%.1f, rtens=%.1f, rshear=%.1f, density=%.1f}",
            id, rcomp, rtens, rshear, density
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomMaterial that)) return false;

        if (Double.compare(that.rcomp, rcomp) != 0) return false;
        if (Double.compare(that.rtens, rtens) != 0) return false;
        if (Double.compare(that.rshear, rshear) != 0) return false;
        if (Double.compare(that.density, density) != 0) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = id.hashCode();
        temp = Double.doubleToLongBits(rcomp);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(rtens);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(rshear);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(density);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}
