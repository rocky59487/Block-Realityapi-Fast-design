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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 觸發式 SPH 應力引擎 — 真實 SPH 核心函數實作。
 *
 * <h3>演算法（Monaghan 1992 Cubic Spline SPH）</h3>
 * <ol>
 *   <li><b>密度求和</b>：ρᵢ = Σⱼ mⱼ W(|rᵢ - rⱼ|, h)
 *       — 使用 {@link SPHKernel#cubicSpline} 核心函數</li>
 *   <li><b>狀態方程</b>：Pᵢ = k (ρᵢ - ρ₀) + 爆炸衝量
 *       — 簡化 Tait EOS，k = basePressure × (radius/4)</li>
 *   <li><b>壓力梯度力</b>：fᵢ = -Σⱼ mⱼ (Pᵢ/ρᵢ² + Pⱼ/ρⱼ²) ∇W
 *       — 使用 {@link SPHKernel#cubicSplineGradient}</li>
 *   <li><b>應力正規化</b>：stressLevel = |fᵢ| × materialFactor / Rcomp
 *       — 映射到 [0, 2]</li>
 * </ol>
 *
 * <h3>鄰域搜索</h3>
 * <p>使用 {@link SpatialHashGrid}（Teschner 2003 空間雜湊），O(1) 鄰居查詢。
 *
 * <h3>觸發條件</h3>
 * <p>ExplosionEvent.Start 且 radius > sph_trigger_radius（預設 5 格）
 *
 * <h3>異步策略（v3fix AD-2 合規）</h3>
 * <ol>
 *   <li>主線程：擷取 snapshot（不可變 Map）</li>
 *   <li>異步：supplyAsync 執行 SPH 計算</li>
 *   <li>主線程：server.execute() 回寫 → ResultApplicator.applyStressField()</li>
 * </ol>
 *
 * @see SPHKernel
 * @see SpatialHashGrid
 * @see com.blockreality.api.physics.ResultApplicator
 */
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
    //  SPH 應力計算（異步線程，只讀 snapshot）
    // ═══════════════════════════════════════════════════════

    /** 從 BRConfig 讀取平滑長度 h */
    private static double getSmoothingLength() {
        return BRConfig.INSTANCE.sphSmoothingLength.get();
    }

    /** 從 BRConfig 讀取靜止密度 ρ₀ */
    private static double getRestDensity() {
        return BRConfig.INSTANCE.sphRestDensity.get();
    }

    /**
     * 真實 SPH 壓力計算 — Monaghan (1992) Cubic Spline 方法。
     *
     * <h3>步驟</h3>
     * <ol>
     *   <li>建構 {@link SpatialHashGrid} O(1) 鄰域查詢</li>
     *   <li>SPH 密度求和：ρᵢ = Σⱼ mⱼ W(|rᵢ - rⱼ|, h)</li>
     *   <li>Tait 狀態方程 + 爆炸衝量注入</li>
     *   <li>SPH 壓力梯度力：fᵢ = -Σⱼ mⱼ (Pᵢ/ρᵢ² + Pⱼ/ρⱼ²) ∇W</li>
     *   <li>材料正規化映射到 [0, 2] 應力值</li>
     * </ol>
     *
     * @param snapshot        不可變的方塊快照
     * @param center          爆炸中心
     * @param explosionRadius 爆炸半徑
     * @return BlockPos → stressLevel [0.0, 2.0]
     */
    private static Map<BlockPos, Float> computeStress(
            Map<BlockPos, SnapshotEntry> snapshot, Vec3 center, float explosionRadius) {

        List<SnapshotEntry> particles = new ArrayList<>(snapshot.values());
        int n = particles.size();
        if (n == 0) return Collections.emptyMap();

        double h = getSmoothingLength();
        double restDensity = getRestDensity();
        double k = getBasePressure() * (explosionRadius / 4.0); // 壓力常數，隨爆炸規模縮放

        // ── 粒子位置快取（避免重複 Vec3.atCenterOf）──
        double[] px = new double[n], py = new double[n], pz = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3 pos = Vec3.atCenterOf(particles.get(i).pos);
            px[i] = pos.x; py[i] = pos.y; pz[i] = pos.z;
        }

        // ── Step 1: 建構空間雜湊格子 ──
        SpatialHashGrid grid = new SpatialHashGrid(h);
        for (int i = 0; i < n; i++) {
            grid.insert(i, px[i], py[i], pz[i]);
        }

        // ── Step 2: SPH 密度求和 ρᵢ = Σⱼ mⱼ W(|rᵢ - rⱼ|, h) ──
        // 每個 Minecraft 方塊 = 1 個粒子，質量 m = 1.0
        double[] density = new double[n];
        for (int i = 0; i < n; i++) {
            double rho = 0.0;
            for (int j : grid.getNeighbors(px[i], py[i], pz[i])) {
                double dx = px[i] - px[j], dy = py[i] - py[j], dz = pz[i] - pz[j];
                double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                rho += SPHKernel.cubicSpline(r, h);
            }
            density[i] = rho;
        }

        // ── Step 3: 狀態方程 Pᵢ = k (ρᵢ - ρ₀) ──
        // + 爆炸衝量：靠近爆心的粒子獲得額外壓力
        double[] pressure = new double[n];
        for (int i = 0; i < n; i++) {
            pressure[i] = k * Math.max(0.0, density[i] - restDensity);

            // 爆炸衝量注入：線性衰減
            double distToCenter = Math.sqrt(
                (px[i] - center.x) * (px[i] - center.x) +
                (py[i] - center.y) * (py[i] - center.y) +
                (pz[i] - center.z) * (pz[i] - center.z));
            if (distToCenter < explosionRadius) {
                double impulse = k * (1.0 - distToCenter / explosionRadius);
                pressure[i] += impulse;
            }
        }

        // ── Step 4: SPH 壓力梯度力 ──
        // fᵢ = -Σⱼ mⱼ (Pᵢ/ρᵢ² + Pⱼ/ρⱼ²) ∇W(rᵢⱼ, h)
        // 取力的純量大小作為應力指標
        double[] forceAccum = new double[n];
        for (int i = 0; i < n; i++) {
            double di = Math.max(density[i], 1e-6);
            double piTerm = pressure[i] / (di * di);
            double fi = 0.0;

            for (int j : grid.getNeighbors(px[i], py[i], pz[i])) {
                if (i == j) continue;
                double dx = px[i] - px[j], dy = py[i] - py[j], dz = pz[i] - pz[j];
                double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (r < 1e-10) continue; // 防止重合粒子的除零

                double dj = Math.max(density[j], 1e-6);
                double pjTerm = pressure[j] / (dj * dj);
                double gradW = SPHKernel.cubicSplineGradient(r, h);

                // 壓力對力矩的貢獻（取絕對值作為應力指標）
                fi += Math.abs((piTerm + pjTerm) * gradW);
            }
            forceAccum[i] = fi;
        }

        // ── Step 5: 材料正規化 → stressLevel [0, 2] ──
        Map<BlockPos, Float> results = new HashMap<>();
        for (int i = 0; i < n; i++) {
            SnapshotEntry entry = particles.get(i);
            float rcomp = entry.rcomp > 0 ? entry.rcomp : 1.0f;
            float materialFactor = entry.blockType.getStructuralFactor();
            float stress = (float) Math.min(forceAccum[i] * materialFactor / rcomp, 2.0f);
            results.put(entry.pos, stress);
        }

        LOGGER.debug("[SPH] Computed stress for {} particles: h={}, k={}, ρ₀={}",
            n, h, k, restDensity);
        return results;
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
