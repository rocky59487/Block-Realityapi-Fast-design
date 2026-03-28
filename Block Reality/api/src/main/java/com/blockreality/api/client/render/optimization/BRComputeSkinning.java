package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

/**
 * GPU Compute Shader 骨骼蒙皮系統 — 將頂點蒙皮從 vertex shader 搬移至 compute shader。
 *
 * 技術架構：
 *   - OpenGL 4.3 Compute Shader 進行骨骼矩陣加權混合
 *   - 三組 SSBO：骨骼矩陣（binding=0）、輸入頂點（binding=1）、輸出頂點（binding=2）
 *   - Work group size = 64，適合大多數 GPU warp/wavefront 大小
 *   - 場景中 50+ 動畫實體時效能優勢明顯
 *
 * 應用場景：
 *   - 大量 collapse 動畫同時播放
 *   - 結構體破壞時的碎片骨骼動畫
 *   - 多實體建築場景中的動畫批次處理
 *
 * 參考：
 *   - "Skinning in a Compute Shader" (Wicked Engine 2017)
 *   - GPU Gems 3, ch.2: Animated Crowd Rendering
 *   - GeckoLib animation pipeline 概念
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public final class BRComputeSkinning {

    private BRComputeSkinning() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-ComputeSkin");

    // ─── 常數 ───────────────────────────────────────────────

    /** 單次 dispatch 最大頂點數 */
    private static final int MAX_COMPUTE_VERTICES = 65536;

    /** 最大骨骼數（引用 BRRenderConfig） */
    private static final int MAX_BONES = BRRenderConfig.MAX_BONES; // 128

    /** 單一骨骼矩陣大小：mat4 = 16 floats * 4 bytes = 64 bytes */
    private static final int BONE_MATRIX_SIZE_BYTES = MAX_BONES * 64;

    /** Compute shader work group 大小 */
    private static final int WORK_GROUP_SIZE = 64;

    /** 輸入頂點大小：position(vec4) + normal(vec4) + boneIds(ivec4) + boneWeights(vec4) = 64 bytes */
    private static final int INPUT_VERTEX_SIZE_BYTES = 64;

    /** 輸出頂點大小：position(vec4) + normal(vec4) = 32 bytes */
    private static final int OUTPUT_VERTEX_SIZE_BYTES = 32;

    // ─── GLSL Compute Shader 原始碼 ─────────────────────────

    private static final String COMPUTE_SHADER_SOURCE = """
            #version 430 core
            layout(local_size_x = 64) in;

            struct InputVertex {
                vec4 position;    // xyz=pos, w=unused
                vec4 normal;      // xyz=normal, w=unused
                ivec4 boneIds;
                vec4 boneWeights;
            };

            struct OutputVertex {
                vec4 position;
                vec4 normal;
            };

            layout(std430, binding = 0) readonly buffer BoneMatrices {
                mat4 bones[];
            };

            layout(std430, binding = 1) readonly buffer InputVertices {
                InputVertex inVerts[];
            };

            layout(std430, binding = 2) writeonly buffer OutputVertices {
                OutputVertex outVerts[];
            };

            uniform int u_vertexCount;

            void main() {
                uint idx = gl_GlobalInvocationID.x;
                if (idx >= u_vertexCount) return;

                InputVertex v = inVerts[idx];
                vec4 skinnedPos = vec4(0.0);
                vec4 skinnedNorm = vec4(0.0);

                for (int i = 0; i < 4; i++) {
                    float w = v.boneWeights[i];
                    if (w <= 0.0) continue;
                    mat4 bm = bones[v.boneIds[i]];
                    skinnedPos += bm * vec4(v.position.xyz, 1.0) * w;
                    skinnedNorm += bm * vec4(v.normal.xyz, 0.0) * w;
                }

                outVerts[idx].position = vec4(skinnedPos.xyz, 1.0);
                outVerts[idx].normal = vec4(normalize(skinnedNorm.xyz), 0.0);
            }
            """;

    // ─── GL 資源 ────────────────────────────────────────────

    private static int computeProgram = 0;
    private static int boneSSBO = 0;
    private static int inputSSBO = 0;
    private static int outputSSBO = 0;
    private static int uniformVertexCount = -1;

    private static boolean initialized = false;
    private static boolean supported = false;

    // ─── 統計 ───────────────────────────────────────────────

    private static int processedEntityCount = 0;
    private static long totalVerticesProcessed = 0L;

    // ═══════════════════════════════════════════════════════════
    //  初始化 / 清理
    // ═══════════════════════════════════════════════════════════

    /**
     * 初始化 compute skinning 系統。
     * 檢查 GL 4.3 支援，編譯 compute shader，配置 SSBO。
     * 若硬體不支援則靜默降級，不拋出例外。
     */
    public static void init() {
        if (initialized) {
            LOG.warn("BRComputeSkinning 已初始化，跳過重複呼叫");
            return;
        }

        // 檢查 GL 4.3 支援
        supported = isSupported();
        if (!supported) {
            LOG.info("GPU 不支援 OpenGL 4.3 — compute skinning 停用，退回 vertex shader 路徑");
            return;
        }

        LOG.info("初始化 GPU Compute Skinning（最大頂點={}，最大骨骼={}）", MAX_COMPUTE_VERTICES, MAX_BONES);

        try {
            // 編譯 compute shader
            computeProgram = compileComputeShader(COMPUTE_SHADER_SOURCE);
            if (computeProgram == 0) {
                LOG.error("Compute shader 編譯失敗 — 停用 compute skinning");
                supported = false;
                return;
            }

            // 取得 uniform location
            uniformVertexCount = GL20.glGetUniformLocation(computeProgram, "u_vertexCount");
            if (uniformVertexCount == -1) {
                LOG.warn("找不到 uniform 'u_vertexCount' — shader 可能有誤");
            }

            // 配置 SSBO
            boneSSBO = createSSBO(BONE_MATRIX_SIZE_BYTES);
            inputSSBO = createSSBO(MAX_COMPUTE_VERTICES * INPUT_VERTEX_SIZE_BYTES);
            outputSSBO = createSSBO(MAX_COMPUTE_VERTICES * OUTPUT_VERTEX_SIZE_BYTES);

            initialized = true;
            LOG.info("GPU Compute Skinning 初始化完成 — boneSSBO={}, inputSSBO={}, outputSSBO={}",
                    boneSSBO, inputSSBO, outputSSBO);

        } catch (Exception e) {
            LOG.error("Compute skinning 初始化時發生例外，退回 vertex shader 路徑", e);
            cleanupResources();
            supported = false;
        }
    }

    /**
     * 釋放所有 GL 資源。
     */
    public static void cleanup() {
        if (!initialized) return;
        LOG.info("清理 GPU Compute Skinning 資源");
        cleanupResources();
        initialized = false;
        processedEntityCount = 0;
        totalVerticesProcessed = 0L;
    }

    /**
     * 查詢系統是否已初始化且可用。
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 查詢目前 GPU 是否支援 OpenGL 4.3（compute shader 最低需求）。
     */
    public static boolean isSupported() {
        try {
            return GL.getCapabilities().OpenGL43;
        } catch (Exception e) {
            LOG.debug("GL capabilities 查詢失敗", e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  資料上傳
    // ═══════════════════════════════════════════════════════════

    /**
     * 上傳骨骼矩陣至 bone SSBO。
     *
     * @param matrices   包含骨骼矩陣資料的 FloatBuffer（每矩陣 16 floats，column-major）
     * @param boneCount  骨骼數量（不得超過 MAX_BONES）
     */
    public static void uploadBoneMatrices(FloatBuffer matrices, int boneCount) {
        if (!initialized) {
            LOG.warn("uploadBoneMatrices 呼叫於未初始化狀態");
            return;
        }
        if (boneCount > MAX_BONES) {
            LOG.warn("骨骼數量 {} 超過上限 {}，截斷處理", boneCount, MAX_BONES);
            boneCount = MAX_BONES;
        }

        int sizeBytes = boneCount * 64; // 16 floats * 4 bytes
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, boneSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, matrices);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * 上傳輸入頂點資料至 input SSBO。
     *
     * @param vertices     頂點資料 FloatBuffer（每頂點 16 floats：pos4 + norm4 + boneIds4 + weights4）
     * @param vertexCount  頂點數量（不得超過 MAX_COMPUTE_VERTICES）
     */
    public static void uploadVertices(FloatBuffer vertices, int vertexCount) {
        if (!initialized) {
            LOG.warn("uploadVertices 呼叫於未初始化狀態");
            return;
        }
        if (vertexCount > MAX_COMPUTE_VERTICES) {
            LOG.warn("頂點數量 {} 超過上限 {}，截斷處理", vertexCount, MAX_COMPUTE_VERTICES);
            vertexCount = MAX_COMPUTE_VERTICES;
        }

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, inputSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, vertices);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    // ═══════════════════════════════════════════════════════════
    //  Dispatch
    // ═══════════════════════════════════════════════════════════

    /**
     * 發送 compute shader dispatch，執行 GPU 蒙皮運算。
     *
     * @param vertexCount 需處理的頂點數量
     */
    public static void dispatch(int vertexCount) {
        if (!initialized) {
            LOG.warn("dispatch 呼叫於未初始化狀態");
            return;
        }
        if (vertexCount <= 0) return;
        if (vertexCount > MAX_COMPUTE_VERTICES) {
            vertexCount = MAX_COMPUTE_VERTICES;
        }

        // 綁定 compute program
        GL20.glUseProgram(computeProgram);

        // 設定 uniform
        GL20.glUniform1i(uniformVertexCount, vertexCount);

        // 綁定 SSBO
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, boneSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, inputSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, outputSSBO);

        // Dispatch compute shader
        int workGroups = (vertexCount + WORK_GROUP_SIZE - 1) / WORK_GROUP_SIZE;
        GL43.glDispatchCompute(workGroups, 1, 1);

        // Memory barrier — 確保 compute shader 寫入完成後才進行後續讀取
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // 解綁 program
        GL20.glUseProgram(0);

        // 更新統計
        processedEntityCount++;
        totalVerticesProcessed += vertexCount;
    }

    // ═══════════════════════════════════════════════════════════
    //  輸出綁定
    // ═══════════════════════════════════════════════════════════

    /**
     * 取得輸出 SSBO ID，供 render pass 綁定使用。
     *
     * @return output SSBO 的 OpenGL buffer ID，未初始化時回傳 0
     */
    public static int getOutputSSBO() {
        return outputSSBO;
    }

    /**
     * 將輸出 SSBO 綁定為頂點屬性來源，供後續 draw call 使用。
     * 綁定至 binding point 2 以供 vertex shader 讀取蒙皮後的頂點資料。
     */
    public static void bindOutputForRendering() {
        if (!initialized) {
            LOG.warn("bindOutputForRendering 呼叫於未初始化狀態");
            return;
        }
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, outputSSBO);
    }

    // ═══════════════════════════════════════════════════════════
    //  統計
    // ═══════════════════════════════════════════════════════════

    /**
     * 取得已處理的實體數量（自上次重置以來）。
     */
    public static int getProcessedEntityCount() {
        return processedEntityCount;
    }

    /**
     * 取得已處理的總頂點數（自上次重置以來）。
     */
    public static long getTotalVerticesProcessed() {
        return totalVerticesProcessed;
    }

    // ═══════════════════════════════════════════════════════════
    //  內部方法
    // ═══════════════════════════════════════════════════════════

    /**
     * 編譯 compute shader 並建立 program。
     */
    private static int compileComputeShader(String source) {
        int shaderId = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shaderId, source);
        GL20.glCompileShader(shaderId);

        if (GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shaderId, 4096);
            LOG.error("Compute shader 編譯失敗:\n{}", log);
            GL20.glDeleteShader(shaderId);
            return 0;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shaderId);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 4096);
            LOG.error("Compute program 連結失敗:\n{}", log);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(shaderId);
            return 0;
        }

        // 連結後 shader 可釋放
        GL20.glDetachShader(program, shaderId);
        GL20.glDeleteShader(shaderId);

        LOG.debug("Compute shader 編譯成功 — program={}", program);
        return program;
    }

    /**
     * 建立 SSBO 並配置初始大小。
     */
    private static int createSSBO(int sizeBytes) {
        int ssbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, sizeBytes, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return ssbo;
    }

    /**
     * 釋放所有 GL 資源（internal）。
     */
    private static void cleanupResources() {
        if (computeProgram != 0) {
            GL20.glDeleteProgram(computeProgram);
            computeProgram = 0;
        }
        if (boneSSBO != 0) {
            GL15.glDeleteBuffers(boneSSBO);
            boneSSBO = 0;
        }
        if (inputSSBO != 0) {
            GL15.glDeleteBuffers(inputSSBO);
            inputSSBO = 0;
        }
        if (outputSSBO != 0) {
            GL15.glDeleteBuffers(outputSSBO);
            outputSSBO = 0;
        }
        uniformVertexCount = -1;
    }
}
