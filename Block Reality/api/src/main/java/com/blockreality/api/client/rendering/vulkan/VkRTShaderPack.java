package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * RT Shader 載入與管理（Phase 2-D）。
 *
 * 負責：
 *   1. 從 classpath 讀取 GLSL 原始碼
 *   2. 透過 shaderc（lwjgl-shaderc）編譯 GLSL → SPIR-V
 *   3. 建立 VkShaderModule
 *   4. 提供 handle 給 VkRTPipeline 組裝
 *
 * Shader 檔案位於：assets/blockreality/shaders/rt/
 *   raygen.rgen.glsl    — 光線生成（2-D 目標：white/sky-blue）
 *   miss.rmiss.glsl     — 主要 miss shader（天空色）
 *   shadow.rmiss.glsl   — 陰影 miss shader（非遮蔽 = false）
 *   closesthit.rchit.glsl — 最近碰撞（漫反射白色）
 *
 * @see VkRTPipeline
 */
@OnlyIn(Dist.CLIENT)
public class VkRTShaderPack {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkRTShader");

    private static final String SHADER_BASE_PATH = "/assets/blockreality/shaders/rt/";

    /** shader 名稱 → VkShaderModule handle */
    private final Map<String, Long> shaderModules = new HashMap<>();

    private final VkContext context;

    /** shaderc 編譯器和選項（長生命週期） */
    private long shadercCompiler = 0;
    private long shadercOptions  = 0;

    public VkRTShaderPack(VkContext context) {
        this.context = context;
    }

    // ═══ 載入所有 RT shader ═══

    /**
     * 載入並編譯所有 RT shader。
     *
     * 若 shader 檔案不存在則自動使用 fallback 內嵌 GLSL。
     *
     * @return true 若至少 raygen + miss + closesthit 成功
     */
    public boolean loadAll() {
        if (!initShaderc()) return false;

        boolean ok = true;
        ok &= loadShader("raygen",     "raygen.rgen.glsl",
            shaderc_raygen_shader,     EMBEDDED_RAYGEN);
        ok &= loadShader("miss",       "miss.rmiss.glsl",
            shaderc_miss_shader,       EMBEDDED_MISS);
        // shadow miss 是 optional
        loadShader("shadow",           "shadow.rmiss.glsl",
            shaderc_miss_shader,       EMBEDDED_SHADOW_MISS);
        ok &= loadShader("closesthit", "closesthit.rchit.glsl",
            shaderc_closesthit_shader, EMBEDDED_CLOSEST_HIT);

        cleanupShaderc();

        if (ok) {
            LOG.info("VkRTShaderPack loaded {} shader modules", shaderModules.size());
        } else {
            LOG.error("VkRTShaderPack: some required shaders failed to load");
        }
        return ok;
    }

    // ═══ 私有：shaderc 初始化 ═══

    private boolean initShaderc() {
        shadercCompiler = shaderc_compiler_initialize();
        if (shadercCompiler == 0) {
            LOG.error("shaderc_compiler_initialize failed");
            return false;
        }
        shadercOptions = shaderc_compile_options_initialize();
        if (shadercOptions == 0) {
            LOG.error("shaderc_compile_options_initialize failed");
            shaderc_compiler_release(shadercCompiler);
            shadercCompiler = 0;
            return false;
        }
        // 目標：Vulkan 1.1，SPIR-V 1.5
        shaderc_compile_options_set_target_env(shadercOptions,
            shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_1);
        shaderc_compile_options_set_target_spirv(shadercOptions,
            shaderc_spirv_version_1_5);
        shaderc_compile_options_set_optimization_level(shadercOptions,
            shaderc_optimization_level_performance);
        return true;
    }

    private void cleanupShaderc() {
        if (shadercOptions  != 0) { shaderc_compile_options_release(shadercOptions);  shadercOptions  = 0; }
        if (shadercCompiler != 0) { shaderc_compiler_release(shadercCompiler);        shadercCompiler = 0; }
    }

    // ═══ 私有：單個 shader 載入 ═══

    /**
     * 載入一個 shader：先嘗試從 classpath 讀取，失敗則使用 embedded fallback。
     */
    private boolean loadShader(String name, String filename, int shaderKind,
                                String embeddedGlsl) {
        String glsl = readResourceGlsl(SHADER_BASE_PATH + filename);
        if (glsl == null) {
            if (embeddedGlsl != null) {
                LOG.debug("Shader file '{}' not found — using embedded fallback", filename);
                glsl = embeddedGlsl;
            } else {
                LOG.warn("Shader '{}' not found and no fallback available", name);
                return false;
            }
        }

        ByteBuffer spirv = compileGlsl(glsl, filename, shaderKind);
        if (spirv == null) return false;

        long moduleHandle = createShaderModule(spirv);
        org.lwjgl.system.MemoryUtil.memFree(spirv);

        if (moduleHandle == 0) return false;

        shaderModules.put(name, moduleHandle);
        LOG.debug("Shader '{}' loaded (module=0x{})", name, Long.toHexString(moduleHandle));
        return true;
    }

    /** 從 classpath 讀取 GLSL 文字 */
    private String readResourceGlsl(String path) {
        try (InputStream is = VkRTShaderPack.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.debug("Cannot read shader resource '{}': {}", path, e.getMessage());
            return null;
        }
    }

