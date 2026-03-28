package com.blockreality.api.client.render.optimization;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lithium-style palette compression for block state storage.
 * <p>
 * Replaces {@code HashMap<Property, Value>} with packed integer encoding.
 * Properties are enum-indexed, values are ordinal-packed into bitfields.
 * A typical block needs only 16 bits.
 * <p>
 * Design follows the FerriteCore/Lithium approach: unique block states are
 * mapped to compact palette indices, which are then packed into long arrays
 * using the same algorithm as Minecraft's internal palette format.
 */
@OnlyIn(Dist.CLIENT)
public final class BRPaletteCompressor {

    private BRPaletteCompressor() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-PaletteCompressor");

    // Interning cache (FerriteCore style)
    private static final ConcurrentHashMap<BlockState, BlockState> INTERN_CACHE = new ConcurrentHashMap<>();

    // Global stats
    private static final AtomicLong totalCompressedBytes = new AtomicLong(0);
    private static final AtomicLong totalRawBytes = new AtomicLong(0);
    private static boolean initialized = false;

    // ========================================================================
    // Palette — maps unique block states to compact indices
    // ========================================================================

    /**
     * A palette that maps unique {@link BlockState} instances to compact integer indices.
     */
    public static final class Palette {
        private final List<BlockState> entries;
        private final Map<BlockState, Integer> reverseMap;
        private int bitsPerEntry;

        private Palette() {
            this.entries = new ArrayList<>();
            this.reverseMap = new LinkedHashMap<>();
            this.bitsPerEntry = 1;
        }

        /**
         * @return the palette index for the given state, or -1 if not present
         */
        public int getIndex(BlockState state) {
            Integer idx = reverseMap.get(state);
            return idx != null ? idx : -1;
        }

        /**
         * @return the block state at the given palette index
         * @throws IndexOutOfBoundsException if index is out of range
         */
        public BlockState getState(int index) {
            return entries.get(index);
        }

        /** @return the number of unique states in this palette */
        public int size() {
            return entries.size();
        }

        /** @return the number of bits needed per palette entry */
        public int getBitsPerEntry() {
            return bitsPerEntry;
        }

        /**
         * Add a state to the palette if not already present.
         *
         * @return the index of the state in the palette
         */
        int addState(BlockState state) {
            Integer existing = reverseMap.get(state);
            if (existing != null) return existing;
            int idx = entries.size();
            entries.add(state);
            reverseMap.put(state, idx);
            bitsPerEntry = Math.max(1, ceilLog2(entries.size()));
            return idx;
        }
    }

    // ========================================================================
    // PackedSection — stores sX * sY * sZ block states in packed long array
    // ========================================================================

    /**
     * A section of block states stored as palette indices packed into a long array.
     */
    public static final class PackedSection {
        private Palette palette;
        private long[] data;
        private final int sizeX, sizeY, sizeZ;
        private final int totalBlocks;

        private PackedSection(Palette palette, long[] data, int sizeX, int sizeY, int sizeZ) {
            this.palette = palette;
            this.data = data;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.totalBlocks = sizeX * sizeY * sizeZ;
        }

        /**
         * Unpack a single block state at the given coordinates.
         */
        public BlockState get(int x, int y, int z) {
            int index = y * sizeX * sizeZ + z * sizeX + x;
            int bitsPerEntry = palette.getBitsPerEntry();
            int entriesPerLong = 64 / bitsPerEntry;
            long mask = (1L << bitsPerEntry) - 1;

            int longIndex = index / entriesPerLong;
            int bitOffset = (index % entriesPerLong) * bitsPerEntry;

            int paletteIdx = (int) ((data[longIndex] >>> bitOffset) & mask);
            return palette.getState(paletteIdx);
        }

        /**
         * Set a single block state at the given coordinates.
         * If the state is new and the palette grows, the data array is repacked.
         */
        public void set(int x, int y, int z, BlockState state) {
            int oldBits = palette.getBitsPerEntry();
            int paletteIdx = palette.addState(state);
            int newBits = palette.getBitsPerEntry();

            // If bits per entry changed, we need to repack the entire array
            if (newBits != oldBits) {
                repack(oldBits, newBits);
            }

            int index = y * sizeX * sizeZ + z * sizeX + x;
            int bitsPerEntry = palette.getBitsPerEntry();
            int entriesPerLong = 64 / bitsPerEntry;
            long mask = (1L << bitsPerEntry) - 1;

            int longIndex = index / entriesPerLong;
            int bitOffset = (index % entriesPerLong) * bitsPerEntry;

            // Clear existing bits and set new value
            data[longIndex] &= ~(mask << bitOffset);
            data[longIndex] |= ((long) paletteIdx & mask) << bitOffset;
        }

        /**
         * @return memory used by the packed representation in bytes
         */
        public long getMemoryUsageBytes() {
            // long array + palette overhead (rough estimate: list + map entries)
            long dataBytes = (long) data.length * 8;
            long paletteBytes = (long) palette.size() * 48; // approx per entry
            return dataBytes + paletteBytes;
        }

        /**
         * @return memory that would be used without compression (one object ref per block)
         */
        public long getRawMemoryUsageBytes() {
            // Without compression: one reference (8 bytes on 64-bit) per block
            return (long) totalBlocks * 8;
        }

