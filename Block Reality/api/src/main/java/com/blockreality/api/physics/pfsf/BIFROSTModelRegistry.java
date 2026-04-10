package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified registry for all BIFROST ML models.
 *
 * <p>Manages loading, validation, and lifecycle of ONNX models used by
 * the hybrid physics system. Each model has a contract ID that must match
 * the Python export contract (see brml/export/onnx_contracts.py).</p>
 *
 * <pre>
 * Model IDs:
 *   "bifrost_surrogate" → FNO3DMultiField  (structural physics)
 *   "bifrost_fluid"     → FNOFluid3D       (water simulation)
 *   "bifrost_lod"       → LODClassifier    (chunk tier)
 *   "bifrost_collapse"  → CollapsePredictor (failure prediction)
 * </pre>
 *
 * <p>Models are loaded from the config directory:</p>
 * <pre>
 *   config/blockreality/models/
 *   ├── bifrost_surrogate.onnx  (or .npz)
 *   ├── bifrost_fluid.onnx
 *   ├── bifrost_lod.onnx
 *   └── bifrost_collapse.onnx
 * </pre>
 *
 * @since v1.0 (BIFROST)
 */
public final class BIFROSTModelRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("BIFROST-Models");

    /** Standard model directory relative to game root. */
    private static final String MODEL_DIR = "config/blockreality/models";

    /** Known model contract IDs and their expected output channel counts. */
    private static final Map<String, ModelSpec> SPECS = Map.of(
        "bifrost_surrogate", new ModelSpec(6, 10, "structural physics"),
        "bifrost_fluid",     new ModelSpec(8, 4, "water simulation"),
        "bifrost_lod",       new ModelSpec(14, 4, "chunk LOD tier"),
        "bifrost_collapse",  new ModelSpec(8, 5, "collapse prediction")
    );

    /** Loaded model runtimes. */
    private final ConcurrentHashMap<String, OnnxPFSFRuntime> models = new ConcurrentHashMap<>();

    /** Model availability flags. */
    private final ConcurrentHashMap<String, Boolean> available = new ConcurrentHashMap<>();

    /**
     * Initialize: scan model directory and load all available models.
     */
    public void init() {
        // Guard: check if ONNX Runtime is on classpath at all
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
        } catch (ClassNotFoundException e) {
            LOGGER.info("[Models] ONNX Runtime not on classpath, ML features disabled");
            return;
        }

        Path modelDir = Path.of(MODEL_DIR);
        if (!Files.isDirectory(modelDir)) {
            LOGGER.info("[Models] No model directory at {}, ML features disabled", MODEL_DIR);
            try {
                Files.createDirectories(modelDir);
                LOGGER.info("[Models] Created {}, place .onnx files here to enable ML", MODEL_DIR);
            } catch (IOException ignored) {}
            return;
        }

        int loaded = 0;
        for (var entry : SPECS.entrySet()) {
            String id = entry.getKey();
            Path onnxPath = modelDir.resolve(id + ".onnx");

            if (Files.exists(onnxPath)) {
                try {
                    OnnxPFSFRuntime runtime = new OnnxPFSFRuntime();
                    boolean ok = runtime.loadModel(onnxPath.toString());
                    if (ok) {
                        models.put(id, runtime);
                        available.put(id, true);
                        loaded++;
                        LOGGER.info("[Models] Loaded: {} ({})", id, entry.getValue().description);
                    } else {
                        available.put(id, false);
                        LOGGER.warn("[Models] Failed to load: {}", id);
                    }
                } catch (Throwable t) {
                    available.put(id, false);
                    LOGGER.warn("[Models] Error loading {}: {}", id, t.toString());
                }
            } else {
                available.put(id, false);
            }
        }

        LOGGER.info("[Models] {}/{} models loaded from {}", loaded, SPECS.size(), MODEL_DIR);
    }

    /**
     * Check if a specific model is available.
     */
    public boolean isAvailable(String modelId) {
        return available.getOrDefault(modelId, false);
    }

    /**
     * Get a loaded model runtime. Returns null if not available.
     */
    public OnnxPFSFRuntime getModel(String modelId) {
        return models.get(modelId);
    }

    /**
     * Get the surrogate model (convenience).
     */
    public OnnxPFSFRuntime getSurrogate() { return models.get("bifrost_surrogate"); }

    /**
     * Get the fluid model (convenience).
     */
    public OnnxPFSFRuntime getFluid() { return models.get("bifrost_fluid"); }

    /**
     * Get the LOD classifier (convenience).
     */
    public OnnxPFSFRuntime getLOD() { return models.get("bifrost_lod"); }

    /**
     * Get the collapse predictor (convenience).
     */
    public OnnxPFSFRuntime getCollapse() { return models.get("bifrost_collapse"); }

    /**
     * Shutdown all models and release resources.
     */
    public void shutdown() {
        for (var runtime : models.values()) {
            runtime.shutdown();
        }
        models.clear();
        available.clear();
        LOGGER.info("[Models] All models shut down");
    }

    /**
     * Get diagnostic string.
     */
    public String getStats() {
        long loaded = available.values().stream().filter(v -> v).count();
        return String.format("Models: %d/%d loaded", loaded, SPECS.size());
    }

    /**
     * Reload a specific model (hot-swap after retraining).
     */
    public boolean reload(String modelId) {
        Path onnxPath = Path.of(MODEL_DIR, modelId + ".onnx");
        if (!Files.exists(onnxPath)) return false;

        // Shutdown old
        OnnxPFSFRuntime old = models.remove(modelId);
        if (old != null) old.shutdown();

        // Load new
        OnnxPFSFRuntime runtime = new OnnxPFSFRuntime();
        boolean ok = runtime.loadModel(onnxPath.toString());
        if (ok) {
            models.put(modelId, runtime);
            available.put(modelId, true);
            LOGGER.info("[Models] Reloaded: {}", modelId);
        } else {
            available.put(modelId, false);
        }
        return ok;
    }

    private record ModelSpec(int inputChannels, int outputChannels, String description) {}
}
