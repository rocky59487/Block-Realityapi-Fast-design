package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL11.glDrawElements;

/**
 * LOD（Level of Detail）渲染引擎 — 大視距區段管理與分段渲染。
 * 負責收集可見 LOD 段落並以適當解析度渲染。
 */
@OnlyIn(Dist.CLIENT)
public final class BRLODEngine {
    private BRLODEngine() {}

    private static boolean initialized       = false;
    private static BRShaderProgram lodShaderProgram = null;
    private static FrustumCuller frustumCuller      = null;

    // 相機位置（雙精度，避免浮點抖動）
    private static double cameraX, cameraY, cameraZ;

    /** LOD 等級定義 */
    public enum LODLevel {
        FULL(0, 1),
        HALF(1, 2),
        QUARTER(2, 4),
        EIGHTH(3, 8);

        public final int index;
        public final int step;
        LODLevel(int index, int step) { this.index = index; this.step = step; }
    }

    /** 代表一個可渲染的 LOD 區段 */
    public static class LODSection {
        public int    vao, indexCount;
        public double centerX, centerZ;
        public float  minX, minY, minZ, maxX, maxY, maxZ;
        public LODLevel lodLevel = LODLevel.FULL;
    }

    private static final List<LODSection> sections = new ArrayList<>();

    // ─── 生命週期 ────────────────────────────────────────────

    public static void init() {
        lodShaderProgram = BRShaderEngine.getLODShader();
        frustumCuller    = new FrustumCuller(BRRenderConfig.FRUSTUM_PADDING);
        initialized      = true;
    }

    public static void cleanup() {
        sections.clear();
        initialized = false;
    }

    public static boolean isInitialized() { return initialized; }

    public static void updateCamera(double x, double y, double z) {
        cameraX = x; cameraY = y; cameraZ = z;
        // frustumCuller is updated in render() when matrices are available
    }

    /**
     * 每幀更新 LOD 等級與相機位置。
     * @param x         相機 X
     * @param y         相機 Y
     * @param z         相機 Z
     * @param frameCount 當前幀計數（用於非同步更新節流）
     */
    public static void update(double x, double y, double z, long frameCount) {
        cameraX = x; cameraY = y; cameraZ = z;
        // LOD 等級重分配（每幀執行）
        for (LODSection section : sections) {
            double dx = section.centerX - x;
            double dz = section.centerZ - z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < BRRenderConfig.LOD_MAX_DISTANCE * 0.25) {
                section.lodLevel = LODLevel.FULL;
            } else if (dist < BRRenderConfig.LOD_MAX_DISTANCE * 0.5) {
                section.lodLevel = LODLevel.HALF;
            } else if (dist < BRRenderConfig.LOD_MAX_DISTANCE * 0.75) {
                section.lodLevel = LODLevel.QUARTER;
            } else {
                section.lodLevel = LODLevel.EIGHTH;
            }
        }
    }

    /**
     * 標記指定段落 GPU 資源需要重建（在 section upload 後呼叫）。
     * @param sectionX 段落 X 座標
     * @param sectionZ 段落 Z 座標
     */
    public static void markDirty(int sectionX, int sectionZ) {
        // 在實際實現中會找到對應段落並清除快取；目前僅記錄
    }

    /**
     * 渲染 LOD 段落的深度 shadow pass（供 CSM 使用）。
     * @param projMatrix 光源正交投影矩陣
     * @param viewMatrix 光源視圖矩陣
     */
    public static void renderShadow(Matrix4f projMatrix, Matrix4f viewMatrix) {
        if (!initialized || lodShaderProgram == null) return;
        if (frustumCuller != null) frustumCuller.update(projMatrix, viewMatrix);
        // 以最低 LOD 渲染至當前綁定的 shadow FBO
        List<LODSection> visibleSections = collectVisibleSections();
        for (LODSection section : visibleSections) {
            if (section.vao < 0 || section.indexCount <= 0) continue;
            glBindVertexArray(section.vao);
            glDrawElements(GL_TRIANGLES, section.indexCount, GL_UNSIGNED_INT, 0);
            glBindVertexArray(0);
        }
    }

    // ─── 渲染 ─────────────────────────────────────────────────

    /**
     * 渲染所有可見的 LOD 段落
     * 應在延遲渲染或正向渲染管道的適當階段調用
     *
     * @param projMatrix 投影矩陣
     * @param viewMatrix 視圖矩陣
     */
    public static void render(Matrix4f projMatrix, Matrix4f viewMatrix) {
        if (!initialized || lodShaderProgram == null) {
            return;
        }

        if (frustumCuller != null) frustumCuller.update(projMatrix, viewMatrix);
        lodShaderProgram.bind();

        // 設置全局著色器統一變數
        lodShaderProgram.setUniformMat4("u_projMatrix", projMatrix);
        lodShaderProgram.setUniformMat4("u_viewMatrix", viewMatrix);
        lodShaderProgram.setUniformVec3("u_cameraPos",
            (float) cameraX, (float) cameraY, (float) cameraZ);
        lodShaderProgram.setUniformFloat("u_lodMaxDistance", (float) BRRenderConfig.LOD_MAX_DISTANCE);

        // 蒐集並渲染可見段落
        List<LODSection> visibleSections = collectVisibleSections();

        for (LODSection section : visibleSections) {
            // 跳過未構建的段落
            if (section.vao < 0 || section.indexCount <= 0) {
                continue;
            }

            // 視錐體裁剪最終檢查（視錐體可能已在 update 中變化）
            if (frustumCuller != null && !frustumCuller.testAABB(
                section.minX, section.minY, section.minZ,
                section.maxX, section.maxY, section.maxZ)) {
                continue;
            }

            // 設置段落特定的統一變數
            Matrix4f modelMatrix = new Matrix4f();
            modelMatrix.translation((float) (section.centerX - cameraX), 0, (float) (section.centerZ - cameraZ));
            lodShaderProgram.setUniformMat4("u_modelMatrix", modelMatrix);
            lodShaderProgram.setUniformInt("u_lodLevel", section.lodLevel.index);

            // 渲染段落
            glBindVertexArray(section.vao);
            glDrawElements(GL_TRIANGLES, section.indexCount, GL_UNSIGNED_INT, 0);
            glBindVertexArray(0);
        }

        lodShaderProgram.unbind();
    }

    private static List<LODSection> collectVisibleSections() {
        List<LODSection> visible = new ArrayList<>();
        for (LODSection s : sections) {
            if (frustumCuller == null || frustumCuller.testAABB(
                    s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ)) {
                visible.add(s);
            }
        }
        return visible;
    }
}
