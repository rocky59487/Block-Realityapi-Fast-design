package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFd;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreFd;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * BRVKGLSync — Vulkan ↔ OpenGL 紋理共享與同步（Phase 5 取代 BRVulkanInterop）。
 *
 * <h3>實作策略（優先順序）</h3>
 * <ol>
 *   <li><b>零拷貝 Semaphore 路徑</b>：
 *       {@code GL_EXT_memory_object_fd} + {@code GL_EXT_semaphore_fd}<br>
 *       Vulkan 端匯出 opaque fd → GL 端匯入為 memory object + semaphore，
 *       GPU 間同步無需 CPU 介入（無 glFinish / vkWaitForFences 阻塞）。</li>
 *   <li><b>記憶體物件路徑（無 Semaphore）</b>：
 *       {@code GL_EXT_memory_object_fd} 但無 semaphore 支援，<br>
 *       使用 glFinish 作為保守 barrier。</li>
 *   <li><b>CPU Readback Fallback</b>：
 *       硬體不支援外部記憶體時，透過 host-visible VkBuffer 回傳像素，
 *       再以 PBO 上傳至 GL texture。效能較低但保證可用。</li>
 * </ol>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 初始化（每次視窗 resize 後重新呼叫）
 * BRVKGLSync.init(width, height);
 *
 * // 每幀 RT 渲染後
 * BRVKGLSync.syncVKToGL();          // 等待 VK 完成並使 GL 端可見
 * int texId = BRVKGLSync.getGLTexture();  // 取得 GL texture ID
 *
 * // 關閉時
 * BRVKGLSync.cleanup();
 * }</pre>
 *
 * @see BRVulkanInterop  已廢棄的前任實作
 * @see BRRTCompositor   主要呼叫端
 */
@OnlyIn(Dist.CLIENT)
public final class BRVKGLSync {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VKGLSync");

    // ════════════════════════════════════════════════════════════════════
    //  GL extension 常數（避免直接依賴尚未確認的 LWJGL 版本常數）
    // ════════════════════════════════════════════════════════════════════

    /** GL_EXT_memory_object: 紋理存儲使用外部記憶體物件。 */
    private static final int GL_TEXTURE_TILING_EXT          = 0x9580;
    private static final int GL_OPTIMAL_TILING_EXT           = 0x9584;
    private static final int GL_HANDLE_TYPE_OPAQUE_FD_EXT    = 0x9586;

    /** GL_EXT_semaphore: semaphore handle type（與 memory object 共用相同值）。 */
    private static final int GL_HANDLE_TYPE_OPAQUE_FD_EXT_SEM = 0x9586;
    /** GL_EXT_semaphore: 布局常數 GL_LAYOUT_GENERAL_EXT（texture layout for semaphore wait/signal）。 */
    private static final int GL_LAYOUT_GENERAL_EXT             = 0x958D;
    /** GL_RGBA16F format value（GL 3.0）。 */
    private static final int GL_RGBA16F                        = 0x881A;
    /** GL_HALF_FLOAT type value（GL 3.0）。 */
    private static final int GL_HALF_FLOAT                     = 0x140B;

    // ════════════════════════════════════════════════════════════════════
    //  狀態機
    // ════════════════════════════════════════════════════════════════════

    private static boolean initialized     = false;
    private static SyncMode activeMode     = SyncMode.UNINITIALIZED;

    private static int  rtWidth            = 0;
    private static int  rtHeight           = 0;

    // ── 路徑 A: 零拷貝 semaphore ─────────────────────────────────────
    private static int  glMemoryObject     = 0;   // GL memory object（外部 VK 記憶體）
    private static int  glTexture          = 0;   // GL texture（由外部記憶體支撐）
    private static int  glVKDoneSemaphore  = 0;   // GL semaphore（等待 VK 完成）

    // ── 路徑 B: 記憶體物件（無 Semaphore） ────────────────────────────
    // 使用相同 glMemoryObject / glTexture，同步改用 glFinish

    // ── 路徑 C: CPU Readback Fallback ────────────────────────────────
    private static int  glFallbackTex     = 0;   // 普通 GL texture（PBO upload）
    private static int  glPBO             = 0;   // Pixel Unpack Buffer

    // ════════════════════════════════════════════════════════════════════
    //  同步模式枚舉
    // ════════════════════════════════════════════════════════════════════

    public enum SyncMode {
        /** 尚未初始化。 */
        UNINITIALIZED,
        /** GL_EXT_memory_object_fd + GL_EXT_semaphore_fd — 完整零拷貝 GPU 同步。 */
        EXT_MEMORY_SEMAPHORE,
        /** GL_EXT_memory_object_fd 但無 semaphore — 使用 glFinish 作 conservative barrier。 */
        EXT_MEMORY_ONLY,
        /** CPU readback via host-visible VkBuffer + PBO upload。 */
        CPU_READBACK
    }

