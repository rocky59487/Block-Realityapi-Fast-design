package com.blockreality.api.client.render;

import com.blockreality.api.block.RBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public class RBlockEntityRenderer implements BlockEntityRenderer<RBlockEntity> {

    public RBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!blockEntity.getChiselState().isFull()) {
            int r = 255;
            int g = 255;
            int b = 255;
            int a = 255;

            poseStack.pushPose();

            com.blockreality.api.client.render.ChiselMeshBuilder.renderVoxelGrid(
                blockEntity.getChiselState().voxelGrid(),
                poseStack,
                bufferSource,
                r, g, b, a,
                packedLight
            );

            poseStack.popPose();
        }
    }
}
