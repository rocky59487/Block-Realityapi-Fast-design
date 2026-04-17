package com.blockreality.api.client.render.rt;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side cache of precompiled SPIR-V blobs. Feeds both the Java
 * Vulkan path (BRVulkanDevice / BRRayTracingPipeline) and, when the
 * native render runtime is active, the matching C++ blob registry
 * inside {@code libbr_core} (so both sides see the same shaders).
 *
 * <p>Lookup order:
 * <ol>
 *   <li>in-memory cache (hit);</li>
 *   <li>{@code assets/blockreality/shaders/<name>.spv} bundled by the
 *       Gradle {@code precompileShaders} task;</li>
 *   <li>GLSL JIT fallback via {@link BRVulkanDevice#compileGLSLtoSPIRV}
 *       (dev path — emits a one-time warning).</li>
 * </ol>
 * </p>
 */
public final class NativeShaderRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("Render-ShaderRegistry");

    private static final String RESOURCE_BASE = "/assets/blockreality/shaders/";

    /** Canonical shader name ⇒ immutable SPIR-V ByteBuffer (native byte order). */
    private static final Map<String, ByteBuffer> CACHE = new ConcurrentHashMap<>();

    private NativeShaderRegistry() {}

    /**
     * Looks up (or loads and caches) the SPIR-V blob for @p canonicalName.
     * Name format: {@code "compute/pfsf/rbgs_smooth.comp"} (relative to
     * the shaders asset root, without the {@code .spv} extension).
     *
     * @return a read-only direct ByteBuffer or {@code null} if neither the
     *         precompiled {@code .spv} nor the GLSL source exists.
     */
    public static ByteBuffer lookup(String canonicalName) {
        if (canonicalName == null || canonicalName.isEmpty()) return null;
        ByteBuffer cached = CACHE.get(canonicalName);
        if (cached != null) return cached.asReadOnlyBuffer();

        ByteBuffer blob = loadFromResource(canonicalName + ".spv");
        if (blob != null) {
            CACHE.put(canonicalName, blob);
            return blob.asReadOnlyBuffer();
        }
        return null;
    }

    /**
     * Preload a manifest of shader names. Warm-up path for server start —
     * drains resource I/O off the hot path. Missing entries log once and
     * are absent from the cache.
     */
    public static void preload(Iterable<String> names) {
        for (String n : names) {
            if (lookup(n) == null) {
                LOGGER.warn("Shader blob missing: {} — will fall back to GLSL JIT", n);
            }
        }
    }

    /** Drops the cache — useful for hot reload in dev environments. */
    public static void clear() { CACHE.clear(); }

    /** @return number of cached SPIR-V blobs. */
    public static int size() { return CACHE.size(); }

    // ─── Internal loaders ────────────────────────────────────────

    private static ByteBuffer loadFromResource(String relativePath) {
        String resourcePath = RESOURCE_BASE + relativePath;
        try (InputStream in = NativeShaderRegistry.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            byte[] raw = in.readAllBytes();
            ByteBuffer buf = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(raw).flip();
            return buf;
        } catch (Throwable t) {
            LOGGER.warn("Failed to read shader resource {}: {}", resourcePath, t.toString());
            return null;
        }
    }

    /** Dev-only: load a {@code .spv} from a filesystem path (hot reload). */
    public static ByteBuffer loadFromFile(String canonicalName, Path spvPath) {
        try {
            byte[] raw = Files.readAllBytes(spvPath);
            ByteBuffer buf = ByteBuffer.allocateDirect(raw.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(raw).flip();
            CACHE.put(canonicalName, buf);
            return buf.asReadOnlyBuffer();
        } catch (Throwable t) {
            LOGGER.warn("Failed to read shader file {}: {}", spvPath, t.toString());
            return null;
        }
    }
}