        /**
         * @return compression ratio (compressed / raw), lower is better
         */
        public float getCompressionRatio() {
            long raw = getRawMemoryUsageBytes();
            return raw > 0 ? (float) getMemoryUsageBytes() / raw : 1.0f;
        }

        private void repack(int oldBits, int newBits) {
            int oldEntriesPerLong = 64 / oldBits;
            long oldMask = (1L << oldBits) - 1;

            int newEntriesPerLong = 64 / newBits;
            long newMask = (1L << newBits) - 1;
            int newDataLength = (totalBlocks + newEntriesPerLong - 1) / newEntriesPerLong;
            long[] newData = new long[newDataLength];

            for (int i = 0; i < totalBlocks; i++) {
                int oldLongIdx = i / oldEntriesPerLong;
                int oldBitOff = (i % oldEntriesPerLong) * oldBits;
                int value = (int) ((data[oldLongIdx] >>> oldBitOff) & oldMask);

                int newLongIdx = i / newEntriesPerLong;
                int newBitOff = (i % newEntriesPerLong) * newBits;
                newData[newLongIdx] |= ((long) value & newMask) << newBitOff;
            }

            this.data = newData;
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /** Initialize the compressor. */
    public static void init() {
        if (initialized) {
            LOG.warn("BRPaletteCompressor already initialized");
            return;
        }
        INTERN_CACHE.clear();
        totalCompressedBytes.set(0);
        totalRawBytes.set(0);
        initialized = true;
        LOG.info("BRPaletteCompressor initialized");
    }

    /** Release resources and clear caches. */
    public static void cleanup() {
        INTERN_CACHE.clear();
        totalCompressedBytes.set(0);
        totalRawBytes.set(0);
        initialized = false;
        LOG.info("BRPaletteCompressor cleaned up");
    }

    /** @return true if {@link #init()} has been called */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Build a palette from an array of block states.
     *
     * @param states array of (possibly duplicate) block states
     * @return a new Palette containing only the unique states
     */
    public static Palette createPalette(BlockState[] states) {
        Palette palette = new Palette();
        for (BlockState state : states) {
            palette.addState(state);
        }
        return palette;
    }

    /**
     * Pack a 3D array of block states into a compressed section.
     *
     * @param blocks the block state array indexed [y][z][x]
     * @param sX     size along X
     * @param sY     size along Y
     * @param sZ     size along Z
     * @return a new PackedSection
     */
    public static PackedSection packSection(BlockState[][][] blocks, int sX, int sY, int sZ) {
        // First pass: build palette
        Palette palette = new Palette();
        for (int y = 0; y < sY; y++) {
            for (int z = 0; z < sZ; z++) {
                for (int x = 0; x < sX; x++) {
                    palette.addState(blocks[y][z][x]);
                }
            }
        }

        int totalBlocks = sX * sY * sZ;
        int bitsPerEntry = palette.getBitsPerEntry();
        int entriesPerLong = 64 / bitsPerEntry;
        int dataLength = (totalBlocks + entriesPerLong - 1) / entriesPerLong;
        long[] data = new long[dataLength];
        long mask = (1L << bitsPerEntry) - 1;

        // Second pass: pack indices
        for (int y = 0; y < sY; y++) {
            for (int z = 0; z < sZ; z++) {
                for (int x = 0; x < sX; x++) {
                    int index = y * sX * sZ + z * sX + x;
                    int paletteIdx = palette.getIndex(blocks[y][z][x]);

                    int longIndex = index / entriesPerLong;
                    int bitOffset = (index % entriesPerLong) * bitsPerEntry;
                    data[longIndex] |= ((long) paletteIdx & mask) << bitOffset;
                }
            }
        }

        PackedSection section = new PackedSection(palette, data, sX, sY, sZ);

        // Update global stats
        totalCompressedBytes.addAndGet(section.getMemoryUsageBytes());
        totalRawBytes.addAndGet(section.getRawMemoryUsageBytes());

        return section;
    }

    // ========================================================================
    // Interning (FerriteCore style)
    // ========================================================================

    /**
     * Intern a block state: if an identical state is already cached, return
     * the cached reference instead. This deduplicates identical BlockState
     * objects to save RAM.
     *
     * @param state the block state to intern
     * @return the canonical instance
     */
    public static BlockState internBlockState(BlockState state) {
        BlockState existing = INTERN_CACHE.putIfAbsent(state, state);
        return existing != null ? existing : state;
    }

    /** @return the number of unique interned block states */
    public static int getInternedCount() {
        return INTERN_CACHE.size();
    }

    /** Clear the intern cache. */
    public static void clearInternCache() {
        INTERN_CACHE.clear();
    }

    // ========================================================================
    // Global stats
    // ========================================================================

    /** @return total compressed bytes across all packed sections */
    public static long getTotalCompressedBytes() {
        return totalCompressedBytes.get();
    }

    /** @return total raw (uncompressed) bytes across all packed sections */
    public static long getTotalRawBytes() {
        return totalRawBytes.get();
    }

    /** @return overall compression ratio (compressed / raw) across all sections */
    public static float getOverallCompressionRatio() {
        long raw = totalRawBytes.get();
        return raw > 0 ? (float) totalCompressedBytes.get() / raw : 1.0f;
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /** Compute ceil(log2(n)), minimum 1. */
    private static int ceilLog2(int n) {
        if (n <= 1) return 1;
        return 32 - Integer.numberOfLeadingZeros(n - 1);
    }
}
