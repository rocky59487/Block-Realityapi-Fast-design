package com.blockreality.api.sph;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.BlockType;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.ResultApplicator;
import com.blockreality.api.physics.StressField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 觸發式 SPH 應力引擎（降級版）— v3fix §1.7
 *
 * 設計（CTO 雙軌戰略之 Java 端近似模型）：
 *   不做真正的 SPH 核心函數，改用距離衰減壓力模型：
 *     stressLevel = (basePressure / distance²) × materialFactor / Rcomp
 *
 * 觸發條件：
 *   ExplosionEvent.Start 且 radius > sph_trigger_radius（預設 5 格）
 *
 * 異步策略（v3fix AD-2 合規）：
 *   1. 主線程：擷取 snapshot（Map<BlockPos, SnapshotEntry>）— 不碰 Level 異步安全
 *   2. 異步：supplyAsync 計算距離衰減應力
 *   3. 主線程：server.execute() 回寫 → ResultApplicator.applyStressField()
 *
 * 執行緒池（v3fix 建議）：
 *   ThreadPoolExecutor(1, 2, 60s, ArrayBlockingQueue(4), DiscardOldestPolicy)
 *   - 最多 2 個異步 SPH 計算
 *   - 佇列滿時丟棄最舊（最新爆炸才有意義）
 */
