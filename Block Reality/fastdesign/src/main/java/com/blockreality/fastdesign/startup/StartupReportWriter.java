package com.blockreality.fastdesign.startup;

import com.blockreality.fastdesign.startup.StartupPhase.EnvironmentInfo;
import com.blockreality.fastdesign.startup.StartupPhase.PhaseResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes startup scan results to a JSON report file.
 * Retains only the latest 5 report files; deletes older ones.
 */
public final class StartupReportWriter {

    private static final Logger LOGGER = LogManager.getLogger("BR-StartupReport");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_REPORTS = 5;
    private static final String MOD_VERSION = "1.0.0";
    private static final DateTimeFormatter TIMESTAMP_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private StartupReportWriter() {}

    /**
     * Write the scan report JSON and rotate old files.
     * Called from the scan thread after all phases complete.
     */
    public static void writeReport(StartupScanPipeline pipeline) {
        try {
            Path logsDir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(logsDir);

            // Build JSON
            JsonObject root = new JsonObject();
            root.addProperty("version", MOD_VERSION);

            Instant now = Instant.now();
            root.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(now));
            root.addProperty("durationMs", pipeline.getTotalDurationMs());
            root.addProperty("warnings", pipeline.getTotalWarnings());
            root.addProperty("errors", pipeline.getTotalErrors());

            // Phases array
            JsonArray phasesArray = new JsonArray();
            for (PhaseResult phase : pipeline.getCompletedPhases()) {
                JsonObject p = new JsonObject();
                p.addProperty("name", phase.name());
                p.addProperty("status", phase.status().name());
                p.addProperty("durationMs", phase.durationMs());

                JsonArray detailsArray = new JsonArray();
                for (String detail : phase.details()) {
                    detailsArray.add(detail);
                }
                p.add("details", detailsArray);

                phasesArray.add(p);
            }
            root.add("phases", phasesArray);

            // Environment
            EnvironmentInfo env = pipeline.getEnvironmentInfo();
            if (env != null) {
                JsonObject envObj = new JsonObject();
                envObj.addProperty("jvm", env.jvmVersion());
                envObj.addProperty("heapMb", env.heapMb());
                envObj.addProperty("os", env.os());
                envObj.addProperty("forgeVersion", env.forgeVersion());
                root.add("environment", envObj);
            }

            // Write file
            String filename = "blockreality-startup-" + TIMESTAMP_FMT.format(now) + ".json";
            Path reportFile = logsDir.resolve(filename);
            Files.writeString(reportFile, GSON.toJson(root));
            LOGGER.info("[StartupReport] Written to {}", reportFile);

            // Rotate: keep only latest MAX_REPORTS
            rotateReports(logsDir);

        } catch (Exception e) {
            LOGGER.error("[StartupReport] Failed to write report", e);
        }
    }

    private static void rotateReports(Path logsDir) {
        try {
            List<Path> reports = new ArrayList<>();
            try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(logsDir, "blockreality-startup-*.json")) {
                for (Path p : stream) {
                    reports.add(p);
                }
            }

            if (reports.size() <= MAX_REPORTS) return;

            // Sort by filename (timestamp-based, so lexicographic order works)
            Collections.sort(reports);

            // Delete oldest files, keeping the newest MAX_REPORTS
            int toDelete = reports.size() - MAX_REPORTS;
            for (int i = 0; i < toDelete; i++) {
                try {
                    Files.deleteIfExists(reports.get(i));
                    LOGGER.debug("[StartupReport] Deleted old report: {}", reports.get(i).getFileName());
                } catch (IOException e) {
                    LOGGER.warn("[StartupReport] Could not delete {}: {}", reports.get(i), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[StartupReport] Failed to rotate reports", e);
        }
    }
}
