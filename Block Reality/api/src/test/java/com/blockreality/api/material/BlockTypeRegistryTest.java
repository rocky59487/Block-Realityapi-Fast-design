package com.blockreality.api.material;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BlockTypeRegistry 測試 — v3fix §M5
 *
 * 驗證：
 *   1. 核心 enum 類型正確識別
 *   2. 擴展類型註冊/查詢/取消註冊
 *   3. 禁止與核心 enum 名稱衝突
 *   4. resolveStructuralFactor 正確解析核心和擴展
 *   5. 無效參數拒絕（null, blank, 非正數）
 *   6. 覆蓋已有擴展類型
 */
class BlockTypeRegistryTest {

    @AfterEach
    void tearDown() {
        BlockTypeRegistry.clearExtensions();
    }

    // ─── 核心類型 ───

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

    // ─── 擴展類型註冊 ───

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
        // 核心類型不在擴展表中
        assertNull(BlockTypeRegistry.getExtension("plain"));
    }

    // ─── 取消註冊 ───

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

    // ─── 名稱衝突 ───

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

    // ─── 覆蓋已有擴展 ───

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
        // #7 fix: 驗證 Registry 回傳值 = BlockType enum 的值（同一來源）
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

    // ─── 無效參數 ───

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

    // ─── isRegistered 統合 ───

    @Test
    void testIsRegisteredCoversAll() {
        // 核心
        assertTrue(BlockTypeRegistry.isRegistered("plain"));
        // 擴展
        BlockTypeRegistry.register("custom", 1.0f);
        assertTrue(BlockTypeRegistry.isRegistered("custom"));
        // 未知
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
        // 核心類型不受影響
        assertTrue(BlockTypeRegistry.isCoreType("plain"));
    }

    // ─── BlockType.isKnownType 整合 ───

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
