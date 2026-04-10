package com.blockreality.api.spi;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 舊纜索物理系統（CableElement / normalizePair / getCable）已移除，
 * PFSF 在 GPU 上處理纜索物理。此測試類別保留空殼供未來重新實作。
 */
@Disabled("Old cable physics removed; DefaultCableManager is now a PFSF stub")
@DisplayName("DefaultCableManager — placeholder (cable physics moved to PFSF GPU)")
class DefaultCableManagerTest {

    @Test
    @DisplayName("stub test — always skipped")
    void placeholder() {
        // This class is @Disabled; no tests run.
        assertTrue(true);
    }
}
