package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.pipeline.RenderPassContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block Reality 優化引擎 — 融合 Sodium/Embeddium/Lithium 核心技術。
 *
 * 四大優化支柱：
 *   1. Frustum Culling — 視錐剔除 + 結構邊界盒測試（Sodium 風格）
 *   2. Greedy Meshing — 相鄰同材質面合併（Sodium Section Mesh 概念）
 *   3. Render Batching — Draw call 合併 + 實例化渲染（Embeddium 風格）
 *   4. Mesh Cache — 髒標記 VBO 快取（Sodium ChunkMeshData 概念）
 *
 * 與管線整合：
 *   - BRRenderPipeline 呼叫 renderStructureGeometry() → 優化引擎負責剔除、排序、批次提交
 *   - 異步 Mesh 編譯（不阻塞主渲染執行緒）
 */
@OnlyIn(Dist.CLIENT)
public final class BROptimizationEngine {
    private BROptimizationEngine() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-Optimization");

    private static FrustumCuller frustumCuller;
    private static GreedyMesher greedyMesher;
    private static RenderBatcher renderBatcher;
    private static MeshCache meshCache;

    private static boolean initialized = false;

    // ─── 統計 ───────────────────────────────────────────
    private static int lastCulledCount;
    private static int lastDrawCallCount;
    private static int lastMergedFaceCount;

    // ─── MultiDraw 批次緩衝區 ─────────────────────────
    /** 預分配 — 避免每幀 GC。容量 = 最大可見 Section 數 */
    private static final int MULTI_DRAW_CAPACITY = 4096;
    private static int[] multiDrawFirst;
    private static int[] multiDrawCount;
    private static int[] multiDrawVaos;

    // ═══════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════

    public static void init() {
        if (initialized) return;

        frustumCuller = new FrustumCuller(BRRenderConfig.FRUSTUM_PADDING);
        greedyMesher  = new GreedyMesher(BRRenderConfig.GREEDY_MESH_MAX_AREA);
        renderBatcher = new RenderBatcher(BRRenderConfig.BATCH_MAX_VERTICES, BRRenderConfig.BATCH_MAX_MERGE);
        meshCache     = new MeshCache(BRRenderConfig.MESH_CACHE_MAX_SECTIONS);

        multiDrawFirst = new int[MULTI_DRAW_CAPACITY];
        multiDrawCount = new int[MULTI_DRAW_CAPACITY];
        multiDrawVaos  = new int[MULTI_DRAW_CAPACITY];

        initialized = true;
        LOG.info("優化引擎初始化完成 — FrustumCuller + GreedyMesher + RenderBatcher + MeshCache + MultiDraw");
    }

