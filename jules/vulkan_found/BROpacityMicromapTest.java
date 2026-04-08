package com.blockreality.api.client.render.rt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BROpacityMicromap 單元測試。
 *
 * 測試覆蓋：
 *   - OMM 狀態/格式常數值
 *   - 透明材料註冊/取消/查詢/計數
 *   - Section OMM 狀態追蹤 (onSectionUpdated / getSectionState / onSectionRemoved)
 *   - any-hit skip / trigger 計數器
 *   - getOMMEfficiency 效率計算
 *   - buildOMMArray 邊界條件（Phase 1：hasOMM=false 下均返回 null）
 *   - shouldUseOpaqueFlag（無 OMM 擴充時恆 false）
 *   - clear() 重置所有可變狀態
 *
 * 所有測試為純 CPU 邏輯，不依賴 Vulkan / BRAdaRTConfig.detect()。
 * BRAdaRTConfig.hasOMM() 在測試環境下恆為 false（未呼叫 detect()），
 * 因此 buildOMMArray 與 shouldUseOpaqueFlag 走 "no-OMM" 路徑。
 */
class BROpacityMicromapTest {

    private BROpacityMicromap omm;

    @BeforeEach
    void setUp() {
        omm = BROpacityMicromap.getInstance();
        // 清除 section 狀態與計數器（不含透明材料集合）
        omm.clear();
    }

