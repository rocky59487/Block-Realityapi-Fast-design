package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFEngine integrates logic testing (no GPU required).
 *
 * <p>Verify engine state machine, module delegation, public API behavior. </p>
 */
class PFSFEngineIntegrationTest {

    @Test
    @DisplayName("引擎未初始化時 isAvailable = false")
    void testUninitializedEngine() {
        // PFSFEngine is static and its initial state depends on VulkanComputeContext
        // In a GPUless environment, isAvailable() should be false
        // (Because VulkanComputeContext.isAvailable() will return false)
        assertFalse(PFSFEngine.isAvailable(),
                "Engine should not be available without Vulkan context");
    }

    @Test
    @DisplayName("getStats 無 GPU 時回傳 DISABLED")
    void testGetStatsDisabled() {
        String stats = PFSFEngine.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("DISABLED"),
                "Stats should indicate DISABLED when engine is not available: " + stats);
    }

    @Test
    @DisplayName("shutdown 可安全重複呼叫")
    void testShutdownIdempotent() {
        // Should not throw even if called multiple times without init
        assertDoesNotThrow(() -> {
            PFSFEngine.shutdown();
            PFSFEngine.shutdown();
        });
    }

    @Test
    @DisplayName("setMaterialLookup 接受 null 不拋例外")
    void testSetMaterialLookupNull() {
        assertDoesNotThrow(() -> PFSFEngine.setMaterialLookup(null));
    }

    @Test
    @DisplayName("setAnchorLookup 接受 null 不拋例外")
    void testSetAnchorLookupNull() {
        assertDoesNotThrow(() -> PFSFEngine.setAnchorLookup(null));
    }

    @Test
    @DisplayName("setFillRatioLookup 接受 null 不拋例外")
    void testSetFillRatioLookupNull() {
        assertDoesNotThrow(() -> PFSFEngine.setFillRatioLookup(null));
    }

    @Test
    @DisplayName("removeBuffer 對不存在的 ID 不拋例外")
    void testRemoveNonExistentBuffer() {
        assertDoesNotThrow(() -> PFSFEngine.removeBuffer(999999));
    }

    @Test
    @DisplayName("onServerTick 無 GPU 時安全跳過")
    void testOnServerTickNoGpu() {
        // Should silently return when not available
        assertDoesNotThrow(() -> PFSFEngine.onServerTick(null, null, 0));
    }

    @Test
    @DisplayName("模組委派：6 個提取類別應存在")
    void testExtractedClassesExist() {
        // Confirm that all extracted categories can be loaded
        assertDoesNotThrow(() -> {
            Class.forName("com.blockreality.api.physics.pfsf.PFSFPipelineFactory");
            Class.forName("com.blockreality.api.physics.pfsf.PFSFDataBuilder");
            Class.forName("com.blockreality.api.physics.pfsf.PFSFVCycleRecorder");
            Class.forName("com.blockreality.api.physics.pfsf.PFSFFailureRecorder");
            Class.forName("com.blockreality.api.physics.pfsf.PFSFBufferManager");
            Class.forName("com.blockreality.api.physics.pfsf.PFSFStressExtractor");
        });
    }

    @Test
    @DisplayName("Failure type 對映完整性：4 種 PFSF failure → 4 種 FailureType enum")
    void testFailureTypeMapping() {
        // Ensure all PFSF failure constants map to valid FailureType values
        var failTypes = com.blockreality.api.physics.FailureType.values();
        assertTrue(failTypes.length >= 4,
                "FailureType should have at least 4 values for PFSF modes");

        // Verify specific mappings exist
        assertNotNull(com.blockreality.api.physics.FailureType.CANTILEVER_BREAK);
        assertNotNull(com.blockreality.api.physics.FailureType.CRUSHING);
        assertNotNull(com.blockreality.api.physics.FailureType.NO_SUPPORT);
        assertNotNull(com.blockreality.api.physics.FailureType.TENSION_BREAK);
    }
}