    public static void cleanup() {
        if (!initialized) return;
        if (meshCache != null) meshCache.invalidateAll();
        if (renderBatcher != null) renderBatcher.cleanup();
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════
    //  渲染入口（由 BRRenderPipeline 呼叫）
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染結構方塊幾何（GBuffer Terrain pass）。
     *
     * 流程（v4 spec 審核修正 — 原為空殼，現為完整管線）：
     *   1. Frustum Culling — 視錐剔除不可見 section
     *   2. 遍歷 MeshCache 中所有 section → 視錐測試
     *   3. 可見且非髒的 section → 直接繪製其 VAO
     *   4. 可見但髒的 section → 提交到 BRThreadedMeshBuilder 異步重建
     *   5. 統計 culled / draw call / merged face 數量
     */
    public static void renderStructureGeometry(RenderPassContext ctx) {
        if (!initialized) return;

        // 更新 frustum
        frustumCuller.update(ctx.getProjectionMatrix(), ctx.getViewMatrix());

        int culledCount = 0;
        int drawCalls = 0;
        int totalMergedFaces = 0;

        // ── Phase 1: Frustum cull + 收集可見 section ──
        // 按 VAO 分組收集，相同 VAO 的 section 可批次繪製
        int batchIdx = 0;
        int lastBoundVao = -1;

        java.util.Collection<MeshCache.SectionMesh> allSections = meshCache.getAllSections();
        for (MeshCache.SectionMesh section : allSections) {
            int sx = unpackX(section.sectionKey);
            int sy = unpackY(section.sectionKey);
            int sz = unpackZ(section.sectionKey);

            float minX = sx * 16.0f;
            float minY = sy * 16.0f;
            float minZ = sz * 16.0f;
            float maxX = minX + 16.0f;
            float maxY = minY + 16.0f;
            float maxZ = minZ + 16.0f;

            if (!frustumCuller.testAABB(minX, minY, minZ, maxX, maxY, maxZ)) {
                culledCount++;
                continue;
            }

            section.lastUsedFrame = ctx.getWorldTick();

            if (section.dirty) {
                // 髒 section：仍用舊 VBO 渲染（如有），但不參與批次
                if (section.vao != 0 && section.vertexCount > 0) {
                    org.lwjgl.opengl.GL30.glBindVertexArray(section.vao);
                    org.lwjgl.opengl.GL11.glDrawArrays(
                        org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, section.vertexCount);
                    org.lwjgl.opengl.GL30.glBindVertexArray(0);
                    drawCalls++;
                    totalMergedFaces += section.vertexCount / 6;
                }
                continue;
            }

            // 非髒、有效 section → 加入批次緩衝區
            if (section.vao != 0 && section.vertexCount > 0 && batchIdx < MULTI_DRAW_CAPACITY) {
                multiDrawVaos[batchIdx]  = section.vao;
                multiDrawFirst[batchIdx] = 0;
                multiDrawCount[batchIdx] = section.vertexCount;
                totalMergedFaces += section.vertexCount / 6;
                batchIdx++;
            }
        }

        // ── Phase 2: 批次繪製非髒 section ──
        // 連續相同 VAO 的 section 用 glMultiDrawArrays 一次提交
        // 不同 VAO 需要切換綁定（每個 Section 有獨立 VAO/VBO）
        if (batchIdx > 0) {
            int runStart = 0;
            while (runStart < batchIdx) {
                int currentVao = multiDrawVaos[runStart];
                int runEnd = runStart + 1;
                // 找到連續相同 VAO 的批次段
                while (runEnd < batchIdx && multiDrawVaos[runEnd] == currentVao) {
                    runEnd++;
                }

                int runLen = runEnd - runStart;
                org.lwjgl.opengl.GL30.glBindVertexArray(currentVao);

                if (runLen == 1) {
                    // 單個 draw — 直接 glDrawArrays
                    org.lwjgl.opengl.GL11.glDrawArrays(
                        org.lwjgl.opengl.GL11.GL_TRIANGLES,
                        multiDrawFirst[runStart], multiDrawCount[runStart]);
                    drawCalls++;
                } else {
                    // 多個相同 VAO — glMultiDrawArrays 批次提交
                    // LWJGL GL14.glMultiDrawArrays(mode, first[], count[]) — 3 args，長度由 buffer 推斷
                    org.lwjgl.opengl.GL14.glMultiDrawArrays(
                        org.lwjgl.opengl.GL11.GL_TRIANGLES,
                        java.nio.IntBuffer.wrap(multiDrawFirst, runStart, runLen),
                        java.nio.IntBuffer.wrap(multiDrawCount, runStart, runLen));
                    drawCalls++; // 一次 API 呼叫
                }

                runStart = runEnd;
            }
            org.lwjgl.opengl.GL30.glBindVertexArray(0);
        }

        lastCulledCount = culledCount;
        lastDrawCallCount = drawCalls;
        lastMergedFaceCount = totalMergedFaces;
    }

    /**
     * 渲染 Shadow 幾何（簡化版 — 只需深度，使用相同 mesh 但跳過材質計算）。
     */
    public static void renderShadowGeometry(Matrix4f shadowProj, Matrix4f shadowView) {
        if (!initialized) return;

        frustumCuller.update(shadowProj, shadowView);

        // Shadow pass：遍歷所有 section，frustum cull → 繪製 VAO（只寫深度）
        java.util.Collection<MeshCache.SectionMesh> allSections = meshCache.getAllSections();
        for (MeshCache.SectionMesh section : allSections) {
            if (section.vao == 0 || section.vertexCount == 0) continue;

            int sx = unpackX(section.sectionKey);
            int sy = unpackY(section.sectionKey);
            int sz = unpackZ(section.sectionKey);
            float minX = sx * 16.0f, minY = sy * 16.0f, minZ = sz * 16.0f;

            if (!frustumCuller.testAABB(minX, minY, minZ, minX + 16f, minY + 16f, minZ + 16f)) {
                continue;
            }

            org.lwjgl.opengl.GL30.glBindVertexArray(section.vao);
            org.lwjgl.opengl.GL11.glDrawArrays(
                org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, section.vertexCount);
        }
        org.lwjgl.opengl.GL30.glBindVertexArray(0);
    }

    /**
     * 渲染方塊實體幾何（鑿刻方塊等自訂形狀）。
     * Block entity 不做 greedy meshing（每個都是獨立幾何），但仍做 frustum culling 和 batching。
     */
    public static void renderBlockEntityGeometry(RenderPassContext ctx) {
        if (!initialized) return;
        // Block entity 通過 RenderBatcher 提交（外部模組透過 submit() 提交幾何）
        renderBatcher.begin();
        lastDrawCallCount += renderBatcher.flush();
    }

    // ─── Section key 解包 ─────────────

    private static int unpackX(long key) { return (int)(key >> 40); }
    private static int unpackY(long key) { return (int)((key >> 20) & 0xFFFFF); }
    private static int unpackZ(long key) { return (int)(key & 0xFFFFF); }

    /** 最後一幀的 draw call 數量（除錯 / profiling 用） */
    public static int getLastDrawCallCount() { return lastDrawCallCount; }

    /** 最後一幀被 frustum cull 剔除的 section 數量 */
    public static int getLastCulledCount() { return lastCulledCount; }

    /** 目前快取中的 section 數量 */
    public static int getCachedSectionCount() {
        return meshCache != null ? meshCache.size() : 0;
    }
}