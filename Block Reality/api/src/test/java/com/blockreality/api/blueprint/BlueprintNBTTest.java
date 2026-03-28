package com.blockreality.api.blueprint;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * BlueprintNBT 序列化往返測試 — C-10
 *
 * 驗證：
 *   - 元數據（name, author, timestamp, version）往返正確
 *   - 尺寸往返正確
 *   - 空藍圖往返安全
 *   - 結構列表往返正確
 *
 * 注意：BlueprintBlock 序列化依賴 Minecraft BlockState 登錄（NbtUtils.writeBlockState），
 * 在非 Forge 環境下會拋出 ExceptionInInitializerError。
 * 因此部分測試用 assumeTrue 保護，非 Forge 環境下優雅跳過。
 */
@DisplayName("BlueprintNBT — Serialization Roundtrip Tests")
class BlueprintNBTTest {

    private static boolean minecraftRegistryAvailable = false;

    @BeforeAll
    static void checkMinecraftEnvironment() {
        try {
            // 嘗試存取 BlockState 登錄 — 非 Forge 環境會失敗
            Class.forName("net.minecraft.world.level.block.Blocks");
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            minecraftRegistryAvailable = true;
        } catch (ExceptionInInitializerError | NoClassDefFoundError | ClassNotFoundException e) {
            System.out.println("[BlueprintNBTTest] Minecraft registry not available: " + e.getMessage());
            minecraftRegistryAvailable = false;
        }
    }

    // ═══ 1. Empty Blueprint Roundtrip ═══

    @Test
    @DisplayName("Empty blueprint: metadata roundtrip preserves all fields")
    void testEmptyBlueprintMetadataRoundtrip() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint original = new Blueprint();
        original.setName("Test Blueprint");
        original.setAuthor("TestUser");
        original.setTimestamp(1234567890L);
        original.setVersion(1);
        original.setSize(10, 20, 30);

        CompoundTag nbt = BlueprintNBT.write(original);
        Blueprint restored = BlueprintNBT.read(nbt);

