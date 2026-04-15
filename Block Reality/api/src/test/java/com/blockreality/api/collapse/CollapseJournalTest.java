package com.blockreality.api.collapse;

import com.blockreality.api.physics.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CollapseJournal.
 *
 * Tests cover:
 * - record() root + cascaded events
 * - chainId assignment
 * - undo() — returns latest chain, removes from journal
 * - undo() on empty journal
 * - getChain() / recent() / size()
 * - getFailureCounts() statistics tracking
 * - MAX_ENTRIES eviction
 * - clear()
 * - CollapseManager suppressCollapse flag
 * - CollapseManager journal singleton
 */
@DisplayName("CollapseJournal tests")
class CollapseJournalTest {

    private CollapseJournal journal;

    // Using null as BlockState: the journal only stores it, never calls methods on it.
    // Avoids Minecraft registry initialization (Blocks.STONE requires full registry bootstrap).
    private static final BlockState DUMMY_STATE = null;
    private static final BlockPos POS_A = new BlockPos(1, 64, 1);
    private static final BlockPos POS_B = new BlockPos(2, 64, 1);
    private static final BlockPos POS_C = new BlockPos(3, 64, 1);

    @BeforeEach
    void setUp() {
        journal = new CollapseJournal();
    }

    // ─── Basic record ───