    // ════════════════════════════════════════════════════════════════════
    //  私有建構子
    // ════════════════════════════════════════════════════════════════════
    private BRVKGLSync() {}

    // ════════════════════════════════════════════════════════════════════
    //  初始化 / 清除
    // ════════════════════════════════════════════════════════════════════

    /**
     * 初始化 GL/VK interop 資源。
     * 根據 GL capabilities 自動選擇最佳同步模式。
     *
     * @param width  RT 輸出寬度（像素）
     * @param height RT 輸出高度（像素）
     */
    public static void init(int width, int height) {
        if (initialized) {
            if (width != rtWidth || height != rtHeight) {
                cleanup();
            } else {
                return;  // 已初始化且尺寸不變
            }
        }

        rtWidth  = width;
        rtHeight = height;

        try {
            var caps = GL.getCapabilities();

            boolean hasMemObj    = caps.GL_EXT_memory_object;
            boolean hasMemObjFd  = caps.GL_EXT_memory_object_fd;
            boolean hasSemaphore = caps.GL_EXT_semaphore;
            boolean hasSemFd     = caps.GL_EXT_semaphore_fd;
            boolean vkExtMem     = BRVulkanDevice.hasExternalMemory();

            LOG.info("[VKGLSync] GL caps: memory_object={}, memory_object_fd={}, " +
                     "semaphore={}, semaphore_fd={}, vk_ext_memory={}",
                hasMemObj, hasMemObjFd, hasSemaphore, hasSemFd, vkExtMem);

            if (hasMemObj && hasMemObjFd && vkExtMem) {
                if (hasSemaphore && hasSemFd) {
                    initSemaphoreMode(width, height);
                } else {
                    initMemoryOnlyMode(width, height);
                }
            } else {
                initFallbackMode(width, height);
            }

            initialized = true;
            LOG.info("[VKGLSync] Initialized: mode={}, {}×{}", activeMode, width, height);

        } catch (Exception e) {
            LOG.warn("[VKGLSync] Init failed, falling back to CPU readback: {}", e.getMessage());
            initFallbackMode(width, height);
            initialized = true;
        }
    }

    /**
     * 路徑 A：GL_EXT_memory_object_fd + GL_EXT_semaphore_fd。
     * VK→GL 紋理共享 + GPU semaphore 同步，零 CPU 阻塞。
     */
    private static void initSemaphoreMode(int width, int height) {
        try (var stack = MemoryStack.stackPush()) {
            // ── 步驟 1: 從 Vulkan 端取得 RT output image 的 opaque fd ────────
            int memFd = BRVulkanDevice.exportRTOutputMemoryFd();
            if (memFd < 0) {
                LOG.warn("[VKGLSync] VK memory fd export failed (fd={}), degrading to memory-only", memFd);
                initMemoryOnlyMode(width, height);
                return;
            }

            // ── 步驟 2: 建立 GL memory object 並匯入 fd ───────────────────────
            IntBuffer memObjBuf = stack.mallocInt(1);
            EXTMemoryObject.glCreateMemoryObjectsEXT(memObjBuf);
            glMemoryObject = memObjBuf.get(0);

            EXTMemoryObjectFd.glImportMemoryFdEXT(
                glMemoryObject,
                (long) width * height * 8,  // RGBA16F = 8 bytes/pixel
                GL_HANDLE_TYPE_OPAQUE_FD_EXT,
                memFd
            );

            // ── 步驟 3: 建立 GL texture 並繫結至外部記憶體 ────────────────────
            IntBuffer texBuf = stack.mallocInt(1);
            GL11.glGenTextures(texBuf);
            glTexture = texBuf.get(0);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            // 使用外部記憶體物件分配紋理存儲（不重複 VK 分配的記憶體）
            EXTMemoryObject.glTexStorageMem2DEXT(
                GL11.GL_TEXTURE_2D,
                1,              // mip levels
                GL_RGBA16F,     // 與 VkImage 格式對應（VK_FORMAT_R16G16B16A16_SFLOAT）
                width, height,
                glMemoryObject,
                0L              // offset in memory object
            );
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            // ── 步驟 4: 從 Vulkan 取得 semaphore fd 並匯入 GL ─────────────────
            int semFd = BRVulkanDevice.exportVKDoneSemaphoreFd();
            if (semFd >= 0) {
                IntBuffer semBuf = stack.mallocInt(1);
                EXTSemaphore.glGenSemaphoresEXT(semBuf);
                glVKDoneSemaphore = semBuf.get(0);
                EXTSemaphoreFd.glImportSemaphoreFdEXT(
                    glVKDoneSemaphore,
                    GL_HANDLE_TYPE_OPAQUE_FD_EXT_SEM,
                    semFd
                );
                activeMode = SyncMode.EXT_MEMORY_SEMAPHORE;
                LOG.info("[VKGLSync] Semaphore mode ready: texture={}, semaphore={}", glTexture, glVKDoneSemaphore);
            } else {
                // Semaphore fd 取得失敗，退而使用記憶體共享但無 semaphore
                LOG.warn("[VKGLSync] VK semaphore fd export failed, using memory-only sync");
                activeMode = SyncMode.EXT_MEMORY_ONLY;
            }

        } catch (Exception e) {
            LOG.warn("[VKGLSync] Semaphore mode init error: {}", e.getMessage());
            cleanupGLObjects();
            initMemoryOnlyMode(width, height);
        }
    }

