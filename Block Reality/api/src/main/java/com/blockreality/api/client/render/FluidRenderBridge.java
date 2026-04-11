package com.blockreality.api.client.render;

import com.blockreality.api.physics.fluid.FluidRegion;
import com.blockreality.api.physics.fluid.FluidRegionRegistry;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流體渲染橋接器 — 每幀將 {@link FluidSurfaceMesher} 輸出傳給 Vulkan RT 透明渲染 pass。
 *
 * <p>協調三個子系統：
 * <ol>
 *   <li>{@link FluidSurfaceMesher} — 從 sub-cell VOF 生成 Marching Cubes 三角形</li>
 *   <li>Vulkan RT 透明 pass — 若可用，上傳 BLAS 並執行光追水面渲染</li>
 *   <li>{@link FluidSplashEmitter} — 在高速流體邊界發射水花粒子</li>
 * </ol>
 *
 * <h3>Vulkan RT 回退策略</h3>
 * <p>若 Vulkan RT 不可用（{@code VulkanComputeContext.isAvailable() == false}），
 * 回退到 {@code RenderPipeline} 的 legacy 透明渲染路徑（Minecraft 原生水面貼圖 + alpha blending）。
 *
 * <h3>每幀執行時序</h3>
 * <pre>
 * 1. onClientTick() — 更新 VOF 網格（增量 rebuild），更新粒子發射
 * 2. uploadMesh()   — 上傳頂點緩衝到 Vulkan（BLAS 更新 或 legacy VBO）
 * 3. submitTransparentPass() — 發送透明 draw call
 * </pre>
 *
 * @see FluidSurfaceMesher
 * @see FluidSplashEmitter
 */
@OnlyIn(Dist.CLIENT)
public class FluidRenderBridge {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidRender");

    private static FluidRenderBridge instance;

    /** 每個活動流體區域的水面網格 FloatBuffer（位置 + 法線，交錯格式） */
    private final ConcurrentHashMap<Integer, FloatBuffer> meshCache = new ConcurrentHashMap<>();

    /** Vulkan RT 可用性（延遲初始化） */
    private boolean vulkanRTAvailable = false;
    private boolean initialized = false;

    private FluidRenderBridge() {}

    public static FluidRenderBridge getInstance() {
        if (instance == null) {
            instance = new FluidRenderBridge();
        }
        return instance;
    }

    /**
     * 初始化（在客戶端 setup 完成後呼叫）。
     */
    public void init() {
        if (initialized) return;
        vulkanRTAvailable = com.blockreality.api.physics.pfsf.VulkanComputeContext.isAvailable();
        initialized = true;
        LOGGER.info("[BR-FluidRender] Initialized, Vulkan RT: {}", vulkanRTAvailable);
    }

    /**
     * 每客戶端 tick 呼叫 — 增量重建水面網格並更新粒子發射。
     *
     * <p>在 ClientTickEvent 末期呼叫（在 FluidGPUEngine.tick() 完成後）。
     */
    public void onClientTick() {
        if (!initialized) return;

        FluidRegionRegistry registry = FluidRegionRegistry.getInstance();
        for (FluidRegion region : registry.getActiveRegions()) {
            int id = region.getRegionId();

            // 增量重建網格
            FloatBuffer existing = meshCache.get(id);
            FloatBuffer updated = FluidSurfaceMesher.updateDirtyChunks(
                region,
                region.getOriginX(), region.getOriginY(), region.getOriginZ(),
                existing
            );
            if (updated != null) {
                meshCache.put(id, updated);
            } else {
                meshCache.remove(id);
            }

            // 粒子發射（Minecraft 原生粒子系統）
            FluidSplashEmitter.emitAtBoundary(
                region,
                region.getOriginX(), region.getOriginY(), region.getOriginZ(),
                (wx, wy, wz, vx, vy, vz) -> spawnSplashParticle(wx, wy, wz, vx, vy, vz)
            );
        }
    }

    /**
     * 上傳指定區域的水面網格到 GPU。
     *
     * <p>若 Vulkan RT 可用，呼叫 BLAS 更新（讓 RT pipeline 重建加速結構）；
     * 否則上傳到傳統 VBO 供 fixed-function 透明渲染使用。
     *
     * @param regionId 流體區域 ID
     */
    public void uploadMesh(int regionId) {
        FloatBuffer mesh = meshCache.get(regionId);
        if (mesh == null || !mesh.hasRemaining()) return;

        if (vulkanRTAvailable) {
            uploadToVulkanBLAS(regionId, mesh);
        } else {
            uploadToLegacyVBO(regionId, mesh);
        }
    }

    /**
     * 提交透明渲染 pass（在主渲染迴圈的透明 pass 中呼叫）。
     *
     * @param pipeline 當前 RenderPipeline（legacy path 用）
     */
    public void submitTransparentPass(@Nullable PersistentRenderPipeline pipeline) {
        for (Map.Entry<Integer, FloatBuffer> entry : meshCache.entrySet()) {
            FloatBuffer mesh = entry.getValue();
            if (mesh == null || !mesh.hasRemaining()) continue;

            if (vulkanRTAvailable) {
                submitVulkanTransparentDraw(entry.getKey(), mesh);
            } else if (pipeline != null) {
                submitLegacyTransparentDraw(entry.getKey(), mesh, pipeline);
            }
        }
    }

    /**
     * 釋放所有緩衝並清除狀態（Mod 卸載時呼叫）。
     */
    public void shutdown() {
        for (FloatBuffer buf : meshCache.values()) {
            if (buf != null) MemoryUtil.memFree(buf);
        }
        meshCache.clear();
        initialized = false;
        LOGGER.info("[BR-FluidRender] Shutdown");
    }

    // ─── 實作細節 ───

    private void uploadToVulkanBLAS(int regionId, FloatBuffer mesh) {
        // 實際實作：呼叫 VulkanComputeContext / RT pipeline 更新 BLAS
        // VulkanRT.updateFluidBLAS(regionId, mesh);
    }

    private void uploadToLegacyVBO(int regionId, FloatBuffer mesh) {
        // 實際實作：使用 LWJGL GL33 上傳 VBO
        // GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, vboHandles.get(regionId));
        // GL33.glBufferData(GL33.GL_ARRAY_BUFFER, mesh, GL33.GL_DYNAMIC_DRAW);
    }

    private void submitVulkanTransparentDraw(int regionId, FloatBuffer mesh) {
        // 實際實作：透過 RT pipeline 提交 draw call
        // VulkanRT.submitTransparentDraw(regionId);
    }

    private void submitLegacyTransparentDraw(int regionId, FloatBuffer mesh,
                                              PersistentRenderPipeline pipeline) {
        // 實際實作：使用 RenderPipeline legacy transparent pass
        // pipeline.submitTransparentMesh(regionId, mesh);
    }

    private void spawnSplashParticle(float wx, float wy, float wz,
                                      float vx, float vy, float vz) {
        // 委託 Minecraft 客戶端粒子系統
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.addParticle(
            net.minecraft.core.particles.ParticleTypes.SPLASH,
            wx, wy, wz,
            vx * 0.1, vy * 0.1, vz * 0.1  // 縮放速度以適配粒子系統
        );
    }
}
