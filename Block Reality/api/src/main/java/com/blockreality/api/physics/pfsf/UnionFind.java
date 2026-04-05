package com.blockreality.api.physics.pfsf;

import java.util.*;

/**
 * 泛型 Union-Find（不相交集合）資料結構。
 * 使用路徑壓縮 + 按秩合併，均攤近 O(α(n)) 時間複雜度。
 *
 * 用於 PFSF 引擎的錨點分群（§2.5.2 雙色 BFS 的前置步驟）。
 *
 * @param <T> 元素類型
 */
public class UnionFind<T> {

    private final Map<T, T> parent;
    private final Map<T, Integer> rank;

    public UnionFind() {
        this.parent = new HashMap<>();
        this.rank = new HashMap<>();
    }

    /**
     * 建立包含指定初始元素的 Union-Find。
     *
     * @param elements 初始元素集合
     */
    public UnionFind(Collection<T> elements) {
        this();
        for (T e : elements) {
            makeSet(e);
        }
    }

    /**
     * 將元素加入為獨立集合。若已存在則忽略。
     */
    public void makeSet(T x) {
        if (!parent.containsKey(x)) {
            parent.put(x, x);
            rank.put(x, 0);
        }
    }

    /**
     * 查找元素所屬集合的代表元素（含路徑壓縮）。
     *
     * @throws IllegalArgumentException 若元素不在集合中
     */
    public T find(T x) {
        if (!parent.containsKey(x)) {
            throw new IllegalArgumentException("Element not in UnionFind: " + x);
        }
        T root = x;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        // 路徑壓縮：將路徑上所有節點直接指向 root
        T current = x;
        while (!current.equals(root)) {
            T next = parent.get(current);
            parent.put(current, root);
            current = next;
        }
        return root;
    }

    /**
     * 合併兩元素所屬的集合（按秩合併）。
     * 若任一元素不存在，先自動建立。
     */
    public void union(T a, T b) {
        makeSet(a);
        makeSet(b);
        T rootA = find(a);
        T rootB = find(b);
        if (rootA.equals(rootB)) return;

        int rankA = rank.get(rootA);
        int rankB = rank.get(rootB);

        if (rankA < rankB) {
            parent.put(rootA, rootB);
        } else if (rankA > rankB) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootB, rootA);
            rank.put(rootA, rankA + 1);
        }
    }

    /**
     * 查詢兩元素是否屬於同一集合。
     */
    public boolean connected(T a, T b) {
        if (!parent.containsKey(a) || !parent.containsKey(b)) return false;
        return find(a).equals(find(b));
    }

    /**
     * 回傳不同集合的數量。
     */
    public int countRoots() {
        Set<T> roots = new HashSet<>();
        for (T key : parent.keySet()) {
            roots.add(find(key));
        }
        return roots.size();
    }

    /**
     * 回傳所有群組，key = 代表元素，value = 群組成員。
     */
    public Map<T, Set<T>> getGroups() {
        Map<T, Set<T>> groups = new HashMap<>();
        for (T key : parent.keySet()) {
            T root = find(key);
            groups.computeIfAbsent(root, k -> new HashSet<>()).add(key);
        }
        return groups;
    }

    /**
     * 回傳集合中的元素總數。
     */
    public int size() {
        return parent.size();
    }

    /**
     * 判斷元素是否已在集合中。
     */
    public boolean contains(T x) {
        return parent.containsKey(x);
    }
}
