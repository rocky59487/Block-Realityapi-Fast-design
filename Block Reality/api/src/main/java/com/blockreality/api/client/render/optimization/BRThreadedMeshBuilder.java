package com.blockreality.api.client.render.optimization;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Block Reality 多线程 LOD 网格构建系统 (C2ME 启发)
 *
 * 本类提供高效的并行网格生成功能，针对客户端渲染优化。
 * 使用固定大小的线程池、任务队列和细粒度的区域锁定机制。
 *
 * 主要特性:
 * - 基于优先级队列的任务调度
 * - 每个网格区域独立的可重入锁
 * - 工作窃取算法实现动态负载均衡
 * - 详细的性能统计和监控
 * - 优雅的线程池关闭机制
 *
 * 线程安全: 所有公共方法都是线程安全的
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
@javax.annotation.concurrent.ThreadSafe // multi-threaded mesh building
public class BRThreadedMeshBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRThreadedMeshBuilder.class);

    // ==================== 单例和线程池 ====================

    private static BRThreadedMeshBuilder INSTANCE;
    private ExecutorService meshBuildPool;
    private ExecutorService ioPool;
    private boolean initialized = false;

    // ==================== 任务队列和结果处理 ====================

    private PriorityBlockingQueue<MeshBuildTask> taskQueue;
    private ConcurrentLinkedQueue<MeshBuildResult> resultQueue;
    private List<WorkerThread> workerThreads;

    // ==================== 区域锁定 ====================

    private ConcurrentHashMap<Long, ReentrantLock> sectionLocks;
    private static final long SECTION_LOCK_TIMEOUT_MS = 50;

    // ==================== 统计信息 ====================

    private AtomicLong tasksSubmitted = new AtomicLong(0);
    private AtomicLong tasksCompleted = new AtomicLong(0);
    private AtomicLong totalBuildTimeNanos = new AtomicLong(0);
    private ConcurrentHashMap<Integer, LodLevelStats> lodStats = new ConcurrentHashMap<>();
    private AtomicLong tasksStolen = new AtomicLong(0);

    // ==================== 线程本地工作队列 ====================

    private static final ThreadLocal<Deque<MeshBuildTask>> localDeque =
        ThreadLocal.withInitial(ArrayDeque::new);

    // ==================== 初始化和单例访问 ====================

    /**
     * 获取 BRThreadedMeshBuilder 单例实例
     *
     * @return 单例实例
     */
    public static synchronized BRThreadedMeshBuilder getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BRThreadedMeshBuilder();
        }
        return INSTANCE;
    }

    /**
     * 私有构造函数
     */
    private BRThreadedMeshBuilder() {
        this.sectionLocks = new ConcurrentHashMap<>();
        this.taskQueue = new PriorityBlockingQueue<>();
        this.resultQueue = new ConcurrentLinkedQueue<>();
        this.workerThreads = new ArrayList<>();
    }

    /**
     * 初始化线程池和工作线程
     *
     * 计算最大线程数为: max(4, Runtime.availableProcessors() - 2)
     * 创建固定大小的网格构建线程池和 I/O 线程池
     */
    public static void init() {
        BRThreadedMeshBuilder instance = getInstance();
        synchronized (instance) {
            if (instance.initialized) {
                LOGGER.warn("BRThreadedMeshBuilder 已初始化");
                return;
            }

            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int meshPoolSize = Math.max(4, availableProcessors - 2);
            int ioPoolSize = 2;

            instance.meshBuildPool = Executors.newFixedThreadPool(
                meshPoolSize,
                createThreadFactory("BR-MeshBuilder")
            );

            instance.ioPool = Executors.newFixedThreadPool(
                ioPoolSize,
                createThreadFactory("BR-MeshIO")
            );

            // 启动工作线程
            for (int i = 0; i < meshPoolSize; i++) {
                WorkerThread worker = new WorkerThread(instance, i);
                instance.workerThreads.add(worker);
                instance.meshBuildPool.execute(worker);
            }

            instance.initialized = true;
            LOGGER.info("BRThreadedMeshBuilder 初始化完成 - 网格线程池大小: {}, I/O 线程池大小: {}",
                meshPoolSize, ioPoolSize);
        }
    }

    /**
     * 创建具有命名约定的线程工厂
     *
     * @param prefix 线程名称前缀
     * @return ThreadFactory 实例
     */
    private static ThreadFactory createThreadFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName(prefix + "-" + count.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1); // 降低优先级，避免阻塞主线程
                return t;
            }
        };
    }

    // ==================== 任务提交接口 ====================

    /**
     * 提交单个网格构建任务
     *
     * 优先级计算: 1.0 / (distanceToCamera + 1.0)
     * 距离相机更近的部分优先构建
     *
     * @param sectionX 网格区域 X 坐标
     * @param sectionZ 网格区域 Z 坐标
     * @param lodLevel LOD 等级
     * @param cameraX 相机 X 坐标
     * @param cameraZ 相机 Z 坐标
     */
    public static void submitBuildTask(int sectionX, int sectionZ, int lodLevel,
                                      double cameraX, double cameraZ) {
        BRThreadedMeshBuilder instance = getInstance();
        if (!instance.initialized) {
            LOGGER.warn("BRThreadedMeshBuilder 未初始化，忽略任务提交");
            return;
        }

        double distanceToCamera = calculateDistance(sectionX, sectionZ, cameraX, cameraZ);
        double priority = 1.0 / (distanceToCamera + 1.0);

        MeshBuildTask task = new MeshBuildTask(sectionX, sectionZ, lodLevel, priority);
        instance.taskQueue.offer(task);
        instance.tasksSubmitted.incrementAndGet();
    }

    /**
     * 批量提交网格构建任务
     *
     * @param sections 网格区域列表，每个元素为 {sectionX, sectionZ}
     * @param lodLevel LOD 等级
     * @param camX 相机 X 坐标
     * @param camZ 相机 Z 坐标
     */
    public static void submitBatchTasks(List<int[]> sections, int lodLevel,
                                       double camX, double camZ) {
        BRThreadedMeshBuilder instance = getInstance();
        if (!instance.initialized) {
            LOGGER.warn("BRThreadedMeshBuilder 未初始化，忽略批量任务提交");
            return;
        }

        for (int[] section : sections) {
            submitBuildTask(section[0], section[1], lodLevel, camX, camZ);
        }
    }

    /**
     * 计算两点间的平面距离
     *
     * @param sectionX 网格区域 X 坐标
     * @param sectionZ 网格区域 Z 坐标
     * @param cameraX 相机 X 坐标
     * @param cameraZ 相机 Z 坐标
     * @return 距离值
     */
    private static double calculateDistance(int sectionX, int sectionZ,
                                           double cameraX, double cameraZ) {
        double dx = (sectionX - cameraX);
        double dz = (sectionZ - cameraZ);
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================== 区域锁定机制 ====================

    /**
     * 获取特定网格区域的可重入锁
     *
     * 使用坐标编码: (long)sectionX << 32 | (sectionZ & 0xFFFFFFFFL)
     *
     * @param sectionX 网格区域 X 坐标
     * @param sectionZ 网格区域 Z 坐标
     * @return ReentrantLock 实例
     */
    private static ReentrantLock getSectionLock(int sectionX, int sectionZ) {
        BRThreadedMeshBuilder instance = getInstance();
        long key = ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
        return instance.sectionLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /**
     * 尝试获取区域锁（非阻塞，含超时）
     *
     * @param sectionX 网格区域 X 坐标
     * @param sectionZ 网格区域 Z 坐标
     * @return 如果成功获取锁返回 true，否则返回 false
     */
    private static boolean tryAcquireSectionLock(int sectionX, int sectionZ) {
        ReentrantLock lock = getSectionLock(sectionX, sectionZ);
        try {
            return lock.tryLock(SECTION_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放区域锁
     *
     * @param sectionX 网格区域 X 坐标
     * @param sectionZ 网格区域 Z 坐标
     */
    private static void releaseSectionLock(int sectionX, int sectionZ) {
        ReentrantLock lock = getSectionLock(sectionX, sectionZ);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    // ==================== 结果处理接口 ====================

    /**
     * 轮询已完成的网格构建结果（非阻塞）
     *
     * 由主线程调用，用于收集工作线程的构建结果
     *
     * @return 下一个可用的结果，如果没有结果返回 null
     */
    public static MeshBuildResult pollResult() {
        BRThreadedMeshBuilder instance = getInstance();
        if (!instance.initialized) {
            return null;
        }
        return instance.resultQueue.poll();
    }

    /**
     * 获取待处理的结果数量
     *
     * @return 结果队列中的结果数量
     */
    public static int getPendingResultCount() {
        BRThreadedMeshBuilder instance = getInstance();
        if (!instance.initialized) {
            return 0;
        }
        return instance.resultQueue.size();
    }

    /**
     * 添加构建结果到结果队列（内部使用）
     *
     * @param result 构建结果
     */
    private static void addResult(MeshBuildResult result) {
        BRThreadedMeshBuilder instance = getInstance();
        instance.resultQueue.offer(result);
    }

    // ==================== 工作窃取实现 ====================

    /**
     * 从其他工作线程的队列中窃取任务
     *
     * 当本地队列为空时，尝试从最繁忙的工作线程队列中窃取任务
     *
     * @return 窃取的任务，如果没有可窃取的任务返回 null
     */
    private static MeshBuildTask stealTask() {
        BRThreadedMeshBuilder instance = getInstance();

        // 找到最繁忙的工作线程（队列中任务最多的）
        WorkerThread busiestWorker = instance.workerThreads.stream()
            .max(Comparator.comparingInt(w -> w.getLocalQueueSize()))
            .orElse(null);

        if (busiestWorker != null && busiestWorker.getLocalQueueSize() > 1) {
            MeshBuildTask stolenTask = busiestWorker.stealTask();
            if (stolenTask != null) {
                instance.tasksStolen.incrementAndGet();
                return stolenTask;
            }
        }

        return null;
    }

    // ==================== 统计信息接口 ====================

    /**
     * 获取详细的统计信息
     *
     * @return 格式化的统计信息字符串
     */
    public static String getStats() {
        BRThreadedMeshBuilder instance = getInstance();
        if (!instance.initialized) {
            return "BRThreadedMeshBuilder 未初始化";
        }

        long submitted = instance.tasksSubmitted.get();
        long completed = instance.tasksCompleted.get();
        long totalTimeMs = instance.totalBuildTimeNanos.get() / 1_000_000;
        long stolen = instance.tasksStolen.get();

        double avgTimeMs = completed > 0 ? (double) totalTimeMs / completed : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("=== BRThreadedMeshBuilder 统计信息 ===\n");
        sb.append("已提交任务数: ").append(submitted).append("\n");
        sb.append("已完成任务数: ").append(completed).append("\n");
        sb.append("待处理任务数: ").append(instance.taskQueue.size()).append("\n");
        sb.append("待处理结果数: ").append(instance.resultQueue.size()).append("\n");
        sb.append("总构建时间: ").append(totalTimeMs).append(" ms\n");
        sb.append("平均构建时间: ").append(String.format("%.2f", avgTimeMs)).append(" ms\n");
        sb.append("工作窃取次数: ").append(stolen).append("\n");

        // 按 LOD 等级的统计信息
        if (!instance.lodStats.isEmpty()) {
            sb.append("\n按 LOD 等级的统计:\n");
            instance.lodStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    LodLevelStats stats = entry.getValue();
                    sb.append("  LOD ").append(entry.getKey()).append(": ")
                      .append("任务数=").append(stats.taskCount.get())
                      .append(", 平均时间=").append(String.format("%.2f", stats.getAverageTimeMs()))
                      .append(" ms\n");
                });
        }

        return sb.toString();
    }

    /**
     * 获取特定 LOD 等级的平均构建时间
     *
     * @param lodLevel LOD 等级
     * @return 平均构建时间（毫秒），如果没有数据返回 0
     */
    public static double getAverageBuildTimeMs(int lodLevel) {
        BRThreadedMeshBuilder instance = getInstance();
        LodLevelStats stats = instance.lodStats.get(lodLevel);
        if (stats == null) {
            return 0;
        }
        return stats.getAverageTimeMs();
    }

    /**
     * 记录任务构建时间
     *
     * @param lodLevel LOD 等级
     * @param buildTimeNanos 构建时间（纳秒）
     */
    private static void recordBuildTime(int lodLevel, long buildTimeNanos) {
        BRThreadedMeshBuilder instance = getInstance();
        LodLevelStats stats = instance.lodStats.computeIfAbsent(
            lodLevel,
            k -> new LodLevelStats()
        );
        stats.recordTime(buildTimeNanos);
    }

    // ==================== 生命周期管理 ====================

    /**
     * 取消所有待处理任务
     *
     * 清空任务队列和本地工作队列，不影响当前正在执行的任务
     */
    public static void cancelAll() {
        BRThreadedMeshBuilder instance = getInstance();
        if (!instance.initialized) {
            return;
        }

        instance.taskQueue.clear();
        instance.workerThreads.forEach(WorkerThread::clearLocalQueue);
        LOGGER.info("已取消所有待处理的网格构建任务");
    }

    /**
     * 检查线程池是否处于空闲状态
     *
     * @return 如果没有待处理或正在运行的任务返回 true
     */
    public static boolean isIdle() {
        BRThreadedMeshBuilder instance = getInstance();
        if (!instance.initialized) {
            return true;
        }

        return instance.taskQueue.isEmpty() &&
               instance.resultQueue.isEmpty() &&
               instance.workerThreads.stream()
                   .allMatch(w -> w.getLocalQueueSize() == 0);
    }

    /**
     * 清理资源并关闭线程池
     *
     * 尝试优雅地关闭线程池，超时为 30 秒
     * 如果线程未能在超时内完成，则强制关闭
     */
    public static void cleanup() {
        BRThreadedMeshBuilder instance = getInstance();
        synchronized (instance) {
            if (!instance.initialized) {
                return;
            }

            try {
                // 尝试停止接收新任务
                instance.meshBuildPool.shutdown();
                instance.ioPool.shutdown();

                // 等待已提交的任务完成
                if (!instance.meshBuildPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOGGER.warn("网格构建线程池在超时内未能完全关闭，执行强制关闭");
                    instance.meshBuildPool.shutdownNow();
                }

                if (!instance.ioPool.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOGGER.warn("I/O 线程池在超时内未能完全关闭，执行强制关闭");
                    instance.ioPool.shutdownNow();
                }

                // 清空队列
                instance.taskQueue.clear();
                instance.resultQueue.clear();
                instance.sectionLocks.clear();
                instance.lodStats.clear();
                instance.workerThreads.clear();

                instance.initialized = false;
                LOGGER.info("BRThreadedMeshBuilder 清理完成");
            } catch (InterruptedException e) {
                LOGGER.error("等待线程池关闭时被中断", e);
                instance.meshBuildPool.shutdownNow();
                instance.ioPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 内部类：任务定义 ====================

    /**
     * 网格构建任务
     *
     * 包含网格区域坐标、LOD 等级、优先级和创建时间
     */
    public static class MeshBuildTask implements Comparable<MeshBuildTask> {
        public final int sectionX;
        public final int sectionZ;
        public final int lodLevel;
        public final double priority;
        public final long creationTime;

        /**
         * 构造网格构建任务
         *
         * @param sectionX 网格区域 X 坐标
         * @param sectionZ 网格区域 Z 坐标
         * @param lodLevel LOD 等级
         * @param priority 优先级（高优先级先执行）
         */
        public MeshBuildTask(int sectionX, int sectionZ, int lodLevel, double priority) {
            this.sectionX = sectionX;
            this.sectionZ = sectionZ;
            this.lodLevel = lodLevel;
            this.priority = priority;
            this.creationTime = System.nanoTime();
        }

        @Override
        public int compareTo(MeshBuildTask other) {
            // 优先级高的先执行（优先级越高，数值越大）
            return Double.compare(other.priority, this.priority);
        }
    }

    // ==================== 内部类：构建结果 ====================

    /**
     * 网格构建结果接口
     *
     * 包含网格顶点、索引和性能计时数据
     */
    public interface MeshBuildResult {
        int getSectionX();
        int getSectionZ();
        float[] getVertices();
        int[] getIndices();
        int getLodLevel();
        long getBuildTimeNanos();
    }

    /**
     * MeshBuildResult 的默认实现
     */
    private static class MeshBuildResultImpl implements MeshBuildResult {
        private final int sectionX;
        private final int sectionZ;
        private final float[] vertices;
        private final int[] indices;
        private final int lodLevel;
        private final long buildTimeNanos;

        public MeshBuildResultImpl(int sectionX, int sectionZ, float[] vertices,
                                 int[] indices, int lodLevel, long buildTimeNanos) {
            this.sectionX = sectionX;
            this.sectionZ = sectionZ;
            this.vertices = vertices;
            this.indices = indices;
            this.lodLevel = lodLevel;
            this.buildTimeNanos = buildTimeNanos;
        }

        @Override
        public int getSectionX() { return sectionX; }

        @Override
        public int getSectionZ() { return sectionZ; }

        @Override
        public float[] getVertices() { return vertices; }

        @Override
        public int[] getIndices() { return indices; }

        @Override
        public int getLodLevel() { return lodLevel; }

        @Override
        public long getBuildTimeNanos() { return buildTimeNanos; }
    }

    // ==================== 内部类：工作线程 ====================

    /**
     * 网格构建工作线程
     *
     * 从全局队列中取任务，执行构建，支持工作窃取算法
     */
    private static class WorkerThread implements Runnable {
        private final BRThreadedMeshBuilder builder;
        private final int workerId;
        private final Deque<MeshBuildTask> localQueue;
        private volatile boolean running = true;

        public WorkerThread(BRThreadedMeshBuilder builder, int workerId) {
            this.builder = builder;
            this.workerId = workerId;
            this.localQueue = new ArrayDeque<>();
        }

        @Override
        public void run() {
            while (running) {
                try {
                    MeshBuildTask task = null;

                    // 首先尝试从本地队列获取任务
                    if (!localQueue.isEmpty()) {
                        task = localQueue.pollFirst();
                    }

                    // 如果本地队列为空，尝试从全局队列获取
                    if (task == null) {
                        task = builder.taskQueue.poll(100, TimeUnit.MILLISECONDS);
                    }

                    // 如果仍然没有任务，尝试窃取
                    if (task == null) {
                        task = stealTask();
                    }

                    if (task != null) {
                        executeMeshBuildTask(task);
                    }
                } catch (InterruptedException e) {
                    if (!running) {
                        break;
                    }
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOGGER.error("工作线程 {} 发生错误", workerId, e);
                }
            }
        }

        /**
         * 执行网格构建任务
         *
         * @param task 任务
         */
        private void executeMeshBuildTask(MeshBuildTask task) {
            if (!tryAcquireSectionLock(task.sectionX, task.sectionZ)) {
                // 如果无法获取锁，将任务放回队列
                builder.taskQueue.offer(task);
                return;
            }

            try {
                long startTime = System.nanoTime();

                // 执行网格构建（这里是模拟实现）
                MeshBuildResult result = buildMesh(task);

                long buildTimeNanos = System.nanoTime() - startTime;

                // 记录统计信息
                builder.tasksCompleted.incrementAndGet();
                builder.totalBuildTimeNanos.addAndGet(buildTimeNanos);
                recordBuildTime(task.lodLevel, buildTimeNanos);

                // 添加结果到队列
                addResult(result);
            } finally {
                releaseSectionLock(task.sectionX, task.sectionZ);
            }
        }

        /**
         * 构建网格（模拟实现）
         *
         * @param task 任务
         * @return 构建结果
         */
        private MeshBuildResult buildMesh(MeshBuildTask task) {
            // 这里应该是实际的网格构建逻辑
            // 为了演示，这里返回空的顶点和索引数据
            float[] vertices = new float[0];
            int[] indices = new int[0];

            return new MeshBuildResultImpl(
                task.sectionX, task.sectionZ, vertices, indices,
                task.lodLevel, System.nanoTime() - task.creationTime
            );
        }

        /**
         * 从其他工作线程窃取任务
         *
         * @return 窃取的任务，如果没有返回 null
         */
        private MeshBuildTask stealTask() {
            return BRThreadedMeshBuilder.stealTask();
        }

        /**
         * 从本地队列中窃取任务（供其他线程调用）
         *
         * @return 窃取的任务，如果队列为空返回 null
         */
        public synchronized MeshBuildTask stealTask_Internal() {
            return localQueue.isEmpty() ? null : localQueue.pollLast();
        }

        /**
         * 获取本地队列大小
         *
         * @return 队列中的任务数
         */
        public int getLocalQueueSize() {
            return localQueue.size();
        }

        /**
         * 清空本地队列
         */
        public void clearLocalQueue() {
            localQueue.clear();
        }

        /**
         * 停止工作线程
         */
        public void stop() {
            running = false;
        }
    }

    // ==================== 内部类：LOD 统计信息 ====================

    /**
     * 单个 LOD 等级的统计数据
     */
    private static class LodLevelStats {
        private final AtomicLong totalTimeNanos = new AtomicLong(0);
        private final AtomicLong taskCount = new AtomicLong(0);

        public void recordTime(long timeNanos) {
            totalTimeNanos.addAndGet(timeNanos);
            taskCount.incrementAndGet();
        }

        public double getAverageTimeMs() {
            long count = taskCount.get();
            if (count == 0) {
                return 0;
            }
            return (double) totalTimeNanos.get() / count / 1_000_000;
        }
    }
}
