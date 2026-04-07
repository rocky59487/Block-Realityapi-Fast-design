package com.blockreality.api.physics.coupling;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.physics.em.EmEngine;
import com.blockreality.api.physics.solver.DiffusionRegion;
import com.blockreality.api.physics.thermal.ThermalConstants;
import com.blockreality.api.physics.thermal.ThermalEngine;
import com.blockreality.api.physics.wind.WindConstants;
import com.blockreality.api.physics.wind.WindEngine;
import com.blockreality.api.spi.IElectromagneticManager;
import com.blockreality.api.spi.IThermalManager;
import com.blockreality.api.spi.IWindManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 跨域連動協調器 — 每 tick 在所有獨立域求解完後執行。
 *
 * <h3>耦合矩陣</h3>
 * <pre>
 *          → Structure  Fluid  Wind  Thermal  EM
 * Wind       ✓(風壓)    —      —     ✓(對流)   ✓(風力發電)
 * Thermal    ✓(熱應力)  ✓(相變) ✓(浮力) —       ✓(熱電)
 * EM         ✓(雷擊)    —      —     ✓(Joule)  —
 * </pre>
 *
 * <p>每個耦合方向是一個輕量函數，讀取域 A 輸出、注入域 B 輸入。
 * 設計為 1-tick 延遲（讀上一 tick 結果，寫入下一 tick 源項）。
 */
public class MultiDomainCoupler {

    private static final Logger LOGGER = LogManager.getLogger("BR-MultiCoupler");

    /**
     * 每 tick 在所有域求解後呼叫。
     * 按耦合強度排序：EM→Thermal（Joule 加熱） > Wind→Thermal（對流增強）
     */
    public static void tick() {
        coupleEmToThermal();
        coupleWindToThermal();
        // 未來擴展：
        // coupleThermalToFluid();   // 相變（冰/蒸氣）
        // coupleWindToStructure();  // 風壓 → PFSF source
        // coupleThermalToStructure(); // 熱應力 → PFSF source
    }

    /**
     * EM → Thermal：Joule 加熱（P = J²/σ → 熱源）。
     *
     * <p>閃電擊中導體 → 局部高溫 → 可能引發火災。
     * 電流通過電阻 → 持續加熱 → 溫度累積。
     */
    private static void coupleEmToThermal() {
        if (!BRConfig.isEmEnabled() || !BRConfig.isThermalEnabled()) return;

        IElectromagneticManager em = ModuleRegistry.getEmManager();
        IThermalManager thermal = ModuleRegistry.getThermalManager();
        if (em == null || thermal == null) return;

        if (em instanceof EmEngine emEngine && thermal instanceof ThermalEngine thermalEngine) {
            for (DiffusionRegion emRegion : emEngine.getRegistry().getActiveRegions()) {
                float[] phi = emRegion.getPhi();
                float[] sigma = emRegion.getConductivity();
                byte[] type = emRegion.getType();
                int sx = emRegion.getSizeX(), sy = emRegion.getSizeY(), sz = emRegion.getSizeZ();

                for (int z = 1; z < sz - 1; z++) {
                    for (int y = 1; y < sy - 1; y++) {
                        for (int x = 1; x < sx - 1; x++) {
                            int idx = emRegion.flatIndex(x, y, z);
                            if (type[idx] != DiffusionRegion.TYPE_ACTIVE) continue;
                            if (sigma[idx] < 1e-10f) continue; // 絕緣體，無電流

                            // |E| ≈ |∇φ|（中心差分估算）
                            float dPhiDx = (phi[emRegion.flatIndex(x+1,y,z)] - phi[emRegion.flatIndex(x-1,y,z)]) * 0.5f;
                            float dPhiDy = (phi[emRegion.flatIndex(x,y+1,z)] - phi[emRegion.flatIndex(x,y-1,z)]) * 0.5f;
                            float dPhiDz = (phi[emRegion.flatIndex(x,y,z+1)] - phi[emRegion.flatIndex(x,y,z-1)]) * 0.5f;
                            float eMag = (float) Math.sqrt(dPhiDx*dPhiDx + dPhiDy*dPhiDy + dPhiDz*dPhiDz);

                            // J = σ × |E|, P = J²/σ = σ × E²
                            float jouleHeating = sigma[idx] * eMag * eMag;
                            if (jouleHeating > 100f) { // > 100 W/m³ 才注入
                                BlockPos pos = new BlockPos(
                                    x + emRegion.getOriginX(),
                                    y + emRegion.getOriginY(),
                                    z + emRegion.getOriginZ()
                                );
                                float currentTemp = thermal.getTemperatureAt(pos);
                                float tempIncrease = jouleHeating * 0.001f; // 縮放至遊戲時間尺度
                                thermalEngine.setHeatSource(pos, currentTemp + tempIncrease);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Wind → Thermal：強制對流增強熱傳導。
     *
     * <p>風速越大，體素的等效熱傳導率越高（牛頓冷卻定律）。
     * h_conv = h_natural + C × v^0.8（簡化的 Dittus-Boelter 關聯式）。
     */
    private static void coupleWindToThermal() {
        if (!BRConfig.isWindEnabled() || !BRConfig.isThermalEnabled()) return;

        IWindManager wind = ModuleRegistry.getWindManager();
        IThermalManager thermal = ModuleRegistry.getThermalManager();
        if (wind == null || thermal == null) return;

        if (thermal instanceof ThermalEngine thermalEngine) {
            for (DiffusionRegion thermalRegion : thermalEngine.getActiveRegions()) {
                float[] sigma = thermalRegion.getConductivity();
                byte[] type = thermalRegion.getType();

                for (int i = 0; i < thermalRegion.getTotalVoxels(); i++) {
                    if (type[i] != DiffusionRegion.TYPE_ACTIVE) continue;

                    // 從風場查詢此位置的風速
                    int sx = thermalRegion.getSizeX();
                    int sy = thermalRegion.getSizeY();
                    int x = i % sx;
                    int y = (i / sx) % sy;
                    int z = i / (sx * sy);
                    BlockPos pos = new BlockPos(
                        x + thermalRegion.getOriginX(),
                        y + thermalRegion.getOriginY(),
                        z + thermalRegion.getOriginZ()
                    );
                    float windSpeed = wind.getWindSpeedAt(pos);

                    // 增強熱傳導率：σ_eff = σ_base × (1 + C × v^0.8)
                    if (windSpeed > 0.5f) {
                        float enhancement = 1.0f + 0.1f * (float) Math.pow(windSpeed, 0.8);
                        sigma[i] *= enhancement;
                    }
                }
                thermalRegion.markDirty();
            }
        }
    }

    // ─── 未來擴展（留空實作） ───

    // private static void coupleThermalToFluid() { /* 相變：T>100→蒸發, T<0→結冰 */ }
    // private static void coupleWindToStructure() { /* 風壓 → PFSF source */ }
    // private static void coupleThermalToStructure() { /* 熱應力 → PFSF source */ }
}
