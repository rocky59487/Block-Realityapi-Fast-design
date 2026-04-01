package com.blockreality.api.spi;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMaterialRegistry 多執行緒並發安全性測試 — M6
 *
 * 測試策略：
 *   驗證 ModuleRegistry.getMaterialRegistry() 的實作在高並發場景下：
 *   1. 並發讀取不發生 ConcurrentModificationException
 *   2. 並發寫入不發生資料競爭或死鎖
 *   3. 讀寫混合不導致不一致狀態
 *   4. 預載的 DefaultMaterial 在並發下始終可查詢
 *
 * 實作基礎：IMaterialRegistry 的預設實作（ModuleRegistry 內部）
 *   使用 ConcurrentHashMap，本測試驗證其正確使用。
 */
@DisplayName("IMaterialRegistry — M6 多執行緒並發安全性")
class MaterialRegistryConcurrencyTest {

    private IMaterialRegistry registry;

    @BeforeEach
    void setUp() {
        registry = ModuleRegistry.getMaterialRegistry();
        assertNotNull(registry, "getMaterialRegistry() 不應回傳 null");
    }

    // ═══════════════════════════════════════════════════════
    //  基本並發讀取測試
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("並發讀取 — 不應拋出例外")
    class ConcurrentReadTests {

        @Test
        @DisplayName("多執行緒並發讀取預設材料不拋例外")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void concurrentReadNoException() throws InterruptedException {
            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> firstError = new AtomicReference<>(null);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await(); // 所有執行緒同時開始
                            // 輪流查詢所有預設材料
                            for (DefaultMaterial mat : DefaultMaterial.values()) {
                                Optional<RMaterial> result = registry.getMaterial(mat.getMaterialId());
                                assertTrue(result.isPresent(),
                                    "預設材料 " + mat.name() + " 在並發讀取下應始終可查詢");
                            }
                        } catch (Throwable t) {
                            firstError.compareAndSet(null, t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown(); // 同時釋放所有執行緒
                assertTrue(doneLatch.await(4, TimeUnit.SECONDS),
                    "所有讀取執行緒應在 4 秒內完成");
            } finally {
                executor.shutdown();
            }

            assertNull(firstError.get(),
                "並發讀取不應拋出任何例外，但捕獲到: " +
                (firstError.get() != null ? firstError.get().getMessage() : "none"));
        }

