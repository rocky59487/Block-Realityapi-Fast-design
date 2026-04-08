package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hybrid physics router — decides per-island whether to use
 * PFSF (iterative) or FNO (ML surrogate) for physics solving.
 *
 * <pre>
 * ┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
 * │  Structure   │────→│ ShapeClassifier  │────→│  score < 0.45   │──→ PFSF (regular)
 * │   Island     │     │  irregularity()  │     │  score >= 0.45  │──→ FNO  (irregular)
 * └─────────────┘     └──────────────────┘     └─────────────────┘
 * </pre>
 *
 * <p>Benefits:</p>
 * <ul>
 *   <li>Regular structures (walls, floors, columns) → PFSF: proven, no ML dependency</li>
 *   <li>Irregular structures (arches, cantilevers, spirals) → FNO: FEM-trained accuracy</li>
 *   <li>Saves 70%+ ML training time by only training on irregular shapes</li>
 *   <li>Classification is cached per island, recomputed only on structural change</li>
 * </ul>
 *
 * <p>FNO inference requires ONNX Runtime on classpath + trained model in resources.
 * If unavailable, all islands fall back to PFSF.</p>
 *
 * @since v0.3a
 * @see ShapeClassifier
 * @see IPFSFRuntime
 */
public class HybridPhysicsRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Router");

    public enum Backend { PFSF, FNO }

    /** Per-island cached routing decision. */
    private final ConcurrentHashMap<Integer, CachedDecision> cache = new ConcurrentHashMap<>();

    /** FNO ONNX runtime (null if unavailable). */
    private OnnxPFSFRuntime onnxRuntime;

    /** FNO availability. */
    private boolean fnoAvailable = false;

    /** Irregularity threshold (configurable). */
    private float threshold = ShapeClassifier.DEFAULT_THRESHOLD;

    // ── Stats (thread-safe) ──
    private final AtomicInteger pfsfCount = new AtomicInteger();
    private final AtomicInteger fnoCount = new AtomicInteger();

    /**
     * Initialize router. Loads FNO ONNX model if path provided.
     *
     * @param modelPath Path to .onnx file (null = FNO disabled, all → PFSF)
     */
    public void init(String modelPath) {
        if (modelPath != null) {
            try {
                onnxRuntime = new OnnxPFSFRuntime();
                fnoAvailable = onnxRuntime.loadModel(modelPath);
                if (fnoAvailable) {
                    LOGGER.info("[Router] FNO model loaded (grid={}), hybrid routing enabled",
                            onnxRuntime.getGridSize());
                } else {
                    LOGGER.warn("[Router] FNO model failed to load, all islands → PFSF");
                    onnxRuntime = null;
                }
            } catch (Exception e) {
                fnoAvailable = false;
                onnxRuntime = null;
                LOGGER.warn("[Router] FNO init failed: {}, all islands → PFSF", e.getMessage());
            }
        } else {
            LOGGER.info("[Router] No FNO model path, all islands → PFSF");
        }
    }

    /** Get the ONNX runtime (null if not available). */
    public OnnxPFSFRuntime getOnnxRuntime() { return onnxRuntime; }

    /** Shutdown ONNX resources. */
    public void shutdown() {
        if (onnxRuntime != null) {
            onnxRuntime.shutdown();
            onnxRuntime = null;
        }
        fnoAvailable = false;
    }

    /**
     * Decide which backend to use for an island.
     *
     * @param islandId   Island identifier
     * @param members    All block positions
     * @param anchors    Anchor positions
     * @param epoch      Current structural epoch (for cache invalidation)
     * @return Backend.PFSF or Backend.FNO
     */
    public Backend route(int islandId, Set<BlockPos> members, Set<BlockPos> anchors,
                         long epoch) {
        // Fast path: FNO not available → always PFSF
        if (!fnoAvailable) return Backend.PFSF;

        // Check cache
        CachedDecision cached = cache.get(islandId);
        if (cached != null && cached.epoch == epoch) {
            return cached.backend;
        }

        // Classify
        float score = ShapeClassifier.score(members, anchors);
        Backend decision = score >= threshold ? Backend.FNO : Backend.PFSF;

        // Cache
        cache.put(islandId, new CachedDecision(decision, score, epoch));

        // Stats
        if (decision == Backend.PFSF) pfsfCount.incrementAndGet();
        else fnoCount.incrementAndGet();

        int total = pfsfCount.get() + fnoCount.get();
        if (total % 100 == 0) {
            LOGGER.debug("[Router] routed {} PFSF + {} FNO islands", pfsfCount.get(), fnoCount.get());
        }

        return decision;
    }

    /**
     * Remove cached decision when island is destroyed.
     */
    public void removeIsland(int islandId) {
        cache.remove(islandId);
    }

    /**
     * Get routing statistics string.
     */
    public String getStats() {
        int pfsf = pfsfCount.get(), fno = fnoCount.get();
        int total = pfsf + fno;
        if (total == 0) return "Router: no islands routed yet";
        float fnoPct = 100.0f * fno / total;
        return "Router: " + pfsf + " PFSF + " + fno + " FNO (" + String.format(java.util.Locale.US, "%.0f", fnoPct) + "% irregular), threshold=" + String.format(java.util.Locale.US, "%.2f", threshold);
    }

    public void setThreshold(float threshold) {
        this.threshold = Math.max(0.0f, Math.min(1.0f, threshold));
    }

    public float getThreshold() { return threshold; }
    public boolean isFnoAvailable() { return fnoAvailable; }

    private record CachedDecision(Backend backend, float score, long epoch) {}
}
