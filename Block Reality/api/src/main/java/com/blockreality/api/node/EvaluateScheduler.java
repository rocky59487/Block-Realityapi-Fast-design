package com.blockreality.api.node;

/**
 * Static scheduler that evaluates the active node graph each frame.
 * Called once per tick from the render/game loop (e.g. BRRenderPipeline.onRenderLevel AFTER_LEVEL).
 * Runs on both client and server sides.
 */
public final class EvaluateScheduler {
    private EvaluateScheduler() {}

    private static NodeGraph activeGraph;
    private static boolean initialized;
    private static long lastEvalTimeNs;
    private static int dirtyNodesEvaluated;

    /**
     * Initialize the scheduler. Safe to call multiple times.
     */
    public static void init() {
        if (initialized) return;
        activeGraph = null;
        lastEvalTimeNs = 0;
        dirtyNodesEvaluated = 0;
        initialized = true;
    }

    /**
     * Tear down the scheduler and release the active graph reference.
     */
    public static void cleanup() {
        activeGraph = null;
        lastEvalTimeNs = 0;
        dirtyNodesEvaluated = 0;
        initialized = false;
    }

    public static void setActiveGraph(NodeGraph graph) {
        activeGraph = graph;
    }

    public static NodeGraph getActiveGraph() {
        return activeGraph;
    }

    /**
     * Evaluate the active graph if any nodes are dirty. Intended to be
     * called once per frame/tick from the game loop.
     */
    public static void tick() {
        if (!initialized || activeGraph == null) return;
        if (!activeGraph.hasDirtyNodes()) {
            dirtyNodesEvaluated = 0;
            return;
        }

        long t0 = System.nanoTime();
        dirtyNodesEvaluated = activeGraph.evaluate();
        lastEvalTimeNs = System.nanoTime() - t0;
    }

    public static long getLastEvalTimeNs() {
        return lastEvalTimeNs;
    }

    public static int getDirtyNodesEvaluated() {
        return dirtyNodesEvaluated;
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
