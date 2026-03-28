package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.pipeline.RenderPassContext;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

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
            this.vx = (rng.nextFloat() - 0.5f) * 0.3f;
            this.vy = rng.nextFloat() * 0.2f;
            this.vz = (rng.nextFloat() - 0.5f) * 0.3f;
            this.rx = 0; this.ry = 0; this.rz = 0;
            this.rvx = (rng.nextFloat() - 0.5f) * 20;
            this.rvy = (rng.nextFloat() - 0.5f) * 20;
            this.rvz = (rng.nextFloat() - 0.5f) * 20;
            this.r = r; this.g = g; this.b = b;
            this.size = 0.05f + rng.nextFloat() * 0.15f;
            this.life = 30 + rng.nextInt(20); // 1.5~2.5 秒
        }

        void tick() {
            x += vx; y += vy; z += vz;
            vy -= 0.015f; // 重力
            vx *= 0.98f; vz *= 0.98f; // 空氣阻力
            rx += rvx; ry += rvy; rz += rvz;
            life--;
        }

        boolean isDead() { return life <= 0; }
        float alpha() { return Math.min(1.0f, life / 10.0f); } // 最後 0.5 秒淡出
    }

    /** 應力警告閃爍 */
    private static final class StressWarning {
        final BlockPos pos;
        final float stressLevel; // [0, 1+]
        int life;

        StressWarning(BlockPos pos, float stress) {
            this.pos = pos;
            this.stressLevel = stress;
            this.life = 40; // 2 秒
        }

        void tick() { life--; }
        boolean isDead() { return life <= 0; }
    }

    private final List<Fragment> fragments = new ArrayList<>();
    private final List<StressWarning> warnings = new ArrayList<>();

    StructuralFXRenderer() {}

    // ═══════════════════════════════════════════════════════
    //  觸發
    // ═══════════════════════════════════════════════════════

    /**
     * 在方塊位置生成崩塌碎片。
     */
    public void spawnCollapseFX(BlockPos pos, int materialId) {
        float r, g, b;
        switch (materialId) {
            case 0 -> { r = 0.7f; g = 0.7f; b = 0.68f; }
            case 1 -> { r = 0.55f; g = 0.6f; b = 0.65f; }
            case 2 -> { r = 0.6f; g = 0.4f; b = 0.2f; }
            case 3 -> { r = 0.5f; g = 0.5f; b = 0.55f; }
            default -> { r = 0.8f; g = 0.8f; b = 0.8f; }
        }

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

    void render(RenderPassContext ctx) {
        renderFragments(ctx);
        renderStressWarnings(ctx);
    }

    private void renderFragments(RenderPassContext ctx) {
        // Tick
        Iterator<Fragment> it = fragments.iterator();
        while (it.hasNext()) {
            Fragment f = it.next();
            f.tick();
            if (f.isDead()) it.remove();
        }

        if (fragments.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tes = Tesselator.getInstance();
        BufferBuilder buf = tes.getBuilder();
        Matrix4f mat = ctx.getPoseStack().last().pose();

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (Fragment f : fragments) {
            float half = f.size;
            int ri = (int)(f.r * 255), gi = (int)(f.g * 255);
            int bi = (int)(f.b * 255), ai = (int)(f.alpha() * 220);

            // 簡化小方塊（不做旋轉 — 正式版會用 matrix rotation）
            buf.vertex(mat, f.x - half, f.y - half, f.z - half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, f.x + half, f.y - half, f.z - half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, f.x + half, f.y + half, f.z - half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, f.x - half, f.y + half, f.z - half).color(ri, gi, bi, ai).endVertex();

            buf.vertex(mat, f.x - half, f.y - half, f.z + half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, f.x + half, f.y - half, f.z + half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, f.x + half, f.y + half, f.z + half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, f.x - half, f.y + half, f.z + half).color(ri, gi, bi, ai).endVertex();
        }

        tes.end();

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private void renderStressWarnings(RenderPassContext ctx) {
        Iterator<StressWarning> it = warnings.iterator();
        while (it.hasNext()) {
            StressWarning w = it.next();
            w.tick();
            if (w.isDead()) it.remove();
        }

        if (warnings.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tes = Tesselator.getInstance();
        BufferBuilder buf = tes.getBuilder();
        Matrix4f mat = ctx.getPoseStack().last().pose();

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (StressWarning w : warnings) {
            // 閃爍 alpha（高頻 sin）
            float flash = 0.3f + 0.3f * (float) Math.sin(w.life * 0.8f);
            float intensity = Math.min(w.stressLevel, 1.5f);

            int r = (int)(255 * intensity), g = (int)(50 * (1 - intensity));
            int b = 0, a = (int)(flash * 150);

            float x0 = w.pos.getX() - 0.001f, y0 = w.pos.getY() - 0.001f, z0 = w.pos.getZ() - 0.001f;
            float x1 = w.pos.getX() + 1.001f, y1 = w.pos.getY() + 1.001f, z1 = w.pos.getZ() + 1.001f;

            // 上面覆蓋
            buf.vertex(mat, x0, y1, z0).color(r, g, b, a).endVertex();
            buf.vertex(mat, x0, y1, z1).color(r, g, b, a).endVertex();
            buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
            buf.vertex(mat, x1, y1, z0).color(r, g, b, a).endVertex();
        }

        tes.end();

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    void cleanup() {
        fragments.clear();
        warnings.clear();
    }

    public int getActiveFragmentCount() { return fragments.size(); }
    public int getActiveWarningCount() { return warnings.size(); }
}
