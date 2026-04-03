package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU 硬體遮蔽查詢剔除器（Hardware Occlusion Query Culling）。
 *
 * 技術架構：
 *   - GL_SAMPLES_PASSED 或 GL_ANY_SAMPLES_PASSED 查詢
 *   - 每個結構 section（16³ 區域）對應一個 query 物件
 *   - 雙幀延遲讀回（避免 GPU stall）：
 *     Frame N: 提交 query → Frame N+1: 讀回 Frame N-1 的結果
 *   - Bounding box 代理幾何（簡化 AABB 取代完整幾何做 query）
 *   - 漸進式查詢：只對螢幕投影面積大於閾值的 section 做 query
 *   - 與 frustum culling 協同：先 frustum cull，再對可見候選做 occlusion query
 *
 * 參考：
 *   - "GPU Pro" ch.12: Practical Occlusion Culling
 *   - Sodium: frustum + distance culling
 *   - Vulkan "GPU-driven rendering" (mesh shader path, 此處用 OGL 模擬)
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BROcclusionCuller {

    private static final Logger LOGGER = LoggerFactory.getLogger(BROcclusionCuller.class);

    /** 最大同時查詢數（= 最大可見 section 候選） */
    private static final int MAX_QUERIES = 512;

    /** Query 物件 ID 池 */
    private static int[] queryIds;

    /** 每個 query 的狀態：0=空閒, 1=已提交(當前幀), 2=待讀回(前一幀) */
    private static int[] queryState;

    /** Query 結果：true=可見, false=被遮蔽 */
    private static boolean[] queryResult;

    /** 每個 query 對應的 section 座標 hash（用於映射回 section） */
    private static long[] querySectionHash;

    /** ★ A-7 fix: 每個 query 進入 PENDING 狀態的幀號，用於超時強制重置 */
    private static long[] queryPendingSinceFrame;
    private static long frameCounter = 0;
    private static final long QUERY_TIMEOUT_FRAMES = 10; // 10 幀未就緒則強制重置

    /** 當前幀已提交的 query 數 */
    private static int submittedCount = 0;

    /** 上一幀的 query 數（待讀回） */
    private static int pendingReadbackCount = 0;

    // ─── 統計 ───
    private static int totalCulled = 0;
    private static int totalVisible = 0;
    private static int totalQueried = 0;

    // ─── 代理 AABB VAO ───
    private static int bboxVao;
    private static int bboxVbo;

    private static boolean initialized = false;

    // ========================= 初始化 =========================

    public static void init() {
        if (initialized) { cleanup(); } // ★ #11 fix: 重複呼叫時先清理舊資源，防止 GL 查詢物件洩漏

        queryIds = new int[MAX_QUERIES];
        queryState = new int[MAX_QUERIES];
        queryResult = new boolean[MAX_QUERIES];
        querySectionHash = new long[MAX_QUERIES];
        queryPendingSinceFrame = new long[MAX_QUERIES];

        // 批量生成 query 物件
        for (int i = 0; i < MAX_QUERIES; i++) {
            queryIds[i] = GL15.glGenQueries();
            queryState[i] = 0;
            queryResult[i] = true; // 預設可見
            querySectionHash[i] = 0;
        }

        // 建立 AABB 代理幾何（單位立方體 0~1）
        createBBoxGeometry();

        submittedCount = 0;
        pendingReadbackCount = 0;
        totalCulled = 0;
        totalVisible = 0;
        totalQueried = 0;

        initialized = true;
        LOGGER.info("[BROcclusionCuller] 遮蔽查詢剔除器初始化完成 — {} query 物件", MAX_QUERIES);
    }

    public static void cleanup() {
        if (!initialized) return;
        for (int i = 0; i < MAX_QUERIES; i++) {
            if (queryIds[i] != 0) {
                GL15.glDeleteQueries(queryIds[i]);
                queryIds[i] = 0;
            }
        }
        if (bboxVao != 0) { GL30.glDeleteVertexArrays(bboxVao); bboxVao = 0; }
        if (bboxVbo != 0) { GL15.glDeleteBuffers(bboxVbo); bboxVbo = 0; }
        initialized = false;
    }

    // ========================= 每幀入口 =========================

    /**
     * 幀開始：讀回前一幀的 query 結果（非阻塞）。
     * 必須在 AFTER_SOLID_BLOCKS 開頭呼叫。
     */
    public static void beginFrame() {
        if (!initialized || !BRRenderConfig.OCCLUSION_QUERY_ENABLED) return;

        frameCounter++;
        totalCulled = 0;
        totalVisible = 0;
        totalQueried = 0;

        // 讀回狀態=2 的 query 結果
        for (int i = 0; i < MAX_QUERIES; i++) {
            if (queryState[i] == 2) {
                // 非阻塞檢查是否就緒
                int available = GL15.glGetQueryObjecti(queryIds[i], GL15.GL_QUERY_RESULT_AVAILABLE);
                if (available == GL11.GL_TRUE) {
                    int samples = GL15.glGetQueryObjecti(queryIds[i], GL15.GL_QUERY_RESULT);
                    queryResult[i] = (samples > 0);
                    queryState[i] = 0; // 釋放回空閒
                    if (samples > 0) totalVisible++;
                    else totalCulled++;
                } else if (frameCounter - queryPendingSinceFrame[i] > QUERY_TIMEOUT_FRAMES) {
                    // ★ A-7 fix: 超時強制重置，防止查詢槽永久卡死
                    queryResult[i] = true; // 保守：假設可見
                    queryState[i] = 0;
                    LOGGER.debug("[BROcclusionCuller] Query slot {} 超時強制重置（{} 幀未就緒）",
                        i, frameCounter - queryPendingSinceFrame[i]);
                }
            }
        }

        // 將當前幀已提交（狀態=1）的 query 升級為待讀回（狀態=2）
        for (int i = 0; i < MAX_QUERIES; i++) {
            if (queryState[i] == 1) {
                queryState[i] = 2;
                queryPendingSinceFrame[i] = frameCounter; // ★ 記錄進入 PENDING 的幀號
            }
        }

        submittedCount = 0;
    }

    /**
     * 對指定 section 提交遮蔽查詢。
     * @param sectionHash section 的唯一 hash
     * @param minX, minY, minZ, maxX, maxY, maxZ AABB 世界座標
     * @return true 表示上一次查詢結果為可見（應渲染），false 表示被遮蔽（可跳過）
     */
    public static boolean querySection(long sectionHash,
                                        float minX, float minY, float minZ,
                                        float maxX, float maxY, float maxZ) {
        if (!initialized || !BRRenderConfig.OCCLUSION_QUERY_ENABLED) return true;
        if (BRRenderConfig.MESH_SHADER_ENABLED && BRMeshShaderPath.isSupported()) return true;

        totalQueried++;

        // 查找對應此 section 的 query slot（或空閒 slot）
        int slot = -1;
        for (int i = 0; i < MAX_QUERIES; i++) {
            if (querySectionHash[i] == sectionHash && queryState[i] == 0) {
                slot = i;
                break;
            }
        }
        if (slot == -1) {
            // 找空閒 slot
            for (int i = 0; i < MAX_QUERIES; i++) {
                if (queryState[i] == 0) {
                    slot = i;
                    querySectionHash[i] = sectionHash;
                    break;
                }
            }
        }
        if (slot == -1) return true; // 無空閒 slot，保守渲染

        // 從歷史結果取可見性
        boolean wasVisible = queryResult[slot];

        // 提交新 query（繪製 AABB 代理幾何）
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);

        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, queryIds[slot]);

        // 繪製 AABB（TODO: 上層需設置好 viewProj 矩陣和 AABB transform）
        drawBBox(minX, minY, minZ, maxX, maxY, maxZ);

        GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);

        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);

        queryState[slot] = 1; // 已提交
        submittedCount++;

        return wasVisible;
    }

    // ========================= 統計 =========================

    public static int getTotalCulled() { return totalCulled; }
    public static int getTotalVisible() { return totalVisible; }
    public static int getTotalQueried() { return totalQueried; }
    public static float getCullRate() {
        int total = totalCulled + totalVisible;
        return total > 0 ? (float) totalCulled / total * 100.0f : 0.0f;
    }

    // ========================= AABB 幾何 =========================

    private static void createBBoxGeometry() {
        // 單位立方體 36 頂點（12 三角形）
        float[] verts = {
            // -Z face
            0,0,0, 1,0,0, 1,1,0, 0,0,0, 1,1,0, 0,1,0,
            // +Z face
            0,0,1, 1,1,1, 1,0,1, 0,0,1, 0,1,1, 1,1,1,
            // -X face
            0,0,0, 0,1,0, 0,1,1, 0,0,0, 0,1,1, 0,0,1,
            // +X face
            1,0,0, 1,1,1, 1,1,0, 1,0,0, 1,0,1, 1,1,1,
            // -Y face
            0,0,0, 1,0,1, 1,0,0, 0,0,0, 0,0,1, 1,0,1,
            // +Y face
            0,1,0, 1,1,0, 1,1,1, 0,1,0, 1,1,1, 0,1,1
        };

        bboxVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(bboxVao);

        bboxVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bboxVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0);

        GL30.glBindVertexArray(0);
    }

    private static void drawBBox(float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        // ★ A-6 fix: 將 AABB 參數傳入 shader uniform，使遮蔽查詢測試正確的包圍盒
        // 單位立方體 (0~1) 透過 u_bboxMin + u_bboxSize 在頂點著色器中縮放至實際 AABB
        // vertex shader: gl_Position = viewProj * vec4(v_pos * u_bboxSize + u_bboxMin, 1.0)
        var shader = BRShaderEngine.getOcclusionQueryShader();
        if (shader != null) {
            shader.setUniformVec3("u_bboxMin", minX, minY, minZ);
            shader.setUniformVec3("u_bboxSize", maxX - minX, maxY - minY, maxZ - minZ);
        }
        GL30.glBindVertexArray(bboxVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
        GL30.glBindVertexArray(0);
    }
}
