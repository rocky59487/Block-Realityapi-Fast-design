package com.blockreality.api.collapse;

import com.blockreality.api.physics.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 崩塌日誌 — 記錄結構崩塌事件鏈，支援因果分析與可逆計算。
 *
 * <p>設計目的：</p>
 * <ul>
 *   <li><b>因果追蹤</b>：每個崩塌事件記錄觸發者（parent），形成因果樹</li>
 *   <li><b>可逆計算</b>：記錄崩塌前的 BlockState 快照，支援 undo 回滾</li>
 *   <li><b>統計分析</b>：按 FailureType 統計崩塌頻率，回饋給求解器調優</li>
 * </ul>
 *
 * <p>生命週期：每個 ServerLevel 維護一個 journal 實例。
 * 透過 {@link #record} 記錄，{@link #undo} 回滾最近的崩塌鏈。
 * 超過 {@link #MAX_ENTRIES} 的舊條目自動淘汰。</p>
 *
 * <p>執行緒安全：內部使用 ConcurrentLinkedDeque，可從任何執行緒記錄。</p>
 */
public class CollapseJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger("CollapseJournal");

    /** 日誌最大條目數（防止記憶體膨脹）。 */
    public static final int MAX_ENTRIES = 10_000;

    /**
     * 崩塌事件記錄 — 不可變值物件。
     *
     * @param id         全域遞增序號
     * @param pos        崩塌座標
     * @param prevState  崩塌前的 BlockState（undo 回滾用）
     * @param failureType 失效類型
     * @param parentId   觸發此崩塌的父事件 id（-1 = 根事件，無 parent）
     * @param chainId    因果鏈 id（同一串級崩塌共享 chainId）
     * @param tickStamp  遊戲 tick 時間戳
     * @param islandId   PFSF island id（-1 = 非 PFSF 觸發）
     */
    public record Entry(
            long id,
            BlockPos pos,
            BlockState prevState,
            FailureType failureType,
            long parentId,
            long chainId,
            long tickStamp,
            int islandId
    ) {}

    // ─── 內部狀態 ───
    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();
    private volatile long nextId = 0;
    private volatile long nextChainId = 0;

    // ─── 統計快取 ───
    private final Map<FailureType, Integer> failureCountMap =
            Collections.synchronizedMap(new EnumMap<>(FailureType.class));

    /**
     * 記錄一個崩塌事件（根事件，無 parent）。
     *
     * @return 此事件的 id
     */
    public long record(BlockPos pos, BlockState prevState, FailureType type,
                       long tickStamp, int islandId) {
        return record(pos, prevState, type, -1, -1, tickStamp, islandId);
    }

    /**
     * 記錄一個崩塌事件（串級事件，有 parent）。
     *
     * @param parentId 觸發此崩塌的父事件 id
     * @param chainId  因果鏈 id（與 parent 相同）
     * @return 此事件的 id
     */
    public long record(BlockPos pos, BlockState prevState, FailureType type,
                       long parentId, long chainId, long tickStamp, int islandId) {
        long id = nextId++;
        if (chainId < 0) {
            chainId = nextChainId++;
        }

        Entry entry = new Entry(id, pos, prevState, type, parentId, chainId, tickStamp, islandId);
        entries.addLast(entry);

        // 更新統計
        failureCountMap.merge(type, 1, Integer::sum);

        // 淘汰舊條目
        while (entries.size() > MAX_ENTRIES) {
            Entry evicted = entries.pollFirst();
            if (evicted != null) {
                failureCountMap.computeIfPresent(evicted.failureType(),
                        (k, v) -> v > 1 ? v - 1 : null);
            }
        }

        return id;
    }

    /**
     * 回滾最近一條因果鏈的所有崩塌。
     *
     * @return 回滾的 Entry 列表（從最新到最舊），呼叫端負責恢復 BlockState。
     *         若日誌為空返回空列表。
     */
    public List<Entry> undo() {
        if (entries.isEmpty()) return Collections.emptyList();

        // 找最新事件的 chainId
        Entry latest = entries.peekLast();
        if (latest == null) return Collections.emptyList();
        long targetChain = latest.chainId();

        // 收集同一 chain 的所有事件（從新到舊）
        List<Entry> undoList = new ArrayList<>();
        Iterator<Entry> desc = entries.descendingIterator();
        while (desc.hasNext()) {
            Entry e = desc.next();
            if (e.chainId() == targetChain) {
                undoList.add(e);
                desc.remove();
                failureCountMap.computeIfPresent(e.failureType(),
                        (k, v) -> v > 1 ? v - 1 : null);
            } else if (e.id() < latest.id() - MAX_ENTRIES) {
                break;  // 太舊了，停止搜尋
            }
        }

        LOGGER.debug("[CollapseJournal] Undo chain {} ({} events)", targetChain, undoList.size());
        return undoList;
    }

    /**
     * 取得指定因果鏈的所有事件。
     */
    public List<Entry> getChain(long chainId) {
        List<Entry> chain = new ArrayList<>();
        for (Entry e : entries) {
            if (e.chainId() == chainId) chain.add(e);
        }
        return chain;
    }

    /**
     * 按 FailureType 統計崩塌次數。
     */
    public Map<FailureType, Integer> getFailureCounts() {
        if (failureCountMap.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new EnumMap<>(failureCountMap));
    }

    /**
     * 最近 N 個事件。
     */
    public List<Entry> recent(int n) {
        List<Entry> result = new ArrayList<>();
        Iterator<Entry> desc = entries.descendingIterator();
        while (desc.hasNext() && result.size() < n) {
            result.add(desc.next());
        }
        return result;
    }

    /** 日誌大小。 */
    public int size() { return entries.size(); }

    /** 清空日誌。 */
    public void clear() {
        entries.clear();
        failureCountMap.clear();
        LOGGER.debug("[CollapseJournal] Cleared");
    }
}
