package com.blockreality.api.physics.pfsf;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.material.BlockType;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.4 M3d — in-game fixture capture for the schema-v1 PFSF fixture JSON.
 *
 * <p>Scans an {@link StructureIsland}'s AABB, maps every {@link RBlockEntity}
 * to a compacted material-id field, and emits the fixture alongside the
 * material registry. The resulting file is compatible with
 * {@code pfsf_cli --fixture} and {@code GoldenParityTest}.</p>
 *
 * <p>Only the required subset of the schema is written; optional fields
 * ({@code fluid_pressure}, {@code curing}, {@code expected_stress}) are
 * omitted — the dump captures the fixture's boundary conditions, not a
 * specific solver trajectory. Parity runs reconstruct those themselves.</p>
 */
public final class PFSFFixtureWriter {

    private PFSFFixtureWriter() {}

    /** Emits a single island to {@code outDir/island_<id>.json}. */
    public static Path dump(ServerLevel level, StructureIsland island, Path outDir)
            throws IOException {
        Files.createDirectories(outDir);
        String fileName = String.format("island_%d.json", island.getId());
        Path outPath = outDir.resolve(fileName);
        String json = buildJson(level, island);
        Files.writeString(outPath, json, StandardCharsets.UTF_8);
        return outPath;
    }

    static String buildJson(ServerLevel level, StructureIsland island) {
        BlockPos mn = island.getMinCorner();
        BlockPos mx = island.getMaxCorner();
        int lx = mx.getX() - mn.getX() + 1;
        int ly = mx.getY() - mn.getY() + 1;
        int lz = mx.getZ() - mn.getZ() + 1;
        int n = lx * ly * lz;

        int[] voxels = new int[n];
        List<int[]> anchors = new ArrayList<>();
        Map<String, Integer> matIdByKey = new HashMap<>();
        List<RMaterial> matOrder = new ArrayList<>();
        List<Boolean> matIsAnchor = new ArrayList<>();

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int zi = 0; zi < lz; zi++) {
            for (int yi = 0; yi < ly; yi++) {
                for (int xi = 0; xi < lx; xi++) {
                    cur.set(mn.getX() + xi, mn.getY() + yi, mn.getZ() + zi);
                    BlockEntity be = level.getBlockEntity(cur);
                    int flat = (zi * ly + yi) * lx + xi;
                    if (!(be instanceof RBlockEntity rbe)) {
                        voxels[flat] = 0;
                        continue;
                    }
                    RMaterial mat = rbe.getMaterial();
                    boolean anchor = rbe.getBlockType() == BlockType.ANCHOR_PILE
                            || mat.isIndestructible();
                    String key = mat.getMaterialId() + "#" + (anchor ? "a" : "s");
                    Integer id = matIdByKey.get(key);
                    if (id == null) {
                        id = matOrder.size() + 1;
                        matIdByKey.put(key, id);
                        matOrder.add(mat);
                        matIsAnchor.add(anchor);
                    }
                    voxels[flat] = id;
                    if (anchor) {
                        anchors.add(new int[]{xi, yi, zi});
                    }
                }
            }
        }

        ByteBuffer bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : voxels) bb.putInt(v);
        String voxelsB64 = Base64.getEncoder().encodeToString(bb.array());

        StringBuilder sb = new StringBuilder(256 + voxelsB64.length());
        sb.append("{\n");
        sb.append("  \"schema_version\": 1,\n");
        sb.append("  \"fixture_id\": \"island_").append(island.getId()).append("\",\n");
        sb.append("  \"description\": \"in-game /br pfsf dump capture\",\n");
        sb.append("  \"recorded_at\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"git_sha\": \"\",\n");
        sb.append("  \"dims\": { \"lx\": ").append(lx)
          .append(", \"ly\": ").append(ly)
          .append(", \"lz\": ").append(lz).append(" },\n");

        sb.append("  \"anchors\": [");
        for (int i = 0; i < anchors.size(); i++) {
            int[] a = anchors.get(i);
            if (i > 0) sb.append(", ");
            sb.append('[').append(a[0]).append(',').append(a[1]).append(',').append(a[2]).append(']');
        }
        sb.append("],\n");

        sb.append("  \"materials\": {\n");
        sb.append("    \"voxels\": \"").append(voxelsB64).append("\",\n");
        sb.append("    \"registry\": [");
        for (int i = 0; i < matOrder.size(); i++) {
            if (i > 0) sb.append(", ");
            RMaterial m = matOrder.get(i);
            boolean isAnchor = matIsAnchor.get(i);
            double rcomp = m.getRcomp();
            double rtens = m.getRtens();
            double density = m.getDensity();
            double youngsGpa = Math.max(m.getYoungsModulusPa() / 1e9, 0.1);
            sb.append("{\"id\":").append(i + 1)
              .append(",\"name\":\"").append(jsonEscape(m.getMaterialId())).append('"')
              .append(",\"rcomp\":").append(fmt(rcomp))
              .append(",\"rtens\":").append(fmt(rtens))
              .append(",\"density\":").append(fmt(density))
              .append(",\"youngs_gpa\":").append(fmt(youngsGpa))
              .append(",\"poisson\":0.2")
              .append(",\"gc\":100.0")
              .append(",\"is_anchor\":").append(isAnchor)
              .append('}');
        }
        sb.append("]\n  },\n");

        sb.append("  \"wind\": [0.0, 0.0, 0.0],\n");
        sb.append("  \"ticks\": 1000\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.0";
        return Double.toString(v);
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }
}
