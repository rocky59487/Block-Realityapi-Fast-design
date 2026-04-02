package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GBuffer VkImage 附件管理器（RT-5-2 整合）
 *
 * <p>管理延遲著色管線所需的所有 GBuffer 附件圖像，以及
 * 供降噪器（ReLAX/NRD）和體積光照使用的讀取視圖：
 *
 * <table>
 *   <tr><th>附件</th><th>格式</th><th>用途</th></tr>
 *   <tr><td>depth[0/1]</td><td>R32F</td>
 *       <td>線性深度 ping-pong（cur/prev 幀交替）</td></tr>
 *   <tr><td>normal</td><td>RGBA16F</td>
 *       <td>世界空間法線 + 材料粗糙度（w 分量）</td></tr>
 *   <tr><td>albedo</td><td>RGBA8_UNORM</td>
 *       <td>漫反射顏色 + 材料類型（a 分量）</td></tr>
 *   <tr><td>material</td><td>RGBA8_UNORM</td>
 *       <td>Roughness / Metallic / Emissive / Flags</td></tr>
 * </table>
 *
 * <h3>生命週期</h3>
 * <ol>
 *   <li>{@link #init(int, int)} — Vulkan device 就緒後（在 BRVulkanRT.init 後）呼叫</li>
 *   <li>{@link #resize(int, int)} — 視窗大小改變時呼叫（重新分配全部附件）</li>
 *   <li>{@link #swapDepthBuffers()} — 每幀結束時呼叫，交換 cur/prev 深度緩衝</li>
 *   <li>{@link #cleanup()} — 模組卸載時釋放全部 Vulkan 資源</li>
 * </ol>
 *
 * <h3>降噪器接線（RT-5-2）</h3>
 * {@code BRVulkanRT.dispatchReLAXFallback()} 使用：
 * <pre>
 *   currentRTView = BRVulkanRT.getRtOutputImageView()
 *   depthView     = BRGBufferAttachments.getInstance().getDepthView()
 *   prevDepthView = BRGBufferAttachments.getInstance().getPrevDepthView()
 *   normalView    = BRGBufferAttachments.getInstance().getNormalView()
 * </pre>
 *
 * @since RT-5-2
 */
@OnlyIn(Dist.CLIENT)
public final class BRGBufferAttachments {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-GBuffer");

    // ─── VkFormat 常數（鏡像 VK10 整數值）────────────────────────────────────
    /** VK_FORMAT_R32_SFLOAT — 線性深度（單通道 32-bit float） */
    private static final int FMT_R32F = 100;
    /** VK_FORMAT_R16G16B16A16_SFLOAT — 法線/GI（四通道 16-bit float） */
    private static final int FMT_RGBA16F = 97;
    /** VK_FORMAT_R8G8B8A8_UNORM — 漫反射顏色（四通道 8-bit UNORM） */
    private static final int FMT_RGBA8 = 37;

    // ─── 單例 ─────────────────────────────────────────────────────────────────
    private static final BRGBufferAttachments INSTANCE = new BRGBufferAttachments();
    public static BRGBufferAttachments getInstance() { return INSTANCE; }
    private BRGBufferAttachments() {}

    // ─── 狀態 ─────────────────────────────────────────────────────────────────
    private boolean initialized = false;
    private int width  = 0;
    private int height = 0;

    /**
     * 線性深度 ping-pong 緩衝（cur = frameIndex&1，prev = cur^1）。
     * 每個 long[3] = {VkImage, VkDeviceMemory, VkImageView}
     */
    private final long[][] depthImg = new long[2][3];

    /**
     * 世界空間法線附件（RGBA16F）。
     * [0] = VkImage, [1] = VkDeviceMemory, [2] = VkImageView
     */
    private final long[] normalImg = new long[3];

    /**
     * 漫反射顏色附件（RGBA8_UNORM）。
     */
    private final long[] albedoImg = new long[3];

    /**
     * 材料屬性附件（RGBA8_UNORM: R=Roughness, G=Metallic, B=Emissive, A=Flags）。
     */
    private final long[] materialImg = new long[3];

    /** 目前幀使用的深度緩衝索引（0 或 1）。*/
    private int curDepthIdx = 0;

    // ─── 公開介面 ─────────────────────────────────────────────────────────────

    /**
     * 初始化 GBuffer 附件（首次呼叫或解析度改變時使用）。
     * 必須在 BRVulkanDevice 就緒後呼叫。
     *
     * @param w 渲染寬度（像素）
     * @param h 渲染高度（像素）
     */
    public void init(int w, int h) {
        if (!BRVulkanDevice.isInitialized()) {
            LOGGER.warn("[GBuffer] BRVulkanDevice 尚未就緒，跳過初始化");
            return;
        }
        if (initialized && w == width && h == height) return;

        cleanup();  // 釋放舊資源

        this.width  = w;
        this.height = h;

        try {
            // ── 深度 ping-pong（兩個 R32F 圖像）────────────────────────────
            for (int i = 0; i < 2; i++) {
                depthImg[i] = BRVulkanDevice.createImage2D(
                    w, h, FMT_R32F,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT
                        | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                );
                // 轉換到 GENERAL layout 供 compute 讀寫
                BRVulkanDevice.transitionImageLayout(
                    depthImg[i][0],
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_GENERAL
                );
                LOGGER.debug("[GBuffer] depth[{}] allocated: image=0x{}", i,
                    Long.toHexString(depthImg[i][0]));
            }

            // ── 法線附件（RGBA16F）────────────────────────────────────────
            normalImg[0] = 0L; normalImg[1] = 0L; normalImg[2] = 0L;
            long[] normalAlloc = BRVulkanDevice.createImage2D(
                w, h, FMT_RGBA16F,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                    | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            System.arraycopy(normalAlloc, 0, normalImg, 0, 3);
            BRVulkanDevice.transitionImageLayout(
                normalImg[0],
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            );
            LOGGER.debug("[GBuffer] normal allocated: image=0x{}", Long.toHexString(normalImg[0]));

            // ── 漫反射顏色附件（RGBA8）────────────────────────────────────
            long[] albedoAlloc = BRVulkanDevice.createImage2D(
                w, h, FMT_RGBA8,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            System.arraycopy(albedoAlloc, 0, albedoImg, 0, 3);
            BRVulkanDevice.transitionImageLayout(
                albedoImg[0],
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            );
            LOGGER.debug("[GBuffer] albedo allocated: image=0x{}", Long.toHexString(albedoImg[0]));

            // ── 材料屬性附件（RGBA8）──────────────────────────────────────
            long[] materialAlloc = BRVulkanDevice.createImage2D(
                w, h, FMT_RGBA8,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            System.arraycopy(materialAlloc, 0, materialImg, 0, 3);
            BRVulkanDevice.transitionImageLayout(
                materialImg[0],
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            );
            LOGGER.debug("[GBuffer] material allocated: image=0x{}", Long.toHexString(materialImg[0]));

            initialized = true;
            LOGGER.info("[GBuffer] 附件初始化完成 ({}×{})", w, h);

        } catch (Exception e) {
            LOGGER.error("[GBuffer] 初始化失敗，降噪器將使用 0L 佔位", e);
            initialized = false;
        }
    }

    /**
     * 視窗大小改變時重新分配所有 GBuffer 附件。
     *
     * @param w 新渲染寬度
     * @param h 新渲染高度
     */
    public void resize(int w, int h) {
        if (w == width && h == height) return;
        LOGGER.info("[GBuffer] Resize: {}×{} → {}×{}", width, height, w, h);
        init(w, h);
    }

    /**
     * 每幀結束時呼叫：交換 cur/prev 深度緩衝索引。
     *
     * <p>必須在 {@code BRReLAXDenoiser.denoise()} 呼叫之後、下一幀開始前呼叫。
     * 確保本幀的深度圖在下一幀作為 "prevDepth" 使用。
     */
    public void swapDepthBuffers() {
        curDepthIdx ^= 1;
    }

    // ─── VkImageView Getters ──────────────────────────────────────────────────

    /**
     * 當前幀線性深度圖的 VkImageView（用於 ReLAX denoiser 的 depthView）。
     * 格式：R32F，layout：GENERAL
     *
     * @return VkImageView handle，或 0L（未初始化）
     */
    public long getDepthView() {
        return depthImg[curDepthIdx][2];
    }

    /**
     * 前一幀線性深度圖的 VkImageView（用於 ReLAX denoiser 的 prevDepthView）。
     * 格式：R32F，layout：GENERAL
     *
     * @return VkImageView handle，或 0L（未初始化）
     */
    public long getPrevDepthView() {
        return depthImg[curDepthIdx ^ 1][2];
    }

    /**
     * 世界空間法線附件的 VkImageView（用於 ReLAX denoiser 的 normalView）。
     * 格式：RGBA16F，layout：SHADER_READ_ONLY_OPTIMAL
     *
     * @return VkImageView handle，或 0L（未初始化）
     */
    public long getNormalView() {
        return normalImg[2];
    }

    /**
     * 漫反射顏色附件的 VkImageView。
     * 格式：RGBA8_UNORM，layout：SHADER_READ_ONLY_OPTIMAL
     *
     * @return VkImageView handle，或 0L（未初始化）
     */
    public long getAlbedoView() {
        return albedoImg[2];
    }

    /**
     * 材料屬性附件的 VkImageView。
     * 格式：RGBA8_UNORM，layout：SHADER_READ_ONLY_OPTIMAL
     *
     * @return VkImageView handle，或 0L（未初始化）
     */
    public long getMaterialView() {
        return materialImg[2];
    }

    /**
     * 當前幀深度 VkImage（用於 vkCmdBlitImage 或 pipeline barrier）。
     *
     * @return VkImage handle，或 0L（未初始化）
     */
    public long getDepthImage() {
        return depthImg[curDepthIdx][0];
    }

    /**
     * 法線 VkImage（用於 pipeline barrier）。
     *
     * @return VkImage handle，或 0L（未初始化）
     */
    public long getNormalImage() {
        return normalImg[0];
    }

    /** 是否已成功初始化（所有附件均已分配）。 */
    public boolean isInitialized() { return initialized; }

    /** 當前渲染解析度寬度。 */
    public int getWidth()  { return width; }

    /** 當前渲染解析度高度。 */
    public int getHeight() { return height; }

    // ─── 資源釋放 ─────────────────────────────────────────────────────────────

    /**
     * 釋放所有 GBuffer VkImage 和 VkImageView 資源。
     * 在 {@link #init} 重建前和模組卸載時呼叫。
     */
    public void cleanup() {
        if (!initialized && depthImg[0][0] == 0L) return;

        try {
            for (int i = 0; i < 2; i++) {
                if (depthImg[i][0] != 0L) {
                    BRVulkanDevice.destroyImage2D(depthImg[i][0], depthImg[i][1], depthImg[i][2]);
                    depthImg[i][0] = depthImg[i][1] = depthImg[i][2] = 0L;
                }
            }
            if (normalImg[0] != 0L) {
                BRVulkanDevice.destroyImage2D(normalImg[0], normalImg[1], normalImg[2]);
                normalImg[0] = normalImg[1] = normalImg[2] = 0L;
            }
            if (albedoImg[0] != 0L) {
                BRVulkanDevice.destroyImage2D(albedoImg[0], albedoImg[1], albedoImg[2]);
                albedoImg[0] = albedoImg[1] = albedoImg[2] = 0L;
            }
            if (materialImg[0] != 0L) {
                BRVulkanDevice.destroyImage2D(materialImg[0], materialImg[1], materialImg[2]);
                materialImg[0] = materialImg[1] = materialImg[2] = 0L;
            }
        } catch (Exception e) {
            LOGGER.error("[GBuffer] cleanup 異常", e);
        }

        initialized = false;
        width = 0;
        height = 0;
        curDepthIdx = 0;
        LOGGER.info("[GBuffer] 附件已釋放");
    }
}
