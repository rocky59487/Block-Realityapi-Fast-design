package com.blockreality.api.material;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlockTypeRegistry test — v3fix §M5
 *
 * verify:
 *   1. Correct identification of core enum types
 *   2. Extended type registration/query/unregistration
 *   3. Prohibit name conflicts with core enum names
 *   4. resolveStructuralFactor correctly resolves core and extensions
 *   5. Invalid parameters are rejected (null, blank, non-positive number)
 *   6. Overwrite existing extension types
 */
class BlockTypeRegistryTest {

    @AfterEach
    void tearDown() {
        BlockTypeRegistry.clearExtensions();
    }

    // ─── Core types ───

    @Test
    void testCoreTypesRecognized() {
        assertTrue(BlockTypeRegistry.isCoreType("plain"));
        assertTrue(BlockTypeRegistry.isCoreType("rebar"));
        assertTrue(BlockTypeRegistry.isCoreType("concrete"));
        assertTrue(BlockTypeRegistry.isCoreType("rc_node"));
        assertTrue(BlockTypeRegistry.isCoreType("anchor_pile"));
    }

    @Test
    void testNonCoreTypeNotRecognized() {
        assertFalse(BlockTypeRegistry.isCoreType("prestressed"));
        assertFalse(BlockTypeRegistry.isCoreType(""));
        assertFalse(BlockTypeRegistry.isCoreType("unknown"));
    }

    // ─── Extended type registration ───

    @Test
    void testRegisterExtensionType() {
        BlockTypeRegistry.BlockTypeEntry entry =
            BlockTypeRegistry.register("prestressed", 0.6f);

        assertNotNull(entry);
        assertEquals("prestressed", entry.serializedName());
        assertEquals(0.6f, entry.structuralFactor(), 0.001f);
        assertTrue(BlockTypeRegistry.isRegistered("prestressed"));
        assertEquals(1, BlockTypeRegistry.extensionCount());
    }

    @Test
    void testGetExtensionReturnsCorrectEntry() {
        BlockTypeRegistry.register("geopolymer", 0.75f);

        BlockTypeRegistry.BlockTypeEntry entry = BlockTypeRegistry.getExtension("geopolymer");
        assertNotNull(entry);
        assertEquals(0.75f, entry.structuralFactor(), 0.001f);
    }

    @Test
    void testGetExtensionReturnsNullForUnknown() {
        assertNull(BlockTypeRegistry.getExtension("nonexistent"));
    }

    @Test
    void testGetExtensionReturnsNullForCoreType() {
        // The core type is not in the extension table
        assertNull(BlockTypeRegistry.getExtension("plain"));
    }

    // ─── Cancel registration ───

    @Test
    void testUnregisterExistingType() {
        BlockTypeRegistry.register("temp_type", 1.0f);
        assertTrue(BlockTypeRegistry.unregister("temp_type"));
        assertFalse(BlockTypeRegistry.isRegistered("temp_type"));
        assertEquals(0, BlockTypeRegistry.extensionCount());
    }

    @Test
    void testUnregisterNonExistentReturnsFalse() {
        assertFalse(BlockTypeRegistry.unregister("never_registered"));
    }

    // ─── Name conflict ───

