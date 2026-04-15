package com.blockreality.api.blueprint;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Blueprint API + BlueprintIO sanitizeName security tests.
 *
 * Tests cover:
 * - Blueprint metadata builder
 * - BlueprintBlock builder
 * - BlueprintStructure builder
 * - BlueprintIO.sanitizeName() security (via reflection)
 * - NBT version field is present
 * - Blueprint.getBlocks() / getStructures() lists
 */
@DisplayName("Blueprint API and sanitizeName security tests")
class BlueprintTest {

    private static boolean minecraftRegistryAvailable = false;

    @BeforeAll
    static void checkMinecraftEnvironment() {
        try {
            Class.forName("net.minecraft.world.level.block.Blocks");
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            minecraftRegistryAvailable = true;
        } catch (ExceptionInInitializerError | NoClassDefFoundError | ClassNotFoundException e) {
            minecraftRegistryAvailable = false;
        }
    }

    // ─── Blueprint metadata ───

    @Test
    @DisplayName("Blueprint default version is CURRENT_VERSION")
    void testDefaultVersion() {
        Blueprint bp = new Blueprint();
        assertEquals(Blueprint.CURRENT_VERSION, bp.getVersion());
    }

    @Test
    @DisplayName("Blueprint setName / getName round trip")
    void testNameRoundTrip() {
        Blueprint bp = new Blueprint();
        bp.setName("My Structure");
        assertEquals("My Structure", bp.getName());
    }

    @Test
    @DisplayName("Blueprint setAuthor / getAuthor round trip")
    void testAuthorRoundTrip() {
        Blueprint bp = new Blueprint();
        bp.setAuthor("Builder42");
        assertEquals("Builder42", bp.getAuthor());
    }

    @Test
    @DisplayName("Blueprint setTimestamp / getTimestamp round trip")
    void testTimestampRoundTrip() {
        Blueprint bp = new Blueprint();
        bp.setTimestamp(9876543210L);
        assertEquals(9876543210L, bp.getTimestamp());
    }

    @Test
    @DisplayName("Blueprint setSize / getSize round trip")
    void testSizeRoundTrip() {
        Blueprint bp = new Blueprint();
        bp.setSize(16, 32, 8);
        assertEquals(16, bp.getSizeX());
        assertEquals(32, bp.getSizeY());
        assertEquals(8, bp.getSizeZ());
    }

    @Test
    @DisplayName("Blueprint initial blocks list is empty")
    void testInitialBlocksEmpty() {
        Blueprint bp = new Blueprint();
        assertNotNull(bp.getBlocks());
        assertTrue(bp.getBlocks().isEmpty());
    }

    @Test
    @DisplayName("Blueprint initial structures list is empty")
    void testInitialStructuresEmpty() {
        Blueprint bp = new Blueprint();
        assertNotNull(bp.getStructures());
        assertTrue(bp.getStructures().isEmpty());
    }

    @Test
    @DisplayName("Blueprint FILE_EXTENSION is .brblp")
    void testFileExtension() {
        assertEquals(".brblp", Blueprint.FILE_EXTENSION);
    }

    // ─── BlueprintBlock builder ───

    @Test
    @DisplayName("BlueprintBlock setRelPos stores all three coordinates")
    void testBlueprintBlockRelPos() {
        Blueprint.BlueprintBlock block = new Blueprint.BlueprintBlock();
        block.setRelPos(5, 10, 15);
        assertEquals(5, block.getRelX());
        assertEquals(10, block.getRelY());
        assertEquals(15, block.getRelZ());
    }

    @Test
    @DisplayName("BlueprintBlock setRMaterialId / getRMaterialId round trip")
    void testBlueprintBlockMaterialId() {
        Blueprint.BlueprintBlock block = new Blueprint.BlueprintBlock();
        block.setRMaterialId("steel");
        assertEquals("steel", block.getRMaterialId());
    }

    @Test
    @DisplayName("BlueprintBlock setAnchored / isAnchored round trip")
    void testBlueprintBlockAnchored() {
        Blueprint.BlueprintBlock block = new Blueprint.BlueprintBlock();
        block.setAnchored(true);
        assertTrue(block.isAnchored());
        block.setAnchored(false);
        assertFalse(block.isAnchored());
    }

    @Test
    @DisplayName("BlueprintBlock setStressLevel / getStressLevel round trip")
    void testBlueprintBlockStressLevel() {
        Blueprint.BlueprintBlock block = new Blueprint.BlueprintBlock();
        block.setStressLevel(0.55f);
        assertEquals(0.55f, block.getStressLevel(), 1e-6f);
    }

    @Test
    @DisplayName("BlueprintBlock setDynamic + dynamic properties round trip")
    void testBlueprintBlockDynamic() {
        Blueprint.BlueprintBlock block = new Blueprint.BlueprintBlock();
        block.setDynamic(true);
        block.setDynRcomp(35.0);
        block.setDynRtens(3.5);
        block.setDynRshear(14.0);
        block.setDynDensity(2500.0);

        assertTrue(block.isDynamic());
        assertEquals(35.0, block.getDynRcomp(), 1e-9);
        assertEquals(3.5, block.getDynRtens(), 1e-9);
        assertEquals(14.0, block.getDynRshear(), 1e-9);
        assertEquals(2500.0, block.getDynDensity(), 1e-9);
    }

