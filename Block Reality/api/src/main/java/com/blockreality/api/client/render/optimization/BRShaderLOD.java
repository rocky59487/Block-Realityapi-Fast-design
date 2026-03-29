package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shader LOD 系統 — 根據效能預算動態降級 shader 品質。
 *
 * 技術架構：
 *   - 幀時間監控（滾動平均 60 幀）
 *   - 三級品質：HIGH / MEDIUM / LOW
 *   - 降級策略（從最不明顯的效果開始）：
 *     HIGH→MEDIUM: 關閉 POM + SSS + Anisotropic，SSGI 半取樣
 *     MEDIUM→LOW:  關閉 SSR + Contact Shadow + SSGI，降 CSM 解析度
 *   - 升級策略（需連續穩定 120 幀才升級，防止抖動）
 *   - 每個 composite pass 在渲染前查詢 shouldRender(passName) 決定是否跳過
 *
 * 參考：
 *   - Sodium: 動態 render distance 調整
 *   - Unreal Engine: Scalability Settings
 *   - Iris: shader profile (low/medium/high)
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRShaderLOD {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRShaderLOD.class);

    public enum QualityLevel {
        HIGH, MEDIUM, LOW
    }

    private static QualityLevel currentLevel = QualityLevel.HIGH;

    /** 幀時間歷史（毫秒） */
    private static final int HISTORY_SIZE = 60;
    private static float[] frameTimeHistory = new float[HISTORY_SIZE];
    private static int historyIndex = 0;
    private static boolean historyFull = false;

    /** 升級穩定計數器（連續低於閾值的幀數） */
    private static int upgradeStableFrames = 0;
    private static final int UPGRADE_REQUIRED_FRAMES = 120;

    /** 降級觸發閾值（ms） */
    private static final float DOWNGRADE_THRESHOLD_MS = 20.0f; // 50 FPS 以下降級
    private static final float UPGRADE_THRESHOLD_MS = 12.0f;   // 83 FPS 以上才升級

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) return;
        currentLevel = QualityLevel.HIGH;
        frameTimeHistory = new float[HISTORY_SIZE];
        historyIndex = 0;
        historyFull = false;
        upgradeStableFrames = 0;
        initialized = true;
        LOGGER.info("[BRShaderLOD] Shader LOD 系統初始化完成 — 起始品質 HIGH");
    }

    public static void cleanup() {
        if (!initialized) return;
        initialized = false;
    }

    // ========================= 每幀更新 =========================

    /**
     * 記錄幀時間並自動調整品質等級。
     * @param frameTimeMs 本幀耗時（毫秒）
     */
    public static void recordFrameTime(float frameTimeMs) {
        if (!initialized || !BRRenderConfig.SHADER_LOD_ENABLED) return;

        frameTimeHistory[historyIndex] = frameTimeMs;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        if (historyIndex == 0) historyFull = true;

        float avg = computeAverage();
        if (avg <= 0.0f) return;

        // ── 降級判定 ──
        if (avg > DOWNGRADE_THRESHOLD_MS) {
            upgradeStableFrames = 0;
            if (currentLevel == QualityLevel.HIGH) {
                currentLevel = QualityLevel.MEDIUM;
                LOGGER.info("[BRShaderLOD] 品質降級 HIGH → MEDIUM（avg {:.1f}ms）", avg);
            } else if (currentLevel == QualityLevel.MEDIUM) {
                currentLevel = QualityLevel.LOW;
                LOGGER.info("[BRShaderLOD] 品質降級 MEDIUM → LOW（avg {:.1f}ms）", avg);
            }
        }

        // ── 升級判定 ──
        if (avg < UPGRADE_THRESHOLD_MS) {
            upgradeStableFrames++;
            if (upgradeStableFrames >= UPGRADE_REQUIRED_FRAMES) {
                upgradeStableFrames = 0;
                if (currentLevel == QualityLevel.LOW) {
                    currentLevel = QualityLevel.MEDIUM;
                    LOGGER.info("[BRShaderLOD] 品質升級 LOW → MEDIUM（穩定 {}幀）", UPGRADE_REQUIRED_FRAMES);
                } else if (currentLevel == QualityLevel.MEDIUM) {
                    currentLevel = QualityLevel.HIGH;
                    LOGGER.info("[BRShaderLOD] 品質升級 MEDIUM → HIGH（穩定 {}幀）", UPGRADE_REQUIRED_FRAMES);
                }
            }
        } else {
            upgradeStableFrames = 0;
        }
    }

    // ========================= 查詢 API =========================

    public static QualityLevel getCurrentLevel() { return currentLevel; }

    /**
     * 判定指定 pass 是否應渲染（在當前品質等級下）。
     * 管線中各 composite pass 在渲染前呼叫此方法。
     */
    public static boolean shouldRenderPass(String passName) {
        if (!initialized || !BRRenderConfig.SHADER_LOD_ENABLED) return true;

        switch (currentLevel) {
            case LOW:
                // LOW: 只保留最基礎的 pass
                switch (passName) {
                    case "SSR": case "ContactShadow": case "SSGI":
                    case "SSS": case "Anisotropic": case "POM":
                    case "DOF": case "Cinematic": case "LensFlare":
                    case "WetPBR": case "Aurora":
                        return false;
                    default:
                        return true;
                }
            case MEDIUM:
                // MEDIUM: 關閉高耗費材質效果
                switch (passName) {
                    case "SSS": case "Anisotropic": case "POM":
                    case "DOF": case "Aurora":
                        return false;
                    default:
                        return true;
                }
            case HIGH:
            default:
                return true;
        }
    }

    /**
     * 取得當前品質等級下的 SSGI 取樣倍率（1.0=全, 0.5=半）。
     */
    public static float getSSGISampleMultiplier() {
        switch (currentLevel) {
            case LOW: return 0.0f;    // 關閉
            case MEDIUM: return 0.5f; // 半取樣
            default: return 1.0f;
        }
    }

    /**
     * 取得當前品質等級下的 CSM 解析度倍率。
     */
    public static float getCSMResolutionMultiplier() {
        switch (currentLevel) {
            case LOW: return 0.5f;
            case MEDIUM: return 0.75f;
            default: return 1.0f;
        }
    }

    // ========================= 內部 =========================

    private static float computeAverage() {
        int count = historyFull ? HISTORY_SIZE : historyIndex;
        if (count == 0) return 0.0f;
        float sum = 0.0f;
        for (int i = 0; i < count; i++) {
            sum += frameTimeHistory[i];
        }
        return sum / count;
    }
}
