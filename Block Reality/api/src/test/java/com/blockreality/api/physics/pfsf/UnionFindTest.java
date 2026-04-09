package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UnionFind data structure unit test.
 */
class UnionFindTest {

    @Test
    @DisplayName("新建元素各為獨立集合")
    void testMakeSetIndependent() {
        UnionFind<String> uf = new UnionFind<>();
        uf.makeSet("A");
        uf.makeSet("B");
        uf.makeSet("C");

        assertEquals(3, uf.countRoots());
        assertFalse(uf.connected("A", "B"));
        assertFalse(uf.connected("B", "C"));
    }

    @Test
    @DisplayName("union 合併兩集合")
    void testUnionBasic() {
        UnionFind<String> uf = new UnionFind<>();
        uf.makeSet("A");
        uf.makeSet("B");
        uf.makeSet("C");

        uf.union("A", "B");
        assertEquals(2, uf.countRoots());
        assertTrue(uf.connected("A", "B"));
        assertFalse(uf.connected("A", "C"));
    }

    @Test
    @DisplayName("鏈式合併收斂到同一集合")
    void testChainUnion() {
        UnionFind<Integer> uf = new UnionFind<>();
        for (int i = 0; i < 10; i++) uf.makeSet(i);

        for (int i = 0; i < 9; i++) uf.union(i, i + 1);

        assertEquals(1, uf.countRoots());
        assertTrue(uf.connected(0, 9));
    }

    @Test
    @DisplayName("getGroups 回傳正確分組")
    void testGetGroups() {
        UnionFind<String> uf = new UnionFind<>();
        uf.makeSet("A"); uf.makeSet("B"); uf.makeSet("C"); uf.makeSet("D");

        uf.union("A", "B");
        uf.union("C", "D");

        Map<String, Set<String>> groups = uf.getGroups();
        assertEquals(2, groups.size());

        // Find the group containing A
        Set<String> groupAB = groups.values().stream()
                .filter(s -> s.contains("A")).findFirst().orElseThrow();
        assertTrue(groupAB.contains("B"));
        assertEquals(2, groupAB.size());
    }

    @Test
    @DisplayName("重複 makeSet 不影響既有集合")
    void testDuplicateMakeSet() {
        UnionFind<String> uf = new UnionFind<>();
        uf.makeSet("A");
        uf.makeSet("B");
        uf.union("A", "B");

        uf.makeSet("A"); // repeat
        assertTrue(uf.connected("A", "B")); // should not be disconnected
    }

    @Test
    @DisplayName("find 不存在的元素拋出例外")
    void testFindNonExistent() {
        UnionFind<String> uf = new UnionFind<>();
        assertThrows(IllegalArgumentException.class, () -> uf.find("X"));
    }

    @Test
    @DisplayName("union 自動建立不存在的元素")
    void testUnionAutoMakeSet() {
        UnionFind<String> uf = new UnionFind<>();
        uf.union("A", "B");
        assertTrue(uf.connected("A", "B"));
        assertEquals(1, uf.countRoots());
    }

    @Test
    @DisplayName("路徑壓縮後 find 仍正確")
    void testPathCompression() {
        UnionFind<Integer> uf = new UnionFind<>();
        // Create long chain 0-1-2-...-99
        for (int i = 0; i < 100; i++) uf.makeSet(i);
        for (int i = 0; i < 99; i++) uf.union(i, i + 1);

        // Multiple find triggers path compression
        for (int i = 0; i < 100; i++) uf.find(i);

        assertEquals(1, uf.countRoots());
        assertTrue(uf.connected(0, 99));
    }

    @Test
    @DisplayName("Collection 建構子正確初始化")
    void testCollectionConstructor() {
        UnionFind<String> uf = new UnionFind<>(Set.of("A", "B", "C"));
        assertEquals(3, uf.countRoots());
        assertTrue(uf.contains("A"));
        assertTrue(uf.contains("B"));
        assertTrue(uf.contains("C"));
    }
}