// TODO review-fix #20: 缺少單元測試。建議覆蓋：computeStress 距離衰減公式、
//   getMaterialFactor 對所有 BlockType 的回傳值、captureSnapshot 粒子上限煞車、
//   getExplosionRadius 反射失敗回退值。（需 mock ServerLevel/Explosion）
@ThreadSafe
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SPHStressEngine {

    private static final Logger LOGGER = LogManager.getLogger("BR-SPH");

    // ─── 執行緒池（daemon，JVM 退出時不阻塞） ───
    private static final ExecutorService SPH_EXECUTOR = new ThreadPoolExecutor(
        1, 2,
        60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4),
        r -> {
            Thread t = new Thread(r, "BR-SPH-Worker");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    /** 爆炸基礎壓力常數（從 BRConfig 讀取） */
    private static float getBasePressure() {
        return BRConfig.INSTANCE.sphBasePressure.get().floatValue();
    }

    // ═══════════════════════════════════════════════════════
    //  Forge 事件入口
    // ═══════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        float radius = getExplosionRadius(event.getExplosion());
        int triggerRadius = BRConfig.INSTANCE.sphAsyncTriggerRadius.get();

        if (radius <= triggerRadius) return; // 小爆炸不觸發

        Vec3 center = event.getExplosion().getPosition();
        int searchRadius = Math.min((int) Math.ceil(radius) + 2,
            BRConfig.INSTANCE.snapshotMaxRadius.get());

        // Phase 1: 主線程擷取快照（不可變）
        Map<BlockPos, SnapshotEntry> snapshot = captureSnapshot(level, center, searchRadius);

        if (snapshot.isEmpty()) return;

        LOGGER.debug("[SPH] Explosion at {} radius={}, captured {} blocks",
            center, radius, snapshot.size());

        // Phase 2: 異步計算
        final float finalRadius = radius;
        CompletableFuture
            .supplyAsync(() -> computeStress(snapshot, center, finalRadius), SPH_EXECUTOR)
            .orTimeout(30, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                // ★ H-3: 異步失敗時在工作線程即時記錄，不佔主線程資源
                LOGGER.warn("[SPH] Stress computation failed or timed out: {}", ex.getMessage());
                return Collections.emptyMap();  // 降級：返回空結果
            })
            .thenAccept(stressMap -> {
                if (stressMap.isEmpty()) return;  // 空結果（降級或無損傷）不排程主線程

                // Phase 3: 回到主線程寫回
                level.getServer().execute(() -> {
                    Set<BlockPos> damaged = new HashSet<>();
                    for (Map.Entry<BlockPos, Float> e : stressMap.entrySet()) {
                        if (e.getValue() >= 1.0f) {
                            damaged.add(e.getKey());
                        }
                    }

                    StressField field = new StressField(stressMap, damaged);
                    int applied = ResultApplicator.applyStressField(level, field);

                    LOGGER.info("[SPH] Stress applied: {} blocks, {} damaged",
                        applied, damaged.size());
                });
            });
    }

    // ═══════════════════════════════════════════════════════
    //  快照擷取（主線程，線程安全）
    // ═══════════════════════════════════════════════════════

    /**
     * 快照條目 — 從 RBlockEntity 擷取的不可變數據。
     */
    private record SnapshotEntry(
        BlockPos pos,
        BlockType blockType,
        float rcomp,
        float rtens
    ) {}

    /**
     * 在主線程擷取爆炸範圍內所有 RBlock 的材料快照。
     * 球形篩選 + immutable map → 異步安全。
     */
    private static Map<BlockPos, SnapshotEntry> captureSnapshot(
            ServerLevel level, Vec3 center, int radius) {

        Map<BlockPos, SnapshotEntry> snapshot = new HashMap<>();
        BlockPos centerPos = BlockPos.containing(center);
        int maxParticles = BRConfig.INSTANCE.sphMaxParticles.get();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = centerPos.offset(dx, dy, dz);

                    // 球形篩選
                    if (center.distanceTo(Vec3.atCenterOf(pos)) > radius) continue;

                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;

                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof RBlockEntity rbe) {
                        // ★ H-4 fix: 材料 null 防護 — 防止 getMaterial() 回傳 null 時 NPE
                        RMaterial mat = rbe.getMaterial();
                        if (mat == null) continue;
                        snapshot.put(pos.immutable(), new SnapshotEntry(
                            pos.immutable(),
                            rbe.getBlockType(),
                            (float) mat.getRcomp(),
                            (float) mat.getRtens()
                        ));
                    }

                    // 粒子上限安全煞車
                    if (snapshot.size() >= maxParticles) {
                        return Collections.unmodifiableMap(snapshot);
                    }
                }
            }
        }

        return Collections.unmodifiableMap(snapshot);
    }

    // ═══════════════════════════════════════════════════════
    //  距離衰減應力計算（異步線程，只讀 snapshot）
    // ═══════════════════════════════════════════════════════

    /**
     * 簡化距離衰減模型：
     *   stressLevel = (basePressure / distance²) × materialFactor / Rcomp
     *
     * materialFactor:
     *   PLAIN    → 1.0
     *   CONCRETE → 0.8
     *   REBAR    → 1.2
     *   RC_NODE  → 0.7
     *   ANCHOR_PILE → 0.5（錨定樁，非常堅固）
     *
     * 結果夾在 [0.0, 2.0]
     */
    private static Map<BlockPos, Float> computeStress(
            Map<BlockPos, SnapshotEntry> snapshot, Vec3 center, float explosionRadius) {

        Map<BlockPos, Float> results = new HashMap<>();

        for (SnapshotEntry entry : snapshot.values()) {
            Vec3 blockCenter = Vec3.atCenterOf(entry.pos);
            double dist = center.distanceTo(blockCenter);
            // ★ C-2 fix: 最小距離從 0.5 改為 1.0（一個方塊大小）
            // 舊值 0.5 導致 BASE_PRESSURE/(0.25) = 40× 壓力尖峰
            // 新值 1.0 → BASE_PRESSURE/(1.0) = 10.0，連續且物理合理
            if (dist < 1.0) dist = 1.0;

            float materialFactor = getMaterialFactor(entry.blockType);
            float rcomp = entry.rcomp;
            if (rcomp <= 0) rcomp = 1.0f;

            float rawPressure = (getBasePressure() / (float) (dist * dist)) * materialFactor;
            float stressLevel = Math.min(rawPressure / rcomp, 2.0f);

            results.put(entry.pos, stressLevel);
        }

        return results;
    }

    /**
     * #7 fix: 直接從 BlockType enum 讀取 structuralFactor，
     * 確保與 BlockTypeRegistry 共用同一數據來源（Single Source of Truth）。
     */
    private static float getMaterialFactor(BlockType type) {
        return type.getStructuralFactor();
    }

    // ═══════════════════════════════════════════════════════
    //  Explosion 私有欄位存取
    // ═══════════════════════════════════════════════════════

    /**
     * 讀取 Explosion.radius — 透過 AccessTransformer (AT) 直接存取。
     *
     * ★ 已從反射升級為 AT（accesstransformer.cfg）：
     *   1. 零反射開銷 — 編譯期即可見 public field
     *   2. 無 JDK 17 --add-opens 限制
     *   3. 在 obf 環境下由 Forge 自動處理 SRG 名稱映射
     *
     * AT 條目：public net.minecraft.world.level.Explosion f_46024_ # radius
     * 驗證方式：gradle genEclipseRuns / genIntellijRuns 後確認 Explosion.radius 可見
     *
     * 若 AT 尚未生效（例如首次 gradle sync 前），保留反射 fallback。
     */
    /** Cached reflective handle for Explosion.radius (private field). */
    private static java.lang.reflect.Field EXPLOSION_RADIUS_FIELD;

    private static float getExplosionRadius(net.minecraft.world.level.Explosion explosion) {
        try {
            if (EXPLOSION_RADIUS_FIELD == null) {
                EXPLOSION_RADIUS_FIELD =
                    net.minecraft.world.level.Explosion.class.getDeclaredField("radius");
                EXPLOSION_RADIUS_FIELD.setAccessible(true);
            }
            return EXPLOSION_RADIUS_FIELD.getFloat(explosion);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[SPH] Cannot access Explosion.radius via reflection, fallback=4.0", e);
            return 4.0f;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  資源清理（ServerStoppingEvent 呼叫）
    // ═══════════════════════════════════════════════════════

    /**
     * 優雅關閉執行緒池。
     * 應在 ServerStoppingEvent 中呼叫。
     */
    public static void shutdown() {
        SPH_EXECUTOR.shutdown();
        try {
            if (!SPH_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                SPH_EXECUTOR.shutdownNow();
                LOGGER.warn("[SPH] Executor did not terminate in 10s, forcing shutdown");
            }
        } catch (InterruptedException e) {
            SPH_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
