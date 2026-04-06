package com.blockreality.fastdesign.client.node;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 評估排程器 — 設計報告 §2.3
 *
 * 惰性評估 + 髒標記傳播：
 *   1. 修改任一輸入 → 標記該節點及所有下游為「髒」
 *   2. 每幀只重新計算髒節點
 *   3. 使用拓撲排序確保評估順序正確
 *
 * 整合到客戶端 tick 或渲染前呼叫 evaluateDirty()。
 */
public class EvaluateScheduler {

    private static final Logger LOGGER = LogManager.getLogger("EvaluateScheduler");

    /** 單幀最大評估節點數（避免卡頓） */
    private static final int MAX_EVALS_PER_FRAME = 256;

    /** 單節點最大評估時間（ns），超過時記錄警告 */
    private static final long SLOW_NODE_THRESHOLD_NS = 1_000_000; // 1ms

    private final NodeGraph graph;
    private long totalEvalTimeNs;
    private int lastEvalCount;

    public EvaluateScheduler(NodeGraph graph) {
        this.graph = graph;
    }

    /**
     * 評估所有髒節點。呼叫時機：每幀渲染前。
     *
     * @return 本次評估的節點數量
     */
    public int evaluateDirty() {
        List<BRNode> order = graph.topologicalOrder();
        int evalCount = 0;
        long frameStart = System.nanoTime();

        for (BRNode node : order) {
            if (!node.isDirty()) continue;
            if (!node.isEnabled()) {
                node.clearDirty();
                continue;
            }
            if (evalCount >= MAX_EVALS_PER_FRAME) {
                LOGGER.debug("單幀評估上限 {}，剩餘髒節點延至下幀", MAX_EVALS_PER_FRAME);
                break;
            }

            long start = System.nanoTime();
            try {
                node.evaluate();
                node.setLastEvalError(null);
            } catch (Exception e) {
                LOGGER.error("節點 {} ({}) 評估失敗", node.displayName(), node.typeId(), e);
                node.setLastEvalError(e);
            }
            long elapsed = System.nanoTime() - start;
            node.setLastEvalTimeNs(elapsed);
            node.clearDirty();
            evalCount++;

            if (elapsed > SLOW_NODE_THRESHOLD_NS) {
                LOGGER.debug("慢節點：{} 耗時 {:.2f}ms", node.displayName(), elapsed / 1_000_000.0);
            }
        }

        totalEvalTimeNs = System.nanoTime() - frameStart;
        lastEvalCount = evalCount;
        return evalCount;
    }

    /**
     * 強制重新評估所有節點（用於載入新圖或重置）。
     */
    public void evaluateAll() {
        for (BRNode node : graph.topologicalOrder()) {
            node.forceDirty();
        }
        // 不限制上限
        List<BRNode> order = graph.topologicalOrder();
        for (BRNode node : order) {
            if (!node.isEnabled()) {
                node.clearDirty();
                continue;
            }
            long start = System.nanoTime();
            try {
                node.evaluate();
                node.setLastEvalError(null);
            } catch (Exception e) {
                LOGGER.error("節點 {} ({}) 全量評估失敗", node.displayName(), node.typeId(), e);
                node.setLastEvalError(e);
            }
            node.setLastEvalTimeNs(System.nanoTime() - start);
            node.clearDirty();
        }
    }

    /**
     * 是否有任何髒節點需要評估。
     */
    public boolean hasDirtyNodes() {
        for (BRNode node : graph.allNodes()) {
            if (node.isDirty() && node.isEnabled()) return true;
        }
        return false;
    }

    // ─── 效能監控 ───

    /** 上一幀總評估時間（ns） */
    public long totalEvalTimeNs()  { return totalEvalTimeNs; }
    /** 上一幀總評估時間（ms） */
    public double totalEvalTimeMs() { return totalEvalTimeNs / 1_000_000.0; }
    /** 上一幀評估的節點數 */
    public int lastEvalCount()     { return lastEvalCount; }
}