    // ─── BlueprintStructure builder ───

    @Test
    @DisplayName("BlueprintStructure setId / getId round trip")
    void testBlueprintStructureId() {
        Blueprint.BlueprintStructure s = new Blueprint.BlueprintStructure();
        s.setId(7);
        assertEquals(7, s.getId());
    }

    @Test
    @DisplayName("BlueprintStructure setBlockCount / getBlockCount round trip")
    void testBlueprintStructureBlockCount() {
        Blueprint.BlueprintStructure s = new Blueprint.BlueprintStructure();
        s.setBlockCount(128);
        assertEquals(128, s.getBlockCount());
    }

    @Test
    @DisplayName("BlueprintStructure setAvgStress / getAvgStress round trip")
    void testBlueprintStructureAvgStress() {
        Blueprint.BlueprintStructure s = new Blueprint.BlueprintStructure();
        s.setAvgStress(0.42f);
        assertEquals(0.42f, s.getAvgStress(), 1e-6f);
    }

    // ─── BlueprintIO.sanitizeName() — security tests via reflection ───

    private static String sanitize(String input) throws Exception {
        Method m = BlueprintIO.class.getDeclaredMethod("sanitizeName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, input);
    }

    @Test
    @DisplayName("sanitizeName: normal name is preserved")
    void testSanitizeNormal() throws Exception {
        assertEquals("MyBlueprint", sanitize("MyBlueprint"));
    }

    @Test
    @DisplayName("sanitizeName: removes path traversal (..)")
    void testSanitizePathTraversal() throws Exception {
        // Should strip ".."
        String result = sanitize("../../etc/passwd");
        assertFalse(result.contains(".."), "Should not contain '..'");
        assertFalse(result.contains("/"), "Should not contain '/'");
    }

    @Test
    @DisplayName("sanitizeName: removes forward and backward slashes")
    void testSanitizeSlashes() throws Exception {
        String result = sanitize("path/to/file");
        assertFalse(result.contains("/"), "Should not contain '/'");
        String result2 = sanitize("path\\file");
        assertFalse(result2.contains("\\"), "Should not contain '\\'");
    }

    @Test
    @DisplayName("sanitizeName: removes dangerous characters < > : \" | ? *")
    void testSanitizeDangerousChars() throws Exception {
        String result = sanitize("test<>:\"|?*name");
        assertFalse(result.contains("<"));
        assertFalse(result.contains(">"));
        assertFalse(result.contains(":"));
        assertFalse(result.contains("\""));
        assertFalse(result.contains("|"));
        assertFalse(result.contains("?"));
        assertFalse(result.contains("*"));
    }

    @Test
    @DisplayName("sanitizeName: allows alphanumeric, hyphens and underscores")
    void testSanitizeAllowedChars() throws Exception {
        String input = "My-Blueprint_123";
        assertEquals(input, sanitize(input));
    }

    @Test
    @DisplayName("sanitizeName: null name throws IllegalArgumentException")
    void testSanitizeNull() {
        assertThrows(Exception.class, () -> sanitize(null));
    }

    @Test
    @DisplayName("sanitizeName: empty string throws IllegalArgumentException")
    void testSanitizeEmpty() {
        assertThrows(Exception.class, () -> sanitize(""));
    }

    @Test
    @DisplayName("sanitizeName: all-invalid characters throws IllegalArgumentException")
    void testSanitizeAllInvalid() {
        assertThrows(Exception.class, () -> sanitize("!@#$%^&()"));
    }

    @Test
    @DisplayName("sanitizeName: name longer than 64 chars is truncated")
    void testSanitizeLongName() throws Exception {
        String longName = "a".repeat(100);
        String result = sanitize(longName);
        assertTrue(result.length() <= 64, "sanitized name must be ≤ 64 chars");
    }

    @Test
    @DisplayName("sanitizeName: exactly 64 chars passes through unchanged")
    void testSanitizeExact64() throws Exception {
        String name64 = "a".repeat(64);
        String result = sanitize(name64);
        assertEquals(64, result.length());
    }

    // ─── NBT round-trip extras ───

    @Test
    @DisplayName("NBT contains 'version', 'metadata', 'blocks', 'structures' tags")
    void testNBTTagStructure() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint bp = new Blueprint();
        bp.setName("Test");
        bp.setSize(1, 1, 1);
        CompoundTag nbt = BlueprintNBT.write(bp);

        assertTrue(nbt.contains("version"));
        assertTrue(nbt.contains("metadata"));
        assertTrue(nbt.contains("blocks"));
        assertTrue(nbt.contains("structures"));
    }

    @Test
    @DisplayName("Adding 100 blocks: all preserved in round-trip")
    void testLargeBlueprintRoundTrip() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint bp = new Blueprint();
        bp.setName("Large");
        bp.setSize(10, 10, 10);

        for (int i = 0; i < 100; i++) {
            Blueprint.BlueprintBlock block = new Blueprint.BlueprintBlock();
            block.setRelPos(i % 10, (i / 10) % 10, i / 100);
            block.setBlockState(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            block.setRMaterialId("concrete");
            block.setStructureId(i % 5);
            bp.getBlocks().add(block);
        }

        CompoundTag nbt = BlueprintNBT.write(bp);
        Blueprint restored = BlueprintNBT.read(nbt);

        assertEquals(100, restored.getBlocks().size());
    }
}
