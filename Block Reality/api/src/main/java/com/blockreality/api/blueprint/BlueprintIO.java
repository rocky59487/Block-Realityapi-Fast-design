package com.blockreality.api.blueprint;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 藍圖 GZIP 存取工具 — v3fix §2.3
 */
public class BlueprintIO {

    private static final Logger LOGGER = LogManager.getLogger("BR-Blueprint");

    public static Path getBlueprintDir() {
        Path dir = FMLPaths.CONFIGDIR.get()
            .resolve("blockreality")
            .resolve("blueprints");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("[Blueprint] Failed to create blueprint directory: {}", dir, e);
        }
        return dir;
    }

    private static String sanitizeName(String name) throws IllegalArgumentException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Blueprint name cannot be empty");
        }
        String sanitized = name
            .replaceAll("\\.\\.", "")
            .replaceAll("[/\\\\]", "")
            .replaceAll("[<>:\"|?*]", "")
            .replaceAll("[^a-zA-Z0-9_-]", "");
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Blueprint name contains only invalid characters");
        }
        return sanitized;
    }

    public static void save(ServerLevel level, BlockPos min, BlockPos max,
                             String name, String author) throws IOException {
        String sanitizedName = sanitizeName(name);
        Blueprint bp = captureBlueprint(level, min, max, sanitizedName, author);
        CompoundTag tag = BlueprintNBT.write(bp);
        Path file = getBlueprintDir().resolve(sanitizedName + Blueprint.FILE_EXTENSION);
        NbtIo.writeCompressed(tag, file.toFile());
        LOGGER.info("[Blueprint] Saved '{}' — {} blocks, size {}x{}x{}, file: {}",
            name, bp.getBlockCount(), bp.getSizeX(), bp.getSizeY(), bp.getSizeZ(), file);
    }

    /**
     * 從世界區域捕獲藍圖（簡便版：不含名稱和作者）
     *
     * @param level  伺服器世界
     * @param min    最小角
     * @param max    最大角
     * @return 捕獲的藍圖
     */
    public static Blueprint capture(ServerLevel level, BlockPos min, BlockPos max) {
        return captureBlueprint(level, min, max, "unnamed", "unknown");
    }

    /**
     * 從世界區域捕獲藍圖（完整版：含名稱和作者）
     *
     * @param level  伺服器世界
     * @param min    最小角
     * @param max    最大角
     * @param name   藍圖名稱
     * @param author 作者名稱
     * @return 捕獲的藍圖
     */
    public static Blueprint captureBlueprint(ServerLevel level, BlockPos min, BlockPos max,
                                              String name, String author) {
        Blueprint bp = new Blueprint();
        bp.setName(name);
        bp.setAuthor(author);
        bp.setTimestamp(System.currentTimeMillis());
        bp.setSizeX(max.getX() - min.getX() + 1);
        bp.setSizeY(max.getY() - min.getY() + 1);
        bp.setSizeZ(max.getZ() - min.getZ() + 1);

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    Blueprint.BlueprintBlock bb = new Blueprint.BlueprintBlock();
                    bb.setRelPos(x - min.getX(), y - min.getY(), z - min.getZ());
                    bb.setBlockState(state);

                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof RBlockEntity rbe) {
                        RMaterial mat = rbe.getMaterial();
                        bb.setRMaterialId(mat.getMaterialId());
                        bb.setStructureId(rbe.getStructureId());
                        bb.setAnchored(rbe.isAnchored());
                        bb.setStressLevel(rbe.getStressLevel());

                        if (mat instanceof DynamicMaterial dm) {
                            bb.setDynamic(true);
                            bb.setDynRcomp(dm.getRcomp());
                            bb.setDynRtens(dm.getRtens());
                            bb.setDynRshear(dm.getRshear());
                            bb.setDynDensity(dm.getDensity());
                        }
                    }

                    bp.getBlocks().add(bb);
                }
            }
        }

        return bp;
    }

    public static Blueprint load(String name) throws IOException {
        String sanitizedName = sanitizeName(name);
        Path file = getBlueprintDir().resolve(sanitizedName + Blueprint.FILE_EXTENSION);
        if (!Files.exists(file)) {
            throw new FileNotFoundException("Blueprint not found: " + sanitizedName +
                " (expected at: " + file + ")");
        }
        CompoundTag tag = NbtIo.readCompressed(file.toFile());
        Blueprint bp = BlueprintNBT.read(tag);
        LOGGER.info("[Blueprint] Loaded '{}' — {} blocks, version {}",
            bp.getName(), bp.getBlockCount(), bp.getVersion());
        return bp;
    }

    public static List<String> listBlueprints() throws IOException {
        Path dir = getBlueprintDir();
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(Blueprint.FILE_EXTENSION))
                .map(p -> {
                    String fn = p.getFileName().toString();
                    return fn.substring(0, fn.length() - Blueprint.FILE_EXTENSION.length());
                })
                .sorted()
                .toList();
        }
    }

    public static int paste(ServerLevel level, Blueprint bp, BlockPos origin) {
        int placed = 0;
        for (Blueprint.BlueprintBlock b : bp.getBlocks()) {
            BlockPos dst = origin.offset(b.getRelX(), b.getRelY(), b.getRelZ());
            BlockState state = b.getBlockState();
            if (state == null || state.isAir()) continue;
            level.setBlock(dst, state, 3);

            BlockEntity be = level.getBlockEntity(dst);
            if (be instanceof RBlockEntity rbe) {
                RMaterial mat = restoreMaterial(b);
                if (mat != null) {
                    rbe.setMaterial(mat);
                }
                rbe.setAnchored(b.isAnchored());
                rbe.setStressLevel(b.getStressLevel());
                rbe.setStructureId(b.getStructureId());
            }
            placed++;
        }
        LOGGER.info("[Blueprint] Pasted '{}' at {} — {} blocks placed",
            bp.getName(), origin, placed);
        return placed;
    }

    private static RMaterial restoreMaterial(Blueprint.BlueprintBlock b) {
        if (b.getRMaterialId() == null || b.getRMaterialId().isEmpty()) {
            return null;
        }
        if (b.isDynamic()) {
            return DynamicMaterial.ofCustom(
                b.getRMaterialId(),
                b.getDynRcomp(),
                b.getDynRtens(),
                b.getDynRshear(),
                b.getDynDensity()
            );
        }
        return DefaultMaterial.fromId(b.getRMaterialId());
    }

    public static boolean delete(String name) throws IOException {
        String sanitizedName = sanitizeName(name);
        Path file = getBlueprintDir().resolve(sanitizedName + Blueprint.FILE_EXTENSION);
        return Files.deleteIfExists(file);
    }
}
