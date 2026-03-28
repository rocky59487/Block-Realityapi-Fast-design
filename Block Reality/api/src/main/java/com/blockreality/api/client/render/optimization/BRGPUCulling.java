package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;

/**
 * GPU-driven frustum + Hi-Z occlusion culling system.
 *
 * <p>Based on Wihlidal/Aaltonen, "GPU-Driven Rendering Pipelines",
 * SIGGRAPH 2015. A compute shader performs frustum and occlusion culling
 * for 1M+ objects in under 0.5 ms. The compute shader writes surviving
 * draw commands into an indirect draw buffer, yielding O(1) CPU overhead.</p>
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>All LOD section AABB bounding boxes are stored in an input SSBO (binding 0).</li>
 *   <li>A compute dispatch tests each AABB against the camera frustum planes.</li>
 *   <li>Surviving AABBs are further tested against the Hi-Z depth pyramid.</li>
 *   <li>Visible objects are packed as indirect draw commands in SSBO (binding 1).</li>
 *   <li>An atomic counter (binding 2) tracks the visible count.</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class BRGPUCulling {

    private static final Logger LOG = LoggerFactory.getLogger("BR-GPUCull");

    /** Maximum number of objects that can be culled in a single dispatch. */
    public static final int MAX_OBJECTS = 16384;

    /** Byte stride of one AABB entry (8 floats: minXYZ + drawBase, maxXYZ + indexCount). */
    public static final int AABB_STRIDE = 32;

    /** Byte stride of one indirect draw command (5 uints). */
    public static final int DRAW_COMMAND_STRIDE = 20;

    /** Compute shader work group size — must match local_size_x in GLSL. */
    public static final int WORK_GROUP_SIZE = 64;

    // ── GLSL compute shader source ─────────────────────────────────────
    private static final String COMPUTE_SHADER_SOURCE = """
            #version 430 core
            layout(local_size_x = 64) in;

            struct AABB {
                vec4 minBound;  // xyz=min, w=drawCommandBase
                vec4 maxBound;  // xyz=max, w=indexCount
            };

            struct DrawCommand {
                uint count;
                uint instanceCount;
                uint firstIndex;
                uint baseVertex;
                uint baseInstance;
            };

            layout(std430, binding = 0) readonly buffer AABBBuffer {
                AABB aabbs[];
            };

            layout(std430, binding = 1) writeonly buffer DrawCommandBuffer {
                DrawCommand commands[];
            };

            layout(std430, binding = 2) buffer AtomicCounter {
                uint visibleCount;
            };

            uniform mat4 u_viewProj;
            uniform vec4 u_frustumPlanes[6];
            uniform int u_objectCount;
            uniform sampler2D u_hiZTexture;
            uniform vec2 u_screenSize;
            uniform int u_hiZMipLevels;

            bool frustumTest(vec3 aabbMin, vec3 aabbMax) {
                for (int i = 0; i < 6; i++) {
                    vec4 p = u_frustumPlanes[i];
                    vec3 positiveVertex = vec3(
                        p.x >= 0.0 ? aabbMax.x : aabbMin.x,
                        p.y >= 0.0 ? aabbMax.y : aabbMin.y,
                        p.z >= 0.0 ? aabbMax.z : aabbMin.z
                    );
                    if (dot(p.xyz, positiveVertex) + p.w < 0.0) return false;
                }
                return true;
            }

            bool hiZOcclusionTest(vec3 aabbMin, vec3 aabbMax) {
                // Project AABB corners to screen space
                vec4 corners[8];
                corners[0] = u_viewProj * vec4(aabbMin.x, aabbMin.y, aabbMin.z, 1.0);
                corners[1] = u_viewProj * vec4(aabbMax.x, aabbMin.y, aabbMin.z, 1.0);
                corners[2] = u_viewProj * vec4(aabbMin.x, aabbMax.y, aabbMin.z, 1.0);
                corners[3] = u_viewProj * vec4(aabbMax.x, aabbMax.y, aabbMin.z, 1.0);
                corners[4] = u_viewProj * vec4(aabbMin.x, aabbMin.y, aabbMax.z, 1.0);
                corners[5] = u_viewProj * vec4(aabbMax.x, aabbMin.y, aabbMax.z, 1.0);
                corners[6] = u_viewProj * vec4(aabbMin.x, aabbMax.y, aabbMax.z, 1.0);
                corners[7] = u_viewProj * vec4(aabbMax.x, aabbMax.y, aabbMax.z, 1.0);

                float minZ = 1.0;
                vec2 minXY = vec2(1.0);
                vec2 maxXY = vec2(0.0);

                for (int i = 0; i < 8; i++) {
                    if (corners[i].w <= 0.0) return true; // behind camera = visible
                    vec3 ndc = corners[i].xyz / corners[i].w;
                    vec2 uv = ndc.xy * 0.5 + 0.5;
                    minXY = min(minXY, uv);
                    maxXY = max(maxXY, uv);
                    minZ = min(minZ, ndc.z * 0.5 + 0.5);
                }

                // Select mip level based on screen-space size
                vec2 size = (maxXY - minXY) * u_screenSize;
                float mipLevel = ceil(log2(max(size.x, size.y)));
                mipLevel = clamp(mipLevel, 0.0, float(u_hiZMipLevels - 1));

                // Sample Hi-Z at centre of the projected AABB
                vec2 center = (minXY + maxXY) * 0.5;
                float hiZDepth = textureLod(u_hiZTexture, center, mipLevel).r;

                return minZ <= hiZDepth; // visible if closer than occluder
            }

            void main() {
                uint idx = gl_GlobalInvocationID.x;
                if (idx >= u_objectCount) return;

                AABB aabb = aabbs[idx];

                if (!frustumTest(aabb.minBound.xyz, aabb.maxBound.xyz)) return;
                if (u_hiZMipLevels > 0 && !hiZOcclusionTest(aabb.minBound.xyz, aabb.maxBound.xyz)) return;

                uint slot = atomicAdd(visibleCount, 1u);
                commands[slot].count = uint(aabb.maxBound.w);
                commands[slot].instanceCount = 1u;
                commands[slot].firstIndex = uint(aabb.minBound.w);
                commands[slot].baseVertex = 0u;
                commands[slot].baseInstance = idx;
            }
            """;

    // ── GL handles ─────────────────────────────────────────────────────
    private static int computeProgram;
    private static int aabbSSBO;
    private static int drawCommandSSBO;
    private static int atomicCounterSSBO;

    // ── Uniform locations ──────────────────────────────────────────────
    private static int uViewProjLoc;
    private static int uFrustumPlanesLoc;
    private static int uObjectCountLoc;
    private static int uHiZTextureLoc;
    private static int uScreenSizeLoc;
    private static int uHiZMipLevelsLoc;

    // ── State ──────────────────────────────────────────────────────────
    private static boolean initialized;
    private static boolean supported;

    // ── Hi-Z texture state ─────────────────────────────────────────────
    private static int hiZTextureId;
    private static int hiZMipLevels;

    // ── Screen dimensions ──────────────────────────────────────────────
    private static int screenWidth = 1920;
    private static int screenHeight = 1080;

    // ── Statistics ─────────────────────────────────────────────────────
    private static int totalDispatched;
    private static int lastVisibleCount;
    private static float lastCullRate;

    private BRGPUCulling() {}

    // ═══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Checks for OpenGL 4.3 compute-shader support, compiles the culling
     * compute program, and allocates the three SSBOs (AABBs, draw commands,
     * atomic counter).
     */
    public static void init() {
        if (initialized) {
            LOG.warn("BRGPUCulling already initialised — skipping");
            return;
        }

        if (!isSupported()) {
            LOG.error("GPU culling requires OpenGL 4.3+ (compute shaders). Feature disabled.");
            return;
        }

        LOG.info("Initialising GPU-driven culling pipeline (max {} objects)", MAX_OBJECTS);

        // ── Compile compute shader ─────────────────────────────────────
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, COMPUTE_SHADER_SOURCE);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String infoLog = GL43.glGetShaderInfoLog(shader, 2048);
            GL43.glDeleteShader(shader);
            LOG.error("Compute shader compilation failed:\n{}", infoLog);
            return;
        }

        computeProgram = GL43.glCreateProgram();
        GL43.glAttachShader(computeProgram, shader);
        GL43.glLinkProgram(computeProgram);

        if (GL43.glGetProgrami(computeProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String infoLog = GL43.glGetProgramInfoLog(computeProgram, 2048);
            GL43.glDeleteProgram(computeProgram);
            GL43.glDeleteShader(shader);
            LOG.error("Compute program link failed:\n{}", infoLog);
            return;
        }

        GL43.glDeleteShader(shader); // no longer needed after linking

        // ── Cache uniform locations ────────────────────────────────────
        uViewProjLoc      = GL43.glGetUniformLocation(computeProgram, "u_viewProj");
        uFrustumPlanesLoc = GL43.glGetUniformLocation(computeProgram, "u_frustumPlanes");
        uObjectCountLoc   = GL43.glGetUniformLocation(computeProgram, "u_objectCount");
        uHiZTextureLoc    = GL43.glGetUniformLocation(computeProgram, "u_hiZTexture");
        uScreenSizeLoc    = GL43.glGetUniformLocation(computeProgram, "u_screenSize");
        uHiZMipLevelsLoc  = GL43.glGetUniformLocation(computeProgram, "u_hiZMipLevels");

        // ── Allocate SSBOs ─────────────────────────────────────────────
        aabbSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, aabbSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) MAX_OBJECTS * AABB_STRIDE, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, aabbSSBO);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        drawCommandSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, drawCommandSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) MAX_OBJECTS * DRAW_COMMAND_STRIDE, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, drawCommandSSBO);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        atomicCounterSSBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 4L, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, atomicCounterSSBO);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        initialized = true;
        LOG.info("GPU culling pipeline ready — SSBOs: aabb={}, draw={}, counter={}",
                aabbSSBO, drawCommandSSBO, atomicCounterSSBO);
    }

    /**
     * Deletes all GL resources (program, SSBOs). Safe to call even if not
     * initialised.
     */
    public static void cleanup() {
        if (!initialized) return;

        GL43.glDeleteProgram(computeProgram);
        GL15.glDeleteBuffers(aabbSSBO);
        GL15.glDeleteBuffers(drawCommandSSBO);
        GL15.glDeleteBuffers(atomicCounterSSBO);

        computeProgram     = 0;
        aabbSSBO           = 0;
        drawCommandSSBO    = 0;
        atomicCounterSSBO  = 0;
        initialized        = false;

        LOG.info("GPU culling pipeline cleaned up");
    }

    /**
     * Returns {@code true} if the current GL context supports compute
     * shaders (OpenGL 4.3+).
     */
    public static boolean isSupported() {
        if (!supported) {
            String version = GL11.glGetString(GL11.GL_VERSION);
            if (version != null) {
                try {
                    String[] parts = version.split("[\\s.]");
                    int major = Integer.parseInt(parts[0]);
                    int minor = Integer.parseInt(parts[1]);
                    supported = (major > 4) || (major == 4 && minor >= 3);
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    LOG.warn("Could not parse GL version string: {}", version);
                    supported = false;
                }
            }
        }
        return supported;
    }

    /** Returns {@code true} after a successful {@link #init()} call. */
    public static boolean isInitialized() {
        return initialized;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Data upload
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Uploads AABB bounding-box data to the input SSBO.
     *
     * @param aabbData    flat float array — each AABB is 8 consecutive floats:
     *                    {@code [minX, minY, minZ, drawCommandBase,
     *                            maxX, maxY, maxZ, indexCount]}
     * @param objectCount number of objects (must be &le; {@link #MAX_OBJECTS})
     */
    public static void uploadAABBs(float[] aabbData, int objectCount) {
        if (!initialized) {
            LOG.warn("uploadAABBs called before init");
            return;
        }
        if (objectCount > MAX_OBJECTS) {
            LOG.warn("Object count {} exceeds MAX_OBJECTS {}; clamping", objectCount, MAX_OBJECTS);
            objectCount = MAX_OBJECTS;
        }

        FloatBuffer buf = BufferUtils.createFloatBuffer(objectCount * 8);
        buf.put(aabbData, 0, objectCount * 8);
        buf.flip();

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, aabbSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, buf);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Sets the six frustum planes used for culling.
     *
     * @param planes 24 floats — 6 planes of 4 components each (nx, ny, nz, d)
     */
    public static void setFrustumPlanes(float[] planes) {
        if (!initialized) return;
        if (planes.length < 24) {
            LOG.error("Frustum planes array must have 24 floats (6 planes x 4), got {}", planes.length);
            return;
        }
        GL43.glUseProgram(computeProgram);
        for (int i = 0; i < 6; i++) {
            int loc = GL43.glGetUniformLocation(computeProgram, "u_frustumPlanes[" + i + "]");
            GL43.glUniform4f(loc, planes[i * 4], planes[i * 4 + 1], planes[i * 4 + 2], planes[i * 4 + 3]);
        }
        GL43.glUseProgram(0);
    }

    /**
     * Uploads the combined view-projection matrix for Hi-Z screen-space
     * projection.
     *
     * @param viewProj the current frame's view-projection matrix
     */
    public static void setViewProjMatrix(Matrix4f viewProj) {
        if (!initialized) return;
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        viewProj.get(buf);
        GL43.glUseProgram(computeProgram);
        GL20.glUniformMatrix4fv(uViewProjLoc, false, buf);
        GL43.glUseProgram(0);
    }

    /**
     * Binds the Hi-Z depth-pyramid texture produced by
     * {@link BRAsyncComputeScheduler}.
     *
     * @param textureId GL texture name
     * @param mipLevels number of mip levels in the pyramid
     */
    public static void setHiZTexture(int textureId, int mipLevels) {
        hiZTextureId = textureId;
        hiZMipLevels = mipLevels;
    }

    /**
     * Sets the screen dimensions used by the Hi-Z mip-level selection.
     *
     * @param width  viewport width in pixels
     * @param height viewport height in pixels
     */
    public static void setScreenSize(int width, int height) {
        screenWidth = width;
        screenHeight = height;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Dispatch
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Resets the atomic visible-count to zero. Must be called before each
     * {@link #dispatch(int)}.
     */
    public static void resetCounter() {
        if (!initialized) return;
        IntBuffer zero = BufferUtils.createIntBuffer(1);
        zero.put(0).flip();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterSSBO);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, zero);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Dispatches the culling compute shader and returns the number of
     * visible objects after frustum + Hi-Z culling.
     *
     * <p>The draw commands are written into the draw-command SSBO,
     * ready for {@code glMultiDrawElementsIndirect}.</p>
     *
     * @param objectCount number of AABBs to test (must match the uploaded
     *                    count)
     * @return number of visible objects
     */
    public static int dispatch(int objectCount) {
        if (!initialized) {
            LOG.warn("dispatch called before init");
            return 0;
        }
        if (objectCount <= 0) return 0;
        if (objectCount > MAX_OBJECTS) objectCount = MAX_OBJECTS;

        totalDispatched = objectCount;

        // Reset counter
        resetCounter();

        // Bind program & set uniforms
        GL43.glUseProgram(computeProgram);
        GL43.glUniform1i(uObjectCountLoc, objectCount);
        GL43.glUniform2f(uScreenSizeLoc, (float) screenWidth, (float) screenHeight);
        GL43.glUniform1i(uHiZMipLevelsLoc, hiZMipLevels);

        // Bind Hi-Z texture to texture unit 0
        if (hiZTextureId != 0 && hiZMipLevels > 0) {
            GL43.glActiveTexture(GL43.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, hiZTextureId);
            GL43.glUniform1i(uHiZTextureLoc, 0);
        }

        // Bind SSBOs
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, aabbSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, drawCommandSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, atomicCounterSSBO);

        // Dispatch
        int numGroups = (objectCount + WORK_GROUP_SIZE - 1) / WORK_GROUP_SIZE;
        GL43.glDispatchCompute(numGroups, 1, 1);

        // Memory barrier — ensure writes are visible to subsequent indirect
        // draw and buffer read-back commands.
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);

        GL43.glUseProgram(0);

        // Read back visible count
        lastVisibleCount = getVisibleCount();
        lastCullRate = totalDispatched > 0
                ? 1.0f - (float) lastVisibleCount / totalDispatched
                : 0.0f;

        return lastVisibleCount;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the draw-command SSBO name, suitable for binding before
     * {@code glMultiDrawElementsIndirect}.
     */
    public static int getDrawCommandSSBO() {
        return drawCommandSSBO;
    }

    /**
     * Reads back the atomic visible count from the GPU. Causes a sync
     * point — prefer {@link #getLastVisibleCount()} after
     * {@link #dispatch(int)}.
     */
    public static int getVisibleCount() {
        if (!initialized) return 0;
        IntBuffer countBuf = BufferUtils.createIntBuffer(1);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterSSBO);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, countBuf);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return countBuf.get(0);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Statistics
    // ═══════════════════════════════════════════════════════════════════

    /** Total objects submitted in the most recent dispatch. */
    public static int getTotalDispatched() {
        return totalDispatched;
    }

    /** Visible objects after the most recent dispatch. */
    public static int getLastVisibleCount() {
        return lastVisibleCount;
    }

    /**
     * Fraction of objects culled in the most recent dispatch
     * (0.0 = nothing culled, 1.0 = everything culled).
     */
    public static float getLastCullRate() {
        return lastCullRate;
    }
}
