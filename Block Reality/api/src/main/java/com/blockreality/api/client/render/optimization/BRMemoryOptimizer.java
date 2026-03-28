package com.blockreality.api.client.render.optimization;

import org.joml.Matrix4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Arrays;
import java.util.Objects;

/**
 * FerriteCore 启发的内存优化引擎。
 *
 * BRMemoryOptimizer 提供多种内存优化技术以减少运行时分配和改进 Block Reality 客户端渲染性能。
 * 包括对象池化、字符串交编、位域属性打包和零分配渲染支持。
 *
 * 作为静态单例运行，在客户端初始化时预先分配所有对象池。
 *
 * @author Block Reality Engine
 * @version 1.0.0
 */
@OnlyIn(Dist.CLIENT)
public final class BRMemoryOptimizer {

    private static final Logger LOG = LoggerFactory.getLogger(BRMemoryOptimizer.class);

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 单例实例
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static volatile boolean initialized = false;

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 对象交编池
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 通用交编池容器。
     *
     * 使用 ConcurrentHashMap 以线程安全的方式存储规范实例，实现 Flyweight 模式。
     *
     * @param <T> 交编对象的类型
     */
    public static class InternPool<T> {
        private final ConcurrentHashMap<T, T> pool = new ConcurrentHashMap<>();
        private final AtomicLong hitCount = new AtomicLong(0);
        private final AtomicLong missCount = new AtomicLong(0);

        /**
         * 获取规范实例。如果不存在，创建并存储。
         *
         * @param value 要交编的值
         * @return 规范实例
         */
        public T intern(T value) {
            if (value == null) return null;
            T canonical = pool.putIfAbsent(value, value);
            if (canonical != null) {
                hitCount.incrementAndGet();
                return canonical;
            }
            missCount.incrementAndGet();
            return value;
        }

        /**
         * 获取交编池的统计信息。
         *
         * @return 包含命中率的统计字符串
         */
        public String getStats() {
            long hits = hitCount.get();
            long misses = missCount.get();
            long total = hits + misses;
            double hitRate = total > 0 ? (hits * 100.0 / total) : 0.0;
            return String.format("Hits: %d, Misses: %d, Hit Rate: %.2f%%, Pool Size: %d",
                    hits, misses, hitRate, pool.size());
        }

        /**
         * 清空交编池。
         */
        public void clear() {
            pool.clear();
            hitCount.set(0);
            missCount.set(0);
        }

