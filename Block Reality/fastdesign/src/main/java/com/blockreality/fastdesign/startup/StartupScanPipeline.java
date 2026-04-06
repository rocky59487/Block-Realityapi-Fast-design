package com.blockreality.fastdesign.startup;

import com.blockreality.api.material.CustomMaterial;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.pfsf.PFSFConstants;
import com.blockreality.api.sidecar.SidecarBridge;
import com.blockreality.api.spi.IMaterialRegistry;
import com.blockreality.api.spi.ModuleRegistry;
import com.blockreality.fastdesign.startup.StartupPhase.EnvironmentInfo;
import com.blockreality.fastdesign.startup.StartupPhase.PhaseResult;
import com.blockreality.fastdesign.startup.StartupPhase.PhaseStatus;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 7-phase startup scan pipeline.
 *
 * Runs on a dedicated daemon thread; never blocks the render or main thread.
 * All mutable state is thread-safe (volatile fields + CopyOnWriteArrayList).
 */
public final class StartupScanPipeline {

    private static final Logger LOGGER = LogManager.getLogger("BR-StartupScan");
    private static final StartupScanPipeline INSTANCE = new StartupScanPipeline();

    private final CopyOnWriteArrayList<PhaseResult> completedPhases = new CopyOnWriteArrayList<>();

    private volatile String currentPhaseName = "";
    private volatile float currentProgress = 0.0f;
    private volatile boolean scanComplete = false;
    private volatile boolean running = false;
    private volatile boolean isClient = false;

    private volatile EnvironmentInfo environmentInfo = null;
    private volatile int totalWarnings = 0;
    private volatile int totalErrors = 0;
    private volatile long totalDurationMs = 0;

    // Shader files to validate in Phase 5
    private static final String[] PFSF_SHADERS = {
        "jacobi_smooth.comp.glsl",
        "rbgs_smooth.comp.glsl",
        "mg_restrict.comp.glsl",
        "mg_prolong.comp.glsl",
        "failure_scan.comp.glsl",
        "failure_compact.comp.glsl",
        "phi_reduce_max.comp.glsl",
        "phase_field_evolve.comp.glsl",
        "stress_heatmap.frag.glsl",
        "morton_utils.glsl"
    };

    private StartupScanPipeline() {}

    public static StartupScanPipeline getInstance() {
        return INSTANCE;
    }

    // ─── Public API ───

    public void start() {
        start(false);
    }

