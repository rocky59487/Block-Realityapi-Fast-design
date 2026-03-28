package com.blockreality.api.spi;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.CableElement;
import com.blockreality.api.physics.CableNode;
import com.blockreality.api.physics.CableState;
import com.blockreality.api.physics.PhysicsConstants;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default Cable Manager Implementation — XPBD-based rope/cable physics.
 *
 * 預設纜索管理器實現 — 基於 XPBD 的繩索/纜索物理。
 *
 * Uses Extended Position Based Dynamics (Macklin & Müller, MIG'16) to simulate
 * cable sag, swing, and tension with physically consistent behavior.
 *
 * XPBD Distance Constraint:
 *   C(x) = |x₁ - x₂| - L_rest
 *   α̃ = compliance / dt²
 *   Δλ = (-C - α̃·λ) / (w₁ + w₂ + α̃)
 *   dx₁ = +w₁ · Δλ · ∇C
 *   dx₂ = -w₂ · Δλ · ∇C
 *
 * Key design: lambda (Lagrange multiplier) is stored PER CONSTRAINT (per segment),
 * NOT per node. Each segment i↔i+1 has its own λ stored in CableState.lambdas[i].
 * This is required by XPBD since a node participates in multiple constraints.
 *
 * Thread safety:
 *   ConcurrentHashMap protects map structure. CableNode/CableState are mutable
 *   and must only be modified from the server tick thread. External read access
 *   to CableState (e.g., rendering) should use snapshot copies.
 *
 * Features:
 *   - ConcurrentHashMap storage for thread-safe map access
 *   - XPBD position-based simulation with gravity, sag, and swing
 *   - Compliance parameter decouples stiffness from iteration count
 *   - NaN/Inf protection with fallback to previous position
 *   - Velocity damping to prevent energy buildup
 */
/**
 * ★ review-fix #4: 本類別的 ConcurrentHashMap 保證 map 結構的執行緒安全，
 * 但 CableNode 的可變陣列（position/velocity）僅由 server tick thread 修改。
 * 渲染執行緒若需讀取節點位置，應使用快照複製。
 *
 * @see CableNode （@NotThreadSafe — 欄位在 tick 中被就地修改）
 */
// TODO review-fix #20: 缺少單元測試。建議覆蓋：attachCable/detachCable CRUD、
//   getCablesAt 端點索引正確性、tickCables XPBD 模擬（鬆弛/張力/斷裂）、
//   removeChunkCables 跨 chunk 邊界保留策略、normalizePair 雙向對稱。
@NotThreadSafe  // CableNode mutations are not thread-safe; map ops use ConcurrentHashMap
public class DefaultCableManager implements ICableManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-CableManager");

    // ─── XPBD Parameters ───

    /** Cable compliance (m/N) — lower = stiffer cable. 1e-6 for steel cable. */
    private static final double COMPLIANCE = 1e-6;

    /** XPBD constraint iterations per tick — 5 is a good balance of accuracy/speed */
    private static final int XPBD_ITERATIONS = 5;

    /** Physics timestep (seconds per game tick, 20 TPS → 0.05s)
     *  ★ new-fix N4: 引用 PhysicsConstants.TICK_DT，消除與 ForceEquilibriumSolver 的重複定義 */
    private static final double DT = PhysicsConstants.TICK_DT;

    /** Velocity damping factor (0.98 = 2% energy loss per tick, prevents oscillation) */
    private static final double VELOCITY_DAMPING = 0.98;

    // ─── Storage ───

    /**
     * Primary storage: normalized key → CableState (with XPBD simulation nodes).
     */
    private final Map<String, CableState> cables = new ConcurrentHashMap<>();

    /**
     * ★ review-fix #11: 端點二級索引 — O(1) 查詢某 BlockPos 上的所有纜索 key。
     * 避免 getCablesAt() 的 O(N) 全表掃描。
     */
    private final Map<BlockPos, Set<String>> endpointIndex = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════
    //  Key normalization
    // ═══════════════════════════════════════════════════════

    /**
     * Normalize a pair of positions into a consistent, bidirectional key.
     */
    static String normalizePair(BlockPos from, BlockPos to) {
        if (from.compareTo(to) <= 0) {
            return String.format("%d,%d,%d:%d,%d,%d",
                from.getX(), from.getY(), from.getZ(),
                to.getX(), to.getY(), to.getZ());
        } else {
            return String.format("%d,%d,%d:%d,%d,%d",
                to.getX(), to.getY(), to.getZ(),
                from.getX(), from.getY(), from.getZ());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Endpoint Index Helpers (review-fix #11)
    // ═══════════════════════════════════════════════════════

    private void indexAdd(BlockPos pos, String key) {
        endpointIndex.computeIfAbsent(pos, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private void indexRemove(BlockPos pos, String key) {
        Set<String> keys = endpointIndex.get(pos);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) endpointIndex.remove(pos);
        }
    }

    private void indexRemoveCable(CableElement cable, String key) {
        indexRemove(cable.nodeA(), key);
        indexRemove(cable.nodeB(), key);
    }

    // ═══════════════════════════════════════════════════════
    //  ICableManager implementation
    // ═══════════════════════════════════════════════════════

    @Override
    public CableElement attachCable(BlockPos from, BlockPos to, RMaterial cableMaterial) {
        if (from == null || to == null || cableMaterial == null) {
            throw new NullPointerException("Cable endpoints and material must not be null");
        }

        String key = normalizePair(from, to);
        CableElement element = CableElement.create(from, to, cableMaterial);
        CableState state = new CableState(element, key);
        CableState previous = cables.put(key, state);
        // ★ review-fix #11: 維護端點索引
        indexAdd(from, key);
        indexAdd(to, key);

        if (previous != null) {
            LOGGER.debug("[BR-CableManager] Replaced existing cable: {} → {} ({} nodes)",
                from.toShortString(), to.toShortString(), state.nodeCount());
        } else {
            LOGGER.debug("[BR-CableManager] Attached new cable: {} → {} ({}, {} nodes)",
                from.toShortString(), to.toShortString(),
                cableMaterial.getMaterialId(), state.nodeCount());
        }

        return element;
    }

    @Override
    public void detachCable(BlockPos from, BlockPos to) {
        if (from == null || to == null) return;
        String key = normalizePair(from, to);
        CableState removed = cables.remove(key);
        if (removed != null) {
            indexRemoveCable(removed.element, key);
            LOGGER.debug("[BR-CableManager] Detached cable: {} → {}",
                from.toShortString(), to.toShortString());
        }
    }

    @Override
    public CableElement getCable(BlockPos from, BlockPos to) {
        if (from == null || to == null) return null;
        String key = normalizePair(from, to);
        CableState state = cables.get(key);
        return state != null ? state.element : null;
    }

    @Override
    public Set<CableElement> getCablesAt(BlockPos pos) {
        if (pos == null) return Collections.emptySet();
        // ★ review-fix #11: 使用端點索引 O(k) 查詢，k = 該位置的纜索數
        Set<String> keys = endpointIndex.get(pos);
        if (keys == null || keys.isEmpty()) return Collections.emptySet();
        Set<CableElement> result = new HashSet<>();
        for (String key : keys) {
            CableState state = cables.get(key);
            if (state != null) {
                result.add(state.element);
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  XPBD Tick — Core physics simulation
    // ═══════════════════════════════════════════════════════

    @Override
    public Set<CableElement> tickCables() {
        Set<CableElement> brokenCables = new HashSet<>();
        // #2 fix: 先收集要移除的 key，迭代後統一移除
        List<String> keysToRemove = new ArrayList<>();
        double alphaTilde = COMPLIANCE / (DT * DT);

        for (CableState cable : cables.values()) {
            List<CableNode> nodes = cable.nodesInternal;

            // Phase 1: Apply gravity, save previous positions, predict new positions
            for (CableNode node : nodes) {
                node.savePrevPosition();
                node.applyGravity(DT);
                node.predictPosition(DT);
            }

            // Phase 2: Reset per-constraint lambdas for this tick
            // #1 fix: lambda 存在 CableState.lambdas[] 而非 CableNode 上
            cable.resetLambdas();

            // Phase 3: XPBD Constraint Iterations
            for (int iter = 0; iter < XPBD_ITERATIONS; iter++) {
                for (int i = 0; i < nodes.size() - 1; i++) {
                    solveDistanceConstraint(nodes.get(i), nodes.get(i + 1),
                        cable.restSegmentLength, alphaTilde,
                        cable.lambdas, i);
                }
            }

            // Phase 4: Derive velocity from position change, apply damping
            for (CableNode node : nodes) {
                node.deriveVelocity(DT);
                node.dampVelocity(VELOCITY_DAMPING);
            }

            // Phase 5: NaN/Inf protection
            boolean valid = true;
            for (CableNode node : nodes) {
                if (!node.validatePosition()) {
                    valid = false;
                }
            }
            if (!valid) {
                LOGGER.warn("[BR-CableManager] NaN detected in cable {} → {}, reset to previous",
                    cable.element.nodeA().toShortString(), cable.element.nodeB().toShortString());
            }

            // Phase 6: Tension calculation & breakage detection
            // ★ review-fix #15: 快取 tension 供外部查詢
            double tension = cable.calculateTension();
            cable.cachedTension = tension;
            double maxTension = cable.maxTension();

            if (maxTension > 0 && tension > maxTension) {
                brokenCables.add(cable.element);
                keysToRemove.add(cable.key);

                LOGGER.warn("[BR-CableManager] Cable broke: {} → {} (T={}N / max={}N)",
                    cable.element.nodeA().toShortString(),
                    cable.element.nodeB().toShortString(),
                    String.format("%.1f", tension), String.format("%.1f", maxTension));
            }
        }

        // #2 fix: 迭代結束後統一移除斷裂纜索
        for (String key : keysToRemove) {
            CableState removed = cables.remove(key);
            if (removed != null) indexRemoveCable(removed.element, key);
        }

        return brokenCables;
    }

    // ═══════════════════════════════════════════════════════
    //  XPBD Distance Constraint Solver
    // ═══════════════════════════════════════════════════════

    /**
     * Solve a single XPBD distance constraint between two nodes.
     *
     * #1 fix: lambda is now per-constraint (stored in lambdas[constraintIdx]),
     * NOT per-node. This is correct XPBD (Macklin & Müller, MIG'16).
     *
     * XPBD formulation:
     *   C(x) = |x₁ - x₂| - L_rest
     *   Δλ = (-C - α̃·λ_i) / (w₁ + w₂ + α̃)
     *   x₁ += w₁ · Δλ · ∇C
     *   x₂ -= w₂ · Δλ · ∇C
     *
     * @param n1             First node
     * @param n2             Second node
     * @param restLen        Rest length of this segment (m)
     * @param alphaTilde     Effective compliance (compliance / dt²)
     * @param lambdas        Per-constraint Lagrange multiplier array
     * @param constraintIdx  Index of this constraint in the array
     */
    private static void solveDistanceConstraint(CableNode n1, CableNode n2,
                                                 double restLen, double alphaTilde,
                                                 double[] lambdas, int constraintIdx) {
        double dx = n2.position[0] - n1.position[0];
        double dy = n2.position[1] - n1.position[1];
        double dz = n2.position[2] - n1.position[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 1e-8) return;  // Degenerate case: nodes overlapping

        double C = dist - restLen;  // Constraint value (positive = stretched)
        double w1 = n1.inverseMass();
        double w2 = n2.inverseMass();

        double denom = w1 + w2 + alphaTilde;
        if (denom < 1e-12) return;  // Both fixed or degenerate

        // #1 fix: XPBD Lagrange multiplier update — per constraint, not per node
        double deltaLambda = (-C - alphaTilde * lambdas[constraintIdx]) / denom;
        lambdas[constraintIdx] += deltaLambda;

        // Normalized constraint gradient (direction vector)
        double nx = dx / dist;
        double ny = dy / dist;
        double nz = dz / dist;

        // Position corrections
        if (!n1.fixed) {
            n1.position[0] += w1 * deltaLambda * nx;
            n1.position[1] += w1 * deltaLambda * ny;
            n1.position[2] += w1 * deltaLambda * nz;
        }
        if (!n2.fixed) {
            n2.position[0] -= w2 * deltaLambda * nx;
            n2.position[1] -= w2 * deltaLambda * ny;
            n2.position[2] -= w2 * deltaLambda * nz;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Chunk Unload Cleanup
    // ═══════════════════════════════════════════════════════

    /**
     * Remove cables whose BOTH endpoints are within the unloaded chunk.
     *
     * #11 跨 chunk 邊界纜索策略：
     * 僅移除兩端都在此 chunk 內的纜索。跨 chunk 邊界的纜索保留在記憶體中，
     * 原因是另一端的 chunk 可能仍然在載入狀態。這些跨界纜索的 fixed endpoint
     * 座標仍然有效（BlockPos 是純數值），只是對應的 BlockEntity 可能暫時不可用。
     * 當卸載 chunk 重新載入時，NBT 恢復會重建 BlockEntity，纜索無縫接續。
     * 若另一端也卸載，該纜索會在對方的 removeChunkCables 呼叫中被移除。
     */
    @Override
    public int removeChunkCables(net.minecraft.world.level.ChunkPos chunkPos) {
        int removed = 0;
        var iterator = cables.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            CableElement cable = entry.getValue().element;
            boolean aInChunk = isInChunk(cable.nodeA(), chunkPos);
            boolean bInChunk = isInChunk(cable.nodeB(), chunkPos);
            if (aInChunk && bInChunk) {
                indexRemoveCable(cable, entry.getKey());
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("[BR-CableManager] Removed {} cables for chunk unload at [{}, {}]",
                removed, chunkPos.x, chunkPos.z);
        }
        return removed;
    }

    private static boolean isInChunk(BlockPos pos, net.minecraft.world.level.ChunkPos chunkPos) {
        return (pos.getX() >> 4) == chunkPos.x && (pos.getZ() >> 4) == chunkPos.z;
    }

    @Override
    public int getCableCount() {
        return cables.size();
    }
}