    @AfterEach
    void tearDown() {
        // 清除本測試可能留下的材料註冊
        for (int id = 0; id <= 255; id++) {
            omm.unregisterTransparentMaterial(id);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  OMM 常數
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void ommStateConstants_correctValues() {
        assertEquals(0, BROpacityMicromap.OMM_STATE_TRANSPARENT,
            "OMM_STATE_TRANSPARENT 應為 0（VkOpacityMicromapStateEXT TRANSPARENT）");
        assertEquals(1, BROpacityMicromap.OMM_STATE_OPAQUE,
            "OMM_STATE_OPAQUE 應為 1（VkOpacityMicromapStateEXT OPAQUE）");
        assertEquals(2, BROpacityMicromap.OMM_STATE_UNKNOWN_TRANSPARENT,
            "OMM_STATE_UNKNOWN_TRANSPARENT 應為 2");
        assertEquals(3, BROpacityMicromap.OMM_STATE_UNKNOWN_OPAQUE,
            "OMM_STATE_UNKNOWN_OPAQUE 應為 3");
    }

    @Test
    void ommFormatConstants_correctValues() {
        assertEquals(1, BROpacityMicromap.OMM_FORMAT_2_STATE,
            "OMM_FORMAT_2_STATE 應為 1（VK_OPACITY_MICROMAP_FORMAT_2_STATE_EXT）");
        assertEquals(2, BROpacityMicromap.OMM_FORMAT_4_STATE,
            "OMM_FORMAT_4_STATE 應為 2（VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT）");
    }

    @Test
    void defaultSubdivisionLevel_isTwo() {
        assertEquals(2, BROpacityMicromap.DEFAULT_SUBDIVISION_LEVEL,
            "預設 subdivision level 應為 2（每面 4 個 micro-triangle）");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  透明材料註冊
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void registerTransparentMaterial_validId_isTransparentReturnsTrue() {
        omm.registerTransparentMaterial(5);
        assertTrue(omm.isTransparent(5), "已註冊材料 5 應為透明");
    }

    @Test
    void unregisteredMaterial_isTransparent_returnsFalse() {
        assertFalse(omm.isTransparent(200), "未註冊材料 200 不應為透明");
    }

    @Test
    void registerTransparentMaterial_negativeId_notAdded() {
        int before = omm.getTransparentMaterialCount();
        omm.registerTransparentMaterial(-1);
        assertEquals(before, omm.getTransparentMaterialCount(),
            "id=-1 為無效值，不應加入透明材料集合");
    }

    @Test
    void registerTransparentMaterial_idOver255_notAdded() {
        int before = omm.getTransparentMaterialCount();
        omm.registerTransparentMaterial(256);
        assertEquals(before, omm.getTransparentMaterialCount(),
            "id=256 超出有效範圍（0-255），不應加入");
    }

    @Test
    void unregisterTransparentMaterial_removesTransparency() {
        omm.registerTransparentMaterial(42);
        assertTrue(omm.isTransparent(42));
        omm.unregisterTransparentMaterial(42);
        assertFalse(omm.isTransparent(42), "取消註冊後材料 42 不應為透明");
    }

    @Test
    void transparentMaterialCount_reflectsDistinctRegistrations() {
        int before = omm.getTransparentMaterialCount();
        omm.registerTransparentMaterial(10);
        omm.registerTransparentMaterial(11);
        assertEquals(before + 2, omm.getTransparentMaterialCount(),
            "新增 2 個不同材料後，計數應 +2");
    }

    @Test
    void registerSameMaterialTwice_countNotDoubled() {
        omm.registerTransparentMaterial(20);
        int afterFirst = omm.getTransparentMaterialCount();
        omm.registerTransparentMaterial(20); // 重複
        assertEquals(afterFirst, omm.getTransparentMaterialCount(),
            "重複註冊同一材料不應使計數增加");
    }

    @Test
    void unregisterNonRegisteredMaterial_noEffect() {
        int before = omm.getTransparentMaterialCount();
        // 取消未註冊的材料：不應 throw，計數不變
        assertDoesNotThrow(() -> omm.unregisterTransparentMaterial(100));
        assertEquals(before, omm.getTransparentMaterialCount());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Section 狀態管理
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void onSectionUpdated_noTransparent_returnsOpaque() {
        BROpacityMicromap.OMMSectionState state = omm.onSectionUpdated(1000L, false);
        assertEquals(BROpacityMicromap.OMMSectionState.OPAQUE, state,
            "不含透明方塊的 section 應回傳 OPAQUE");
    }

    @Test
    void onSectionUpdated_hasTransparent_returnsHasTransparent() {
        BROpacityMicromap.OMMSectionState state = omm.onSectionUpdated(2000L, true);
        assertEquals(BROpacityMicromap.OMMSectionState.HAS_TRANSPARENT, state,
            "含透明方塊的 section 應回傳 HAS_TRANSPARENT");
    }

    @Test
    void getSectionState_unknownKey_defaultsToOpaque() {
        BROpacityMicromap.OMMSectionState state = omm.getSectionState(999_999L);
        assertEquals(BROpacityMicromap.OMMSectionState.OPAQUE, state,
            "未知 sectionKey 應預設回傳 OPAQUE（保守策略）");
    }

    @Test
    void getSectionState_afterUpdate_returnsStoredState() {
        omm.onSectionUpdated(3000L, true);
        assertEquals(BROpacityMicromap.OMMSectionState.HAS_TRANSPARENT,
            omm.getSectionState(3000L),
            "update 後查詢應返回 HAS_TRANSPARENT");
    }

    @Test
    void onSectionUpdated_overwriteState_latestStateWins() {
        omm.onSectionUpdated(3100L, true);
        omm.onSectionUpdated(3100L, false); // 覆寫為 opaque
        assertEquals(BROpacityMicromap.OMMSectionState.OPAQUE,
            omm.getSectionState(3100L),
            "第二次 update 應覆寫先前狀態");
    }

    @Test
    void onSectionRemoved_clearsSectionEntry() {
        omm.onSectionUpdated(4000L, true);
        omm.onSectionRemoved(4000L);
        assertEquals(BROpacityMicromap.OMMSectionState.OPAQUE,
            omm.getSectionState(4000L),
            "移除後查詢應回退至預設 OPAQUE");
    }

    @Test
    void getTrackedSectionCount_matchesUpdates() {
        omm.onSectionUpdated(5001L, false);
        omm.onSectionUpdated(5002L, true);
        omm.onSectionUpdated(5003L, false);
        assertEquals(3, omm.getTrackedSectionCount(),
            "追蹤計數應等於不重複 sectionKey 的數量");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Any-hit 計數器
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void anyHitSkipCount_incrementsOnOpaqueSection() {
        int before = omm.getAnyHitSkipCount();
        omm.onSectionUpdated(6001L, false);
        assertEquals(before + 1, omm.getAnyHitSkipCount(),
            "opaque section 應使 skip 計數 +1");
    }

    @Test
    void anyHitTriggerCount_incrementsOnTransparentSection() {
        int before = omm.getAnyHitTriggerCount();
        omm.onSectionUpdated(6002L, true);
        assertEquals(before + 1, omm.getAnyHitTriggerCount(),
            "transparent section 應使 trigger 計數 +1");
    }

    @Test
    void counters_resetToZeroAfterClear() {
        omm.onSectionUpdated(6003L, false);
        omm.onSectionUpdated(6004L, true);
        omm.clear();
        assertEquals(0, omm.getAnyHitSkipCount(), "clear 後 skip 計數應歸零");
        assertEquals(0, omm.getAnyHitTriggerCount(), "clear 後 trigger 計數應歸零");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  OMM 效率計算
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void ommEfficiency_noSections_returnsOne() {
        // 剛 clear 後，total=0 → 效率定義為 1.0（完美，無負擔）
        assertEquals(1.0f, omm.getOMMEfficiency(), 0.001f,
            "無 section 時效率應為 1.0");
    }

    @Test
    void ommEfficiency_allOpaque_returnsOne() {
        omm.onSectionUpdated(7001L, false);
        omm.onSectionUpdated(7002L, false);
        assertEquals(1.0f, omm.getOMMEfficiency(), 0.001f,
            "全部 opaque section 效率應為 1.0");
    }

    @Test
    void ommEfficiency_allTransparent_returnsZero() {
        omm.onSectionUpdated(7003L, true);
        omm.onSectionUpdated(7004L, true);
        assertEquals(0.0f, omm.getOMMEfficiency(), 0.001f,
            "全部 transparent section 效率應為 0.0");
    }

    @Test
    void ommEfficiency_threeOpaque_oneTransparent_returnsSeventyFivePercent() {
        // skip=3, trigger=1 → 3/4 = 0.75
        omm.onSectionUpdated(8001L, false);
        omm.onSectionUpdated(8002L, false);
        omm.onSectionUpdated(8003L, false);
        omm.onSectionUpdated(8004L, true);
        assertEquals(0.75f, omm.getOMMEfficiency(), 0.001f,
            "3 opaque + 1 transparent → 效率 0.75");
    }

    @Test
    void ommEfficiency_rangeIsZeroToOne() {
        omm.onSectionUpdated(8005L, false);
        omm.onSectionUpdated(8006L, true);
        float eff = omm.getOMMEfficiency();
        assertTrue(eff >= 0.0f && eff <= 1.0f,
            "效率值應在 [0.0, 1.0] 範圍內");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  buildOMMArray — Phase 1 路徑（hasOMM=false）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void buildOMMArray_noOMMSupport_returnsNull() {
        // Phase 1：BRAdaRTConfig.hasOMM() 在測試環境恆為 false
        byte[] blockTypes = new byte[4096];
        byte[] result = omm.buildOMMArray(9001L, blockTypes);
        assertNull(result,
            "Phase 1：hasOMM=false 時 buildOMMArray 應返回 null");
    }

    @Test
    void buildOMMArray_nullBlockTypes_returnsNull() {
        // hasOMM=false 路徑首先返回 null，null 輸入亦返回 null
        assertNull(omm.buildOMMArray(9002L, null));
    }

    @Test
    void buildOMMArray_tooShortBlockTypes_returnsNull() {
        // 長度 < 4096 時 hasOMM=false 先觸發，仍返回 null
        assertNull(omm.buildOMMArray(9003L, new byte[100]));
    }

    @Test
    void buildOMMArray_noOMMSupport_doesNotModifySectionState() {
        // hasOMM=false 路徑直接返回，不應呼叫 onSectionUpdated
        // 未知 key 預設 OPAQUE
        omm.buildOMMArray(9004L, new byte[4096]);
        assertEquals(BROpacityMicromap.OMMSectionState.OPAQUE,
            omm.getSectionState(9004L),
            "buildOMMArray 在 hasOMM=false 時不應修改 section 狀態");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  shouldUseOpaqueFlag
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void shouldUseOpaqueFlag_withoutOMM_alwaysReturnsFalse() {
        // hasOMM=false → shouldUseOpaqueFlag 恆 false，不論 section 狀態
        omm.onSectionUpdated(9005L, false); // OPAQUE state
        assertFalse(omm.shouldUseOpaqueFlag(9005L),
            "無 OMM 擴充（hasOMM=false）時 shouldUseOpaqueFlag 應為 false");
    }

    @Test
    void shouldUseOpaqueFlag_unknownSection_withoutOMM_returnsFalse() {
        assertFalse(omm.shouldUseOpaqueFlag(9006L),
            "未知 section 且無 OMM 擴充時應為 false");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  clear()
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void clear_resetsAllMutableState() {
        omm.onSectionUpdated(10001L, false);
        omm.onSectionUpdated(10002L, true);
        omm.clear();

        assertEquals(0, omm.getAnyHitSkipCount(), "clear 後 skip 計數應為 0");
        assertEquals(0, omm.getAnyHitTriggerCount(), "clear 後 trigger 計數應為 0");
        assertEquals(0, omm.getTrackedSectionCount(), "clear 後 section 追蹤數應為 0");
        assertEquals(1.0f, omm.getOMMEfficiency(), 0.001f,
            "clear 後（total=0）效率應為 1.0");
    }

    @Test
    void clear_doesNotAffectTransparentMaterials() {
        // clear() 不清除材料集合（設計決策：材料系統初始化後不應被 clear 清除）
        omm.registerTransparentMaterial(77);
        omm.clear();
        assertTrue(omm.isTransparent(77),
            "clear() 不應清除透明材料集合");
    }
}
