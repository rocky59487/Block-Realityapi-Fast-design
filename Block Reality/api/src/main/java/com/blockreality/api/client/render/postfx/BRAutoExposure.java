package com.blockreality.api.client.render.postfx;

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

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * GPU luminance histogram compute shader for auto-exposure.
 * <p>
 * Replaces the simplified CPU implementation with a two-pass compute shader approach:
 * <ol>
 *   <li>Build a 64-bin luminance histogram from the HDR scene texture</li>
 *   <li>Reduce the histogram to a single average luminance, excluding low/high percentiles</li>
 * </ol>
 * The result is read back to the CPU via an SSBO for use by the tone-mapping pass.
 */
@OnlyIn(Dist.CLIENT)
public final class BRAutoExposure {

    private BRAutoExposure() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-AutoExposure");

    // Constants
    public static final float MIN_LOG_LUMINANCE = -4.0f;   // log2(1/16)
    public static final float MAX_LOG_LUMINANCE = 16.0f;
    public static final float LOG_LUM_RANGE = MAX_LOG_LUMINANCE - MIN_LOG_LUMINANCE; // 20.0
    public static final int HISTOGRAM_BINS = 64;

    private static final float DEFAULT_LOW_PERCENTILE = 0.05f;
    private static final float DEFAULT_HIGH_PERCENTILE = 0.95f;

    // GL resources
    private static int histogramBuildProgram = 0;
    private static int histogramReduceProgram = 0;
    private static int histogramSSBO = 0;
    private static int resultSSBO = 0;

    // State
    private static boolean initialized = false;
    private static float averageLuminance = 0.18f; // default mid-grey

    // ---- Embedded GLSL sources ----

    private static final String HISTOGRAM_BUILD_SOURCE = """
            #version 430 core
            layout(local_size_x = 16, local_size_y = 16) in;

            uniform sampler2D u_sceneTex;
            uniform vec2 u_resolution;
            uniform float u_minLogLum;
            uniform float u_logLumRange;

            layout(std430, binding = 0) buffer Histogram {
                uint bins[64];
            };

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                if (pixel.x >= int(u_resolution.x) || pixel.y >= int(u_resolution.y)) return;

                vec2 uv = (vec2(pixel) + 0.5) / u_resolution;
                vec3 color = texture(u_sceneTex, uv).rgb;
                float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));

                if (lum < 0.001) return;

                float logLum = clamp((log2(lum) - u_minLogLum) / u_logLumRange, 0.0, 1.0);
                uint binIndex = uint(logLum * 63.0);
                atomicAdd(bins[binIndex], 1u);
            }
            """;

    private static final String HISTOGRAM_REDUCE_SOURCE = """
            #version 430 core
            layout(local_size_x = 64) in;

            layout(std430, binding = 0) buffer Histogram {
                uint bins[64];
            };

            layout(std430, binding = 1) buffer Result {
                float avgLuminance;
            };

            uniform float u_minLogLum;
            uniform float u_logLumRange;
            uniform float u_lowPercentile;
            uniform float u_highPercentile;
            uniform uint u_totalPixels;

            shared uint localBins[64];

            void main() {
                uint idx = gl_LocalInvocationID.x;
                localBins[idx] = bins[idx];
                barrier();

                // Count total (excluding black)
                uint totalCount = 0u;
                for (int i = 0; i < 64; i++) totalCount += localBins[i];
                if (totalCount == 0u) { if (idx == 0u) avgLuminance = 0.18; return; }

                // Find percentile bounds
                uint lowCut = uint(float(totalCount) * u_lowPercentile);
                uint highCut = uint(float(totalCount) * u_highPercentile);

                // Weighted average (exclude low/high percentile)
                if (idx == 0u) {
                    uint cumulative = 0u;
                    float weightedSum = 0.0;
                    float weightTotal = 0.0;

                    for (int i = 0; i < 64; i++) {
                        uint count = localBins[i];
                        uint prevCum = cumulative;
                        cumulative += count;

                        uint effectiveLow = max(prevCum, lowCut);
                        uint effectiveHigh = min(cumulative, highCut);
                        if (effectiveHigh <= effectiveLow) continue;

                        float binCenter = (float(i) + 0.5) / 64.0;
                        float logLum = u_minLogLum + binCenter * u_logLumRange;
                        float weight = float(effectiveHigh - effectiveLow);

                        weightedSum += logLum * weight;
                        weightTotal += weight;
                    }

                    float avgLogLum = (weightTotal > 0.0) ? weightedSum / weightTotal : log2(0.18);
                    avgLuminance = exp2(avgLogLum);
                }
            }
            """;

