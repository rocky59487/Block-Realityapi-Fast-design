package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 天氣引擎 — 統一管理所有天氣子系統的中央狀態機。
 *
 * 天氣類型：
 *   - CLEAR:   無降水，正常渲染
 *   - RAIN:    雨滴 + 水花 + 濕潤 PBR（反射率提升、粗糙度降低）
 *   - SNOW:    雪花 + 積雪漸變（法線偏移覆蓋白色）
 *   - STORM:   暴雨 + 閃電 + 螢幕閃光
 *   - AURORA:  極光帷幕（高緯度夜晚自動觸發）
 *
 * 設計原則：
 *   - 天氣轉場平滑過渡（intensity 0→1 線性插值）
 *   - 每種天氣子系統獨立 init/tick/render/cleanup
 *   - 濕潤度係數全域共享（GBuffer material pass 讀取）
 *   - 與大氣引擎聯動（雲量影響降水概率）
 *
 * @author Block Reality Team
 * @version 1.0
  * @deprecated Since 2.0, superseded by Vulkan RT + Voxy LOD pipeline on capable hardware.
  *             Still used as GL fallback when RT is unavailable; do not remove until
  *             a fully equivalent GL replacement is provided.
*/
@Deprecated(since = "2.0")
@OnlyIn(Dist.CLIENT)
public class BRWeatherEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRWeatherEngine.class);

    // ========================= 天氣狀態 =========================

    public enum WeatherType {
        CLEAR, RAIN, SNOW, STORM, AURORA
    }

    private static volatile WeatherType currentWeather = WeatherType.CLEAR;
    private static volatile WeatherType targetWeather = WeatherType.CLEAR;

    /** 當前天氣強度（0.0=無, 1.0=最大） */
    private static volatile float weatherIntensity = 0.0f;

    /** 目標天氣強度 */
    private static volatile float targetIntensity = 0.0f;

    /** 天氣過渡速度（每 tick） */
    private static final float TRANSITION_SPEED = 0.005f;

    /** 全域濕潤度（0.0=乾燥, 1.0=完全濕潤）— 影響 PBR 材質 */
    private static volatile float globalWetness = 0.0f;

    /** 積雪覆蓋度（0.0=無雪, 1.0=完全覆蓋） */
    private static volatile float snowCoverage = 0.0f;

    /** 閃電冷卻計時器（tick） */
    private static volatile int lightningCooldown = 0;

    /** 閃電螢幕閃光強度（快速衰減） */
    private static volatile float lightningFlash = 0.0f;

    private static volatile boolean initialized = false;

    // ========================= 初始化 / 清理 =========================

    public static void init() {
        if (initialized) return;

        BRRainRenderer.init();
        BRSnowRenderer.init();
        BRLightningRenderer.init();
        // BRAuroraRenderer.init() omitted — replaced by Vulkan RT pipeline in 2.0

        currentWeather = WeatherType.CLEAR;
        targetWeather = WeatherType.CLEAR;
        weatherIntensity = 0.0f;
        targetIntensity = 0.0f;
        globalWetness = 0.0f;
        snowCoverage = 0.0f;

        initialized = true;
        LOGGER.info("[BRWeatherEngine] 天氣系統初始化完成");
    }

    public static void cleanup() {
        if (!initialized) return;
        // BRAuroraRenderer.cleanup() omitted — replaced by Vulkan RT pipeline in 2.0
        BRLightningRenderer.cleanup();
        BRSnowRenderer.cleanup();
        BRRainRenderer.cleanup();
        initialized = false;
    }

    // ========================= 天氣控制 API =========================

    /** 設定目標天氣（會平滑過渡） */
    public static void setWeather(WeatherType type, float intensity) {
        targetWeather = type;
        targetIntensity = Math.max(0.0f, Math.min(1.0f, intensity));
    }

    /** 立即切換天氣（無過渡） */
    public static void forceWeather(WeatherType type, float intensity) {
        currentWeather = type;
        targetWeather = type;
        weatherIntensity = Math.max(0.0f, Math.min(1.0f, intensity));
        targetIntensity = weatherIntensity;
    }

    public static WeatherType getCurrentWeather() { return currentWeather; }
    public static float getWeatherIntensity() { return weatherIntensity; }
    public static float getGlobalWetness() { return globalWetness; }
    public static float getSnowCoverage() { return snowCoverage; }
    public static float getLightningFlash() { return lightningFlash; }

    // ========================= 每幀更新 =========================

    /**
     * 每 tick 更新天氣狀態機。
     * @param deltaTime 幀間隔（秒）
     * @param gameTime  遊戲總時間
     * @param playerY   玩家 Y 座標（影響降水可見性）
     */
    public static void tick(float deltaTime, float gameTime, float playerY) {
        if (!initialized || !BRRenderConfig.WEATHER_ENABLED) return;

        // ── 天氣過渡 ──
        if (currentWeather != targetWeather) {
            // 先衰減當前天氣
            weatherIntensity -= TRANSITION_SPEED;
            if (weatherIntensity <= 0.0f) {
                weatherIntensity = 0.0f;
                currentWeather = targetWeather;
            }
        } else {
            // 趨近目標強度
            if (weatherIntensity < targetIntensity) {
                weatherIntensity = Math.min(weatherIntensity + TRANSITION_SPEED, targetIntensity);
            } else if (weatherIntensity > targetIntensity) {
                weatherIntensity = Math.max(weatherIntensity - TRANSITION_SPEED, targetIntensity);
            }
        }

        // ── 濕潤度更新 ──
        if (currentWeather == WeatherType.RAIN || currentWeather == WeatherType.STORM) {
            // 下雨/暴風雨 → 濕潤度逐漸上升
            globalWetness = Math.min(1.0f, globalWetness + weatherIntensity * 0.002f);
        } else {
            // 其他天氣 → 濕潤度逐漸乾燥
            globalWetness = Math.max(0.0f, globalWetness - 0.0005f);
        }

        // ── 積雪覆蓋 ──
        if (currentWeather == WeatherType.SNOW) {
            snowCoverage = Math.min(1.0f, snowCoverage + weatherIntensity * 0.001f);
        } else {
            // 非下雪天氣：積雪緩慢融化
            snowCoverage = Math.max(0.0f, snowCoverage - 0.0002f);
        }

        // ── 閃電邏輯 ──
        if (currentWeather == WeatherType.STORM && weatherIntensity > 0.5f) {
            lightningCooldown--;
            if (lightningCooldown <= 0) {
                // 隨機觸發閃電
                float chance = weatherIntensity * 0.02f; // 強度越高越頻繁
                if (Math.random() < chance) {
                    triggerLightning(gameTime);
                    // 下次閃電冷卻 60~200 tick
                    lightningCooldown = 60 + (int)(Math.random() * 140);
                }
            }
        }
        // 閃光衰減
        lightningFlash = Math.max(0.0f, lightningFlash - deltaTime * 4.0f);

        // ── 子系統 tick ──
        if (currentWeather == WeatherType.RAIN || currentWeather == WeatherType.STORM) {
            BRRainRenderer.tick(deltaTime, weatherIntensity, playerY);
        }
        if (currentWeather == WeatherType.SNOW) {
            BRSnowRenderer.tick(deltaTime, weatherIntensity, playerY);
        }
        if (currentWeather == WeatherType.STORM) {
            BRLightningRenderer.tick(deltaTime, gameTime);
        }
        // AURORA tick omitted — BRAuroraRenderer replaced by Vulkan RT pipeline in 2.0
    }

    // ========================= 渲染入口 =========================

    /**
     * 渲染天氣效果（在 composite chain 中呼叫）。
     * @param gameTime  遊戲時間
     * @param playerY   玩家 Y 座標
     */
    public static void render(float gameTime, float playerY) {
        if (!initialized || !BRRenderConfig.WEATHER_ENABLED) return;
        if (weatherIntensity <= 0.001f && lightningFlash <= 0.001f) return;

        if (currentWeather == WeatherType.RAIN || currentWeather == WeatherType.STORM) {
            BRRainRenderer.render(weatherIntensity, gameTime);
        }
        if (currentWeather == WeatherType.SNOW) {
            BRSnowRenderer.render(weatherIntensity, gameTime);
        }
        if (currentWeather == WeatherType.STORM) {
            BRLightningRenderer.render(gameTime);
        }
        // AURORA render omitted — BRAuroraRenderer replaced by Vulkan RT pipeline in 2.0
    }

    // ========================= 內部方法 =========================

    private static void triggerLightning(float gameTime) {
        lightningFlash = 1.0f;
        BRLightningRenderer.triggerBolt(gameTime);
    }
}

