package com.blockreality.api.client.render.effect;

import com.blockreality.api.client.render.BRRenderConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
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
 * 放置特效渲染器 — 方塊放置時的粒子爆發效果。
 *
 * 效果描述：
 *   當方塊被放置時，從方塊中心向外射出小型發光粒子，
 *   粒子依材質顏色著色，隨時間衰減並消失。
 *
 * 未來擴展：
 *   - 人物動作特效（揮錘、鑿刻等）
 *   - 結構完成慶祝粒子
 */
@OnlyIn(Dist.CLIENT)
public final class PlacementFXRenderer {

    /** 單一粒子實例 */
    private static final class Particle {
        float x, y, z;          // 世界座標
        float vx, vy, vz;       // 速度
        float r, g, b, a;       // 顏色
        int life;               // 剩餘 tick
        int maxLife;
        float size;

        Particle(BlockPos pos, float r, float g, float b) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            this.x = pos.getX() + 0.5f;
            this.y = pos.getY() + 0.5f;
            this.z = pos.getZ() + 0.5f;
            this.vx = (rng.nextFloat() - 0.5f) * 0.15f;
            this.vy = rng.nextFloat() * 0.1f + 0.05f;
            this.vz = (rng.nextFloat() - 0.5f) * 0.15f;
            this.r = r; this.g = g; this.b = b; this.a = 1.0f;
            this.maxLife = BRRenderConfig.PLACEMENT_FX_DURATION;
            this.life = this.maxLife;
            this.size = 2.0f + rng.nextFloat() * 2.0f;
        }

        void tick() {
            x += vx;
            y += vy;
            z += vz;
            vy -= 0.005f; // 重力
            life--;
            a = (float) life / maxLife; // 淡出
        }

        boolean isDead() { return life <= 0; }
    }

    private final List<Particle> particles = new ArrayList<>();

    PlacementFXRenderer() {}

    // ═══════════════════════════════════════════════════════
    //  觸發
    // ═══════════════════════════════════════════════════════

    /**
     * 在指定方塊位置生成放置特效。
     * ★ review-fix ICReM: 增強材質顏色映射 + RC 節點特殊效果
     *
     * @param pos 方塊位置
     * @param materialId BR 材質 ID（用於顏色）
     */
    public void spawnPlacementFX(BlockPos pos, int materialId) {
        float r, g, b;
        int count = BRRenderConfig.PLACEMENT_FX_PARTICLE_COUNT;

        switch (materialId) {
            case 0 -> { r = 0.75f; g = 0.75f; b = 0.73f; } // 混凝土
            case 1 -> { r = 0.6f;  g = 0.65f; b = 0.75f; } // 鋼材
            case 2 -> { r = 0.7f;  g = 0.5f;  b = 0.3f;  } // 木材
            case 3 -> { r = 0.55f; g = 0.55f; b = 0.6f;  } // 鋼筋
            case 4 -> {
                // ★ review-fix ICReM: RC 節點放置 — 金色光芒粒子 + 額外粒子數
                r = 0.9f; g = 0.75f; b = 0.2f;
                count = (int) (count * 1.5f); // 50% 更多粒子
            }
            case 5 -> {
                // ★ review-fix ICReM: 錨樁放置 — 藍白色能量粒子
                r = 0.4f; g = 0.7f; b = 1.0f;
                count = (int) (count * 1.3f);
            }
            default -> { r = 0.9f; g = 0.9f;  b = 0.9f;  }
        }

        for (int i = 0; i < count; i++) {
            particles.add(new Particle(pos, r, g, b));
        }
    }

    /**
     * 生成自訂顏色的特效（用於選框操作等）。
     */
    public void spawnCustomFX(BlockPos pos, float r, float g, float b, int count) {
        for (int i = 0; i < count; i++) {
            particles.add(new Particle(pos, r, g, b));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════════════════

    void render(Matrix4f projMatrix, Matrix4f viewMatrix) {
        if (particles.isEmpty()) return;

        // Tick 所有粒子
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.tick();
            if (p.isDead()) it.remove();
        }

        if (particles.isEmpty()) return;

        // 渲染
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tes = Tesselator.getInstance();
        BufferBuilder buf = tes.getBuilder();
        // Use the provided projection and view matrices
        Matrix4f mat = new Matrix4f(projMatrix).mul(viewMatrix);

        // 使用小方塊代替 point sprite（相容性更好）
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (Particle p : particles) {
            float half = p.size * 0.01f; // 小方塊半徑
            int ri = (int)(p.r * 255), gi = (int)(p.g * 255);
            int bi = (int)(p.b * 255), ai = (int)(p.a * 200);

            // ★ review-fix ICReM: 十字交叉 billboard — 從任何角度都可見
            // XY 平面
            buf.vertex(mat, p.x - half, p.y - half, p.z).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, p.x + half, p.y - half, p.z).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, p.x + half, p.y + half, p.z).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, p.x - half, p.y + half, p.z).color(ri, gi, bi, ai).endVertex();
            // YZ 平面（垂直交叉）
            buf.vertex(mat, p.x, p.y - half, p.z - half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, p.x, p.y - half, p.z + half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, p.x, p.y + half, p.z + half).color(ri, gi, bi, ai).endVertex();
            buf.vertex(mat, p.x, p.y + half, p.z - half).color(ri, gi, bi, ai).endVertex();
        }

        tes.end();

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    void cleanup() {
        particles.clear();
    }

    public int getActiveParticleCount() { return particles.size(); }
}

