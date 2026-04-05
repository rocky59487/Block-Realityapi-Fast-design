package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 結構特效渲染器 — 崩塌碎片、應力警告閃爍。
 *
 * 特效：
 *   1. 崩塌碎片 — 結構失去支撐時方塊碎裂飛散
 *   2. 應力警告閃爍 — 高應力方塊紅色脈衝
 *   3. 裂縫線 — 接近破壞極限的方塊表面裂紋
 */
@OnlyIn(Dist.CLIENT)
public final class StructuralFXRenderer {

    /** 崩塌碎片 */
    private static final class Fragment {
        float x, y, z;
        float vx, vy, vz;
        float rx, ry, rz;       // 旋轉角度
        float rvx, rvy, rvz;    // 旋轉速度
        float r, g, b;
        float size;
        int life;

        Fragment(BlockPos pos, float r, float g, float b) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            this.x = pos.getX() + rng.nextFloat();
            this.y = pos.getY() + rng.nextFloat();
            this.z = pos.getZ() + rng.nextFloat();
            // ★ review-fix ICReM: 增強碎片速度分布 — 更有爆裂感
            this.vx = (rng.nextFloat() - 0.5f) * 0.4f;
            this.vy = rng.nextFloat() * 0.25f + 0.05f; // 確保向上初速
            this.vz = (rng.nextFloat() - 0.5f) * 0.4f;
            this.rx = 0; this.ry = 0; this.rz = 0;
            this.rvx = (rng.nextFloat() - 0.5f) * 25;
            this.rvy = (rng.nextFloat() - 0.5f) * 25;
            this.rvz = (rng.nextFloat() - 0.5f) * 25;
            // ★ review-fix ICReM: 顏色微變化 — 同材質碎片略有色差
            float colorVar = 0.9f + rng.nextFloat() * 0.2f; // 0.9~1.1
            this.r = Math.min(1.0f, r * colorVar);
            this.g = Math.min(1.0f, g * colorVar);
            this.b = Math.min(1.0f, b * colorVar);
            this.size = 0.04f + rng.nextFloat() * 0.18f;
            this.life = 35 + rng.nextInt(25); // 1.75~3.0 秒（延長展示）
        }

        void tick() {
            x += vx; y += vy; z += vz;
            vy -= 0.018f; // ★ review-fix ICReM: 略強重力 → 更真實的拋物線
            vx *= 0.97f; vz *= 0.97f; // 空氣阻力
            vy *= 0.99f;
            rx += rvx; ry += rvy; rz += rvz;
            // ★ review-fix ICReM: 旋轉速度衰減（碎片逐漸穩定）
            rvx *= 0.98f; rvy *= 0.98f; rvz *= 0.98f;
            life--;
        }

