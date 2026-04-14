package com.blockreality.api.client.render;

import com.blockreality.api.chisel.VoxelGrid;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Matrix4f;

/**
 * 體素網格 → 渲染 mesh 建構器（僅顯示，不做物理計算）。
 *
 * 使用面剔除演算法將與空氣相鄰的體素面渲染出來。
 * 每個 0.1m 子體素對應 RenderType.cutoutMipped() 不透明面。
 *
 * 此類僅在 client 端使用，由 BlockEntityRenderer 呼叫。
 */
public final class ChiselMeshBuilder {

    private ChiselMeshBuilder() {} // 工具類

    private static final int S = VoxelGrid.SIZE; // 10
    private static final float STEP = 1.0f / S;  // 0.1

    // 預設使用石材紋理 — 固定 UV 錨點避免每幀查詢
    private static final ResourceLocation STONE_SPRITE_LOC =
            new ResourceLocation("minecraft", "block/stone");

    /**
     * 渲染雕刻方塊的體素網格。
     * 為非完整方塊生成子體素面（0.1m 精度）。
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

        // 從方塊紋理圖集取得石材 sprite，獲取有效的 UV 座標範圍。
        // 原本 uv(0,0) 指向圖集左上角（空白/透明區域），導致微體素不可見。
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getModelManager()
                .getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(STONE_SPRITE_LOC);
        float su0 = sprite.getU0(), su1 = sprite.getU1();
        float sv0 = sprite.getV0(), sv1 = sprite.getV1();

        // cutoutMipped：不透明面渲染通道，效能優於 translucent 且排序正確
        VertexConsumer consumer = buffer.getBuffer(RenderType.cutoutMipped());
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
                             -1, 0, 0, r, g, b, alpha, light, su0, sv0, su1, sv1);
                    }
                    // +X face
                    if (x == S - 1 || !grid.get(x + 1, y, z)) {
                        quad(consumer, mat, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0,
                             1, 0, 0, r, g, b, alpha, light, su0, sv0, su1, sv1);
                    }
                    // -Y face (bottom)
                    if (y == 0 || !grid.get(x, y - 1, z)) {
                        quad(consumer, mat, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,
                             0, -1, 0, r, g, b, alpha, light, su0, sv0, su1, sv1);
                    }
                    // +Y face (top)
                    if (y == S - 1 || !grid.get(x, y + 1, z)) {
                        quad(consumer, mat, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,
                             0, 1, 0, r, g, b, alpha, light, su0, sv0, su1, sv1);
                    }
                    // -Z face
                    if (z == 0 || !grid.get(x, y, z - 1)) {
                        quad(consumer, mat, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0,
                             0, 0, -1, r, g, b, alpha, light, su0, sv0, su1, sv1);
                    }
                    // +Z face
                    if (z == S - 1 || !grid.get(x, y, z + 1)) {
                        quad(consumer, mat, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1,
                             0, 0, 1, r, g, b, alpha, light, su0, sv0, su1, sv1);
                    }
                }
            }
        }
    }

    /**
     * 發射一個四邊形（4 頂點），UV 對應 sprite 完整範圍。
     * su0/sv0 = sprite 左上角；su1/sv1 = sprite 右下角。
     */
    private static void quad(VertexConsumer c, Matrix4f mat,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float nx, float ny, float nz,
                             int r, int g, int b, int a,
                             int light,
                             float su0, float sv0, float su1, float sv1) {
        vertex(c, mat, x0, y0, z0, nx, ny, nz, r, g, b, a, light, su0, sv0);
        vertex(c, mat, x1, y1, z1, nx, ny, nz, r, g, b, a, light, su0, sv1);
        vertex(c, mat, x2, y2, z2, nx, ny, nz, r, g, b, a, light, su1, sv1);
        vertex(c, mat, x3, y3, z3, nx, ny, nz, r, g, b, a, light, su1, sv0);
    }

    private static void vertex(VertexConsumer c, Matrix4f mat,
                               float x, float y, float z,
                               float nx, float ny, float nz,
                               int r, int g, int b, int a,
                               int light, float u, float v) {
        c.vertex(mat, x, y, z)
         .color(r, g, b, a)
         .uv(u, v)
         .uv2(light)
         .normal(nx, ny, nz)
         .endVertex();
    }
}