    /** shaderc 編譯 GLSL → SPIR-V ByteBuffer（呼叫者負責 memFree） */
    private ByteBuffer compileGlsl(String source, String filename, int shaderKind) {
        long result = shaderc_compile_into_spv(
            shadercCompiler,
            source,
            shaderKind,
            filename,
            "main",
            shadercOptions);

        if (result == 0) {
            LOG.error("shaderc_compile_into_spv returned null for '{}'", filename);
            return null;
        }

        int status = (int) shaderc_result_get_compilation_status(result);
        if (status != shaderc_compilation_status_success) {
            LOG.error("Shader '{}' compile failed (status={}): {}",
                filename, status, shaderc_result_get_error_message(result));
            shaderc_result_release(result);
            return null;
        }

        long byteLen = shaderc_result_get_length(result);
        ByteBuffer spirvBytes = shaderc_result_get_bytes(result);
        // 複製到獨立 buffer（result 釋放後原始指標失效）
        ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc((int) byteLen);
        copy.put(spirvBytes).rewind();

        shaderc_result_release(result);
        return copy;
    }

    /** 建立 VkShaderModule */
    private long createShaderModule(ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spirv);

            LongBuffer pModule = stack.mallocLong(1);
            int result = vkCreateShaderModule(context.getDevice(), moduleInfo, null, pModule);
            if (result != VK_SUCCESS) {
                LOG.error("vkCreateShaderModule failed: {}", result);
                return 0;
            }
            return pModule.get(0);
        }
    }

    // ═══ 查詢 ═══

    /**
     * 取得指定 shader 的 VkShaderModule handle。
     *
     * @param name shader 名稱（"raygen", "miss", "shadow", "closesthit"）
     * @return handle，未找到返回 0
     */
    public long getModule(String name) {
        return shaderModules.getOrDefault(name, 0L);
    }

    public boolean isLoaded(String name) { return shaderModules.containsKey(name); }

    // ═══ 釋放 ═══

    /**
     * 銷毀所有 VkShaderModule（pipeline 建立後可安全釋放）。
     */
    public void cleanup() {
        VkDevice device = context.getDevice();
        shaderModules.forEach((name, handle) -> {
            if (handle != 0 && device != null) {
                vkDestroyShaderModule(device, handle, null);
            }
        });
        shaderModules.clear();
        cleanupShaderc();
        LOG.info("VkRTShaderPack cleanup complete");
    }

    // ═══ 內嵌 Fallback GLSL（Phase 2-D 目標：白色/天藍色） ═══

    /**
     * Raygen shader：
     *   - ray hit → 白色（1,1,1,1）
     *   - ray miss → 天藍色（0.4, 0.6, 1.0, 1.0）
     *
     * 此為最小可驗證版本，Phase 3 替換為完整光追。
     */
    private static final String EMBEDDED_RAYGEN = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(set = 0, binding = 0) uniform accelerationStructureEXT topLevelAS;
        layout(set = 0, binding = 1, rgba16f) uniform image2D outputImage;

        layout(location = 0) rayPayloadEXT vec4 hitPayload;

        layout(set = 0, binding = 2) uniform CameraUBO {
            mat4 viewInverse;
            mat4 projInverse;
            vec4 cameraPos;
            float time;
        } camera;

        void main() {
            const vec2 pixelCenter = vec2(gl_LaunchIDEXT.xy) + vec2(0.5);
            const vec2 uv = pixelCenter / vec2(gl_LaunchSizeEXT.xy);

            // Reconstruct ray from inverse view-projection
            vec4 target    = camera.projInverse * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
            vec3 direction = normalize(vec3(camera.viewInverse * vec4(normalize(target.xyz), 0.0)));
            vec3 origin    = vec3(camera.viewInverse[3]);

            hitPayload = vec4(0.4, 0.6, 1.0, 1.0); // default: sky blue

            traceRayEXT(
                topLevelAS,
                gl_RayFlagsOpaqueEXT,
                0xFF,        // cullMask
                0,           // sbtOffset (hit group 0)
                0,           // sbtStride
                0,           // missIndex
                origin,
                0.001,       // tmin
                direction,
                10000.0,     // tmax
                0            // payload location
            );

            imageStore(outputImage, ivec2(gl_LaunchIDEXT.xy), hitPayload);
        }
        """;

    private static final String EMBEDDED_MISS = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadInEXT vec4 hitPayload;

        void main() {
            // Sky gradient: bottom=white, top=sky blue
            hitPayload = vec4(0.4, 0.6, 1.0, 1.0);
        }
        """;

    private static final String EMBEDDED_SHADOW_MISS = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 1) rayPayloadInEXT bool isShadowed;

        void main() {
            isShadowed = false; // Ray reached light — not in shadow
        }
        """;

    private static final String EMBEDDED_CLOSEST_HIT = """
        #version 460
        #extension GL_EXT_ray_tracing : require

        layout(location = 0) rayPayloadInEXT vec4 hitPayload;
        hitAttributeEXT vec2 attribs;

        void main() {
            // Phase 2-D: simple white to confirm hit
            hitPayload = vec4(1.0, 1.0, 1.0, 1.0);
        }
        """;
}