    @Test
    void testRegisterCoreTypeNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTypeRegistry.register("plain", 1.0f),
            "Should reject registration of core enum name 'plain'");
    }

    @Test
    void testRegisterAllCoreNamesThrow() {
        for (BlockType coreType : BlockType.values()) {
            assertThrows(IllegalArgumentException.class, () ->
                BlockTypeRegistry.register(coreType.getSerializedName(), 1.0f),
                "Should reject registration of core enum name: " + coreType.getSerializedName());
        }
    }

    // ─── Coverage has been extended ───

    @Test
    void testOverwriteExistingExtension() {
        BlockTypeRegistry.register("composite", 0.8f);
        BlockTypeRegistry.register("composite", 0.5f);

        BlockTypeRegistry.BlockTypeEntry entry = BlockTypeRegistry.getExtension("composite");
        assertNotNull(entry);
        assertEquals(0.5f, entry.structuralFactor(), 0.001f,
            "Overwrite should update the structural factor");
        assertEquals(1, BlockTypeRegistry.extensionCount(),
            "Should still have only 1 extension");
    }

    // ─── resolveStructuralFactor ───

    @Test
    void testResolveStructuralFactorForCoreTypes() {
        // #7 fix: Verify that the Registry return value = the value of BlockType enum (same source)
        for (BlockType type : BlockType.values()) {
            assertEquals(
                type.getStructuralFactor(),
                BlockTypeRegistry.resolveStructuralFactor(type.getSerializedName()),
                0.001f,
                "Registry factor for " + type + " should match enum"
            );
        }
    }

    @Test
    void testBlockTypeEnumHasStructuralFactor() {
        assertEquals(1.0f, BlockType.PLAIN.getStructuralFactor(), 0.001f);
        assertEquals(0.8f, BlockType.CONCRETE.getStructuralFactor(), 0.001f);
        assertEquals(1.2f, BlockType.REBAR.getStructuralFactor(), 0.001f);
        assertEquals(0.7f, BlockType.RC_NODE.getStructuralFactor(), 0.001f);
        assertEquals(0.5f, BlockType.ANCHOR_PILE.getStructuralFactor(), 0.001f);
    }

    @Test
    void testResolveStructuralFactorForExtension() {
        BlockTypeRegistry.register("lightweight", 1.5f);
        assertEquals(1.5f, BlockTypeRegistry.resolveStructuralFactor("lightweight"), 0.001f);
    }

    @Test
    void testResolveStructuralFactorForUnknownReturnsFallback() {
        assertEquals(1.0f, BlockTypeRegistry.resolveStructuralFactor("totally_unknown"), 0.001f,
            "Unknown type should fallback to 1.0f (PLAIN equivalent)");
    }

    // ─── Invalid parameter ───

    @Test
    void testRegisterNullNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTypeRegistry.register(null, 1.0f));
    }

    @Test
    void testRegisterBlankNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTypeRegistry.register("  ", 1.0f));
    }

    @Test
    void testRegisterZeroFactorThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTypeRegistry.register("zero_factor", 0.0f));
    }

    @Test
    void testRegisterNegativeFactorThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            BlockTypeRegistry.register("negative", -0.5f));
    }

    // ─── isRegistered ───

    @Test
    void testIsRegisteredCoversAll() {
        // core
        assertTrue(BlockTypeRegistry.isRegistered("plain"));
        // Expand
        BlockTypeRegistry.register("custom", 1.0f);
        assertTrue(BlockTypeRegistry.isRegistered("custom"));
        // unknown
        assertFalse(BlockTypeRegistry.isRegistered("nonexistent"));
    }

    // ─── getAllExtensions ───

    @Test
    void testGetAllExtensions() {
        BlockTypeRegistry.register("type_a", 0.5f);
        BlockTypeRegistry.register("type_b", 1.5f);

        var extensions = BlockTypeRegistry.getAllExtensions();
        assertEquals(2, extensions.size());
    }

    // ─── clearExtensions ───

    @Test
    void testClearExtensions() {
        BlockTypeRegistry.register("temp1", 1.0f);
        BlockTypeRegistry.register("temp2", 1.0f);
        assertEquals(2, BlockTypeRegistry.extensionCount());

        BlockTypeRegistry.clearExtensions();
        assertEquals(0, BlockTypeRegistry.extensionCount());
        // Core types are not affected
        assertTrue(BlockTypeRegistry.isCoreType("plain"));
    }

    // ─── BlockType.isKnownType integration ───

    @Test
    void testBlockTypeIsKnownTypeIntegration() {
        assertTrue(BlockType.isKnownType("plain"),
            "Core type should be known");
        assertFalse(BlockType.isKnownType("custom_ext"),
            "Unregistered type should not be known");

        BlockTypeRegistry.register("custom_ext", 0.9f);
        assertTrue(BlockType.isKnownType("custom_ext"),
            "Registered extension should be known");
    }
}
