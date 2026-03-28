package com.blockreality.api.spi;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.physics.CableElement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 單元測試：DefaultCableManager — XPBD 纜索管理器
 *
 * 測試範圍：
 *   1. normalizePair 雙向對稱性
 *   2. attachCable / detachCable CRUD
 *   3. getCablesAt 端點索引正確性
 *   4. 重複附加相同纜索（最新覆蓋語意）
 *   5. removeChunkCables chunk 邊界行為
 *   6. getCableCount / getCable 一致性
 *   7. null 輸入防禦
 *
 * 注意：tickCables（XPBD 模擬）需要 Minecraft ClassLoader，
 * 留待整合測試環境使用，此處不覆蓋。
 */
@DisplayName("DefaultCableManager — XPBD Cable Management")
class DefaultCableManagerTest {

    private DefaultCableManager manager;

    // chunk(0,0): x=[0,15], z=[0,15]
    private static final BlockPos A = new BlockPos(0, 64, 0);   // chunk(0,0)
    private static final BlockPos B = new BlockPos(5, 64, 0);   // chunk(0,0)
    private static final BlockPos C = new BlockPos(10, 64, 0);  // chunk(0,0)
    // cross-chunk: A-side in chunk(0,0), D-side in chunk(1,0)
    private static final BlockPos D = new BlockPos(20, 64, 0);  // chunk(1,0)

    @BeforeEach
    void setUp() {
        manager = new DefaultCableManager();
    }

    // ═══════════════════════════════════════════════════════
    //  normalizePair — 雙向對稱
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("normalizePair()")
    class NormalizePairTests {

        @Test
        @DisplayName("正向與反向產生相同 key")
        void symmetry() {
            String forward  = DefaultCableManager.normalizePair(A, B);
            String backward = DefaultCableManager.normalizePair(B, A);
            assertEquals(forward, backward,
                "normalizePair(A,B) 必須等於 normalizePair(B,A)");
        }

        @Test
        @DisplayName("排序後較小座標在 key 前半")
        void smallerFirst() {
            // A=(0,64,0) 比 B=(5,64,0) 小（compareTo）
            String key = DefaultCableManager.normalizePair(B, A);
            assertTrue(key.startsWith("0,64,0"),
                "較小座標的 pos 應排在 key 前半");
        }

        @Test
        @DisplayName("自環（相同端點）不崩潰")
        void selfLoop() {
            assertDoesNotThrow(() -> DefaultCableManager.normalizePair(A, A));
        }

        @Test
        @DisplayName("三個不同端點對產生三個不同 key")
        void uniqueKeys() {
            String k1 = DefaultCableManager.normalizePair(A, B);
            String k2 = DefaultCableManager.normalizePair(B, C);
            String k3 = DefaultCableManager.normalizePair(A, C);
            assertNotEquals(k1, k2);
            assertNotEquals(k2, k3);
            assertNotEquals(k1, k3);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  CRUD — attachCable / detachCable
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("attachCable() / detachCable()")
    class CrudTests {

        @Test
        @DisplayName("附加纜索後 getCableCount() 遞增")
        void attachIncreasesCount() {
            assertEquals(0, manager.getCableCount());
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            assertEquals(1, manager.getCableCount());
        }

        @Test
        @DisplayName("attachCable 回傳非 null CableElement")
        void attachReturnsElement() {
            CableElement el = manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            assertNotNull(el);
        }

        @Test
        @DisplayName("重複附加相同端點（反向）不增加 count（覆蓋語義）")
        void attachReverseIsReplace() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.attachCable(B, A, DefaultMaterial.STEEL);  // 反向，相同 normalized key
            assertEquals(1, manager.getCableCount(),
                "相同端點對的纜索應覆蓋舊值，count 保持 1");
        }

        @Test
        @DisplayName("detachCable 移除後 count 歸零")
        void detachDecreasesCount() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.detachCable(A, B);
            assertEquals(0, manager.getCableCount());
        }

        @Test
        @DisplayName("detach 不存在的纜索不拋異常")
        void detachNonexistentNoThrow() {
            assertDoesNotThrow(() -> manager.detachCable(A, B));
        }

        @Test
        @DisplayName("附加後可用反向端點 detach")
        void detachByReverseEndpoints() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.detachCable(B, A);  // 反向
            assertEquals(0, manager.getCableCount(),
                "應該能用反向端點移除纜索");
        }

        @Test
        @DisplayName("null 端點拋 NullPointerException")
        void nullEndpointThrows() {
            assertThrows(NullPointerException.class,
                () -> manager.attachCable(null, B, DefaultMaterial.CONCRETE));
            assertThrows(NullPointerException.class,
                () -> manager.attachCable(A, null, DefaultMaterial.CONCRETE));
        }