    public void start(boolean client) {
        if (running) return;
        running = true;
        isClient = client;

        Thread scanThread = new Thread(this::runAllPhases, "BR-StartupScan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    public boolean isComplete() {
        return scanComplete;
    }

    public boolean isRunning() {
        return running;
    }

    public float getCurrentProgress() {
        return currentProgress;
    }

    public String getCurrentPhaseName() {
        return currentPhaseName;
    }

    public List<PhaseResult> getCompletedPhases() {
        return completedPhases;
    }

    public EnvironmentInfo getEnvironmentInfo() {
        return environmentInfo;
    }

    public int getTotalWarnings() {
        return totalWarnings;
    }

    public int getTotalErrors() {
        return totalErrors;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    // ─── Scan Execution ───

    private void runAllPhases() {
        long startTime = System.currentTimeMillis();
        LOGGER.info("[StartupScan] Beginning 7-phase startup scan...");

        try {
            runPhase("Environment",       0.00f, 0.10f, this::phaseEnvironment);
            runPhase("Material Registry",  0.10f, 0.30f, this::phaseMaterialRegistry);
            runPhase("Physics Config",     0.30f, 0.50f, this::phasePhysicsConfig);
            runPhase("Sidecar",            0.50f, 0.65f, this::phaseSidecar);
            runPhase("Shader Validation",  0.65f, 0.80f, this::phaseShaderValidation);
            runPhase("Node Registry",      0.80f, 0.95f, this::phaseNodeRegistry);

            // Phase 7: Ready
            totalDurationMs = System.currentTimeMillis() - startTime;
            int warnings = 0;
            int errors = 0;
            for (PhaseResult r : completedPhases) {
                if (r.status() == PhaseStatus.WARN) warnings++;
                if (r.status() == PhaseStatus.ERROR) errors++;
            }
            totalWarnings = warnings;
            totalErrors = errors;

            currentPhaseName = "Ready";
            currentProgress = 0.95f;

            List<String> readyDetails = new ArrayList<>();
            readyDetails.add("Total elapsed: " + totalDurationMs + " ms");
            readyDetails.add("Warnings: " + warnings + "  Errors: " + errors);
            completedPhases.add(new PhaseResult("Ready", PhaseStatus.OK,
                System.currentTimeMillis() - startTime - totalDurationMs, readyDetails));

            currentProgress = 1.0f;

            LOGGER.info("[StartupScan] Complete in {} ms  (W:{} E:{})",
                totalDurationMs, warnings, errors);

            // Write JSON report
            StartupReportWriter.writeReport(this);

        } catch (Exception e) {
            LOGGER.error("[StartupScan] Unexpected pipeline error", e);
        } finally {
            scanComplete = true;
            running = false;
        }
    }

    @FunctionalInterface
    private interface PhaseAction {
        PhaseResult execute() throws Exception;
    }

    private void runPhase(String name, float progressStart, float progressEnd, PhaseAction action) {
        currentPhaseName = name;
        currentProgress = progressStart;
        long phaseStart = System.currentTimeMillis();

        try {
            PhaseResult result = action.execute();
            completedPhases.add(result);

            if (result.status() == PhaseStatus.ERROR) {
                LOGGER.error("[StartupScan] {} -> ERROR: {}", name, result.details());
            } else if (result.status() == PhaseStatus.WARN) {
                LOGGER.warn("[StartupScan] {} -> WARN: {}", name, result.details());
            } else {
                LOGGER.info("[StartupScan] {} -> OK ({} ms)", name, result.durationMs());
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - phaseStart;
            List<String> details = List.of("Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            completedPhases.add(new PhaseResult(name, PhaseStatus.ERROR, duration, details));
            LOGGER.error("[StartupScan] {} threw unexpected exception", name, e);
        }

        currentProgress = progressEnd;
    }

    // ─── Phase Implementations ───

    private PhaseResult phaseEnvironment() {
        long start = System.currentTimeMillis();
        List<String> details = new ArrayList<>();

        String jvm = System.getProperty("java.version", "unknown");
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        String osName = System.getProperty("os.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        String os = osName + " " + osArch;

        // GPU info: try system property, fallback to "Unknown GPU"
        // (GL calls require render thread, so we use a property if available)
        String gpu = System.getProperty("blockreality.gpu.name", "Unknown GPU");

        // Forge version
        String forgeVersion = "unknown";
        try {
            forgeVersion = net.minecraftforge.versions.forge.ForgeVersion.getVersion();
        } catch (Exception e) {
            details.add("Could not read Forge version: " + e.getMessage());
        }

        environmentInfo = new EnvironmentInfo(jvm, heapMb, os, gpu, forgeVersion);

        details.add("JVM: " + jvm);
        details.add("Heap: " + heapMb + " MB");
        details.add("OS: " + os);
        details.add("GPU: " + gpu);
        details.add("Forge: " + forgeVersion);

        return new PhaseResult("Environment", PhaseStatus.OK,
            System.currentTimeMillis() - start, details);
    }

    private PhaseResult phaseMaterialRegistry() {
        long start = System.currentTimeMillis();
        List<String> details = new ArrayList<>();
        PhaseStatus status = PhaseStatus.OK;

        IMaterialRegistry registry = ModuleRegistry.getMaterialRegistry();
        Collection<String> ids = registry.getAllMaterialIds();
        details.add("Registered materials: " + ids.size());

        for (String id : ids) {
            Optional<RMaterial> opt = registry.getMaterial(id);
            if (opt.isEmpty()) continue;

            RMaterial mat = opt.get();
            String tier;
            if (mat instanceof DynamicMaterial) {
                tier = "DYNAMIC";
            } else if (mat instanceof CustomMaterial) {
                tier = "COMPOSITE";
            } else if (mat instanceof DefaultMaterial) {
                tier = "BASIC";
            } else {
                tier = "UNKNOWN";
            }

            details.add("  " + id + " [" + tier + "]");

            // Validate required fields
            boolean missingStrength = mat.getRcomp() == 0 && mat.getRtens() == 0;
            boolean missingDensity = mat.getDensity() <= 0;
            boolean missingE = mat.getYoungsModulusPa() <= 0;

            if (missingStrength || missingDensity || missingE) {
                StringBuilder warn = new StringBuilder("    WARNING: " + id + " missing:");
                if (missingE) warn.append(" E");
                if (missingDensity) warn.append(" density");
                if (missingStrength) warn.append(" strength");
                details.add(warn.toString());
                status = PhaseStatus.WARN;
            }
        }

        return new PhaseResult("Material Registry", status,
            System.currentTimeMillis() - start, details);
    }

    private PhaseResult phasePhysicsConfig() {
        long start = System.currentTimeMillis();
        List<String> details = new ArrayList<>();
        PhaseStatus status = PhaseStatus.OK;

        float l0 = PFSFConstants.PHASE_FIELD_L0;
        float gcBase = PFSFConstants.G_C_CONCRETE;
        float shearEdge = PFSFConstants.SHEAR_EDGE_PENALTY;
        float shearCorner = PFSFConstants.SHEAR_CORNER_PENALTY;

        details.add("PHASE_FIELD_L0 = " + l0);
        details.add("G_C_CONCRETE = " + gcBase);
        details.add("SHEAR_EDGE_PENALTY = " + shearEdge);
        details.add("SHEAR_CORNER_PENALTY = " + shearCorner);

        // Validate ranges
        if (l0 < 1.0f || l0 > 4.0f) {
            details.add("WARNING: l0 out of range [1.0, 4.0]: " + l0);
            status = PhaseStatus.WARN;
        }
        if (gcBase <= 0) {
            details.add("WARNING: G_C_CONCRETE must be > 0: " + gcBase);
            status = PhaseStatus.WARN;
        }
        if (shearEdge <= 0 || shearEdge >= 1) {
            details.add("WARNING: SHEAR_EDGE_PENALTY out of range (0, 1): " + shearEdge);
            status = PhaseStatus.WARN;
        }
        if (shearCorner <= 0 || shearCorner >= 1) {
            details.add("WARNING: SHEAR_CORNER_PENALTY out of range (0, 1): " + shearCorner);
            status = PhaseStatus.WARN;
        }

        // Also report convergence threshold if available
        details.add("MG_INTERVAL = " + PFSFConstants.MG_INTERVAL);
        details.add("WARMUP_STEPS = " + PFSFConstants.WARMUP_STEPS);

        return new PhaseResult("Physics Config", status,
            System.currentTimeMillis() - start, details);
    }

    private PhaseResult phaseSidecar() {
        long start = System.currentTimeMillis();
        List<String> details = new ArrayList<>();
        PhaseStatus status = PhaseStatus.OK;

        try {
            SidecarBridge bridge = SidecarBridge.getInstance();
            if (!bridge.isRunning()) {
                details.add("Sidecar status: OFFLINE");
                details.add("Sidecar not started — will be available when needed");
                status = PhaseStatus.WARN;
            } else {
                try {
                    JsonObject result = bridge.call("ping", new JsonObject(), 2000);
                    details.add("Sidecar status: ONLINE");
                    details.add("Ping response: " + (result != null ? "OK" : "null"));
                } catch (SidecarBridge.SidecarException e) {
                    details.add("Sidecar status: TIMEOUT");
                    details.add("Ping failed: " + e.getMessage());
                    status = PhaseStatus.WARN;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    details.add("Sidecar status: INTERRUPTED");
                    status = PhaseStatus.WARN;
                }
            }
        } catch (Exception e) {
            details.add("Sidecar status: ERROR");
            details.add("Could not access SidecarBridge: " + e.getMessage());
            status = PhaseStatus.WARN;
        }

        return new PhaseResult("Sidecar", status,
            System.currentTimeMillis() - start, details);
    }

    private PhaseResult phaseShaderValidation() {
        long start = System.currentTimeMillis();
        List<String> details = new ArrayList<>();
        PhaseStatus status = PhaseStatus.OK;
        int missing = 0;

        for (String shader : PFSF_SHADERS) {
            String resourcePath = "assets/blockreality/shaders/compute/pfsf/" + shader;
            boolean found = getClass().getClassLoader().getResource(resourcePath) != null;

            if (found) {
                details.add("  " + shader + " ... PRESENT");
            } else {
                details.add("  " + shader + " ... MISSING");
                missing++;
            }
        }

        details.add(0, "PFSF shaders: " + (PFSF_SHADERS.length - missing) + "/" + PFSF_SHADERS.length + " present");

        if (missing > 0) {
            status = PhaseStatus.ERROR;
            details.add("ERROR: " + missing + " shader file(s) missing");
        }

        return new PhaseResult("Shader Validation", status,
            System.currentTimeMillis() - start, details);
    }

    private PhaseResult phaseNodeRegistry() {
        long start = System.currentTimeMillis();
        List<String> details = new ArrayList<>();
        PhaseStatus status = PhaseStatus.OK;

        if (!isClient) {
            details.add("Skipped (server) — NodeRegistry is client-only");
            return new PhaseResult("Node Registry", PhaseStatus.OK,
                System.currentTimeMillis() - start, details);
        }

        try {
            // NodeRegistry is client-only; access via reflection-free direct call
            // since we already know isClient == true
            int total = com.blockreality.fastdesign.client.node.NodeRegistry.registeredCount();
            details.add("Total registered nodes: " + total);

            Map<String, ? extends List<?>> categories =
                com.blockreality.fastdesign.client.node.NodeRegistry.byCategory();

            for (Map.Entry<String, ? extends List<?>> entry : categories.entrySet()) {
                details.add("  " + entry.getKey() + ": " + entry.getValue().size());
            }

            // Defensively check for null evaluate() implementations
            int nullEvalCount = 0;
            for (var nodeEntry : com.blockreality.fastdesign.client.node.NodeRegistry.allEntries()) {
                try {
                    var node = nodeEntry.createInstance();
                    if (node != null) {
                        // Try to detect AbstractMethodError by checking the method exists
                        node.getClass().getMethod("evaluate");
                    }
                } catch (AbstractMethodError e) {
                    nullEvalCount++;
                    details.add("  WARNING: " + nodeEntry.typeId() + " has null evaluate()");
                } catch (Exception ignored) {
                    // Other errors are not our concern here
                }
            }

            if (nullEvalCount > 0) {
                details.add("WARNING: " + nullEvalCount + " node(s) with unimplemented evaluate()");
                status = PhaseStatus.WARN;
            }

        } catch (Exception e) {
            details.add("Error scanning NodeRegistry: " + e.getMessage());
            status = PhaseStatus.ERROR;
        }

        return new PhaseResult("Node Registry", status,
            System.currentTimeMillis() - start, details);
    }
}
