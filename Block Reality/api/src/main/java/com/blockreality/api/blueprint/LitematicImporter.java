package com.blockreality.api.blueprint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Litematica (.litematic) 檔案匯入器。
 *
 * <p>將 Litematica mod 的結構檔案轉換為 Block Reality 的 {@link Blueprint} 格式，
 * 包括解析 NBT 元數據、調色盤（palette）、以及 packed long array 的方塊狀態。</p>
 *
 * @since 1.0.0
 */
public class LitematicImporter {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/LitematicImporter");

    /**
     * 匯入一個 .litematic 檔案並轉換為 {@link Blueprint}。
     *
     * @param filePath .litematic 檔案路徑
     * @return 匯入後的 Blueprint
     * @throws IOException 若檔案讀取失敗或 NBT 格式不合法
     */
    public static Blueprint importLitematic(Path filePath) throws IOException {
        if (!isLitematicFile(filePath)) {
            throw new IOException("Not a valid .litematic file: " + filePath);
        }

        CompoundTag root;
        try {
            root = NbtIo.readCompressed(filePath.toFile());
        } catch (Exception e) {
            throw new IOException("Failed to read litematic NBT: " + filePath, e);
        }

        Blueprint bp = new Blueprint();

        // ── 解析元數據 ──────────────────────────────────
        if (root.contains("Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag metadata = root.getCompound("Metadata");
            bp.setName(metadata.getString("Name"));
            bp.setAuthor(metadata.getString("Author"));
            bp.setTimestamp(metadata.getLong("TimeCreated"));

            if (metadata.contains("EnclosingSize", Tag.TAG_COMPOUND)) {
                CompoundTag enclosing = metadata.getCompound("EnclosingSize");
                bp.setSizeX(enclosing.getInt("x"));
                bp.setSizeY(enclosing.getInt("y"));
                bp.setSizeZ(enclosing.getInt("z"));
            }
        }

        // ── 解析各區域 ──────────────────────────────────
        if (!root.contains("Regions", Tag.TAG_COMPOUND)) {
            LOGGER.warn("Litematic file has no Regions tag: {}", filePath);
            return bp;
        }

        CompoundTag regions = root.getCompound("Regions");
        for (String regionName : regions.getAllKeys()) {
            if (!regions.contains(regionName, Tag.TAG_COMPOUND)) {
                continue;
            }
            try {
                parseRegion(regions.getCompound(regionName), regionName, bp);
            } catch (Exception e) {
                LOGGER.warn("Failed to parse region '{}' in {}: {}", regionName, filePath, e.getMessage());
            }
        }

        return bp;
    }

    /**
     * 檢查檔案是否為 .litematic 格式。
     *
     * @param filePath 檔案路徑
     * @return true 若副檔名為 .litematic
     */
    public static boolean isLitematicFile(Path filePath) {
        if (filePath == null) {
            return false;
        }
        String fileName = filePath.getFileName().toString();
        return fileName.toLowerCase(Locale.ROOT).endsWith(".litematic");
    }

    /**
     * 將調色盤中的一個條目轉換為 Minecraft {@link BlockState}。
     *
     * <p>若方塊不存在於註冊表中，會記錄警告並回傳空氣。</p>
     *
     * @param entry 調色盤 CompoundTag（含 "Name" 及可選 "Properties"）
     * @return 對應的 BlockState，未知方塊回傳空氣
     */
    public static BlockState parsePaletteEntry(CompoundTag entry) {
        String name = entry.getString("Name");
        if (name.isEmpty()) {
            return Blocks.AIR.defaultBlockState();
        }

        ResourceLocation rl = new ResourceLocation(name);
        Block block = BuiltInRegistries.BLOCK.get(rl);

        // 若查不到方塊（回傳空氣代表未註冊），且名稱不是空氣本身，記錄警告
        if (block == Blocks.AIR && !"minecraft:air".equals(name)) {
            LOGGER.warn("Unknown block in litematic palette: '{}', substituting air", name);
            return Blocks.AIR.defaultBlockState();
        }

        BlockState state = block.defaultBlockState();

        // 套用方塊屬性（如 facing=north, half=top 等）
        if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
            CompoundTag props = entry.getCompound("Properties");
            for (String key : props.getAllKeys()) {
                state = applyProperty(state, key, props.getString(key));
            }
        }

        return state;
    }

