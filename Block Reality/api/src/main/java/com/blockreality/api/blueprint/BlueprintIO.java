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
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 藍圖 GZIP 存取工具 — v3fix §2.3
 */
public class BlueprintIO {

    private static final Logger LOGGER = LogManager.getLogger("BR-Blueprint");

    /**
     * ★ Audit fix C-004: per-file write locks preventing concurrent writes to the same blueprint.
     *
     * <p>Atomic write (C-003) protects against crash corruption, but two threads writing
     * the same file simultaneously can still race on the temp file. This ConcurrentHashMap
     * of per-path ReentrantLocks ensures mutual exclusion per file path.
     *
     * <p>Locks are created lazily and never removed (bounded by number of unique blueprint names,
     * typically < 100). ReentrantLock allows the same thread to re-enter (e.g., importAndSaveLitematic).
     */
    private static final ConcurrentHashMap<String, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    private static ReentrantLock lockForPath(Path path) {
        return FILE_LOCKS.computeIfAbsent(path.toAbsolutePath().toString(), k -> new ReentrantLock());
    }

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
        // ★ Security fix (P0-005): enforce max length to prevent filesystem abuse
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized;
    }

    /** Verify that a resolved path is still inside the blueprint directory (symlink safety). */
    private static void assertWithinBlueprintDir(Path resolved) {
        try {
            Path base = getBlueprintDir().toRealPath();
            Path target = resolved.normalize();
            if (!target.startsWith(base)) {
                throw new IllegalArgumentException("Path escape detected: " + resolved);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot verify blueprint path safety: " + e.getMessage());
        }
    }

    public static void save(ServerLevel level, BlockPos min, BlockPos max,
                             String name, String author) throws IOException {
        String sanitizedName = sanitizeName(name);
        Blueprint bp = captureBlueprint(level, min, max, sanitizedName, author);
        CompoundTag tag = BlueprintNBT.write(bp);
        Path file = getBlueprintDir().resolve(sanitizedName + Blueprint.FILE_EXTENSION);
        assertWithinBlueprintDir(file); // ★ Security fix (P0-005): symlink escape guard
        // ★ Audit fix C-003: atomic write — write to temp then rename, prevents corruption on crash
        atomicWriteCompressed(tag, file);
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
        assertWithinBlueprintDir(file); // ★ Security fix (P0-005): symlink escape guard
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

    /**
     * 每 tick 最大放置方塊數 — 防止大藍圖凍結伺服器。
     * 200 blocks/tick ≈ 4000 blocks/秒，足夠快且不會超出 tick 預算。
     */
    private static final int BLOCKS_PER_TICK = 200;

    /**
     * 同步放置（小型藍圖 ≤ BLOCKS_PER_TICK 時直接完成）。
     * 大型藍圖改用 {@link #pasteProgressive} 以漸進式放置。
     */
    public static int paste(ServerLevel level, Blueprint bp, BlockPos origin) {
        List<Blueprint.BlueprintBlock> blocks = bp.getBlocks();

        // 小型藍圖：直接同步放置
        if (blocks.size() <= BLOCKS_PER_TICK) {
            return pasteBatch(level, blocks, origin, 0, blocks.size());
        }

        // 大型藍圖：漸進式放置，分散到多個 tick
        pasteProgressive(level, bp, origin);
        return blocks.size(); // 回傳預期總數
    }

    /**
     * 漸進式藍圖放置 — 每 tick 放置 BLOCKS_PER_TICK 個方塊，
     * 透過 ServerLevel.getServer().tell() 排程後續批次，
     * 避免長時間阻塞伺服器主執行緒。
     */
    public static void pasteProgressive(ServerLevel level, Blueprint bp, BlockPos origin) {
        List<Blueprint.BlueprintBlock> blocks = bp.getBlocks();
        final int total = blocks.size();
        LOGGER.info("[Blueprint] Progressive paste '{}' at {} — {} blocks, ~{} ticks",
            bp.getName(), origin, total, (total + BLOCKS_PER_TICK - 1) / BLOCKS_PER_TICK);

        // 排程第一批
        schedulePasteBatch(level, blocks, origin, 0, total, bp.getName());
    }

    private static void schedulePasteBatch(ServerLevel level,
                                            List<Blueprint.BlueprintBlock> blocks,
                                            BlockPos origin, int fromIndex, int total,
                                            String bpName) {
        level.getServer().tell(new net.minecraft.server.TickTask(
            level.getServer().getTickCount() + 1, () -> {
                int end = Math.min(fromIndex + BLOCKS_PER_TICK, total);
                int placed = pasteBatch(level, blocks, origin, fromIndex, end);

                if (end < total) {
                    // 還有剩餘 — 排程下一批
                    schedulePasteBatch(level, blocks, origin, end, total, bpName);
                } else {
                    LOGGER.info("[Blueprint] Progressive paste '{}' complete — {} blocks total",
                        bpName, total);
                }
            }
        ));
    }

    /**
     * 放置 [fromIndex, toIndex) 範圍的方塊。
     */
    private static int pasteBatch(ServerLevel level,
                                   List<Blueprint.BlueprintBlock> blocks,
                                   BlockPos origin, int fromIndex, int toIndex) {
        int placed = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            Blueprint.BlueprintBlock b = blocks.get(i);
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

    /**
     * 原子寫入壓縮 NBT — 先寫入 .tmp 暫存檔再重命名。
     * 若寫入途中崩潰，原檔案不受影響（暫存檔會殘留，下次正常覆蓋）。
     *
     * @param tag  要寫入的 NBT 複合標籤
     * @param target  最終目標路徑
     * @throws IOException 如果寫入或重命名失敗
     */
    private static void atomicWriteCompressed(CompoundTag tag, Path target) throws IOException {
        // ★ Audit fix C-004: per-file lock prevents concurrent writes to the same blueprint
        ReentrantLock lock = lockForPath(target);
        lock.lock();
        try {
            Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            try {
                NbtIo.writeCompressed(tag, temp.toFile());
                Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 清理暫存檔（best-effort）
                try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    public static boolean delete(String name) throws IOException {
        String sanitizedName = sanitizeName(name);
        Path file = getBlueprintDir().resolve(sanitizedName + Blueprint.FILE_EXTENSION);
        return Files.deleteIfExists(file);
    }

    // ═══════════════════════════════════════════════════════════════
    //  .litematic 匯入支援（Litematica mod 格式）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 從 .litematic 檔案匯入藍圖。
     * 支援 Litematica mod (Masa) 的 NBT 壓縮格式。
     *
     * @param filePath .litematic 檔案路徑
     * @return 轉換後的 Blueprint
     * @throws IOException 如果檔案讀取失敗
     */
    public static Blueprint importLitematic(Path filePath) throws IOException {
        Blueprint bp = LitematicImporter.importLitematic(filePath);
        LOGGER.info("[Blueprint] 匯入 litematic '{}' — {} 方塊, 尺寸 {}x{}x{}",
            bp.getName(), bp.getBlockCount(), bp.getSizeX(), bp.getSizeY(), bp.getSizeZ());
        return bp;
    }

    /**
     * 從 .litematic 匯入並直接儲存為 .brblp 格式。
     *
     * @param litematicPath .litematic 來源路徑
     * @param name          儲存名稱
     * @throws IOException 如果讀寫失敗
     */
    public static void importAndSaveLitematic(Path litematicPath, String name) throws IOException {
        Blueprint bp = importLitematic(litematicPath);
        if (name != null && !name.isEmpty()) {
            bp.setName(sanitizeName(name));
        }
        CompoundTag tag = BlueprintNBT.write(bp);
        Path file = getBlueprintDir().resolve(bp.getName() + Blueprint.FILE_EXTENSION);
        // ★ Audit fix C-003: atomic write for litematic import
        atomicWriteCompressed(tag, file);
        LOGGER.info("[Blueprint] litematic 轉存為 .brblp: {}", file);
    }

    /**
     * 列出匯入目錄中的所有 .litematic 檔案。
     */
    public static List<String> listLitematicFiles() throws IOException {
        Path dir = getBlueprintDir();
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".litematic"))
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        }
    }
}