    /**
     * 路徑 B：GL_EXT_memory_object_fd（無 semaphore），使用 glFinish 作 conservative barrier。
     */
    private static void initMemoryOnlyMode(int width, int height) {
        try (var stack = MemoryStack.stackPush()) {
            int memFd = BRVulkanDevice.exportRTOutputMemoryFd();
            if (memFd < 0) {
                LOG.warn("[VKGLSync] Memory-only: VK fd export failed, falling back to CPU");
                initFallbackMode(width, height);
                return;
            }

            IntBuffer memObjBuf = stack.mallocInt(1);
            EXTMemoryObject.glCreateMemoryObjectsEXT(memObjBuf);
            glMemoryObject = memObjBuf.get(0);
            EXTMemoryObjectFd.glImportMemoryFdEXT(
                glMemoryObject,
                (long) width * height * 8,
                GL_HANDLE_TYPE_OPAQUE_FD_EXT,
                memFd
            );

            IntBuffer texBuf = stack.mallocInt(1);
            GL11.glGenTextures(texBuf);
            glTexture = texBuf.get(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            EXTMemoryObject.glTexStorageMem2DEXT(
                GL11.GL_TEXTURE_2D, 1, GL42.GL_RGBA16F, width, height, glMemoryObject, 0L);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            activeMode = SyncMode.EXT_MEMORY_ONLY;
            LOG.info("[VKGLSync] Memory-only mode ready: texture={}", glTexture);

        } catch (Exception e) {
            LOG.warn("[VKGLSync] Memory-only init error: {}", e.getMessage());
            cleanupGLObjects();
            initFallbackMode(width, height);
        }
    }

    /**
     * 路徑 C：CPU Readback Fallback。
     * 普通 GL texture，由 CPU 從 Vulkan staging buffer 讀取後 PBO 上傳。
     */
    private static void initFallbackMode(int width, int height) {
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer texBuf = stack.mallocInt(1);
            GL11.glGenTextures(texBuf);
            glFallbackTex = texBuf.get(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glFallbackTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
                width, height, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            IntBuffer pboBuf = stack.mallocInt(1);
            GL30.glGenBuffers(pboBuf);
            glPBO = pboBuf.get(0);
            GL30.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, glPBO);
            GL30.glBufferData(GL30.GL_PIXEL_UNPACK_BUFFER,
                (long) width * height * 8, GL30.GL_STREAM_DRAW);  // RGBA16F = 8 bytes/pixel
            GL30.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, 0);

            activeMode = SyncMode.CPU_READBACK;
            LOG.info("[VKGLSync] CPU readback fallback ready: texture={}, pbo={}", glFallbackTex, glPBO);
        }
    }

    public static void cleanup() {
        if (!initialized) return;
        cleanupGLObjects();
        initialized  = false;
        activeMode   = SyncMode.UNINITIALIZED;
        rtWidth      = 0;
        rtHeight     = 0;
        LOG.info("[VKGLSync] Cleaned up");
    }

    private static void cleanupGLObjects() {
        try (var stack = MemoryStack.stackPush()) {
            if (glVKDoneSemaphore != 0) {
                EXTSemaphore.glDeleteSemaphoresEXT(stack.ints(glVKDoneSemaphore));
                glVKDoneSemaphore = 0;
            }
            if (glMemoryObject != 0) {
                EXTMemoryObject.glDeleteMemoryObjectsEXT(stack.ints(glMemoryObject));
                glMemoryObject = 0;
            }
        }
        if (glTexture != 0) {
            GL11.glDeleteTextures(glTexture);
            glTexture = 0;
        }
        if (glFallbackTex != 0) {
            GL11.glDeleteTextures(glFallbackTex);
            glFallbackTex = 0;
        }
        if (glPBO != 0) {
            GL30.glDeleteBuffers(glPBO);
            glPBO = 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  每幀同步
    // ════════════════════════════════════════════════════════════════════

    /**
     * VK → GL 同步。
     *
     * <p>在 {@link BRVulkanRT#traceRays} 之後、GL 讀取 RT 紋理之前呼叫。
     * 依 {@link #activeMode} 選擇最優同步策略：
     * <ul>
     *   <li>{@link SyncMode#EXT_MEMORY_SEMAPHORE}：等待 GL semaphore（GPU-only，無 CPU 阻塞）</li>
     *   <li>{@link SyncMode#EXT_MEMORY_ONLY}：glFlush（確保 GL 端看到 VK 寫入）</li>
     *   <li>{@link SyncMode#CPU_READBACK}：CPU 讀取 VK staging buffer，PBO 上傳至 GL texture</li>
     * </ul>
     *
     * @param dstTex 在 semaphore 模式中，等待後此紋理即可讀；
     *               其他模式下此參數未使用
     */
    public static void syncVKToGL() {
        if (!initialized) return;

        switch (activeMode) {
            case EXT_MEMORY_SEMAPHORE -> {
                // GPU semaphore wait — 無 CPU 阻塞
                // 告知 GL 等待 VK 完成信號才可讀取 glTexture
                if (glVKDoneSemaphore != 0 && glTexture != 0) {
                    try (var stack = MemoryStack.stackPush()) {
                        // LWJGL wrapper: buffers/textures counts inferred from IntBuffer.remaining()
                        IntBuffer texArray = stack.ints(glTexture);
                        IntBuffer layouts  = stack.ints(GL_LAYOUT_GENERAL_EXT);
                        EXTSemaphore.glWaitSemaphoreEXT(
                            glVKDoneSemaphore,
                            null,      // no buffer barriers
                            texArray,  // texture to wait on
                            layouts    // expected src layout (GENERAL)
                        );
                    }
                }
            }
            case EXT_MEMORY_ONLY -> {
                // Conservative: glFlush 確保任何 GL→VK 寫入完成
                // VK fence 已在 traceRays() 等待；GL 端只需確保可讀
                GL11.glFlush();
            }
            case CPU_READBACK -> {
                uploadFallbackFrame();
            }
            default -> { /* UNINITIALIZED, no-op */ }
        }
    }

    /**
     * CPU Readback：從 Vulkan staging buffer 取得像素並透過 PBO 上傳至 GL。
     */
    private static void uploadFallbackFrame() {
        if (glPBO == 0 || glFallbackTex == 0) return;

        ByteBuffer pixels = BRVulkanDevice.readbackRTOutputPixels();
        if (pixels == null) return;  // VK 端暫無數據（首幀或未初始化）

        // PBO upload → GL texture（非同步，避免 glTexImage2D 的 CPU 停頓）
        GL30.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, glPBO);
        ByteBuffer mapped = GL30.glMapBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, GL30.GL_WRITE_ONLY);
        if (mapped != null) {
            mapped.put(pixels);
            mapped.flip();
            GL30.glUnmapBuffer(GL30.GL_PIXEL_UNPACK_BUFFER);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glFallbackTex);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0,
                rtWidth, rtHeight, GL11.GL_RGBA, GL_HALF_FLOAT, 0L);  // offset 0 in PBO
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        }
        GL30.glBindBuffer(GL30.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    // ════════════════════════════════════════════════════════════════════
    //  Accessors
    // ════════════════════════════════════════════════════════════════════

    /**
     * 取得可供 GL 讀取的 RT 輸出紋理 ID。
     * 必須在 {@link #syncVKToGL()} 之後呼叫以確保內容有效。
     *
     * @return GL texture ID，0 表示未初始化或不可用
     */
    public static int getGLTexture() {
        return switch (activeMode) {
            case EXT_MEMORY_SEMAPHORE, EXT_MEMORY_ONLY -> glTexture;
            case CPU_READBACK                          -> glFallbackTex;
            default                                    -> 0;
        };
    }

    public static boolean isInitialized() { return initialized; }
    public static SyncMode getSyncMode()  { return activeMode; }
    public static int  getWidth()         { return rtWidth; }
    public static int  getHeight()        { return rtHeight; }

    /**
     * 在視窗 resize 時重新初始化（thread-safe：必須在 GL 執行緒呼叫）。
     */
    public static void resize(int newWidth, int newHeight) {
        if (newWidth == rtWidth && newHeight == rtHeight) return;
        cleanup();
        init(newWidth, newHeight);
    }
}
