package com.blockreality.api.client.render;

import com.blockreality.api.fragment.StructureFragmentEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * Client-side renderer for {@link StructureFragmentEntity}.
 *
 * Rendering approach
 * ──────────────────
 * · Each tick, the entity carries a LOCAL-space block snapshot (map of offsets → states).
 * · The entity's world position is its centre of mass (set by the server each tick).
 * · The entity's rotation quaternion is synced via SynchedEntityData.
 *
 * Per-frame:
 *   1. Push pose.
 *   2. Apply rotation quaternion from SynchedEntityData (lerped by partial-tick on client).
 *   3. For each local block: translate by its local-grid offset, call renderSingleBlock.
 *   4. Pop pose.
 *
 * Performance note: for fragments up to MAX_FRAGMENT_BLOCKS (2048), this calls
 * renderSingleBlock 2048 times. The Minecraft batch renderer (MultiBufferSource.BufferSource)
 * accumulates all geometry into vertex buffers before flushing, so the cost is proportional
 * to vertex count, not draw calls. Acceptable for physics lifetime ≤ 30 s (600 ticks).
 *
 * This class is {@link OnlyIn}(CLIENT) and must never be referenced from server code.
 */
@OnlyIn(Dist.CLIENT)
public class StructureFragmentRenderer extends EntityRenderer<StructureFragmentEntity> {

    public StructureFragmentRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(StructureFragmentEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {

        Map<BlockPos, BlockState> snapshot = entity.getLocalSnapshot();
        if (snapshot.isEmpty()) return;

        poseStack.pushPose();

        // Apply rotation quaternion (identity = (0,0,0,1); updated each server tick via SynchedEntityData).
        // No partial-tick interpolation for the quaternion — server sends it every tick anyway.
        float qx = entity.getRotQx();
        float qy = entity.getRotQy();
        float qz = entity.getRotQz();
        float qw = entity.getRotQw();
        poseStack.mulPose(new Quaternionf(qx, qy, qz, qw));

        // Render each block at its local-space position
        net.minecraft.client.renderer.block.BlockRenderDispatcher dispatcher =
            Minecraft.getInstance().getBlockRenderer();

        for (Map.Entry<BlockPos, BlockState> entry : snapshot.entrySet()) {
            BlockPos   lp    = entry.getKey();
            BlockState state = entry.getValue();
            if (state == null || state.isAir()) continue;

            poseStack.pushPose();
            // Translate so the block's lower-corner is at (lp.x, lp.y, lp.z) in local space.
            // Minecraft's renderSingleBlock renders the block filling [0,1]³ in model space,
            // so this translation places the block exactly at its local-grid position.
            poseStack.translate(lp.getX(), lp.getY(), lp.getZ());

            dispatcher.renderSingleBlock(
                state,
                poseStack,
                bufferSource,
                packedLight,
                OverlayTexture.NO_OVERLAY
            );
            poseStack.popPose();
        }

        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /**
     * Entity renderers require a texture location, but all geometry here is rendered
     * through {@code renderSingleBlock} which uses the block atlas internally.
     * Return the block-texture atlas as a safe fallback.
     */
    @SuppressWarnings("deprecation")
    @Override
    public ResourceLocation getTextureLocation(StructureFragmentEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