        @Test
        @DisplayName("null 材料拋 NullPointerException")
        void nullMaterialThrows() {
            assertThrows(NullPointerException.class,
                () -> manager.attachCable(A, B, null));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  getCable — 直接查詢
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("getCable()")
    class GetCableTests {

        @Test
        @DisplayName("attach 後 getCable 回傳非 null")
        void getCableAfterAttach() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            assertNotNull(manager.getCable(A, B));
            assertNotNull(manager.getCable(B, A), "反向查詢也應命中");
        }

        @Test
        @DisplayName("detach 後 getCable 回傳 null")
        void getCableAfterDetach() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.detachCable(A, B);
            assertNull(manager.getCable(A, B));
        }

        @Test
        @DisplayName("不存在的纜索回傳 null（不拋）")
        void getNonexistentReturnsNull() {
            assertNull(manager.getCable(A, B));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  端點索引 — getCablesAt
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("getCablesAt() — 端點索引正確性")
    class EndpointIndexTests {

        @Test
        @DisplayName("附加後兩端點都能查到此纜索")
        void bothEndpointsIndexed() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);

            Set<CableElement> fromA = manager.getCablesAt(A);
            Set<CableElement> fromB = manager.getCablesAt(B);

            assertEquals(1, fromA.size(), "端點 A 應有 1 條纜索");
            assertEquals(1, fromB.size(), "端點 B 應有 1 條纜索");
            // 兩端查到的 CableElement 應是同一個物件（normalized key → 同一 state）
            assertEquals(fromA.iterator().next(), fromB.iterator().next(),
                "從兩端點查到的 CableElement 應相同");
        }

        @Test
        @DisplayName("多條纜索共用同一端點時都能查到")
        void multiCableOnSameEndpoint() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.attachCable(A, C, DefaultMaterial.STEEL);

            Set<CableElement> cables = manager.getCablesAt(A);
            assertEquals(2, cables.size(),
                "端點 A 應有 2 條纜索");
        }

        @Test
        @DisplayName("detach 後端點索引也被清理")
        void indexCleanedOnDetach() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.detachCable(A, B);

            assertTrue(manager.getCablesAt(A).isEmpty(), "detach 後端點 A 索引應清空");
            assertTrue(manager.getCablesAt(B).isEmpty(), "detach 後端點 B 索引應清空");
        }

        @Test
        @DisplayName("未附加任何纜索時查詢回傳空集合（不拋 exception）")
        void queryEmptyIsEmpty() {
            Set<CableElement> result = manager.getCablesAt(A);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null pos 不拋異常，回傳空集合")
        void nullPosReturnsEmpty() {
            Set<CableElement> result = manager.getCablesAt(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Chunk 清理
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("removeChunkCables() — chunk 邊界行為")
    class ChunkCleanupTests {

        @Test
        @DisplayName("chunk 內纜索被清除")
        void removesIntraChunkCable() {
            // A=(0,64,0), B=(5,64,0) 都在 chunk(0,0)
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            int removed = manager.removeChunkCables(new ChunkPos(0, 0));
            assertTrue(removed >= 1, "chunk(0,0) 內的 A-B 纜索應被移除");
            assertEquals(0, manager.getCableCount());
        }

        @Test
        @DisplayName("跨 chunk 纜索不被清除")
        void crossChunkCablePreserved() {
            // A 在 chunk(0,0)，D 在 chunk(1,0)
            manager.attachCable(A, D, DefaultMaterial.CONCRETE);
            manager.removeChunkCables(new ChunkPos(0, 0));
            // 跨 chunk 纜索應被保留
            assertEquals(1, manager.getCableCount(),
                "跨 chunk 的 A-D 纜索不應被移除");
        }

        @Test
        @DisplayName("清空不存在纜索的 chunk 回傳 0")
        void emptyChunkReturnsZero() {
            int removed = manager.removeChunkCables(new ChunkPos(99, 99));
            assertEquals(0, removed);
        }

        @Test
        @DisplayName("chunk 內外同時有纜索：只清 chunk 內的")
        void mixedChunkRemoveOnlyIntra() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);    // chunk(0,0) 內
            manager.attachCable(A, D, DefaultMaterial.STEEL);       // 跨 chunk
            assertEquals(2, manager.getCableCount());

            int removed = manager.removeChunkCables(new ChunkPos(0, 0));
            assertTrue(removed >= 1);
            // A-D 仍在
            assertNotNull(manager.getCable(A, D),
                "跨 chunk 纜索應保留");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  count 一致性
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("getCableCount() 一致性")
    class CountConsistencyTests {

        @Test
        @DisplayName("多條纜索後 count 正確")
        void multipleAttachCount() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.attachCable(B, C, DefaultMaterial.STEEL);
            assertEquals(2, manager.getCableCount());
        }

        @Test
        @DisplayName("全部 detach 後 count 歸零")
        void attachDetachReturnsToZero() {
            manager.attachCable(A, B, DefaultMaterial.CONCRETE);
            manager.attachCable(B, C, DefaultMaterial.STEEL);
            manager.detachCable(A, B);
            manager.detachCable(B, C);
            assertEquals(0, manager.getCableCount());
        }
    }
}