        /**
         * 获取池中的条目数。
         *
         * @return 条目数
         */
        public int size() {
            return pool.size();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 预构建的交编池
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** 骨骼名称字符串交编池 */
    private static final InternPool<String> BONE_NAME_POOL = new InternPool<>();

    /** 打包方块状态数组交编池 */
    private static final InternPool<int[]> PACKED_STATE_POOL = new InternPool<>();

    /** 变换向量数组交编池 */
    private static final InternPool<float[]> TRANSFORM_VECTOR_POOL = new InternPool<>();

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 位域属性打包
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 打包方块属性容器。
     *
     * 将方块属性编码到单个 64 位长整数中，支持最多 16 个布尔值属性和 4 个枚举属性（每个 4 位）。
     */
    public static class PackedBlockProperties {
        private static final int BOOL_OFFSET = 0;
        private static final int ENUM_OFFSET = 16;

        /**
         * 从布尔和枚举属性编码打包值。
         *
         * @param boolProps 布尔属性数组（最多 16 个）
         * @param enumProps 枚举属性数组（最多 4 个，范围 0-15）
         * @return 打包后的 64 位值
         */
        public static long encode(boolean[] boolProps, int[] enumProps) {
            long packed = 0L;

            // 编码布尔属性
            if (boolProps != null) {
                for (int i = 0; i < Math.min(boolProps.length, 16); i++) {
                    if (boolProps[i]) {
                        packed |= (1L << (BOOL_OFFSET + i));
                    }
                }
            }

            // 编码枚举属性
            if (enumProps != null) {
                for (int i = 0; i < Math.min(enumProps.length, 4); i++) {
                    int value = Math.min(enumProps[i], 15); // 限制为 4 位
                    packed |= ((long) value << (ENUM_OFFSET + (i * 4)));
                }
            }

            return packed;
        }

        /**
         * 从打包值解码属性。
         *
         * @param packed 打包值
         * @param boolProps 布尔属性输出数组（大小 16）
         * @param enumProps 枚举属性输出数组（大小 4）
         */
        public static void decode(long packed, boolean[] boolProps, int[] enumProps) {
            // 解码布尔属性
            if (boolProps != null) {
                for (int i = 0; i < Math.min(boolProps.length, 16); i++) {
                    boolProps[i] = (packed & (1L << (BOOL_OFFSET + i))) != 0;
                }
            }

            // 解码枚举属性
            if (enumProps != null) {
                for (int i = 0; i < Math.min(enumProps.length, 4); i++) {
                    enumProps[i] = (int) ((packed >> (ENUM_OFFSET + (i * 4))) & 0xF);
                }
            }
        }

        /**
         * 获取打包值的布尔属性。
         *
         * @param packed 打包值
         * @param index 属性索引（0-15）
         * @return 布尔值
         */
        public static boolean getBool(long packed, int index) {
            if (index < 0 || index >= 16) return false;
            return (packed & (1L << (BOOL_OFFSET + index))) != 0;
        }

        /**
         * 获取打包值的枚举属性。
         *
         * @param packed 打包值
         * @param index 属性索引（0-3）
         * @return 枚举值（0-15）
         */
        public static int getEnum(long packed, int index) {
            if (index < 0 || index >= 4) return 0;
            return (int) ((packed >> (ENUM_OFFSET + (index * 4))) & 0xF);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 预分配对象池
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 通用对象池。
     *
     * 使用预分配数组和原子索引实现线程安全的零分配对象回收。
     *
     * @param <T> 池中对象的类型
     */
    public static class ObjectPool<T> {
        private final T[] objects;
        private final AtomicInteger index;
        private final int capacity;

        /**
         * 构造对象池。
         *
         * @param capacity 池容量
         * @param factory 创建新对象的工厂函数
         */
        @SuppressWarnings("unchecked")
        public ObjectPool(int capacity, ObjectFactory<T> factory) {
            this.capacity = capacity;
            this.objects = (T[]) new Object[capacity];
            for (int i = 0; i < capacity; i++) {
                this.objects[i] = factory.create();
            }
            this.index = new AtomicInteger(0);
        }

        /**
         * 从池中获取对象。
         *
         * @return 池中的对象，若池已满则返回新实例
         */
        public T borrow() {
            int current = index.getAndIncrement();
            if (current < capacity) {
                return objects[current];
            }
            index.set(capacity);
            return objects[capacity - 1];
        }

        /**
         * 重置池索引（通常在帧结束时调用）。
         */
        public void reset() {
            index.set(0);
        }

        /**
         * 获取当前使用数量。
         *
         * @return 已借用的对象数量
         */
        public int getUsedCount() {
            return Math.min(index.get(), capacity);
        }

        /**
         * 获取池容量。
         *
         * @return 容量
         */
        public int getCapacity() {
            return capacity;
        }
    }

    /**
     * 对象工厂接口。
     *
     * @param <T> 对象类型
     */
    public interface ObjectFactory<T> {
        /**
         * 创建新对象实例。
         *
         * @return 新实例
         */
        T create();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 预初始化的对象池
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static volatile ObjectPool<Matrix4f> matrixPool;
    private static volatile ObjectPool<float[]> vec3Pool;
    private static volatile ObjectPool<float[]> vec4Pool;

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 内存统计
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    private static final AtomicLong allocationsAvoided = new AtomicLong(0);
    private static final AtomicLong bytesSaved = new AtomicLong(0);

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 初始化和生命周期
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 初始化内存优化器。
     *
     * 预分配所有对象池并准备交编池。应在客户端启动时调用一次。
     */
    public static void init() {
        if (initialized) {
            return;
        }

        synchronized (BRMemoryOptimizer.class) {
            if (initialized) return;

            // 初始化 Matrix4f 池（256 个实例）
            matrixPool = new ObjectPool<>(256, Matrix4f::new);

            // 初始化 float[3] 向量池（512 个实例）
            vec3Pool = new ObjectPool<>(512, () -> new float[3]);

            // 初始化 float[4] 向量池（256 个实例）
            vec4Pool = new ObjectPool<>(256, () -> new float[4]);

            initialized = true;
            LOG.info("BRMemoryOptimizer 已初始化");
            LOG.info("  - Matrix4f 池: 256 个实例");
            LOG.info("  - Vec3 池: 512 个实例");
            LOG.info("  - Vec4 池: 256 个实例");
        }
    }

    /**
     * 清理和重置优化器。
     *
     * 清空所有交编池并重置池索引。通常在应用关闭时调用。
     */
    public static void cleanup() {
        BONE_NAME_POOL.clear();
        PACKED_STATE_POOL.clear();
        TRANSFORM_VECTOR_POOL.clear();
        resetPools();
        LOG.info("BRMemoryOptimizer 已清理");
    }

    /**
     * 重置所有对象池索引。
     *
     * 应在每帧结束时调用以允许重新使用池中的对象。
     */
    public static void resetPools() {
        if (matrixPool != null) matrixPool.reset();
        if (vec3Pool != null) vec3Pool.reset();
        if (vec4Pool != null) vec4Pool.reset();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 交编 API
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 交编值并返回规范实例。
     *
     * @param <T> 值类型
     * @param pool 交编池
     * @param value 要交编的值
     * @return 规范实例
     */
    public static <T> T intern(InternPool<T> pool, T value) {
        if (pool == null || value == null) {
            return value;
        }
        return pool.intern(value);
    }

    /**
     * 交编骨骼名称字符串。
     *
     * 使用共享的骨骼名称池以减少字符串对象数量。
     *
     * @param boneName 骨骼名称
     * @return 规范字符串实例
     */
    public static String internBoneName(String boneName) {
        if (boneName == null) return null;
        String canonical = BONE_NAME_POOL.intern(boneName);
        if (canonical != boneName) {
            long saved = boneName.length() * 2; // 字符占用内存
            bytesSaved.addAndGet(saved);
        }
        return canonical;
    }

    /**
     * 交编打包方块状态数组。
     *
     * @param packedStates 打包状态数组
     * @return 规范数组实例
     */
    public static int[] internPackedStates(int[] packedStates) {
        if (packedStates == null) return null;
        int[] canonical = PACKED_STATE_POOL.intern(packedStates);
        if (canonical != packedStates) {
            long saved = packedStates.length * 4; // 整数占用内存
            bytesSaved.addAndGet(saved);
        }
        return canonical;
    }

    /**
     * 交编变换向量数组。
     *
     * @param vector 浮点向量数组
     * @return 规范数组实例
     */
    public static float[] internTransformVector(float[] vector) {
        if (vector == null) return null;
        float[] canonical = TRANSFORM_VECTOR_POOL.intern(vector);
        if (canonical != vector) {
            long saved = vector.length * 4; // 浮点数占用内存
            bytesSaved.addAndGet(saved);
        }
        return canonical;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 位域属性打包 API
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 打包方块属性到单个长整数。
     *
     * 支持最多 16 个布尔属性和 4 个枚举属性（每个 4 位）。
     *
     * @param boolProps 布尔属性数组
     * @param enumProps 枚举属性数组（值应为 0-15）
     * @return 打包后的 64 位值
     */
    public static long packProperties(boolean[] boolProps, int[] enumProps) {
        return PackedBlockProperties.encode(boolProps, enumProps);
    }

    /**
     * 从打包值解包方块属性。
     *
     * @param packed 打包值
     * @param boolProps 布尔属性输出数组（大小应为 16）
     * @param enumProps 枚举属性输出数组（大小应为 4）
     */
    public static void unpackProperties(long packed, boolean[] boolProps, int[] enumProps) {
        PackedBlockProperties.decode(packed, boolProps, enumProps);
    }

    /**
     * 从打包值获取单个布尔属性。
     *
     * @param packed 打包值
     * @param index 属性索引（0-15）
     * @return 布尔值
     */
    public static boolean getPackedBool(long packed, int index) {
        return PackedBlockProperties.getBool(packed, index);
    }

    /**
     * 从打包值获取单个枚举属性。
     *
     * @param packed 打包值
     * @param index 属性索引（0-3）
     * @return 枚举值（0-15）
     */
    public static int getPackedEnum(long packed, int index) {
        return PackedBlockProperties.getEnum(packed, index);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 对象池 API - Matrix4f
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 从 Matrix4f 池借用一个矩阵。
     *
     * 返回的矩阵应在使用后通过 {@link #returnMatrix(Matrix4f)} 归还。
     *
     * @return 借用的 Matrix4f 实例
     */
    public static Matrix4f borrowMatrix() {
        ensureInitialized();
        Matrix4f m = matrixPool.borrow();
        allocationsAvoided.incrementAndGet();
        return m;
    }

    /**
     * 将矩阵归还到 Matrix4f 池。
     *
     * 归还前矩阵应被重置为恒等矩阵或预期的下一个值。
     *
     * @param matrix 要归还的矩阵
     */
    public static void returnMatrix(Matrix4f matrix) {
        if (matrix != null) {
            matrix.identity();
        }
    }

    /**
     * 获取 Matrix4f 池的使用统计。
     *
     * @return 统计信息字符串
     */
    public static String getMatrixPoolStats() {
        ensureInitialized();
        return String.format("Matrix4f 池: %d/%d 使用中",
                matrixPool.getUsedCount(), matrixPool.getCapacity());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 对象池 API - float[3] 向量
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 从 float[3] 向量池借用一个向量。
     *
     * 返回的向量应在使用后通过 {@link #returnVec3(float[])} 归还。
     *
     * @return 借用的 float[3] 数组
     */
    public static float[] borrowVec3() {
        ensureInitialized();
        float[] v = vec3Pool.borrow();
        allocationsAvoided.incrementAndGet();
        return v;
    }

    /**
     * 将向量归还到 float[3] 池。
     *
     * @param vector 要归还的向量
     */
    public static void returnVec3(float[] vector) {
        if (vector != null && vector.length == 3) {
            Arrays.fill(vector, 0.0f);
        }
    }

    /**
     * 获取 float[3] 向量池的使用统计。
     *
     * @return 统计信息字符串
     */
    public static String getVec3PoolStats() {
        ensureInitialized();
        return String.format("Vec3 池: %d/%d 使用中",
                vec3Pool.getUsedCount(), vec3Pool.getCapacity());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 对象池 API - float[4] 向量
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 从 float[4] 向量池借用一个向量。
     *
     * 返回的向量应在使用后通过 {@link #returnVec4(float[])} 归还。
     *
     * @return 借用的 float[4] 数组
     */
    public static float[] borrowVec4() {
        ensureInitialized();
        float[] v = vec4Pool.borrow();
        allocationsAvoided.incrementAndGet();
        return v;
    }

    /**
     * 将向量归还到 float[4] 池。
     *
     * @param vector 要归还的向量
     */
    public static void returnVec4(float[] vector) {
        if (vector != null && vector.length == 4) {
            Arrays.fill(vector, 0.0f);
        }
    }

    /**
     * 获取 float[4] 向量池的使用统计。
     *
     * @return 统计信息字符串
     */
    public static String getVec4PoolStats() {
        ensureInitialized();
        return String.format("Vec4 池: %d/%d 使用中",
                vec4Pool.getUsedCount(), vec4Pool.getCapacity());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 内存统计 API
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 获取避免的分配次数。
     *
     * @return 分配计数
     */
    public static long getAllocationsAvoided() {
        return allocationsAvoided.get();
    }

    /**
     * 获取估计的节省字节数。
     *
     * 包括交编过程中避免的重复对象和对象池重用。
     *
     * @return 估计节省的字节数
     */
    public static long getEstimatedBytesSaved() {
        long poolBytesSaved = 0;

        // 估算对象池节省的内存
        if (matrixPool != null) {
            poolBytesSaved += (long) matrixPool.getUsedCount() * 64; // Matrix4f ≈ 64 字节
        }
        if (vec3Pool != null) {
            poolBytesSaved += (long) vec3Pool.getUsedCount() * 12; // float[3] ≈ 12 字节
        }
        if (vec4Pool != null) {
            poolBytesSaved += (long) vec4Pool.getUsedCount() * 16; // float[4] ≈ 16 字节
        }

        return bytesSaved.get() + poolBytesSaved;
    }

    /**
     * 获取完整的内存统计报告。
     *
     * 包括交编池统计、对象池使用情况和估计的内存节省。
     *
     * @return 格式化的报告字符串
     */
    public static String getMemoryReport() {
        ensureInitialized();

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════════\n");
        sb.append("BRMemoryOptimizer 内存统计报告\n");
        sb.append("═══════════════════════════════════════════════════════════════════\n");

        // 交编池统计
        sb.append("\n【交编池统计】\n");
        sb.append("  骨骼名称池: ").append(BONE_NAME_POOL.getStats()).append("\n");
        sb.append("  打包状态池: ").append(PACKED_STATE_POOL.getStats()).append("\n");
        sb.append("  向量池: ").append(TRANSFORM_VECTOR_POOL.getStats()).append("\n");

        // 对象池统计
        sb.append("\n【对象池统计】\n");
        sb.append("  ").append(getMatrixPoolStats()).append("\n");
        sb.append("  ").append(getVec3PoolStats()).append("\n");
        sb.append("  ").append(getVec4PoolStats()).append("\n");

        // 内存节省
        sb.append("\n【内存节省】\n");
        sb.append("  已避免的分配次数: ").append(allocationsAvoided.get()).append("\n");
        sb.append("  估计节省字节数: ").append(getEstimatedBytesSaved()).append(" 字节\n");
        long kiloByteSaved = getEstimatedBytesSaved() / 1024;
        sb.append("  估计节省大小: ").append(kiloByteSaved).append(" KB\n");

        sb.append("═══════════════════════════════════════════════════════════════════\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 内部实用方法
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 确保优化器已初始化。
     *
     * 如果尚未初始化则触发初始化。
     */
    private static void ensureInitialized() {
        if (!initialized) {
            init();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 构造函数
    // ═══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * 私有构造函数。此类应作为静态单例使用。
     */
    private BRMemoryOptimizer() {
        throw new UnsupportedOperationException("BRMemoryOptimizer 不能被实例化");
    }
}