    /**
     * 從 packed long array 解碼調色盤索引。
     *
     * <p>Litematica 使用可變位寬（variable bit width）將調色盤索引打包進 long 陣列。
     * 位寬 = max(2, ceil(log2(paletteSize)))，每個 long 可存 floor(64 / bitsPerEntry) 個條目。</p>
     *
     * @param packed     打包的 long 陣列
     * @param volume     區域體積（方塊總數）
     * @param paletteSize 調色盤大小
     * @return 各位置的調色盤索引陣列
     */
    public static int[] unpackBlockStates(long[] packed, int volume, int paletteSize) {
        int bitsPerEntry = Math.max(2, ceilLog2(paletteSize));
        int entriesPerLong = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1;

        int[] result = new int[volume];
        for (int i = 0; i < volume; i++) {
            int longIndex = i / entriesPerLong;
            int bitOffset = (i % entriesPerLong) * bitsPerEntry;

            if (longIndex >= packed.length) {
                // 超出陣列範圍，剩餘視為空氣（索引 0）
                break;
            }

            int paletteIndex = (int) ((packed[longIndex] >>> bitOffset) & mask);
            if (paletteIndex >= paletteSize) {
                // 索引超出調色盤範圍，視為空氣
                LOGGER.warn("Palette index {} out of range (palette size {}), substituting 0", paletteIndex, paletteSize);
                paletteIndex = 0;
            }
            result[i] = paletteIndex;
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  內部方法
    // ═══════════════════════════════════════════════════════

    /**
     * 解析單一區域並將方塊加入 Blueprint。
     */
    private static void parseRegion(CompoundTag region, String regionName, Blueprint bp) {
        // 區域位移
        int offX = 0, offY = 0, offZ = 0;
        if (region.contains("Position", Tag.TAG_COMPOUND)) {
            CompoundTag pos = region.getCompound("Position");
            offX = pos.getInt("x");
            offY = pos.getInt("y");
            offZ = pos.getInt("z");
        }

        // 區域尺寸（可能為負值）
        int sX = 1, sY = 1, sZ = 1;
        if (region.contains("Size", Tag.TAG_COMPOUND)) {
            CompoundTag size = region.getCompound("Size");
            sX = size.getInt("x");
            sY = size.getInt("y");
            sZ = size.getInt("z");
        }

        // 負尺寸的處理：迭代用絕對值，偏移量做調整
        int absSX = Math.abs(sX);
        int absSY = Math.abs(sY);
        int absSZ = Math.abs(sZ);
        int adjustX = sX < 0 ? sX + 1 : 0;
        int adjustY = sY < 0 ? sY + 1 : 0;
        int adjustZ = sZ < 0 ? sZ + 1 : 0;

        // 解析調色盤
        if (!region.contains("BlockStatePalette", Tag.TAG_LIST)) {
            LOGGER.warn("Region '{}' has no BlockStatePalette", regionName);
            return;
        }
        ListTag paletteTag = region.getList("BlockStatePalette", Tag.TAG_COMPOUND);
        int paletteSize = paletteTag.size();
        BlockState[] palette = new BlockState[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = parsePaletteEntry(paletteTag.getCompound(i));
        }

        // 解包方塊狀態
        if (!region.contains("BlockStates", Tag.TAG_LONG_ARRAY)) {
            LOGGER.warn("Region '{}' has no BlockStates long array", regionName);
            return;
        }
        long[] packed = region.getLongArray("BlockStates");
        int volume = absSX * absSY * absSZ;
        int[] indices = unpackBlockStates(packed, volume, paletteSize);

        // 將非空氣方塊加入 Blueprint
        for (int y = 0; y < absSY; y++) {
            for (int z = 0; z < absSZ; z++) {
                for (int x = 0; x < absSX; x++) {
                    int idx = y * absSX * absSZ + z * absSX + x;
                    if (idx >= indices.length) {
                        continue;
                    }

                    int paletteIndex = indices[idx];
                    if (paletteIndex < 0 || paletteIndex >= paletteSize) {
                        continue;
                    }

                    BlockState state = palette[paletteIndex];
                    if (state.isAir()) {
                        continue;
                    }

                    Blueprint.BlueprintBlock bb = new Blueprint.BlueprintBlock();
                    bb.setRelPos(
                            offX + adjustX + x,
                            offY + adjustY + y,
                            offZ + adjustZ + z
                    );
                    bb.setBlockState(state);
                    bp.getBlocks().add(bb);
                }
            }
        }

        LOGGER.debug("Region '{}': {}x{}x{} ({} non-air blocks added)",
                regionName, absSX, absSY, absSZ,
                bp.getBlockCount());
    }

    /**
     * 將方塊屬性字串值套用至 BlockState。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, String propertyName, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
        if (property == null) {
            LOGGER.warn("Unknown block property '{}' for block {}", propertyName,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            return state;
        }

        Optional<?> parsed = property.getValue(value);
        if (parsed.isPresent()) {
            return state.setValue((Property) property, (Comparable) parsed.get());
        } else {
            LOGGER.warn("Invalid value '{}' for property '{}' of block {}", value, propertyName,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            return state;
        }
    }

    /**
     * 計算 ceil(log2(n))，n <= 0 時回傳 0。
     */
    private static int ceilLog2(int n) {
        if (n <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(n - 1);
    }
}
