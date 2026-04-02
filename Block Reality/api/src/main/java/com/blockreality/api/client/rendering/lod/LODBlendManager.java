package com.blockreality.api.client.rendering.lod;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LOD 混合管理器 — 基於相機距離計算每級 LOD 的混合 alpha，
 * 使用 Bayer 4×4 有序抖動矩陣用於時間穩定性（不產生 TAA 鬼影）。
 *
 * <p>混合區間（block 單位，相對 section 中心）：
 * <ul>
 *   <li>LOD0→LOD1：8 chunks (128 blocks) 距離，16-block 混合區</li>
 *   <li>LOD1→LOD2：32 chunks (512 blocks) 距離，32-block 混合區</li>
 *   <li>LOD2→LOD3：128 chunks (2048 blocks) 距離，64-block 混合區</li>
 * </ul>
 *
 * <p>混合 alpha：
 * <ul>
 *   <li>0.0 = 舊 LOD 完全可見</li>
 *   <li>1.0 = 新 LOD 完全可見</li>
 * </ul>
 *
 * <p>Bayer 4×4 矩陣用於抖動閾值生成（避免 TAA 鬼影），
 * 透過 {@link #getDitherThreshold(int, int)} 取得 [0,1] 雜訊值。
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class LODBlendManager {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODBlend");

    // ── 混合參數（block 單位） ──────────────────────────────────────────────
    private static final double LOD0_DISTANCE = 128.0;  // 8 chunks = 128 blocks
    private static final double LOD0_BLEND_WIDTH = 16.0;

    private static final double LOD1_DISTANCE = 512.0;  // 32 chunks = 512 blocks
    private static final double LOD1_BLEND_WIDTH = 32.0;

    private static final double LOD2_DISTANCE = 2048.0; // 128 chunks = 2048 blocks
    private static final double LOD2_BLEND_WIDTH = 64.0;

    // ── Bayer 4×4 有序抖動矩陣 ──────────────────────────────────────────────
    /**
     * Bayer 4×4 矩陣（正規化 [0, 1)）。
     * 用於時間穩定抖動，避免 TAA 鬼影。
     */
    private static final float[] BAYER_4X4 = {
        0.0f/16.0f,  8.0f/16.0f,  2.0f/16.0f, 10.0f/16.0f,
        12.0f/16.0f,  4.0f/16.0f, 14.0f/16.0f,  6.0f/16.0f,
        3.0f/16.0f, 11.0f/16.0f,  1.0f/16.0f,  9.0f/16.0f,
        15.0f/16.0f,  7.0f/16.0f, 13.0f/16.0f,  5.0f/16.0f
    };

    // ── 單例 ──────────────────────────────────────────────────────────────
    private static LODBlendManager INSTANCE;

    public static LODBlendManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new LODBlendManager();
            LOG.info("LODBlendManager initialized");
        }
        return INSTANCE;
    }

    private LODBlendManager() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  公開 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 取得指定 LOD 級別的混合 alpha（基於到相機的距離）。
     *
     * <p>混合區間由 LOD 等級決定：
     * <ul>
     *   <li>LOD 0：[0, 128 + 16] blocks</li>
     *   <li>LOD 1：[128, 512 + 32] blocks</li>
     *   <li>LOD 2：[512, 2048 + 64] blocks</li>
     *   <li>LOD 3：2048 blocks 以上</li>
     * </ul>
     *
     * @param lodLevel       LOD 等級 (0-3)
     * @param distanceBlocks 到相機的距離（block 單位）
     * @return 混合 alpha [0.0, 1.0]：0 = 舊 LOD，1 = 新 LOD
     */
    public float getBlendAlpha(int lodLevel, double distanceBlocks) {
        switch (lodLevel) {
            case 0:
                // LOD 0 到 LOD 1 的過渡：[128, 144] blocks
                if (distanceBlocks < LOD0_DISTANCE) {
                    return 0.0f;  // 純 LOD 0
                }
                if (distanceBlocks >= LOD0_DISTANCE + LOD0_BLEND_WIDTH) {
                    return 1.0f;  // 純 LOD 1
                }
                // 線性插值
                double progress = (distanceBlocks - LOD0_DISTANCE) / LOD0_BLEND_WIDTH;
                return (float) Math.min(1.0, Math.max(0.0, progress));

            case 1:
                // LOD 1 到 LOD 2 的過渡：[512, 544] blocks
                if (distanceBlocks < LOD1_DISTANCE) {
                    return 0.0f;
                }
                if (distanceBlocks >= LOD1_DISTANCE + LOD1_BLEND_WIDTH) {
                    return 1.0f;
                }
                progress = (distanceBlocks - LOD1_DISTANCE) / LOD1_BLEND_WIDTH;
                return (float) Math.min(1.0, Math.max(0.0, progress));

            case 2:
                // LOD 2 到 LOD 3 的過渡：[2048, 2112] blocks
                if (distanceBlocks < LOD2_DISTANCE) {
                    return 0.0f;
                }
                if (distanceBlocks >= LOD2_DISTANCE + LOD2_BLEND_WIDTH) {
                    return 1.0f;
                }
                progress = (distanceBlocks - LOD2_DISTANCE) / LOD2_BLEND_WIDTH;
                return (float) Math.min(1.0, Math.max(0.0, progress));

            case 3:
                // LOD 3 永遠返回 0（無進一步混合）
                return 0.0f;

            default:
                return 0.0f;
        }
    }

    /**
     * 從 Bayer 4×4 矩陣取得像素位置的抖動閾值。
     *
     * <p>用於有序抖動（ordered dithering），生成時間穩定的雜訊值。
     * Bayer 矩陣按 4×4 平鋪，所以只需 pixelX % 4 與 pixelY % 4 即可。
     *
     * @param pixelX 螢幕像素 X 座標
     * @param pixelY 螢幕像素 Y 座標
     * @return 抖動閾值 [0.0, 1.0)
     */
    public float getDitherThreshold(int pixelX, int pixelY) {
        int x = pixelX & 3;  // % 4
        int y = pixelY & 3;  // % 4
        return BAYER_4X4[y * 4 + x];
    }

    /**
     * 判斷指定像素在給定 LOD 等級是否可見（使用混合 alpha 與抖動）。
     *
     * <p>計算混合 alpha，並與抖動閾值比較：
     * <ul>
     *   <li>若 alpha >= dither → LOD 可見</li>
     *   <li>否則 → LOD 隱藏</li>
     * </ul>
     *
     * @param lodLevel       LOD 等級 (0-3)
     * @param distanceBlocks 到相機的距離（block 單位）
     * @param pixelX         螢幕像素 X（用於抖動）
     * @param pixelY         螢幕像素 Y（用於抖動）
     * @return true 若該 LOD 在此像素可見，false 隱藏
     */
    public boolean isVisibleAtLOD(int lodLevel, double distanceBlocks, int pixelX, int pixelY) {
        float alpha  = getBlendAlpha(lodLevel, distanceBlocks);
        float dither = getDitherThreshold(pixelX, pixelY);
        return alpha >= dither;
    }

    /**
     * 取得指定 LOD 等級的混合區間寬度（block 單位）。
     *
     * @param lodLevel LOD 等級 (0-3)
     * @return 混合區間寬度，或 0 若 LOD >= 3
     */
    public double getBlendWidth(int lodLevel) {
        switch (lodLevel) {
            case 0: return LOD0_BLEND_WIDTH;
            case 1: return LOD1_BLEND_WIDTH;
            case 2: return LOD2_BLEND_WIDTH;
            default: return 0.0;
        }
    }

    /**
     * 取得指定 LOD 等級開始的距離（block 單位）。
     *
     * @param lodLevel LOD 等級 (0-3)
     * @return 距離閾值
     */
    public double getDistanceForLOD(int lodLevel) {
        switch (lodLevel) {
            case 0: return 0.0;
            case 1: return LOD0_DISTANCE;
            case 2: return LOD1_DISTANCE;
            case 3: return LOD2_DISTANCE;
            default: return Double.MAX_VALUE;
        }
    }
}
