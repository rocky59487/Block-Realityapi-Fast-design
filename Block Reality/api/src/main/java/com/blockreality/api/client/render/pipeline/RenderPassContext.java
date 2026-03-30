package com.blockreality.api.client.render.pipeline;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 單一渲染 Pass 的執行上下文。
 *
 * 包含該 pass 所需的所有狀態：
 *   - 當前 pass 階段
 *   - 攝影機資訊（視角矩陣、投影矩陣）
 *   - PoseStack（用於世界座標偏移）
 *   - Framebuffer ID（該 pass 寫入的 FBO）
 *   - partial tick（用於插值）
 *   - 陰影光源方向（僅 SHADOW pass）
 *
 * @deprecated Since 2.0, replaced by Vulkan RT + Voxy LOD pipeline in com.blockreality.api.client.rendering
 */
@Deprecated(since = "2.0", forRemoval = true)
@OnlyIn(Dist.CLIENT)
public final class RenderPassContext {

    private final RenderPass pass;
    private final PoseStack poseStack;
    private final Camera camera;
    private final Matrix4f projectionMatrix;
    private final Matrix4f viewMatrix;
    private final float partialTick;
    private final int framebufferId;

    // 光源資訊（Deferred Lighting 用）
    private float sunAngle;
    private float shadowLightDirX, shadowLightDirY, shadowLightDirZ;

    // 時間（shader uniform 用）
    private float gameTime;
    private int worldTick;

    public RenderPassContext(RenderPass pass, PoseStack poseStack, Camera camera,
                             Matrix4f projectionMatrix, Matrix4f viewMatrix,
                             float partialTick, int framebufferId) {
        this.pass = pass;
        this.poseStack = poseStack;
        this.camera = camera;
        this.projectionMatrix = projectionMatrix;
        this.viewMatrix = viewMatrix;
        this.partialTick = partialTick;
        this.framebufferId = framebufferId;
    }

    // ─── Getters ──────────────────────────────────────────

    public RenderPass getPass() { return pass; }
    public PoseStack getPoseStack() { return poseStack; }
    public Camera getCamera() { return camera; }
    public Matrix4f getProjectionMatrix() { return projectionMatrix; }
    public Matrix4f getViewMatrix() { return viewMatrix; }
    public float getPartialTick() { return partialTick; }
    public int getFramebufferId() { return framebufferId; }

    public float getSunAngle() { return sunAngle; }
    public float getShadowLightDirX() { return shadowLightDirX; }
    public float getShadowLightDirY() { return shadowLightDirY; }
    public float getShadowLightDirZ() { return shadowLightDirZ; }

    public float getGameTime() { return gameTime; }
    public int getWorldTick() { return worldTick; }

    // ─── Setters（Builder 模式） ─────────────────────────

    public RenderPassContext withSunAngle(float sunAngle) {
        this.sunAngle = sunAngle;
        return this;
    }

    public RenderPassContext withShadowLightDir(float x, float y, float z) {
        this.shadowLightDirX = x;
        this.shadowLightDirY = y;
        this.shadowLightDirZ = z;
        return this;
    }

    public RenderPassContext withTime(float gameTime, int worldTick) {
        this.gameTime = gameTime;
        this.worldTick = worldTick;
        return this;
    }

    // ─── 工具方法 ────────────────────────────────────────

    /** 取得攝影機世界座標（用於頂點偏移） */
    public double getCamX() { return camera.getPosition().x; }
    public double getCamY() { return camera.getPosition().y; }
    public double getCamZ() { return camera.getPosition().z; }

    /** 是否為需要寫入 GBuffer 的 pass */
    public boolean isGBufferPass() { return pass.writesGBuffer(); }

    /** 是否為後處理 pass（全螢幕 quad） */
    public boolean isPostProcessPass() { return pass.isPostProcess(); }
}