    /**
     * Initialize compute shaders and SSBO resources.
     * Requires GL 4.3 (compute shader support).
     */
    public static void init() {
        if (initialized) {
            LOG.warn("BRAutoExposure already initialized, skipping");
            return;
        }

        if (!isSupported()) {
            LOG.warn("GL 4.3 not available — BRAutoExposure cannot initialize");
            return;
        }

        try {
            // Compile histogram build shader
            histogramBuildProgram = compileComputeShader(HISTOGRAM_BUILD_SOURCE, "histogram_build");
            if (histogramBuildProgram == 0) return;

            // Compile histogram reduce shader
            histogramReduceProgram = compileComputeShader(HISTOGRAM_REDUCE_SOURCE, "histogram_reduce");
            if (histogramReduceProgram == 0) {
                GL20.glDeleteProgram(histogramBuildProgram);
                histogramBuildProgram = 0;
                return;
            }

            // Allocate histogram SSBO: 64 uints = 256 bytes
            histogramSSBO = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, histogramSSBO);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, HISTOGRAM_BINS * 4L, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            // Allocate result SSBO: 1 float = 4 bytes
            resultSSBO = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, resultSSBO);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 4L, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            initialized = true;
            LOG.info("BRAutoExposure initialized (histogram bins: {}, log range: [{}, {}])",
                    HISTOGRAM_BINS, MIN_LOG_LUMINANCE, MAX_LOG_LUMINANCE);
        } catch (Exception e) {
            LOG.error("Failed to initialize BRAutoExposure", e);
            cleanup();
        }
    }

    /**
     * Release all GL resources.
     */
    public static void cleanup() {
        if (histogramBuildProgram != 0) {
            GL20.glDeleteProgram(histogramBuildProgram);
            histogramBuildProgram = 0;
        }
        if (histogramReduceProgram != 0) {
            GL20.glDeleteProgram(histogramReduceProgram);
            histogramReduceProgram = 0;
        }
        if (histogramSSBO != 0) {
            GL15.glDeleteBuffers(histogramSSBO);
            histogramSSBO = 0;
        }
        if (resultSSBO != 0) {
            GL15.glDeleteBuffers(resultSSBO);
            resultSSBO = 0;
        }
        initialized = false;
        averageLuminance = 0.18f;
        LOG.info("BRAutoExposure cleaned up");
    }

    /**
     * @return true if this system requires GL 4.3+ compute shaders and they are available
     */
    public static boolean isSupported() {
        return GL.getCapabilities().OpenGL43;
    }

    /**
     * @return true if {@link #init()} completed successfully
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Dispatch the two-pass compute pipeline: histogram build, then reduce, then readback.
     *
     * @param sceneTexture the HDR scene texture handle
     * @param screenWidth  current framebuffer width
     * @param screenHeight current framebuffer height
     */
    public static void compute(int sceneTexture, int screenWidth, int screenHeight) {
        if (!initialized) {
            LOG.warn("BRAutoExposure not initialized, skipping compute");
            return;
        }

        // Clear histogram before each frame
        clearHistogram();

        // --- Pass 1: Build histogram ---
        GL20.glUseProgram(histogramBuildProgram);

        // Bind scene texture to unit 0
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTexture);
        GL20.glUniform1i(GL20.glGetUniformLocation(histogramBuildProgram, "u_sceneTex"), 0);
        GL20.glUniform2f(GL20.glGetUniformLocation(histogramBuildProgram, "u_resolution"),
                screenWidth, screenHeight);
        GL20.glUniform1f(GL20.glGetUniformLocation(histogramBuildProgram, "u_minLogLum"),
                MIN_LOG_LUMINANCE);
        GL20.glUniform1f(GL20.glGetUniformLocation(histogramBuildProgram, "u_logLumRange"),
                LOG_LUM_RANGE);

        // Bind histogram SSBO
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, histogramSSBO);

        // Dispatch: ceil(width/16) x ceil(height/16)
        int groupsX = (screenWidth + 15) / 16;
        int groupsY = (screenHeight + 15) / 16;
        GL43.glDispatchCompute(groupsX, groupsY, 1);

        // Memory barrier to ensure histogram writes are visible
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // --- Pass 2: Reduce histogram ---
        GL20.glUseProgram(histogramReduceProgram);

        GL20.glUniform1f(GL20.glGetUniformLocation(histogramReduceProgram, "u_minLogLum"),
                MIN_LOG_LUMINANCE);
        GL20.glUniform1f(GL20.glGetUniformLocation(histogramReduceProgram, "u_logLumRange"),
                LOG_LUM_RANGE);
        GL20.glUniform1f(GL20.glGetUniformLocation(histogramReduceProgram, "u_lowPercentile"),
                DEFAULT_LOW_PERCENTILE);
        GL20.glUniform1f(GL20.glGetUniformLocation(histogramReduceProgram, "u_highPercentile"),
                DEFAULT_HIGH_PERCENTILE);
        GL20.glUniform1ui(GL20.glGetUniformLocation(histogramReduceProgram, "u_totalPixels"),
                screenWidth * screenHeight);

        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, histogramSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, resultSSBO);

        GL43.glDispatchCompute(1, 1, 1);

        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        // --- Readback result ---
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, resultSSBO);
        FloatBuffer resultBuf = GL30.glMapBufferRange(
                GL43.GL_SHADER_STORAGE_BUFFER, 0, 4,
                GL30.GL_MAP_READ_BIT
        ).asFloatBuffer();
        if (resultBuf != null) {
            averageLuminance = resultBuf.get(0);
            GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        GL20.glUseProgram(0);
    }

    /**
     * @return the last computed average scene luminance (default 0.18 mid-grey)
     */
    public static float getAverageLuminance() {
        return averageLuminance;
    }

    /**
     * Zero the histogram SSBO before each frame.
     */
    public static void clearHistogram() {
        if (histogramSSBO == 0) return;
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, histogramSSBO);
        GL43.glClearBufferData(GL43.GL_SHADER_STORAGE_BUFFER, GL30.GL_R32UI,
                GL11.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer) null);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    // ---- Internal helpers ----

    private static int compileComputeShader(String source, String debugName) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            LOG.error("Failed to compile compute shader '{}': {}", debugName, log);
            GL20.glDeleteShader(shader);
            return 0;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program, 4096);
            LOG.error("Failed to link compute program '{}': {}", debugName, log);
            GL20.glDeleteProgram(program);
            return 0;
        }

        return program;
    }
}