        @Test
        @DisplayName("並發 getAllMaterialIds() 不拋例外")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void concurrentGetAllIdsNoException() throws InterruptedException {
            int threadCount = 15;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < 10; j++) {
                                var ids = registry.getAllMaterialIds();
                                assertNotNull(ids, "getAllMaterialIds() 不應回傳 null");
                                assertFalse(ids.isEmpty(),
                                    "預設材料已預載，getAllMaterialIds() 不應為空");
                            }
                        } catch (Throwable t) {
                            errorCount.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(4, TimeUnit.SECONDS));
            } finally {
                executor.shutdown();
            }

            assertEquals(0, errorCount.get(),
                "並發 getAllMaterialIds() 不應有任何執行緒拋出例外");
        }

        @Test
        @DisplayName("並發 getCount() 在無寫入下回傳一致值")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void concurrentGetCountConsistent() throws InterruptedException {
            int baseCount = registry.getCount();
            assertTrue(baseCount > 0, "預載後的 registry 應有材料");

            int threadCount = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            List<Integer> counts = new ArrayList<>();
            Object lock = new Object();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            int c = registry.getCount();
                            synchronized (lock) {
                                counts.add(c);
                            }
                        } catch (Throwable t) {
                            // 忽略，由 doneLatch 後驗證
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(4, TimeUnit.SECONDS));
            } finally {
                executor.shutdown();
            }

            assertEquals(threadCount, counts.size(), "所有執行緒應成功回傳 count");
            // 無寫入時所有讀取應一致
            counts.forEach(c ->
                assertTrue(c >= baseCount,
                    "並發讀取的 count 不應小於初始值 " + baseCount + "，得到 " + c));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  並發寫入測試
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("並發寫入 — 不應死鎖或丟失資料")
    class ConcurrentWriteTests {

        @Test
        @DisplayName("多執行緒並發 registerMaterial() 不拋例外")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void concurrentRegisterNoException() throws InterruptedException {
            int threadCount = 16;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicReference<Throwable> firstError = new AtomicReference<>(null);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    final int threadId = i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            // 每個執行緒寫入唯一材料 ID
                            String id = "concurrency_test_material_" + threadId;
                            registry.registerMaterial(id, createTestMaterial(id, threadId * 10.0));
                        } catch (Throwable t) {
                            firstError.compareAndSet(null, t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(4, TimeUnit.SECONDS),
                    "所有寫入執行緒應在 4 秒內完成");
            } finally {
                executor.shutdown();
            }

            assertNull(firstError.get(),
                "並發寫入不應拋出例外：" +
                (firstError.get() != null ? firstError.get().getMessage() : "none"));

            // 驗證所有寫入都已持久化
            for (int i = 0; i < threadCount; i++) {
                String id = "concurrency_test_material_" + i;
                Optional<RMaterial> found = registry.getMaterial(id);
                assertTrue(found.isPresent(),
                    "並發寫入的材料 " + id + " 應可被查詢到（無資料丟失）");
            }
        }

        @Test
        @DisplayName("並發寫入相同 ID 不導致 NPE 或損壞狀態")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void concurrentWriteSameIdNoCorruption() throws InterruptedException {
            String sharedId = "concurrent_overwrite_test";
            int threadCount = 12;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    final int strength = (i + 1) * 5;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            // 所有執行緒寫同一個 ID（覆寫競爭）
                            registry.registerMaterial(sharedId, createTestMaterial(sharedId, strength));
                        } catch (Throwable t) {
                            errorCount.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(4, TimeUnit.SECONDS));
            } finally {
                executor.shutdown();
            }

            assertEquals(0, errorCount.get(),
                "並發覆寫相同 ID 不應拋出例外");

            // 最終狀態應仍可查詢（不管哪個執行緒贏得最後寫入）
            Optional<RMaterial> finalResult = registry.getMaterial(sharedId);
            assertTrue(finalResult.isPresent(),
                "並發覆寫後材料仍應可查詢（register is not destructive）");
            assertTrue(finalResult.get().getRcomp() > 0,
                "最終材料的 Rcomp 應為正值");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  讀寫混合測試
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("讀寫混合 — 不死鎖、不 ConcurrentModificationException")
    class ReadWriteMixedTests {

        @Test
        @DisplayName("讀寫混合並發不拋 ConcurrentModificationException")
        @Timeout(value = 8, unit = TimeUnit.SECONDS)
        void readWriteMixedNoConcurrentModification() throws InterruptedException {
            int readerCount = 10;
            int writerCount = 5;
            int totalThreads = readerCount + writerCount;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalThreads);
            AtomicReference<Throwable> firstError = new AtomicReference<>(null);

            ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
            try {
                // 讀取者：持續查詢所有材料 ID
                for (int i = 0; i < readerCount; i++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < 20; j++) {
                                var ids = registry.getAllMaterialIds();
                                for (String id : ids) {
                                    registry.getMaterial(id); // 讀取每個材料
                                }
                            }
                        } catch (Throwable t) {
                            firstError.compareAndSet(null, t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                // 寫入者：持續寫入新材料
                for (int i = 0; i < writerCount; i++) {
                    final int writerId = i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < 10; j++) {
                                String id = "rw_mixed_" + writerId + "_" + j;
                                registry.registerMaterial(id, createTestMaterial(id, 10.0 * j));
                            }
                        } catch (Throwable t) {
                            firstError.compareAndSet(null, t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(7, TimeUnit.SECONDS),
                    "讀寫混合測試應在 7 秒內完成（無死鎖）");
            } finally {
                executor.shutdown();
            }

            assertNull(firstError.get(),
                "讀寫混合並發不應拋出例外，捕獲到: " +
                (firstError.get() != null ? firstError.get().getClass().getSimpleName() + ": " +
                firstError.get().getMessage() : "none"));
        }

        @RepeatedTest(3)
        @DisplayName("DefaultMaterial 預載材料在讀寫混合時始終可查詢（重複 3 次）")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void defaultMaterialsAlwaysQueryable() throws InterruptedException {
            int writerCount = 4;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(writerCount + 1);
            AtomicInteger queryFailures = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(writerCount + 1);
            try {
                // 一個讀取者持續查詢預設材料
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 50; i++) {
                            for (DefaultMaterial mat : DefaultMaterial.values()) {
                                Optional<RMaterial> result = registry.getMaterial(mat.getMaterialId());
                                if (result.isEmpty()) {
                                    queryFailures.incrementAndGet();
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });

                // 多個寫入者干擾
                for (int i = 0; i < writerCount; i++) {
                    final int id = i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < 20; j++) {
                                registry.registerMaterial(
                                    "interference_" + id + "_" + j,
                                    createTestMaterial("interference_" + id + "_" + j, 5.0)
                                );
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(4, TimeUnit.SECONDS));
            } finally {
                executor.shutdown();
            }

            assertEquals(0, queryFailures.get(),
                "並發寫入時，預設材料的查詢不應有任何失敗（ConcurrentHashMap 保證讀取可見性）");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  canPair() 並發安全性
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("canPair() — 並發調用不產生例外")
    class CanPairConcurrencyTests {

        @Test
        @DisplayName("並發 canPair() 不拋例外")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void concurrentCanPairNoException() throws InterruptedException {
            int threadCount = 12;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            // 測試所有 DefaultMaterial 組合
                            for (DefaultMaterial a : DefaultMaterial.values()) {
                                for (DefaultMaterial b : DefaultMaterial.values()) {
                                    // canPair() 不應拋出任何例外，回傳值 true/false 皆可
                                    registry.canPair(a, b);
                                }
                            }
                        } catch (Throwable t) {
                            errorCount.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertTrue(doneLatch.await(4, TimeUnit.SECONDS));
            } finally {
                executor.shutdown();
            }

            assertEquals(0, errorCount.get(),
                "並發 canPair() 調用不應拋出例外");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Helper
    // ═══════════════════════════════════════════════════════

    /**
     * 建立用於測試的匿名 RMaterial 實作。
     */
    private RMaterial createTestMaterial(String id, double rcomp) {
        return new RMaterial() {
            @Override public double getRcomp()  { return rcomp; }
            @Override public double getRtens()  { return rcomp * 0.8; }
            @Override public double getRshear() { return rcomp * 0.5; }
            @Override public double getDensity(){ return 2400.0; }
            @Override public String getMaterialId() { return id; }
        };
    }
}
