package com.blockreality.api.client.render.rt;

import com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BROpacityMicromap — Opacity Micromap (OMM) 管理器。
 *
 * <h3>技術背景</h3>
 * <p>{@code VK_EXT_opacity_micromap}（Ada SM 8.9+）允許將方塊的透明度資訊
 * 以 1/4/16-state micro-triangle 格式燒錄至 BLAS，讓 RT 核心在 BVH 遍歷時
 * 直接跳過不透明 micro-triangle 的 any-hit shader 呼叫，
 * 降低 any-hit 觸發頻率約 30-70%（依場景透明幾何密度而定）。
 *
 * <h3>OMM 狀態定義（VkOpacityMicromapStateEXT）</h3>
 * <pre>
 * TRANSPARENT (0) — ray 直接穿透（不觸發 any-hit）
 * OPAQUE      (1) — ray 命中（跳過 any-hit，直接呼叫 closest-hit）
 * UNKNOWN_TRANSPARENT (2) — 呼叫 any-hit 決定
 * UNKNOWN_OPAQUE      (3) — 呼叫 any-hit 決定
 * </pre>
 *
 * <h3>目前實作狀態（Phase 1 — RT-1-4）</h3>
 * <p>由於當前 BLAS 幾何使用 AABB（非 triangle），
 * {@code VK_EXT_opacity_micromap} 的 micro-triangle 路徑尚不適用。
 * Phase 1 的 OMM 支援以 <b>等效最佳化</b> 方式實作：
 * <ul>
 *   <li>對不含透明方塊的 section/cluster，在 BLAS 建構時設定
 *       {@code VK_GEOMETRY_OPAQUE_BIT_KHR}，跳過 any-hit shader</li>
 *   <li>此類別追蹤每個 section 的透明狀態，並向
 *       {@link com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder}
 *       提供查詢介面</li>
 * </ul>
 *
 * <h3>Phase 3 完整 OMM 整合計畫</h3>
 * <p>當 LOD 0 改為 triangle geometry 後，此類別將負責：
 * <ol>
 *   <li>{@link #buildOMMArray(long, byte[])} — 從方塊類型資料生成 OMM state array</li>
 *   <li>呼叫 {@code vkCreateMicromapEXT} 建立 VkMicromapEXT</li>
 *   <li>將 OMM handle 附加至 BLAS 建構（{@code VkAccelerationStructureTrianglesOpacityMicromapEXT}）</li>
 *   <li>啟用 {@code VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_OPACITY_MICROMAP_UPDATE_BIT_EXT}
 *       供增量更新</li>
 * </ol>
 *
 * @see BRAdaRTConfig#hasOMM()
 * @see com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder#markSectionTransparent
 * @see BRVulkanBVH#buildBLASOpaque
 */
@OnlyIn(Dist.CLIENT)
public final class BROpacityMicromap {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-OpacityMicromap");

    // ════════════════════════════════════════════════════════════════════════
    //  OMM State 常數（對應 VkOpacityMicromapStateEXT）
    // ════════════════════════════════════════════════════════════════════════

    /** OMM 狀態：完全透明（0） — ray 穿透，不觸發 any-hit */
    public static final byte OMM_STATE_TRANSPARENT         = 0;
    /** OMM 狀態：完全不透明（1） — ray 命中，跳過 any-hit */
    public static final byte OMM_STATE_OPAQUE              = 1;
    /** OMM 狀態：未知（透明側）（2） — 觸發 any-hit 決定 */
    public static final byte OMM_STATE_UNKNOWN_TRANSPARENT = 2;
    /** OMM 狀態：未知（不透明側）（3） — 觸發 any-hit 決定 */
    public static final byte OMM_STATE_UNKNOWN_OPAQUE      = 3;

    // ════════════════════════════════════════════════════════════════════════
    //  OMM 格式常數（VkOpacityMicromapFormatEXT）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 2-state OMM format：每 micro-triangle 1 bit（0=透明, 1=不透明）。
     * 最省記憶體，適用於無 alpha blending 的方塊（石頭、磚塊等）。
     */
    public static final int OMM_FORMAT_2_STATE  = 1; // VK_OPACITY_MICROMAP_FORMAT_2_STATE_EXT

    /**
     * 4-state OMM format：每 micro-triangle 2 bits（含 UNKNOWN_*）。
     * 適用於含 alpha-tested 幾何的方塊（葉片、草、柵欄等）。
     */
    public static final int OMM_FORMAT_4_STATE  = 2; // VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT

    /**
     * OMM subdivision level（micro-triangle 細分度）。
     * <ul>
     *   <li>Level 0 = 1 micro-triangle / triangle（等同 no OMM）</li>
     *   <li>Level 2 = 4 micro-triangles / triangle（1×1 block face 的合理密度）</li>
     *   <li>Level 4 = 16 micro-triangles / triangle（高精度 alpha test，記憶體更大）</li>
     * </ul>
     * 預設 2（每 quad face 分為 4 個 micro-triangle，平衡精度與記憶體）。
     */
    public static final int DEFAULT_SUBDIVISION_LEVEL = 2;

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BROpacityMicromap INSTANCE = new BROpacityMicromap();

    public static BROpacityMicromap getInstance() {
        return INSTANCE;
    }

    private BROpacityMicromap() {}

    // ════════════════════════════════════════════════════════════════════════
    //  透明方塊材料集合（靜態查詢表）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 需要 any-hit shader 的方塊類型（含 alpha test 或 alpha blend）。
     * 這些材料對應的 section 不能使用 {@code VK_GEOMETRY_OPAQUE_BIT_KHR}。
     *
     * <p>此集合由材料系統初始化時填充（{@link #registerTransparentMaterial(int)}），
     * 並在 section BLAS 建構時查詢（{@link #isTransparent(int)}）。
     */
    private final Set<Integer> transparentMaterialIds = ConcurrentHashMap.newKeySet();

    /**
     * sectionKey（由 {@link BRVulkanBVH#encodeSectionKey} 生成）→
     * 該 section 的 OMM 處理狀態。
     */
    private final ConcurrentHashMap<Long, OMMSectionState> sectionStates = new ConcurrentHashMap<>();

    /** 累計 OMM 跳過的 any-hit 呼叫次數（效能監控，近似值） */
    private final AtomicInteger ommAnyHitSkipCount = new AtomicInteger(0);

    /** 累計 OMM 觸發的 any-hit 呼叫次數（含 UNKNOWN_* 狀態） */
    private final AtomicInteger ommAnyHitTriggerCount = new AtomicInteger(0);

    // ════════════════════════════════════════════════════════════════════════
    //  Section OMM 狀態
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 單一 section 的 OMM 處理狀態。
     */
    public enum OMMSectionState {
        /** 不含透明方塊，使用 VK_GEOMETRY_OPAQUE_BIT_KHR（Phase 1 等效 OMM） */
        OPAQUE,
        /** 含透明方塊，使用標準 BLAS（觸發 any-hit） */
        HAS_TRANSPARENT,
        /** Phase 3：已生成完整 OMM array，附加至 BLAS */
        OMM_ATTACHED,
        /** Phase 3：OMM 生成失敗，回退至標準 BLAS */
        OMM_FALLBACK
    }

    // ════════════════════════════════════════════════════════════════════════
    //  公開 API — 材料系統整合
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 向 OMM 管理器註冊透明材料 ID。
     * 材料系統在初始化時呼叫（每種含 alpha 的材料）。
     *
     * @param materialId 材料 ID（0-255，對應 BlockTypeRegistry）
     */
    public void registerTransparentMaterial(int materialId) {
        if (materialId < 0 || materialId > 255) {
            LOGGER.warn("[OMM] Invalid materialId={} (must be 0-255)", materialId);
            return;
        }
        boolean added = transparentMaterialIds.add(materialId);
        if (added) {
            LOGGER.debug("[OMM] Registered transparent material: id={}", materialId);
        }
    }

    /**
     * 取消註冊透明材料（材料更新時使用）。
     *
     * @param materialId 材料 ID
     */
    public void unregisterTransparentMaterial(int materialId) {
        transparentMaterialIds.remove(materialId);
    }

    /**
     * 查詢指定材料 ID 是否需要 any-hit shader（即為透明材料）。
     *
     * @param materialId 材料 ID（0-255）
     * @return true = 需要 any-hit；false = 可使用 opaque 旗標
     */
    public boolean isTransparent(int materialId) {
        return transparentMaterialIds.contains(materialId);
    }

    /** @return 目前已註冊的透明材料數量 */
    public int getTransparentMaterialCount() {
        return transparentMaterialIds.size();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  公開 API — Section 狀態管理
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 通知 OMM 管理器某 section 的透明狀態（Phase 1 等效路徑）。
     *
     * <p>在 {@link com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder#markSectionTransparent}
     * 呼叫後，此方法更新對應 section 的 OMM 狀態並決定
     * 是否可以使用 {@code VK_GEOMETRY_OPAQUE_BIT_KHR}。
     *
     * @param sectionKey     {@link BRVulkanBVH#encodeSectionKey} 的輸出
     * @param hasTransparent 此 section 是否含透明方塊
     * @return 應使用的 {@link OMMSectionState}
     */
    public OMMSectionState onSectionUpdated(long sectionKey, boolean hasTransparent) {
        OMMSectionState state = hasTransparent
            ? OMMSectionState.HAS_TRANSPARENT
            : OMMSectionState.OPAQUE;

        sectionStates.put(sectionKey, state);

        if (!hasTransparent) {
            ommAnyHitSkipCount.incrementAndGet();
        } else {
            ommAnyHitTriggerCount.incrementAndGet();
        }

        return state;
    }

    /**
     * 查詢 section 的 OMM 狀態。
     *
     * @param sectionKey section key
     * @return OMM 狀態，若未追蹤則返回 {@link OMMSectionState#OPAQUE}（保守預設）
     */
    public OMMSectionState getSectionState(long sectionKey) {
        return sectionStates.getOrDefault(sectionKey, OMMSectionState.OPAQUE);
    }

    /**
     * section 卸載時清理追蹤資料。
     *
     * @param sectionKey section key
     */
    public void onSectionRemoved(long sectionKey) {
        sectionStates.remove(sectionKey);
    }

    /**
     * 清空所有 section 狀態（世界卸載時呼叫）。
     * 同時釋放所有 Phase 3 VkMicromapEXT 資源。
     */
    public void clear() {
        // Phase 3：釋放所有 VkMicromapEXT
        BROMMPhase3Builder.getInstance().cleanup();

        sectionStates.clear();
        ommAnyHitSkipCount.set(0);
        ommAnyHitTriggerCount.set(0);
        LOGGER.info("[OMM] All section OMM states cleared (Phase 3 micromaps released)");
    }

    /**
     * section 卸載時清理 Phase 3 micromap（若有）。
     *
     * @param sectionKey section key
     */
    public void onSectionRemovedWithCleanup(long sectionKey) {
        onSectionRemoved(sectionKey);
        BROMMPhase3Builder.getInstance().destroyMicromap(sectionKey);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Phase 3 完整 OMM 整合（P2-B）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Phase 1 相容入口：掃描 section 是否含透明方塊並更新狀態。
     *
     * <p>Phase 1：根據是否含透明方塊決定使用 opaque flag 或 any-hit。
     * Phase 3（LOD 0 triangle geometry 後）：請改用
     * {@link #buildOMMArrayPhase3(long, byte[], int[], int)} 並呼叫
     * {@link BROMMPhase3Builder#buildMicromap}。
     *
     * @param sectionKey   section key（用於快取查找）
     * @param blockTypes   section 中每個方塊的材料 ID（16×16×16 = 4096 元素）
     * @return null（Phase 1 行為），Phase 3 路徑透過 {@link #buildOMMArrayPhase3} 單獨呼叫
     */
    public byte[] buildOMMArray(long sectionKey, byte[] blockTypes) {
        if (!BRAdaRTConfig.hasOMM()) return null;
        if (blockTypes == null || blockTypes.length < 4096) {
            LOGGER.warn("[OMM] buildOMMArray: invalid blockTypes (sectionKey={})", sectionKey);
            return null;
        }

        boolean anyTransparent = false;
        for (byte matId : blockTypes) {
            if (isTransparent(Byte.toUnsignedInt(matId))) {
                anyTransparent = true;
                break;
            }
        }

        onSectionUpdated(sectionKey, anyTransparent);

        if (!anyTransparent) {
            LOGGER.debug("[OMM] Section {} fully opaque — using VK_GEOMETRY_OPAQUE_BIT_KHR", sectionKey);
        } else {
            LOGGER.debug("[OMM] Section {} has transparent blocks — will use any-hit (Phase 1) or OMM (Phase 3)", sectionKey);
        }
        // Phase 1 continues to return null; callers use opaque flag or any-hit path.
        // Phase 3 callers use buildOMMArrayPhase3().
        return null;
    }

    /**
     * Phase 3 完整 OMM 路徑：生成 OMM bit array 並建立 VkMicromapEXT。
     *
     * <p><b>前置條件：</b>LOD 0 已改為 triangle geometry，且
     * {@link BRAdaRTConfig#hasOMM()} == true。
     *
     * <p>OMM 格式：{@code VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT}，
     * subdivision level 1（4 micro-triangles/triangle）。
     *
     * @param sectionKey       section key
     * @param blockTypes       4096 bytes 材料 ID 陣列（16³）
     * @param triToBlockIdx    三角形 → 方塊索引映射（長度 = triangleCount）
     * @param triangleCount    LOD 0 mesh 的三角形數量
     * @return VkMicromapEXT handle（供 BLAS 建構附加），或 0L 若失敗
     */
    public long buildOMMArrayPhase3(long sectionKey, byte[] blockTypes,
                                     int[] triToBlockIdx, int triangleCount) {
        if (!BRAdaRTConfig.hasOMM()) return 0L;
        if (blockTypes == null || triToBlockIdx == null || triangleCount <= 0) {
            LOGGER.warn("[OMM] buildOMMArrayPhase3: invalid inputs (sectionKey={})", sectionKey);
            return 0L;
        }

        // 建立 blockTransparency 查詢表（256 entries）
        byte[] blockTransparency = buildTransparencyTable();

        // CPU-side OMM bit array 生成
        byte[] ommData = BROMMPhase3Builder.getInstance().buildOMMArrayCPU(
                triangleCount, triToBlockIdx, blockTypes, blockTransparency);
        if (ommData == null) {
            LOGGER.warn("[OMM] buildOMMArrayPhase3: CPU OMM generation returned null (sectionKey={})", sectionKey);
            return 0L;
        }

        // GPU micromap 建立
        long micromapHandle = BROMMPhase3Builder.getInstance()
                .buildMicromap(sectionKey, triangleCount, ommData);

        if (micromapHandle != 0L) {
            // 升級 section 狀態至 OMM_ATTACHED
            sectionStates.put(sectionKey, OMMSectionState.OMM_ATTACHED);
            LOGGER.info("[OMM] Phase 3 micromap attached for sectionKey={} (triCount={})",
                    sectionKey, triangleCount);
        } else {
            sectionStates.put(sectionKey, OMMSectionState.OMM_FALLBACK);
            LOGGER.warn("[OMM] Phase 3 micromap build failed — using any-hit fallback (sectionKey={})",
                    sectionKey);
        }

        return micromapHandle;
    }

    /**
     * 建立透明度查詢表（256 entries），從已註冊的透明材料 ID 集合生成。
     *
     * <p>編碼：0=OPAQUE, 1=ALPHA_TESTED, 2=TRANSLUCENT, 3=AIR
     * （與 {@code omm_classify.comp.glsl} 中 transFlags 的語義一致）
     */
    private byte[] buildTransparencyTable() {
        byte[] table = new byte[256];
        for (int id = 0; id < 256; id++) {
            table[id] = transparentMaterialIds.contains(id) ? (byte) 1 : (byte) 0;
        }
        // materialId = 0 通常為空氣/空體
        table[0] = (byte) 3;
        return table;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  查詢 / 統計
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 判斷是否應對指定 section 使用 {@code VK_GEOMETRY_OPAQUE_BIT_KHR}（Phase 1 OMM 等效）。
     *
     * <p>條件：OMM 擴充可用 AND section 不含透明方塊。
     *
     * @param sectionKey section key
     * @return true = 使用 opaque flag，跳過 any-hit
     */
    public boolean shouldUseOpaqueFlag(long sectionKey) {
        if (!BRAdaRTConfig.hasOMM()) return false;
        OMMSectionState state = getSectionState(sectionKey);
        return state == OMMSectionState.OPAQUE || state == OMMSectionState.OMM_ATTACHED;
    }

    /** @return 目前追蹤的 section OMM 狀態數量 */
    public int getTrackedSectionCount() {
        return sectionStates.size();
    }

    /**
     * @return 累計 any-hit 跳過次數（使用 opaque flag 的 section 數量）。
     *         可用於評估 OMM 最佳化效益。
     */
    public int getAnyHitSkipCount() {
        return ommAnyHitSkipCount.get();
    }

    /**
     * @return 累計 any-hit 觸發次數（含透明方塊的 section 數量）。
     *         值較高表示場景透明幾何較多，Phase 3 OMM 完整整合後可進一步最佳化。
     */
    public int getAnyHitTriggerCount() {
        return ommAnyHitTriggerCount.get();
    }

    /**
     * @return OMM 效率比（skipCount / totalCount），範圍 [0.0, 1.0]。
     *         0.0 = 無效益（全透明），1.0 = 完美（全不透明場景）。
     */
    public float getOMMEfficiency() {
        int skip    = ommAnyHitSkipCount.get();
        int trigger = ommAnyHitTriggerCount.get();
        int total   = skip + trigger;
        return (total == 0) ? 1.0f : (float) skip / total;
    }

    /**
     * 記錄 OMM 效率統計（Debug 用）。
     */
    public void logStats() {
        LOGGER.info("[OMM] Stats: sections={}, transparentMaterials={}, " +
                "anyHitSkip={}, anyHitTrigger={}, efficiency={:.1f}%",
            sectionStates.size(),
            transparentMaterialIds.size(),
            ommAnyHitSkipCount.get(),
            ommAnyHitTriggerCount.get(),
            getOMMEfficiency() * 100.0f);
    }
}
