package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * PFSF 物理引擎運行時抽象 — Strategy pattern。
 *
 * <p>允許在不同求解後端之間切換（Java/LWJGL vs C++ native via JNI），
 * 而不影響上層呼叫者（{@link PFSFEngine} 靜態 facade、ServerTickHandler 等）。</p>
 *
 * <p>實作：
 * <ul>
 *   <li>{@link PFSFEngineInstance} — Java/LWJGL Vulkan 後端（現有）</li>
 *   <li>NativePFSFRuntime — C++ libpfsf via JNI（Phase 3 計畫）</li>
 * </ul>
 *
 * @since v0.3a (libpfsf Phase 0)
 * @see PFSFEngine
 * @see PFSFEngineInstance
 */
public interface IPFSFRuntime {

    // ═══ Lifecycle ═══

    /** 初始化引擎（Vulkan context、compute pipeline、descriptor pool）。 */
    void init();

    /** 關閉引擎並釋放所有 GPU 資源。可安全重複呼叫。 */
    void shutdown();

    /** 引擎是否已初始化且 GPU 可用。 */
    boolean isAvailable();

    /** 回傳引擎診斷統計字串。 */
    String getStats();

    // ═══ Main tick loop ═══

    /**
     * 每 server tick 呼叫一次 — 驅動物理求解主迴圈。
     *
     * @param level        當前 ServerLevel（用於資料查詢和結果寫回）
     * @param players      線上玩家列表（用於 LOD 距離計算）
     * @param currentEpoch 結構變更紀元號（用於髒島偵測）
     */
    void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch);

    // ═══ Sparse notification ═══

    /**
     * 通知單一方塊變更，觸發稀疏 GPU 更新。
     *
     * @param islandId    所屬結構島 ID
     * @param pos         變更位置
     * @param newMaterial 新材料（null 表示方塊移除）
     * @param anchors     當前錨點集合
     */
    void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial, Set<BlockPos> anchors);

    // ═══ Configuration ═══

    void setMaterialLookup(Function<BlockPos, RMaterial> lookup);
    void setAnchorLookup(Function<BlockPos, Boolean> lookup);
    void setFillRatioLookup(Function<BlockPos, Float> lookup);
    void setCuringLookup(Function<BlockPos, Float> lookup);
    void setWindVector(Vec3 wind);

    // ═══ Buffer management ═══

    /** 移除指定 island 的 GPU buffer（island 銷毀時呼叫）。 */
    void removeBuffer(int islandId);
}
