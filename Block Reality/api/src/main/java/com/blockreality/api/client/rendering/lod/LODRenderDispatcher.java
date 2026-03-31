package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.blockreality.api.client.rendering.vulkan.*;
import com.blockreality.api.config.BRConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/**
 * LOD + Vulkan RT 渲染調度器（Phase 1-E + Phase 2 整合）。
 *
 * 頂層協調器，負責：
 *   1. 生命週期管理（init / cleanup / dimension change）
 *   2. 每幀渲染（render()）：LOD OpenGL 渲染 + RT dispatch
 *   3. 每 tick 更新：chunk 管理 + 觸發 BLAS 建構
 *   4. GL-Vulkan 渲染結果合成
 *
 * 子系統關係：
 *   LODTerrainBuffer ← LODChunkManager（上傳 mesh）
 *   VkContext → VkMemoryAllocator
 *   VkContext → VkAccelStructBuilder（BLAS per chunk）
 *   VkRTShaderPack → VkRTPipeline
 *   VkSwapchain（GL interop render target）
 *
 * @see LODChunkManager
 * @see LODTerrainBuffer
 * @see VkContext
 * @see VkRTPipeline
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "blockreality", value = Dist.CLIENT)
public class LODRenderDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger("BR-LODDispatch");

    // ─── Phase 1：LOD OpenGL 子系統 ───
    private final LODTerrainBuffer terrainBuffer = new LODTerrainBuffer();
    private final LODChunkManager  chunkManager  = new LODChunkManager(terrainBuffer);
    private final VoxyLODMesher    mesher        = new VoxyLODMesher();
    /** Phase 1-C：LOD terrain shader（光照 + 霧效 + sun cycle） */
    private LODShaderProgram lodShaderProgram = null;

    // ─── Phase 2：Vulkan RT 子系統（可選，需 RT 硬體） ───
    private VkContext            vkContext       = null;
    private VkMemoryAllocator    vkAllocator     = null;
    private VkAccelStructBuilder accelBuilder    = null;
    private VkRTShaderPack       shaderPack      = null;
    private VkRTPipeline         rtPipeline      = null;
    private VkSwapchain          vkSwapchain     = null;

    // ─── 狀態 ───
    private boolean initialized   = false;
    private boolean rtEnabled     = false;
    private long    frameCount    = 0;

    /** 上次 RT render target 大小（用於偵測 resize） */
    private int lastRtWidth = 0, lastRtHeight = 0;

    // ─── Phase 3：Camera UBO + 持久化 Command Buffer ───
    /** Camera UBO 大小（bytes）：2×mat4 + vec4 + 4×float = 160 bytes，std140 layout */
    private static final int CAMERA_UBO_SIZE = 160;
    /** 持久化每幀 command buffer（VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT 已開啟） */
    private long frameCmdBuffer    = VK_NULL_HANDLE;
    /** Camera UBO VkBuffer handle */
    private long cameraUBOBuffer   = VK_NULL_HANDLE;
    /** Camera UBO VmaAllocation handle（用於 writeFloats） */
    private long cameraUBOAllocation = 0;
    /** 本幀是否已成功提交 RT dispatch（供 ForgeRenderEventBridge 判斷是否 composite） */
    private boolean rtDispatchedThisFrame = false;

    // ─── 單例 ───
    private static LODRenderDispatcher instance;

    private LODRenderDispatcher() {}

    public static LODRenderDispatcher getInstance() {
        if (instance == null) {
            instance = new LODRenderDispatcher();
        }
        return instance;
    }

    // ═══ 生命週期 ═══

    /**
     * 初始化 LOD + RT 系統（在 GL 執行緒呼叫，ClientSetup 中）。
     */
    public void init() {
        if (!BRRenderTier.isFeatureEnabled("voxy_lod")) {
            LOG.info("LOD rendering disabled (BRRenderTier voxy_lod=false)");
            return;
        }

        // Phase 1: LOD OpenGL buffer
        terrainBuffer.init();
        initialized = true;

        // Phase 1-C: LOD shader（光照 + 霧效）
        lodShaderProgram = new LODShaderProgram();
        if (!lodShaderProgram.compile()) {
            LOG.warn("LOD shader compile failed — terrain will render without lighting/fog");
            lodShaderProgram = null;
        }

        // Phase 2: Vulkan RT（若 config 啟用且硬體支援）
        if (BRConfig.INSTANCE.rtEnabled.get() && BRRenderTier.isFeatureEnabled("vulkan_rt")) {
            initVulkanRT();
        } else {
            LOG.info("Vulkan RT disabled (config={})", BRConfig.INSTANCE.rtEnabled.get());
        }

        LOG.info("LODRenderDispatcher initialized (LOD=true, RT={})", rtEnabled);
    }

    /** 初始化 Vulkan RT 子系統（Phase 2） */
    private void initVulkanRT() {
        vkContext = VkContext.getInstance();
        boolean rtOk = vkContext.init();
        if (!rtOk) {
            LOG.warn("VkContext init failed or RT not supported — running in LOD-only mode");
            vkContext = null;
            return;
        }

        vkAllocator = new VkMemoryAllocator(vkContext);
        if (!vkAllocator.init()) {
            LOG.error("VkMemoryAllocator init failed");
            vkContext.cleanup(); vkContext = null; vkAllocator = null;
            return;
        }

        accelBuilder = new VkAccelStructBuilder(vkContext, vkAllocator);

        shaderPack = new VkRTShaderPack(vkContext);
        if (!shaderPack.loadAll()) {
            LOG.error("VkRTShaderPack loadAll failed — RT disabled");
            cleanupVulkanRT();
            return;
        }

        rtPipeline = new VkRTPipeline(vkContext, vkAllocator, shaderPack);
        if (!rtPipeline.create()) {
            LOG.error("VkRTPipeline create failed — RT disabled");
            cleanupVulkanRT();
            return;
        }

        // Shader modules 在 pipeline 建立後可釋放
        shaderPack.cleanup();

        vkSwapchain = new VkSwapchain(vkContext, vkAllocator);
        // render target 在首次 render() 時建立（需要視窗尺寸）

        // Phase 3：分配 Camera UBO（host-mapped，每幀更新）
        if (!allocateCameraUBO()) {
            LOG.error("Camera UBO allocation failed — RT disabled");
            cleanupVulkanRT();
            return;
        }

        // Phase 3：分配持久化 frame command buffer
        frameCmdBuffer = allocateFrameCommandBuffer();
        if (frameCmdBuffer == VK_NULL_HANDLE) {
            LOG.error("Frame command buffer allocation failed — RT disabled");
            cleanupVulkanRT();
            return;
        }

        rtEnabled = true;
        LOG.info("Vulkan RT subsystem initialized (GPU: {})", vkContext.getGpuName());
    }

    /**
     * 釋放所有 GL / GPU 資源。
     */
    public void cleanup() {
        cleanupVulkanRT();
        if (lodShaderProgram != null) {
            lodShaderProgram.cleanup();
            lodShaderProgram = null;
        }
        chunkManager.clear();
        terrainBuffer.cleanup();
        initialized = false;
        instance = null;
        LOG.info("LODRenderDispatcher cleanup complete");
    }

    private void cleanupVulkanRT() {
        // Phase 3：先釋放 frame CB 和 Camera UBO（在 context/allocator 清理之前）
        if (frameCmdBuffer != VK_NULL_HANDLE && vkContext != null
                && vkContext.getDevice() != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pCmd = stack.pointers(frameCmdBuffer);
                vkFreeCommandBuffers(vkContext.getDevice(),
                    vkContext.getCommandPool(), pCmd);
            }
            frameCmdBuffer = VK_NULL_HANDLE;
        }
        if (cameraUBOBuffer != VK_NULL_HANDLE && vkAllocator != null) {
            vkAllocator.freeBuffer(cameraUBOBuffer);
            cameraUBOBuffer    = VK_NULL_HANDLE;
            cameraUBOAllocation = 0;
        }

        if (vkSwapchain  != null) { vkSwapchain.cleanup();  vkSwapchain  = null; }
        if (rtPipeline   != null) { rtPipeline.cleanup();   rtPipeline   = null; }
        if (shaderPack   != null) { shaderPack.cleanup();   shaderPack   = null; }
        if (accelBuilder != null) { accelBuilder.cleanup(); accelBuilder = null; }
        if (vkAllocator  != null) { vkAllocator.cleanup();  vkAllocator  = null; }
        if (vkContext    != null) { vkContext.cleanup();    vkContext    = null; }
        rtEnabled = false;
    }

    // ═══ 渲染 ═══

    /**
     * 每幀渲染 LOD 地形（AFTER_SOLID_BLOCKS 階段呼叫）。
     *
     * 渲染流程：
     *   1. LOD OpenGL 渲染（所有 LOD chunk）
     *   2. RT dispatch（若 RT 可用：TLAS → trace rays → composite）
     *
     * @param partialTick 插值係數（0.0–1.0）
     */
    public void render(float partialTick) {
        if (!initialized) return;
        frameCount++;
        rtDispatchedThisFrame = false;  // 每幀重置，renderRT() 成功時設為 true

        // ─── Phase 1: LOD OpenGL 渲染 ───
        Camera mainCam = Minecraft.getInstance().gameRenderer.getMainCamera();
        boolean shaderActive = lodShaderProgram != null && lodShaderProgram.isLinked();
        if (shaderActive) {
            lodShaderProgram.use();
            lodShaderProgram.setUniforms(mainCam, partialTick);
        }
        terrainBuffer.render();
        if (shaderActive) {
            lodShaderProgram.unuse();
        }

        // ─── Phase 2: Vulkan RT ───
        if (rtEnabled) {
            renderRT();
        }
    }

    /** 舊介面相容（ForgeRenderEventBridge 呼叫） */
    public void onRenderFrame(float partialTick) {
        render(partialTick);
    }

    /**
     * Vulkan RT 渲染（Phase 2-D 目標：white/sky-blue overlay）。
     *
     * 流程：
     *   1. 確保 render target 尺寸正確
     *   2. 重建 TLAS（所有可見 BLAS）
     *   3. Record + submit trace rays command
     *   4. GL composite（50% blend，Phase 2 驗證用）
     */
    private void renderRT() {
        if (vkSwapchain == null || rtPipeline == null || accelBuilder == null) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Render target resize
        if (!vkSwapchain.isReady() || w != lastRtWidth || h != lastRtHeight) {
            if (!vkSwapchain.createRenderTarget(w, h)) {
                LOG.warn("createRenderTarget failed, disabling RT this frame");
                return;
            }
            lastRtWidth  = w;
            lastRtHeight = h;
        }

        // 2. Wait for previous frame
        vkSwapchain.waitForRenderFinished();
        vkSwapchain.ensureGeneralLayout();

        // 3. Build TLAS from all ready BLAS
        long[] readyKeys = chunkManager.getReadyChunkKeys();
        if (readyKeys.length == 0) return;

        long[] blasAddresses = new long[readyKeys.length];
        float[][] worldOffsets = new float[readyKeys.length][];
        int valid = 0;
        for (long key : readyKeys) {
            long addr = accelBuilder.getBLASAddress(key);
            if (addr != 0) {
                blasAddresses[valid] = addr;
                worldOffsets[valid]  = chunkWorldOffset(key);
                valid++;
            }
        }

        if (valid == 0) return;

        long tlasHandle = accelBuilder.rebuildTLAS(
            java.util.Arrays.copyOf(blasAddresses, valid),
            java.util.Arrays.copyOf(worldOffsets, valid));
        if (tlasHandle == 0) return;

        // 4. Camera UBO + trace rays（Phase 3 完整實作）
        updateCameraUBO();
        recordAndSubmitRT(tlasHandle, w, h);
        rtDispatchedThisFrame = true;
        LOG.trace("RT dispatch: {} BLASes, {}×{}", valid, w, h);
    }

    // ─── Phase 3 helpers ───

    /** 分配 camera UBO（host-mapped，每幀寫入） */
    private boolean allocateCameraUBO() {
        long[] buf = vkAllocator.allocateUniformBuffer(CAMERA_UBO_SIZE);
        if (buf == null) return false;
        cameraUBOBuffer    = buf[0];
        cameraUBOAllocation = buf[1];
        return true;
    }

    /** 從 VkContext command pool 分配持久化 frame command buffer */
    private long allocateFrameCommandBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(vkContext.getCommandPool())
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);

            PointerBuffer pCmd = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(vkContext.getDevice(), ai, pCmd) != VK_SUCCESS) {
                LOG.error("vkAllocateCommandBuffers (frame CB) failed");
                return VK_NULL_HANDLE;
            }
            return pCmd.get(0);
        }
    }

    /**
     * 從 Minecraft camera 狀態更新 camera UBO。
     *
     * UBO std140 layout（160 bytes）：
     *   mat4 viewInverse  — 相機到世界空間變換
     *   mat4 projInverse  — 裁切空間到視空間逆投影
     *   vec4 cameraPos    — 世界空間相機位置
     *   float time, debugMode, _pad0, _pad1
     */
    private void updateCameraUBO() {
        if (cameraUBOBuffer == VK_NULL_HANDLE) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;

        Camera cam = mc.gameRenderer.getMainCamera();
        float px = (float) cam.getPosition().x;
        float py = (float) cam.getPosition().y;
        float pz = (float) cam.getPosition().z;

        // 相機座標系（從 Camera 方向向量重建）
        Vector3f look = cam.getLookVector();
        Vector3f up   = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        // right = -left（Minecraft getLeftVector 返回真正的左向量）
        float rx = -left.x, ry = -left.y, rz = -left.z;

        // viewInverse：相機→世界空間（basis columns + translation）
        // JOML Matrix4f：m[column][row] 命名，column-major
        Matrix4f viewInverse = new Matrix4f();
        // column 0 = right
        viewInverse.m00(rx); viewInverse.m01(ry); viewInverse.m02(rz); viewInverse.m03(0);
        // column 1 = up
        viewInverse.m10(up.x); viewInverse.m11(up.y); viewInverse.m12(up.z); viewInverse.m13(0);
        // column 2 = look（相機 -Z = forward）
        viewInverse.m20(look.x); viewInverse.m21(look.y); viewInverse.m22(look.z); viewInverse.m23(0);
        // column 3 = translation（camera world pos）
        viewInverse.m30(px); viewInverse.m31(py); viewInverse.m32(pz); viewInverse.m33(1);

        // projInverse：逆投影矩陣
        Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f projInverse = proj.invert(new Matrix4f());

        // 封裝為 40 floats（std140 column-major）
        float[] data = new float[CAMERA_UBO_SIZE / Float.BYTES]; // 40 floats
        viewInverse.get(data, 0);   // bytes  0–63
        projInverse.get(data, 16);  // bytes 64–127
        data[32] = px;              // cameraPos.x  byte 128
        data[33] = py;              // cameraPos.y  byte 132
        data[34] = pz;              // cameraPos.z  byte 136
        data[35] = 1.0f;            // cameraPos.w  byte 140
        data[36] = (float) ((System.currentTimeMillis() % 1_000_000L) / 1000.0); // time
        data[37] = 0.0f;            // debugMode
        data[38] = 0.0f;            // _pad0
        data[39] = 0.0f;            // _pad1

        vkAllocator.writeFloats(cameraUBOAllocation, data);
    }

    /**
     * 重置 frame command buffer，錄製 traceRays，提交到 graphics queue。
     *
     * @param tlasHandle TLAS handle（VkAccelerationStructureKHR）
     * @param w          輸出寬度（pixels）
     * @param h          輸出高度（pixels）
     */
    private void recordAndSubmitRT(long tlasHandle, int w, int h) {
        if (frameCmdBuffer == VK_NULL_HANDLE || rtPipeline == null) return;

        VkDevice device = vkContext.getDevice();
        VkCommandBuffer cmd = new VkCommandBuffer(frameCmdBuffer, device);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 重置並開始錄製（pool 有 RESET_COMMAND_BUFFER_BIT）
            vkResetCommandBuffer(cmd, 0);
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, bi);

            // 錄製 traceRays（Phase 3：vertex/stress SSBO 傳 0 → fallback 到 uboBuffer）
            rtPipeline.recordTraceRays(
                frameCmdBuffer,
                tlasHandle,
                vkSwapchain.getRTOutputImageView(),
                cameraUBOBuffer,
                0L,  // vertex SSBO — Phase 4 升級為獨立 consolidated SSBO
                0L,  // stress SSBO — Phase 4 升級
                w, h);

            vkEndCommandBuffer(cmd);

            // 提交（不等待：fence 在下幀 waitForRenderFinished() 中等待）
            PointerBuffer pCmd = stack.pointers(frameCmdBuffer);
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(pCmd);
            int result = vkQueueSubmit(vkContext.getGraphicsQueue(),
                submitInfo, vkSwapchain.getRenderFinishedFence());
            if (result != VK_SUCCESS) {
                LOG.warn("vkQueueSubmit (RT) failed: {}", result);
            }
        }
    }

    /** 從 chunkKey 解碼世界座標偏移（格式：x<<32 | z，與 LODChunkManager.chunkKey 一致） */
    private static float[] chunkWorldOffset(long chunkKey) {
        int cx = (int)(chunkKey >>> 32);          // upper 32 bits = chunkX
        int cz = (int)(chunkKey & 0xFFFFFFFFL);   // lower 32 bits = chunkZ
        return new float[]{ cx * 16.0f, 0.0f, cz * 16.0f };
    }

    // ═══ Tick 更新 ═══

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (instance == null || !instance.initialized) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;

        instance.chunkManager.tick();

        // Phase 2: 每 tick 嘗試為新就緒的 chunk 建構 BLAS
        if (instance.rtEnabled) {
            instance.tickBLASBuilds();
        }
    }

    /**
     * 為新就緒的 LOD chunk 觸發 BLAS 建構。
     *
     * 每 tick 最多建構 2 個 BLAS（避免 GPU stall 阻塞主執行緒）。
     * BLAS 建構使用 one-time command buffer，同步等待完成。
     * Phase 3 升級：改為非同步建構（VkFence polling on worker thread）。
     */
    private void tickBLASBuilds() {
        if (accelBuilder == null) return;

        long[] readyKeys = chunkManager.getReadyChunkKeys();
        int built = 0;
        for (long key : readyKeys) {
            if (built >= 2) break;
            if (accelBuilder.hasBLAS(key)) continue;

            LODChunkManager.MeshData mesh = chunkManager.getMeshData(key);
            if (mesh == null || mesh.vertices() == null || mesh.indices() == null) continue;

            long blasAddr = accelBuilder.buildBLAS(
                key, mesh.vertices(), mesh.vertexCount(), mesh.indices());
            if (blasAddr != 0) {
                built++;
                LOG.debug("BLAS built for chunk 0x{}, addr=0x{}",
                    Long.toHexString(key), Long.toHexString(blasAddr));
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (instance != null) {
            instance.chunkManager.clear();
            // RT cleanup：清除所有 BLAS（TLAS 在下次 rebuildTLAS 時重建）
            if (instance.accelBuilder != null) {
                instance.accelBuilder.cleanup();
            }
            LOG.info("LOD data cleared on logout");
        }
    }

    // ═══ 統計（Debug HUD 用） ═══

    public String getDebugInfo() {
        if (!initialized) return "LOD: disabled";
        return String.format(
            "LOD: %d ready | %d building | %.1f%% VBO | RT: %s | BLASes: %d | frame: %d",
            chunkManager.getReadyCount(),
            chunkManager.getBuildingCount(),
            terrainBuffer.getUtilization() * 100f,
            rtEnabled ? "ON" : "OFF",
            accelBuilder != null ? accelBuilder.getBLASCount() : 0,
            frameCount
        );
    }

    // ═══ Getters ═══

    public LODChunkManager   getChunkManager()   { return chunkManager; }
    public LODTerrainBuffer  getTerrainBuffer()  { return terrainBuffer; }
    public VoxyLODMesher     getMesher()         { return mesher; }
    public VkContext         getVkContext()       { return vkContext; }
    public VkAccelStructBuilder getAccelBuilder(){ return accelBuilder; }
    public VkRTPipeline      getRTPipeline()     { return rtPipeline; }
    public VkSwapchain       getVkSwapchain()    { return vkSwapchain; }
    public boolean isInitialized()               { return initialized; }
    public boolean isRTEnabled()                 { return rtEnabled; }
    /** 本幀是否已成功執行 RT dispatch（composite pass 用來判斷是否需要繪製 overlay） */
    public boolean isRTDispatchedThisFrame()     { return rtDispatchedThisFrame; }
}