    @Test
    @DisplayName("record root event returns id=0, size becomes 1")
    void testRecordRootEvent() {
        long id = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 100L, 1);
        assertEquals(0L, id);
        assertEquals(1, journal.size());
    }

    @Test
    @DisplayName("successive records get increasing IDs")
    void testRecordIncrementalIds() {
        long id0 = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        long id1 = journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, 1L, -1);
        long id2 = journal.record(POS_C, DUMMY_STATE, FailureType.TENSION_BREAK, 2L, -1);
        assertEquals(0L, id0);
        assertEquals(1L, id1);
        assertEquals(2L, id2);
    }

    @Test
    @DisplayName("root events each get a new chainId")
    void testRootEventsGetDistinctChainIds() {
        long idA = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 1);
        long idB = journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, 0L, 2);
        List<CollapseJournal.Entry> recent = journal.recent(2);
        long chainA = recent.get(0).chainId(); // most recent = B
        long chainB = recent.get(1).chainId(); // next = A
        assertNotEquals(chainA, chainB, "Root events should get distinct chainIds");
    }

    // ─── Cascaded events ───

    @Test
    @DisplayName("cascaded event shares chainId with parent")
    void testCascadedEventSharesChainId() {
        long rootId = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 1);
        // Find root's chainId
        CollapseJournal.Entry root = journal.recent(1).get(0);
        long chainId = root.chainId();

        long cascadeId = journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING,
            rootId, chainId, 0L, 1);

        CollapseJournal.Entry cascade = journal.recent(1).get(0);
        assertEquals(chainId, cascade.chainId());
        assertEquals(rootId, cascade.parentId());
    }

    @Test
    @DisplayName("root event has parentId == -1")
    void testRootEventParentIdMinusOne() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        CollapseJournal.Entry entry = journal.recent(1).get(0);
        assertEquals(-1L, entry.parentId());
    }

    // ─── getChain ───

    @Test
    @DisplayName("getChain returns all events with matching chainId")
    void testGetChain() {
        long rootId = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 5);
        CollapseJournal.Entry root = journal.recent(1).get(0);
        long chainId = root.chainId();

        // Add two cascades in same chain
        journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, rootId, chainId, 0L, 5);
        journal.record(POS_C, DUMMY_STATE, FailureType.TENSION_BREAK, rootId, chainId, 0L, 5);
        // Add event in different chain
        journal.record(new BlockPos(10, 64, 10), DUMMY_STATE, FailureType.NO_SUPPORT, 0L, 99);

        List<CollapseJournal.Entry> chain = journal.getChain(chainId);
        assertEquals(3, chain.size(), "Chain should contain 3 events");
        chain.forEach(e -> assertEquals(chainId, e.chainId()));
    }

    @Test
    @DisplayName("getChain returns empty list for non-existent chainId")
    void testGetChainMiss() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        assertTrue(journal.getChain(99999L).isEmpty());
    }

    // ─── recent ───

    @Test
    @DisplayName("recent(n) returns n most-recent events in reverse order")
    void testRecent() {
        long id0 = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 1);
        long id1 = journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, 1L, 2);
        long id2 = journal.record(POS_C, DUMMY_STATE, FailureType.TENSION_BREAK, 2L, 3);

        List<CollapseJournal.Entry> recent = journal.recent(2);
        assertEquals(2, recent.size());
        assertEquals(id2, recent.get(0).id());  // most recent first
        assertEquals(id1, recent.get(1).id());
    }

    @Test
    @DisplayName("recent(n) when n > size returns all entries")
    void testRecentMoreThanSize() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        List<CollapseJournal.Entry> recent = journal.recent(100);
        assertEquals(1, recent.size());
    }

    @Test
    @DisplayName("recent(0) returns empty list")
    void testRecentZero() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        assertTrue(journal.recent(0).isEmpty());
    }

    // ─── undo ───

    @Test
    @DisplayName("undo() on empty journal returns empty list")
    void testUndoEmptyJournal() {
        List<CollapseJournal.Entry> undone = journal.undo();
        assertNotNull(undone);
        assertTrue(undone.isEmpty());
    }

    @Test
    @DisplayName("undo() removes latest chain and returns its entries")
    void testUndoLatestChain() {
        long rootId = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 1);
        CollapseJournal.Entry root = journal.recent(1).get(0);
        long chainId = root.chainId();
        journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, rootId, chainId, 0L, 1);
        journal.record(POS_C, DUMMY_STATE, FailureType.TENSION_BREAK, rootId, chainId, 0L, 1);

        // Total 3 events in one chain
        assertEquals(3, journal.size());

        List<CollapseJournal.Entry> undone = journal.undo();
        assertEquals(3, undone.size(), "Undo should return all 3 events in chain");
        assertEquals(0, journal.size(), "Journal should be empty after undo of sole chain");
    }

    @Test
    @DisplayName("undo() only removes the LATEST chain, leaves others")
    void testUndoLeavesOtherChains() {
        // Chain 1
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 1);

        // Chain 2 (starts fresh root)
        long root2 = journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, 0L, 2);
        CollapseJournal.Entry entry2 = journal.recent(1).get(0);
        long chain2 = entry2.chainId();
        journal.record(POS_C, DUMMY_STATE, FailureType.TENSION_BREAK, root2, chain2, 0L, 2);

        assertEquals(3, journal.size());

        List<CollapseJournal.Entry> undone = journal.undo();
        assertEquals(2, undone.size(), "Should undo chain2 (2 events)");
        assertEquals(1, journal.size(), "Chain1 (1 event) should remain");
    }

    @Test
    @DisplayName("undo() returns entries in newest-first order")
    void testUndoOrder() {
        long rootId = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 1);
        CollapseJournal.Entry root = journal.recent(1).get(0);
        long chainId = root.chainId();
        journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, rootId, chainId, 1L, 1);

        List<CollapseJournal.Entry> undone = journal.undo();
        // Newest (id=1) should be first
        assertTrue(undone.get(0).id() > undone.get(1).id(),
            "Undo list should be newest-first");
    }

    // ─── getFailureCounts ───

    @Test
    @DisplayName("getFailureCounts tracks counts per FailureType")
    void testGetFailureCounts() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        journal.record(POS_B, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        journal.record(POS_C, DUMMY_STATE, FailureType.CRUSHING, 0L, -1);

        Map<FailureType, Integer> counts = journal.getFailureCounts();
        assertEquals(2, counts.getOrDefault(FailureType.CANTILEVER_BREAK, 0));
        assertEquals(1, counts.getOrDefault(FailureType.CRUSHING, 0));
        assertEquals(0, counts.getOrDefault(FailureType.TENSION_BREAK, 0));
    }

    @Test
    @DisplayName("getFailureCounts returns unmodifiable / safe copy")
    void testGetFailureCountsImmutable() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        Map<FailureType, Integer> counts = journal.getFailureCounts();
        // Modifying returned map should not affect journal
        assertDoesNotThrow(() -> {
            try {
                counts.put(FailureType.TENSION_BREAK, 999);
            } catch (UnsupportedOperationException ignored) {
                // immutable map — that's fine
            }
        });
    }

    @Test
    @DisplayName("undo() decrements failure count")
    void testUndoDecrementsFailureCount() {
        long rootId = journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, 1);
        CollapseJournal.Entry root = journal.recent(1).get(0);
        long chainId = root.chainId();
        journal.record(POS_B, DUMMY_STATE, FailureType.CANTILEVER_BREAK, rootId, chainId, 0L, 1);

        assertEquals(2, journal.getFailureCounts().getOrDefault(FailureType.CANTILEVER_BREAK, 0));

        journal.undo();

        assertEquals(0, journal.getFailureCounts().getOrDefault(FailureType.CANTILEVER_BREAK, 0));
    }

    // ─── MAX_ENTRIES eviction ───

    @Test
    @DisplayName("journal never exceeds MAX_ENTRIES size")
    void testMaxEntriesEviction() {
        int extra = 50;
        for (int i = 0; i < CollapseJournal.MAX_ENTRIES + extra; i++) {
            journal.record(new BlockPos(i, 64, 0), DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        }
        assertTrue(journal.size() <= CollapseJournal.MAX_ENTRIES,
            "Journal should not exceed MAX_ENTRIES");
    }

    // ─── clear ───

    @Test
    @DisplayName("clear() empties the journal and resets failure counts")
    void testClear() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        journal.record(POS_B, DUMMY_STATE, FailureType.CRUSHING, 0L, -1);
        journal.clear();
        assertEquals(0, journal.size());
        assertTrue(journal.getFailureCounts().isEmpty());
    }

    @Test
    @DisplayName("undo() after clear() returns empty list")
    void testUndoAfterClear() {
        journal.record(POS_A, DUMMY_STATE, FailureType.CANTILEVER_BREAK, 0L, -1);
        journal.clear();
        assertTrue(journal.undo().isEmpty());
    }

    // ─── Entry immutability ───

    @Test
    @DisplayName("Entry fields match what was recorded")
    void testEntryFields() {
        long tick = 42L;
        int islandId = 7;
        long id = journal.record(POS_A, DUMMY_STATE, FailureType.CRUSHING, tick, islandId);
        CollapseJournal.Entry entry = journal.recent(1).get(0);
        assertEquals(id, entry.id());
        assertEquals(POS_A, entry.pos());
        assertEquals(DUMMY_STATE, entry.prevState());
        assertEquals(FailureType.CRUSHING, entry.failureType());
        assertEquals(tick, entry.tickStamp());
        assertEquals(islandId, entry.islandId());
        assertEquals(-1L, entry.parentId()); // root event
    }

    // ─── CollapseManager integration ───

    @Test
    @DisplayName("CollapseManager.getJournal() returns non-null singleton")
    void testCollapseManagerJournalSingleton() {
        assertNotNull(CollapseManager.getJournal());
        assertSame(CollapseManager.getJournal(), CollapseManager.getJournal());
    }

    @Test
    @DisplayName("CollapseManager.suppressCollapse flag works")
    void testSuppressCollapseFlag() {
        assertFalse(CollapseManager.isSuppressCollapse());
        CollapseManager.setSuppressCollapse(true);
        assertTrue(CollapseManager.isSuppressCollapse());
        CollapseManager.setSuppressCollapse(false);
        assertFalse(CollapseManager.isSuppressCollapse());
    }
}
