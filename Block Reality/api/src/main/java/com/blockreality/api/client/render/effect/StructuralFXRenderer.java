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