        assertEquals("Test Blueprint", restored.getName(), "Name should roundtrip");
        assertEquals("TestUser", restored.getAuthor(), "Author should roundtrip");
        assertEquals(1234567890L, restored.getTimestamp(), "Timestamp should roundtrip");
        assertEquals(1, restored.getVersion(), "Version should roundtrip");
        assertEquals(10, restored.getSizeX(), "SizeX should roundtrip");
        assertEquals(20, restored.getSizeY(), "SizeY should roundtrip");
        assertEquals(30, restored.getSizeZ(), "SizeZ should roundtrip");
    }

    // ═══ 2. Blueprint With Blocks ═══

    @Test
    @DisplayName("Blueprint with blocks: block count preserved")
    void testBlueprintWithBlocksRoundtrip() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint original = new Blueprint();
        original.setName("Block Test");
        original.setAuthor("Author");
        original.setSize(3, 3, 3);

        // Add blocks using Minecraft AIR as placeholder (available in all environments)
        Blueprint.BlueprintBlock block1 = new Blueprint.BlueprintBlock();
        block1.setRelPos(0, 0, 0);
        block1.setBlockState(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        block1.setRMaterialId("concrete");
        block1.setAnchored(true);
        block1.setStressLevel(0.25f);
        block1.setStructureId(1);
        original.getBlocks().add(block1);

        Blueprint.BlueprintBlock block2 = new Blueprint.BlueprintBlock();
        block2.setRelPos(1, 0, 0);
        block2.setBlockState(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        block2.setRMaterialId("timber");
        block2.setAnchored(false);
        block2.setStressLevel(0.75f);
        block2.setStructureId(1);
        original.getBlocks().add(block2);

        CompoundTag nbt = BlueprintNBT.write(original);
        Blueprint restored = BlueprintNBT.read(nbt);

        assertEquals(2, restored.getBlocks().size(), "Block count should be preserved");

        Blueprint.BlueprintBlock rb1 = restored.getBlocks().get(0);
        assertEquals(0, rb1.getRelX());
        assertEquals(0, rb1.getRelY());
        assertEquals(0, rb1.getRelZ());
        assertEquals("concrete", rb1.getRMaterialId());
        assertTrue(rb1.isAnchored());
        assertEquals(0.25f, rb1.getStressLevel(), 0.01f);

        Blueprint.BlueprintBlock rb2 = restored.getBlocks().get(1);
        assertEquals(1, rb2.getRelX());
        assertEquals("timber", rb2.getRMaterialId());
        assertFalse(rb2.isAnchored());
    }

    // ═══ 3. Dynamic Material Roundtrip ═══

    @Test
    @DisplayName("Dynamic material properties preserved through roundtrip")
    void testDynamicMaterialRoundtrip() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint original = new Blueprint();
        original.setName("Dynamic Mat Test");
        original.setSize(1, 1, 1);

        Blueprint.BlueprintBlock block = new Blueprint.BlueprintBlock();
        block.setRelPos(0, 0, 0);
        block.setBlockState(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        block.setRMaterialId("custom_rc_blend");
        block.setDynamic(true);
        block.setDynRcomp(35.5);
        block.setDynRtens(3.2);
        block.setDynRshear(12.0);
        block.setDynDensity(2400.0);
        original.getBlocks().add(block);

        CompoundTag nbt = BlueprintNBT.write(original);
        Blueprint restored = BlueprintNBT.read(nbt);

        Blueprint.BlueprintBlock rb = restored.getBlocks().get(0);
        assertTrue(rb.isDynamic(), "isDynamic flag should be preserved");
        assertEquals(35.5, rb.getDynRcomp(), 0.1);
        assertEquals(3.2, rb.getDynRtens(), 0.1);
        assertEquals(12.0, rb.getDynRshear(), 0.1);
        assertEquals(2400.0, rb.getDynDensity(), 0.1);
    }

    // ═══ 4. Structure Roundtrip ═══

    @Test
    @DisplayName("Structures preserved through roundtrip")
    void testStructureRoundtrip() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint original = new Blueprint();
        original.setName("Structure Test");
        original.setSize(5, 5, 5);

        Blueprint.BlueprintStructure struct = new Blueprint.BlueprintStructure();
        struct.setId(1);
        struct.setBlockCount(42);
        struct.setAvgStress(0.35f);
        original.getStructures().add(struct);

        CompoundTag nbt = BlueprintNBT.write(original);
        Blueprint restored = BlueprintNBT.read(nbt);

        assertEquals(1, restored.getStructures().size());
        Blueprint.BlueprintStructure rs = restored.getStructures().get(0);
        assertEquals(1, rs.getId());
        assertEquals(42, rs.getBlockCount());
        assertEquals(0.35f, rs.getAvgStress(), 0.01f);
    }

    // ═══ 5. NBT Output Non-Null ═══

    @Test
    @DisplayName("write() never returns null for valid blueprint")
    void testWriteNeverNull() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint bp = new Blueprint();
        bp.setName("Minimal");
        bp.setSize(0, 0, 0);

        CompoundTag nbt = BlueprintNBT.write(bp);
        assertNotNull(nbt, "write() should never return null");
        assertTrue(nbt.contains("version"), "NBT should contain version tag");
        assertTrue(nbt.contains("metadata"), "NBT should contain metadata tag");
    }

    // ═══ 6. Null-Safe Metadata ═══

    @Test
    @DisplayName("Null name/author serialized as empty string")
    void testNullMetadataSafe() {
        assumeTrue(minecraftRegistryAvailable, "Requires Minecraft registry");

        Blueprint bp = new Blueprint();
        // name and author are null by default

        CompoundTag nbt = BlueprintNBT.write(bp);
        Blueprint restored = BlueprintNBT.read(nbt);

        // Should not throw, and restored values should be empty string (not null)
        assertNotNull(restored.getName());
        assertNotNull(restored.getAuthor());
    }
}
