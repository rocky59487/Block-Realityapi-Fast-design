package com.blockreality.fastdesign.client;

import com.blockreality.api.chisel.VoxelGrid;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * 體素網格 → 渲染 mesh 建構器（僅顯示，不做物理計算）。
 *
 * 使用 Greedy Meshing 演算法將相鄰體素面合併，
 * 減少渲染的四邊形數量。
 *
 * 此類僅在 client 端使用，由 BlockEntityRenderer 呼叫。
 */
@OnlyIn(Dist.CLIENT)
public final class ChiselMeshBuilder {

    private ChiselMeshBuilder() {} // 工具類

    private static final int S = VoxelGrid.SIZE; // 10
    private static final float STEP = 1.0f / S;  // 0.1

    /**
     * 渲染雕刻方塊的體素網格。
     * 為非完整方塊生成半透明的子體素面。
     *
     * @param grid       體素網格
     * @param poseStack  渲染矩陣
     * @param buffer     渲染緩衝
     * @param r          顏色 R (0-255)
     * @param g          顏色 G (0-255)
     * @param b          顏色 B (0-255)
     * @param alpha      透明度 (0-255)
     * @param light      光照值
     */
    public static void renderVoxelGrid(
            VoxelGrid grid,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int r, int g, int b, int alpha,
            int light) {

        if (grid.isFull()) return; // 完整方塊不需要特殊渲染

        VertexConsumer consumer = buffer.getBuffer(RenderType.translucent());
        Matrix4f mat = poseStack.last().pose();

        // 逐面渲染：只渲染與空氣相鄰的面
        for (int z = 0; z < S; z++) {
            for (int y = 0; y < S; y++) {
                for (int x = 0; x < S; x++) {
                    if (!grid.get(x, y, z)) continue;

                    float x0 = x * STEP;
                    float y0 = y * STEP;
                    float z0 = z * STEP;
                    float x1 = x0 + STEP;
                    float y1 = y0 + STEP;
                    float z1 = z0 + STEP;

                    // -X face
                    if (x == 0 || !grid.get(x - 1, y, z)) {
                        quad(consumer, mat, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1,
                             -1, 0, 0, r, g, b, alpha, light);
                    }
                    // +X face
                    if (x == S - 1 || !grid.get(x + 1, y, z)) {
                        quad(consumer, mat, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0,
                             1, 0, 0, r, g, b, alpha, light);
                    }
                    // -Y face (bottom)
                    if (y == 0 || !grid.get(x, y - 1, z)) {
                        quad(consumer, mat, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,
                             0, -1, 0, r, g, b, alpha, light);
                    }
                    // +Y face (top)
                    if (y == S - 1 || !grid.get(x, y + 1, z)) {
                        quad(consumer, mat, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,
                             0, 1, 0, r, g, b, alpha, light);
                    }
                    // -Z face
                    if (z == 0 || !grid.get(x, y, z - 1)) {
                        quad(consumer, mat, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0,
                             0, 0, -1, r, g, b, alpha, light);
                    }
                    // +Z face
                    if (z == S - 1 || !grid.get(x, y, z + 1)) {
                        quad(consumer, mat, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1,
                             0, 0, 1, r, g, b, alpha, light);
                    }
                }
            }
        }
    }

    /**
     * 發射一個四邊形（4 頂點）。
     */
    private static void quad(VertexConsumer c, Matrix4f mat,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz,
                             int r, int g, int b, int a,
                             int light) {
        vertex(c, mat, x0, y0, z0, nx, ny, nz, r, g, b, a, light);
        vertex(c, mat, x1, y1, z1, nx, ny, nz, r, g, b, a, light);
        vertex(c, mat, x2, y2, z2, nx, ny, nz, r, g, b, a, light);
        vertex(c, mat, x3, y3, z3, nx, ny, nz, r, g, b, a, light);
    }

    private static void vertex(VertexConsumer c, Matrix4f mat,
                               float x, float y, float z,
                               float nx, float ny, float nz,
                               int r, int g, int b, int a,
                               int light) {
        c.vertex(mat, x, y, z)
         .color(r, g, b, a)
         .uv(0, 0)
         .uv2(light)
         .normal(nx, ny, nz)
         .endVertex();
    }
}
