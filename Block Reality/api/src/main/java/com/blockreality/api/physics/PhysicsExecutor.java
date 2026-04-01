package com.blockreality.api.physics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 物理運算專用執行緒池。
 * 單例管理，生命週期跟隨 Minecraft Server。
 *
 * ★ Phase 2 升級：改用 ForkJoinPool 支援多島嶼並行計算。
 * - 不同 island 的物理任務可以並行執行
 * - 保留 2 個 CPU 核心給 Minecraft server thread 和 network I/O
 * - 可透過 BRConfig.physicsThreadCount 配置（預設 auto）
 *
 * 向後相容：原有的 submit(snapshot, scanMargin) API 保持不變。
 */
@javax.annotation.concurrent.ThreadSafe // volatile ExecutorService + AtomicInteger
public class PhysicsExecutor {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/PhysicsExecutor");

    private static volatile ExecutorService executor;

    /** 目前配置的執行緒數（用於診斷） */
    private static volatile int configuredThreadCount;

    /**
     * 使用預設執行緒數啟動（auto: availableProcessors - 2，最少 1）。
     */
    public static synchronized void start() {
        start(0); // 0 = auto
    }

    /**
     * ★ Phase 2: 使用指定執行緒數啟動。
     *
     * @param threadCount 執行緒數（0 = auto，使用 availableProcessors - 2）
     */
    public static synchronized void start(int threadCount) {
        if (executor != null && !executor.isShutdown()) {
            LOGGER.warn("PhysicsExecutor already running");
            return;
        }

        int cores = Runtime.getRuntime().availableProcessors();
        int threads;
        if (threadCount <= 0) {
            // Auto: 保留 2 核給 MC server thread + network
            threads = Math.max(1, cores - 2);
        } else {
            threads = Math.max(1, Math.min(threadCount, cores));
        }
        configuredThreadCount = threads;

        if (threads == 1) {
            // 單核心：使用單執行緒 executor（最低開銷）
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BR-Physics");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
            LOGGER.info("PhysicsExecutor started (single thread, daemon)");
        } else {
            // 多核心：使用 ForkJoinPool 支援 work-stealing
            AtomicInteger threadNum = new AtomicInteger(0);
            executor = new ForkJoinPool(threads,
                pool -> {
                    ForkJoinPool.ForkJoinWorkerThreadFactory factory =
                        ForkJoinPool.defaultForkJoinWorkerThreadFactory;
                    var worker = factory.newThread(pool);
                    worker.setName("BR-Physics-" + threadNum.getAndIncrement());
                    worker.setDaemon(true);
                    worker.setPriority(Thread.NORM_PRIORITY - 1);
                    return worker;
                },
                null, // UncaughtExceptionHandler
                false // asyncMode
            );
            LOGGER.info("PhysicsExecutor started (ForkJoinPool, {} threads, {} cores available)",
                threads, cores);
        }
    }

    /**
     * 提交快照進行非同步物理運算。
     * 快照擷取必須在主執行緒完成，此方法只負責 BFS 運算。
     *
     * @param snapshot   含 margin 的完整掃描區快照
     * @param scanMargin 掃描邊距（崩塌區 = 快照 - 2*margin）
     */
    public static CompletableFuture<BFSConnectivityAnalyzer.PhysicsResult> submit(RWorldSnapshot snapshot, int scanMargin) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        ExecutorService exec = executor; // volatile read once
        if (exec == null || exec.isShutdown()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("PhysicsExecutor not running")
            );
        }
        return CompletableFuture.supplyAsync(
            () -> BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, scanMargin),
            exec
        );
    }

    /**
     * ★ Phase 2: 提交指定 island 的物理任務。
     * 不同 island 的任務可以被 ForkJoinPool 的不同 worker thread 平行執行。
     *
     * @param snapshot   快照
     * @param scanMargin 掃描邊距
     * @param islandId   島嶼 ID（用於日誌和診斷）
     */
    public static CompletableFuture<BFSConnectivityAnalyzer.PhysicsResult> submitForIsland(
            RWorldSnapshot snapshot, int scanMargin, int islandId) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        ExecutorService exec = executor;
        if (exec == null || exec.isShutdown()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("PhysicsExecutor not running")
            );
        }
        return CompletableFuture.supplyAsync(
            () -> {
                long t0 = System.nanoTime();
                BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, scanMargin);
                LOGGER.debug("[PhysicsExecutor] Island {} completed in {}ms on {}",
                    islandId, String.format("%.2f", (System.nanoTime() - t0) / 1e6),
                    Thread.currentThread().getName());
                return result;
            },
            exec
        );
    }

    /** 無 margin 版本（向後相容） */
    public static CompletableFuture<BFSConnectivityAnalyzer.PhysicsResult> submit(RWorldSnapshot snapshot) {
        return submit(snapshot, 0);
    }

    /**
     * ★ Phase 2: 提交通用的 Runnable 到物理執行緒池。
     * 用於 ForceEquilibriumSolver 和 BeamStressEngine 的非同步任務。
     */
    public static <T> CompletableFuture<T> submitTask(java.util.function.Supplier<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        ExecutorService exec = executor;
        if (exec == null || exec.isShutdown()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("PhysicsExecutor not running")
            );
        }
        return CompletableFuture.supplyAsync(task, exec);
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.info("PhysicsExecutor stopped");
        }
    }

    /** 取得配置的執行緒數（診斷用） */
    public static int getConfiguredThreadCount() {
        return configuredThreadCount;
    }

    /** 檢查是否正在運行 */
    public static boolean isRunning() {
        ExecutorService exec = executor;
        return exec != null && !exec.isShutdown();
    }
}
