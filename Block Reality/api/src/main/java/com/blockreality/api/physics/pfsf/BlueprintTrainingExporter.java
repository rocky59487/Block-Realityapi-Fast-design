package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;

/**
 * Export in-game structures to binary format for BIFROST ML training.
 *
 * <p>Output format (.brbin) — matches brml PhysicsDataset._load_binary():</p>
 * <pre>
 *   header:       Lx(i32), Ly(i32), Lz(i32)
 *   source:       float32[N]       — self-weight (ρ·g·V)
 *   conductivity: float32[6N]      — 6-direction (SoA)
 *   type:         uint8[N]         — 0=air, 1=solid, 2=anchor
 *   rcomp:        float32[N]       — compression strength (MPa)
 *   phi_steady:   float32[N]       — PFSF converged φ (if available, else zeros)
 * </pre>
 *
 * <p>Usage in-game: {@code /fd export-training <name>} or programmatic via
 * {@link #exportRegion(ServerLevel, BlockPos, BlockPos, Path, Function, Function)}.</p>
 *
 * <p>Feed exported .brbin files to brml auto-train pipeline:
 * {@code brml-train-surrogate --data-dir ./exports/}</p>
 *
 * @since v1.0 (BIFROST Sprint 1)
 */
public final class BlueprintTrainingExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("BIFROST-Export");
    private static final float GRAVITY = 9.81f;

    private BlueprintTrainingExporter() {}

    /**
     * Export a world region to .brbin training data.
     *
     * @param level          Server world
     * @param min            AABB minimum corner
     * @param max            AABB maximum corner
     * @param outputPath     Output .brbin file path
     * @param materialLookup BlockPos → RMaterial (from BlockTypeRegistry)
     * @param anchorLookup   BlockPos → isAnchor
     * @return Number of solid voxels exported, or -1 on failure
     */
    public static int exportRegion(
            ServerLevel level,
            BlockPos min, BlockPos max,
            Path outputPath,
            Function<BlockPos, RMaterial> materialLookup,
            Function<BlockPos, Boolean> anchorLookup
    ) {
        int lx = max.getX() - min.getX() + 1;
        int ly = max.getY() - min.getY() + 1;
        int lz = max.getZ() - min.getZ() + 1;
        int N = lx * ly * lz;

        if (N <= 0 || N > 50_000) {
            LOGGER.error("[Export] Region too large: {}×{}×{} = {} (max 50000)", lx, ly, lz, N);
            return -1;
        }

        // Allocate arrays
        float[] source       = new float[N];
        float[] conductivity = new float[6 * N];
        byte[]  type         = new byte[N];
        float[] rcomp        = new float[N];
        float[] phi          = new float[N]; // zeros — FEM will compute ground truth

        int solidCount = 0;

        for (int x = 0; x < lx; x++) {
            for (int y = 0; y < ly; y++) {
                for (int z = 0; z < lz; z++) {
                    int flatIdx = x + lx * (y + ly * z);
                    BlockPos worldPos = min.offset(x, y, z);

                    BlockState state = level.getBlockState(worldPos);
                    if (state.isAir()) {
                        type[flatIdx] = 0; // AIR
                        continue;
                    }

                    RMaterial mat = materialLookup.apply(worldPos);
                    if (mat == null) continue;

                    boolean isAnchor = anchorLookup != null && anchorLookup.apply(worldPos);
                    type[flatIdx] = isAnchor ? (byte) 2 : (byte) 1;

                    float density = (float) mat.getDensity();
                    source[flatIdx] = density * GRAVITY * 1.0f; // ρ·g·V

                    rcomp[flatIdx] = (float) mat.getRcomp();

                    // Isotropic conductivity from Young's modulus
                    float condVal = (float)(mat.getYoungsModulusPa() / 1e6); // scale to MPa
                    for (int d = 0; d < 6; d++) {
                        conductivity[d * N + flatIdx] = condVal;
                    }

                    solidCount++;
                }
            }
        }

        if (solidCount == 0) {
            LOGGER.warn("[Export] No solid blocks in region");
            return 0;
        }

        // Write binary file
        try {
            Files.createDirectories(outputPath.getParent());

            // Write to .tmp then rename (atomic)
            Path tmpPath = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");

            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmpPath)))) {

                // Header: Lx, Ly, Lz (little-endian i32)
                writeIntLE(out, lx);
                writeIntLE(out, ly);
                writeIntLE(out, lz);

                // source: float32[N]
                writeFloatArrayLE(out, source);

                // conductivity: float32[6N]
                writeFloatArrayLE(out, conductivity);

                // type: uint8[N]
                out.write(type);

                // rcomp: float32[N]
                writeFloatArrayLE(out, rcomp);

                // phi_steady: float32[N] (zeros — to be filled by FEM)
                writeFloatArrayLE(out, phi);
            }

            // Atomic rename
            Files.move(tmpPath, outputPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            LOGGER.info("[Export] Exported {}×{}×{} ({} solid blocks) → {}",
                    lx, ly, lz, solidCount, outputPath);
            return solidCount;

        } catch (IOException e) {
            LOGGER.error("[Export] Failed to write {}: {}", outputPath, e.getMessage());
            return -1;
        }
    }

    /**
     * Export a StructureIsland to .brbin.
     */
    public static int exportIsland(
            ServerLevel level,
            StructureIslandRegistry.StructureIsland island,
            Path outputDir,
            Function<BlockPos, RMaterial> materialLookup,
            Function<BlockPos, Boolean> anchorLookup
    ) {
        Set<BlockPos> members = island.getMembers();
        if (members.isEmpty()) return 0;

        // Compute AABB
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : members) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }

        Path filePath = outputDir.resolve("island_" + island.getId() + ".brbin");
        return exportRegion(level, new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ),
                filePath, materialLookup, anchorLookup);
    }

    // ── Little-endian writers ──

    private static void writeIntLE(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void writeFloatArrayLE(DataOutputStream out, float[] arr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : arr) buf.putFloat(v);
        out.write(buf.array());
    }
}