        boolean isDead() { return life <= 0; }
        // ★ review-fix ICReM: 更長的淡出期（15 ticks = 0.75 秒）
        float alpha() { return Math.min(1.0f, life / 15.0f); }
    }

    /** ★ review-fix ICReM: 增強應力警告 — 更長持續時間 + 裂縫指示 */
    private static final class StressWarning {
        final BlockPos pos;
        final float stressLevel; // [0, 1+]
        int life;
        final int maxLife;

        StressWarning(BlockPos pos, float stress) {
            this.pos = pos;
            this.stressLevel = stress;
            // ★ review-fix ICReM: 高應力持續更久（應力越高越醒目）
            this.maxLife = (int) (40 + 20 * Math.min(stress, 1.5f)); // 40~70 ticks
            this.life = this.maxLife;
        }

        void tick() { life--; }
        boolean isDead() { return life <= 0; }
    }

    /**
     * 電影級墜落碎塊 — 多方塊群組帶旋轉墜落、地面碰撞彈跳。
     * 比 Fragment（小碎片粒子）大 5-10 倍，模擬建築結構體掉落。
     */
    private static final class FallingChunk {
        float x, y, z;
        float vx, vy, vz;
        float rotX, rotY, rotZ;     // 旋轉角度
        float rotVx, rotVy;         // 旋轉速度
        float r, g, b;
        float scale;                // 方塊大小（0.6-1.2 = 接近完整方塊）
        int life;
        boolean grounded;           // 已著地（停止位移，只衰退）
        int bounceCount;            // 彈跳次數

        FallingChunk(BlockPos pos, float r, float g, float b, float scale) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            this.x = pos.getX() + 0.5f + (rng.nextFloat() - 0.5f) * 0.4f;
            this.y = pos.getY() + 0.5f;
            this.z = pos.getZ() + 0.5f + (rng.nextFloat() - 0.5f) * 0.4f;
            this.vx = (rng.nextFloat() - 0.5f) * 0.08f;
            this.vy = 0.02f + rng.nextFloat() * 0.03f;  // 初始微上拋
            this.vz = (rng.nextFloat() - 0.5f) * 0.08f;
            this.rotX = rng.nextFloat() * 10;
            this.rotY = rng.nextFloat() * 360;
            this.rotZ = rng.nextFloat() * 10;
            this.rotVx = (rng.nextFloat() - 0.5f) * 8.0f;
            this.rotVy = (rng.nextFloat() - 0.5f) * 5.0f;
            // 顏色微偏差
            this.r = r + (rng.nextFloat() - 0.5f) * 0.08f;
            this.g = g + (rng.nextFloat() - 0.5f) * 0.08f;
            this.b = b + (rng.nextFloat() - 0.5f) * 0.08f;
            this.scale = scale;
            this.life = 50 + rng.nextInt(30);  // 50-80 ticks（2.5-4 秒）
            this.grounded = false;
            this.bounceCount = 0;
        }

        void tick() {
            if (!grounded) {
                x += vx; y += vy; z += vz;
                // 重力（比小碎片更重）
                vy -= 0.025f;
                // 空氣阻力
                vx *= 0.985f; vz *= 0.985f; vy *= 0.995f;
                // 旋轉
                rotX += rotVx; rotY += rotVy;
                rotVx *= 0.97f; rotVy *= 0.97f;

                // 地面碰撞（y ≈ 起始高度 - 若 vy < 0 且 y 接近整數格）
                if (vy < 0 && y < Math.floor(y) + 0.2) {
                    if (bounceCount < 2) {
                        // 彈跳（能量保留 30%）
                        vy = Math.abs(vy) * 0.3f;
                        vx *= 0.5f; vz *= 0.5f;
                        rotVx *= 0.4f; rotVy *= 0.4f;
                        bounceCount++;
                    } else {
                        // 停止
                        grounded = true;
                        vy = 0; vx = 0; vz = 0;
                        rotVx = 0; rotVy = 0;
                    }
                }
            }
            life--;
        }

        boolean isDead() { return life <= 0; }
        float alpha() {
            if (life > 20) return 1.0f;
            return life / 20.0f;  // 最後 1 秒淡出
        }
    }

    private final List<Fragment> fragments = new ArrayList<>();
    private final List<StressWarning> warnings = new ArrayList<>();
    private final List<FallingChunk> fallingChunks = new ArrayList<>();

    StructuralFXRenderer() {}

    // ═══════════════════════════════════════════════════════
    //  觸發
    // ═══════════════════════════════════════════════════════

    /**
     * 在方塊位置生成崩塌碎片（向後相容，預設 NO_SUPPORT 行為）。
     */
    public void spawnCollapseFX(BlockPos pos, int materialId) {
        spawnCollapseFX(pos, com.blockreality.api.physics.SupportPathAnalyzer.FailureType.NO_SUPPORT, materialId);
    }

    /**
     * ★ review-fix ICReM-5: 根據失敗類型生成不同的崩塌視覺效果。
     *
     * CANTILEVER_BREAK — 斷裂效果：
     *   大型碎片 + 斷裂線閃光 + 碎片沿斷裂面飛散
     *   模擬懸臂從根部折斷的視覺效果
     *
     * CRUSHING — 壓碎效果：
     *   大量微小碎片 + 粉塵雲 + 漸進式裂紋
     *   碎片主要向下和向外擴散
     *
     * NO_SUPPORT — 標準掉落：
     *   中等碎片 + 均勻擴散
     */
    public void spawnCollapseFX(BlockPos pos,
                                 com.blockreality.api.physics.SupportPathAnalyzer.FailureType type,
                                 int materialId) {
        float r, g, b;
        switch (materialId) {
            case 0 -> { r = 0.7f; g = 0.7f; b = 0.68f; }   // 混凝土
            case 1 -> { r = 0.55f; g = 0.6f; b = 0.65f; }  // 鋼材
            case 2 -> { r = 0.6f; g = 0.4f; b = 0.2f; }    // 木材
            case 3 -> { r = 0.5f; g = 0.5f; b = 0.55f; }   // 鋼筋
            case 4 -> { r = 0.9f; g = 0.75f; b = 0.2f; }   // RC 節點
            case 5 -> { r = 0.4f; g = 0.7f; b = 1.0f; }    // 錨樁
            default -> { r = 0.8f; g = 0.8f; b = 0.8f; }
        }

        switch (type) {
            case CANTILEVER_BREAK -> spawnCantileverBreakFX(pos, r, g, b);
            case CRUSHING -> spawnCrushingFX(pos, r, g, b);
            case TENSION_BREAK -> spawnTensionBreakFX(pos, r, g, b);
            case NO_SUPPORT -> spawnNoSupportFX(pos, r, g, b);
        }
    }

    /**
     * 懸臂斷裂效果：大型碎片沿斷裂面飛散 + 斷裂閃光
     */
    private void spawnCantileverBreakFX(BlockPos pos, float r, float g, float b) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // 大型碎片（較少但更大）— 模擬整段斷裂
        int count = Math.min(BRRenderConfig.COLLAPSE_FX_MAX_FRAGMENTS, 6 + rng.nextInt(4));
        for (int i = 0; i < count; i++) {
            Fragment f = new Fragment(pos, r, g, b);
            f.size *= 1.5f; // 更大的碎片
            f.vy = rng.nextFloat() * 0.1f - 0.05f; // 水平飛散為主，不太向上
            fragments.add(f);
        }
        // 斷裂面閃光粒子（白色高速小碎片）
        for (int i = 0; i < 4; i++) {
            Fragment spark = new Fragment(pos, 1.0f, 0.95f, 0.8f);
            spark.size = 0.02f + rng.nextFloat() * 0.03f;
            spark.vx *= 2.0f; spark.vz *= 2.0f; // 高速水平飛散
            spark.life = 8 + rng.nextInt(6); // 短壽命閃光
            fragments.add(spark);
        }
        // 應力警告（斷裂點脈衝）
        addStressWarning(pos, 1.5f);
    }

    /**
     * 壓碎效果：大量微小碎片 + 粉塵向下擴散
     */
    private void spawnCrushingFX(BlockPos pos, float r, float g, float b) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // 大量微小碎片（漸進式裂開）
        int count = Math.min(BRRenderConfig.COLLAPSE_FX_MAX_FRAGMENTS,
            16 + rng.nextInt(12));
        for (int i = 0; i < count; i++) {
            Fragment f = new Fragment(pos, r, g, b);
            f.size *= 0.5f; // 更小的碎片
            f.vx *= 0.6f; f.vz *= 0.6f; // 速度更低（壓碎非爆裂）
            f.vy = -(rng.nextFloat() * 0.15f); // 主要向下（壓碎）
            f.life += 10; // 粉塵持續更久
            fragments.add(f);
        }
        // 粉塵雲（半透明淺色微粒）
        float dustR = Math.min(1.0f, r + 0.2f);
        float dustG = Math.min(1.0f, g + 0.2f);
        float dustB = Math.min(1.0f, b + 0.2f);
        for (int i = 0; i < 8; i++) {
            Fragment dust = new Fragment(pos, dustR, dustG, dustB);
            dust.size = 0.08f + rng.nextFloat() * 0.12f;
            dust.vx *= 0.3f; dust.vy = rng.nextFloat() * 0.05f; dust.vz *= 0.3f;
            dust.life = 50 + rng.nextInt(20); // 長壽命粉塵
            dust.rvx = 0; dust.rvy = 0; dust.rvz = 0; // 粉塵不旋轉
            fragments.add(dust);
        }
        // 裂紋警告（多 tick 漸進）
        addStressWarning(pos, 1.2f);
    }

    /**
     * Fix 3: 拉力撕裂效果 — 水平噴射薄長碎片 + 裂紋線粒子。
     * 視覺上與 CANTILEVER（向下掉落）明確區分。
     */
    private void spawnTensionBreakFX(BlockPos pos, float r, float g, float b) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int count = Math.min(BRRenderConfig.COLLAPSE_FX_MAX_FRAGMENTS,
            10 + rng.nextInt(6));

        for (int i = 0; i < count; i++) {
            Fragment frag = new Fragment(pos, r, g, b);
            // 薄長碎片（拉扯撕裂的薄片形狀）
            frag.size = 0.02f + rng.nextFloat() * 0.06f;

            // 水平噴射（Y 速度極小，X/Z 速度大 — 模擬被拉扯飛出）
            frag.vx = (rng.nextFloat() - 0.5f) * 0.25f;
            frag.vy = rng.nextFloat() * 0.03f;
            frag.vz = (rng.nextFloat() - 0.5f) * 0.25f;

            // 高速旋轉（薄片在空中翻滾）
            frag.rvx = (rng.nextFloat() - 0.5f) * 30.0f;
            frag.rvy = (rng.nextFloat() - 0.5f) * 20.0f;
            frag.life += 5;  // 稍長壽命
            fragments.add(frag);
        }

        // 裂紋線粒子：2 條對稱線從中心向外擴展
        for (int axis = 0; axis < 2; axis++) {
            for (int seg = 0; seg < 3; seg++) {
                Fragment crack = new Fragment(pos, 1.0f, 0.9f, 0.7f);  // 淡黃色裂紋
                crack.size = 0.01f;
                float offset = (seg + 1) * 0.15f;
                crack.vx = axis == 0 ? offset * (rng.nextBoolean() ? 1 : -1) : 0;
                crack.vz = axis == 1 ? offset * (rng.nextBoolean() ? 1 : -1) : 0;
                crack.vy = 0;
                crack.life = 8 + seg * 3;  // 短壽命，形成擴展效果
                fragments.add(crack);
            }
        }

        addStressWarning(pos, 1.8f);  // 高強度裂紋閃光
    }

    /**
     * 無支撐掉落效果：標準碎片 + 均勻擴散
     */
    private void spawnNoSupportFX(BlockPos pos, float r, float g, float b) {
        int count = Math.min(BRRenderConfig.COLLAPSE_FX_MAX_FRAGMENTS,
            8 + ThreadLocalRandom.current().nextInt(8));
        for (int i = 0; i < count; i++) {
            fragments.add(new Fragment(pos, r, g, b));
        }
    }

    /**
     * 添加應力警告閃爍。
     */
    public void addStressWarning(BlockPos pos, float stressLevel) {
        // 避免同位置重複
        for (StressWarning w : warnings) {
            if (w.pos.equals(pos)) return;
        }
        warnings.add(new StressWarning(pos, stressLevel));
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    /**
     * 生成電影級墜落碎塊（崩塌時呼叫）。
     *
     * @param pos       崩塌位置
     * @param r,g,b     材質顏色
     * @param count     碎塊數量（1-4）
     * @param scaleBase 基礎大小（0.6 = 小碎塊，1.0 = 完整方塊大小）
     */
    void spawnFallingChunks(BlockPos pos, float r, float g, float b, int count, float scaleBase) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            float scale = scaleBase + (rng.nextFloat() - 0.5f) * 0.3f;
            fallingChunks.add(new FallingChunk(pos, r, g, b, Math.max(0.3f, scale)));
        }
    }

    void render(Matrix4f projMatrix, Matrix4f viewMatrix) {
        renderFragments(projMatrix, viewMatrix);
        renderFallingChunks(projMatrix, viewMatrix);
        renderStressWarnings(projMatrix, viewMatrix);
    }

    private void renderFragments(Matrix4f projMatrix, Matrix4f viewMatrix) {
        // Tick
        Iterator<Fragment> it = fragments.iterator();
        while (it.hasNext()) {
            Fragment f = it.next();
            f.tick();
            if (f.isDead()) it.remove();
        }

        if (fragments.isEmpty()) return;

        // ★ P5-fix: 以 try-finally 包裹，確保 GL 狀態即使例外也會還原
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            Tesselator tes = Tesselator.getInstance();
            BufferBuilder buf = tes.getBuilder();
            // Use the provided projection and view matrices (merged into model-view-projection)
            Matrix4f mat = new Matrix4f(projMatrix).mul(viewMatrix);

            buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            for (Fragment f : fragments) {
                float half = f.size;
                int ri = (int)(f.r * 255), gi = (int)(f.g * 255);
                int bi = (int)(f.b * 255), ai = (int)(f.alpha() * 220);

                // ★ review-fix ICReM: 渲染碎片全 6 面（立體感更強）
                float x0 = f.x - half, y0 = f.y - half, z0 = f.z - half;
                float x1 = f.x + half, y1 = f.y + half, z1 = f.z + half;
                // ★ 面朝光源的面稍亮（簡易光照）
                int riL = Math.min(255, ri + 20), giL = Math.min(255, gi + 20), biL = Math.min(255, bi + 20);
                int riD = (int)(ri * 0.7f), giD = (int)(gi * 0.7f), biD = (int)(bi * 0.7f);

                // Top (Y+) — 亮面
                buf.vertex(mat, x0, y1, z0).color(riL, giL, biL, ai).endVertex();
                buf.vertex(mat, x0, y1, z1).color(riL, giL, biL, ai).endVertex();
                buf.vertex(mat, x1, y1, z1).color(riL, giL, biL, ai).endVertex();
                buf.vertex(mat, x1, y1, z0).color(riL, giL, biL, ai).endVertex();
                // Bottom (Y-) — 暗面
                buf.vertex(mat, x0, y0, z0).color(riD, giD, biD, ai).endVertex();
                buf.vertex(mat, x1, y0, z0).color(riD, giD, biD, ai).endVertex();
                buf.vertex(mat, x1, y0, z1).color(riD, giD, biD, ai).endVertex();
                buf.vertex(mat, x0, y0, z1).color(riD, giD, biD, ai).endVertex();
                // North/South (Z) — 中等亮度
                buf.vertex(mat, x0, y0, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x0, y1, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y1, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y0, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y0, z1).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y1, z1).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x0, y1, z1).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x0, y0, z1).color(ri, gi, bi, ai).endVertex();
                // West/East (X) — 側面亮度
                buf.vertex(mat, x0, y0, z1).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x0, y1, z1).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x0, y1, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x0, y0, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y0, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y1, z0).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y1, z1).color(ri, gi, bi, ai).endVertex();
                buf.vertex(mat, x1, y0, z1).color(ri, gi, bi, ai).endVertex();
            }

            tes.end();
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }

    /**
     * 渲染電影級墜落碎塊 — 帶旋轉的大型方塊碎片。
     */
    private void renderFallingChunks(Matrix4f projMatrix, Matrix4f viewMatrix) {
        if (fallingChunks.isEmpty()) return;

        // Tick and cull
        Iterator<FallingChunk> it = fallingChunks.iterator();
        while (it.hasNext()) {
            FallingChunk chunk = it.next();
            chunk.tick();
            if (chunk.isDead()) it.remove();
        }

        if (fallingChunks.isEmpty()) return;

        // 取攝影機位置
        Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (FallingChunk chunk : fallingChunks) {
            float alpha = chunk.alpha();
            if (alpha <= 0) continue;

            float s = chunk.scale * 0.5f;  // half-size
            float cx = (float) (chunk.x - camPos.x);
            float cy = (float) (chunk.y - camPos.y);
            float cz = (float) (chunk.z - camPos.z);

            // 簡化旋轉：只用 Y 軸旋轉（避免複雜矩陣運算）
            float rad = (float) Math.toRadians(chunk.rotY);
            float cosR = (float) Math.cos(rad);
            float sinR = (float) Math.sin(rad);

            // 著地碎塊顏色稍暗（灰塵覆蓋效果）
            float colorMul = chunk.grounded ? 0.7f : 1.0f;
            int r = (int) (chunk.r * 255 * colorMul * alpha);
            int g = (int) (chunk.g * 255 * colorMul * alpha);
            int b = (int) (chunk.b * 255 * colorMul * alpha);
            int a = (int) (alpha * 230);

            // 6 面渲染（帶 Y 軸旋轉）
            // Top face (Y+)
            addRotatedQuad(buf, cx, cy + s, cz, s, cosR, sinR, r, g, b, a, 0, 1, 0);
            // Bottom face (Y-)
            addRotatedQuad(buf, cx, cy - s, cz, s, cosR, sinR, r, g, b, a, 0, -1, 0);
            // Front face (Z+)
            addRotatedQuad(buf, cx, cy, cz + s, s, cosR, sinR, r, g, b, a, 0, 0, 1);
            // Back face (Z-)
            addRotatedQuad(buf, cx, cy, cz - s, s, cosR, sinR, r, g, b, a, 0, 0, -1);
            // Right face (X+)
            addRotatedQuad(buf, cx + s, cy, cz, s, cosR, sinR, (int)(r*0.9), (int)(g*0.9), (int)(b*0.9), a, 1, 0, 0);
            // Left face (X-)
            addRotatedQuad(buf, cx - s, cy, cz, s, cosR, sinR, (int)(r*0.9), (int)(g*0.9), (int)(b*0.9), a, -1, 0, 0);
        }

        tesselator.end();
        RenderSystem.disableBlend();
    }

    /** 輔助：渲染帶 Y 旋轉的面 */
    private static void addRotatedQuad(BufferBuilder buf,
                                        float cx, float cy, float cz, float s,
                                        float cosR, float sinR,
                                        int r, int g, int b, int a,
                                        int nx, int ny, int nz) {
        // 根據法線方向決定四個頂點
        float[][] offsets;
        if (ny != 0) {
            // 水平面
            offsets = new float[][]{
                    {-s, 0, -s}, {s, 0, -s}, {s, 0, s}, {-s, 0, s}
            };
        } else if (nz != 0) {
            // 前/後面
            offsets = new float[][]{
                    {-s, -s, 0}, {s, -s, 0}, {s, s, 0}, {-s, s, 0}
            };
        } else {
            // 左/右面
            offsets = new float[][]{
                    {0, -s, -s}, {0, -s, s}, {0, s, s}, {0, s, -s}
            };
        }

        for (float[] off : offsets) {
            // 套用 Y 軸旋轉
            float rx = off[0] * cosR - off[2] * sinR;
            float rz = off[0] * sinR + off[2] * cosR;
            buf.vertex(cx + rx, cy + off[1], cz + rz)
                    .color(r, g, b, a)
                    .endVertex();
        }
    }

    private void renderStressWarnings(Matrix4f projMatrix, Matrix4f viewMatrix) {
        Iterator<StressWarning> it = warnings.iterator();
        while (it.hasNext()) {
            StressWarning w = it.next();
            w.tick();
            if (w.isDead()) it.remove();
        }

        if (warnings.isEmpty()) return;

        // ★ P5-fix: 以 try-finally 包裹，確保 GL 狀態即使例外也會還原
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        try {
            Tesselator tes = Tesselator.getInstance();
            BufferBuilder buf = tes.getBuilder();
            // Use the provided projection and view matrices
            Matrix4f mat = new Matrix4f(projMatrix).mul(viewMatrix);

            buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            for (StressWarning w : warnings) {
                // ★ review-fix ICReM: 增強閃爍效果 — 應力越高頻率越快
                float freq = 0.6f + w.stressLevel * 0.4f; // 0.6~1.0 Hz
                float flash = 0.3f + 0.3f * (float) Math.sin(w.life * freq);
                // ★ 生命值淡出（最後 15 ticks 衰減）
                float lifeFade = Math.min(1.0f, w.life / 15.0f);
                float intensity = Math.min(w.stressLevel, 1.5f);

                int r = (int)(255 * intensity), g = (int)(40 * (1.0f - intensity * 0.7f));
                int b = 0, a = (int)(flash * lifeFade * 180);

                float x0 = w.pos.getX() - 0.002f, y0 = w.pos.getY() - 0.002f, z0 = w.pos.getZ() - 0.002f;
                float x1 = w.pos.getX() + 1.002f, y1 = w.pos.getY() + 1.002f, z1 = w.pos.getZ() + 1.002f;

                // ★ review-fix ICReM: 渲染全 6 面（不只上面）— 從任何角度都能看到警告
                // Top (Y+)
                buf.vertex(mat, x0, y1, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y1, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y1, z0).color(r, g, b, a).endVertex();
                // Bottom (Y-)
                buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y0, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y0, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y0, z1).color(r, g, b, a).endVertex();
                // North (Z-)
                buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y1, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y1, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y0, z0).color(r, g, b, a).endVertex();
                // South (Z+)
                buf.vertex(mat, x1, y0, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y1, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y0, z1).color(r, g, b, a).endVertex();
                // West (X-)
                buf.vertex(mat, x0, y0, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y1, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y1, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
                // East (X+)
                buf.vertex(mat, x1, y0, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y1, z0).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
                buf.vertex(mat, x1, y0, z1).color(r, g, b, a).endVertex();
            }

            tes.end();
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }

    void cleanup() {
        fragments.clear();
        warnings.clear();
    }

    public int getActiveFragmentCount() { return fragments.size(); }
    public int getActiveWarningCount() { return warnings.size(); }
}

