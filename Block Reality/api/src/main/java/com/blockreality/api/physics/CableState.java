package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Cable Simulation State — Mutable runtime state for one cable's XPBD simulation.
 *
 * 纜索模擬狀態 — 一條纜索的 XPBD 模擬可變運行時狀態。
 *
 * Wraps a CableElement (immutable definition) with a chain of CableNode
 * for position-based dynamics simulation. The first and last nodes are
 * fixed to their respective BlockPos endpoints.
 *
 * Node layout for a cable between blocks A and B with restLength L:
 *   [fixed(A)] — [free] — [free] — ... — [fixed(B)]
 *   Segment count = ceil(L / segmentSpacing)
 *   Node count = segment count + 1
 *
 * XPBD constraint lambda storage:
 *   lambdas[i] corresponds to the distance constraint between nodes[i] and nodes[i+1].
 *   Per-constraint lambdas are required by XPBD (Macklin & Müller MIG'16) —
 *   storing lambda per-node would corrupt the Lagrange multiplier when a node
 *   participates in multiple constraints (which is always the case in a chain).
 *
 * @see CableNode
 * @see CableElement
 */
// TODO review-fix #20: 缺少單元測試。建議覆蓋：建構子節點數計算（短/長纜索）、
//   restSegmentLength 計算、resetLambdas()、calculateTension() 鬆弛/拉伸/斷裂、
//   isBroken() 閾值判定、nodes unmodifiable 防禦。
public final class CableState {

    /** Spacing between cable nodes (meters) — controls simulation resolution */
    private static final double NODE_SPACING = 0.5;

    /** Maximum nodes per cable (prevent excessive computation) */
    private static final int MAX_NODES = 64;

    /** Minimum nodes per cable (at least start + end) */
    private static final int MIN_NODES = 2;

    /** The immutable cable element definition (material, endpoints, rest length) */
    public final CableElement element;

    /** The normalized pair key for ConcurrentHashMap lookup */
    public final String key;

    /**
     * Ordered list of simulation nodes (first = endpoint A, last = endpoint B).
     * ★ review-fix #2: 對外暴露 unmodifiable view，防止外部破壞 lambdas.length == nodes.size()-1 不變式。
     * 內部 solver 透過 mutableNodes() 存取可變版本。
     */
    public final List<CableNode> nodes;

    /** 內部可變列表 — 僅供 DefaultCableManager solver 使用 */
    public final List<CableNode> nodesInternal;

    /** Rest length per segment (m) — total restLength / (nodeCount - 1) */
    public final double restSegmentLength;

    /**
     * XPBD Lagrange multipliers — one per constraint (segment).
     * lambdas[i] = accumulated λ for the distance constraint between nodes[i] and nodes[i+1].
     * Reset to 0 at the start of each tick; accumulated across XPBD iterations within a tick.
     */
    public final double[] lambdas;

    /**
     * ★ review-fix #15: 快取上一次計算的 tension，避免 calculateTension() 被重複呼叫時
     * 每次都遍歷所有 segment 計算距離。由 DefaultCableManager.tickCables() 更新。
     *
     * ★ new-fix N6: 加 volatile 保證 JMM 可見性。
     * server tick thread 寫入，渲染執行緒可透過 getCachedTension() 讀取；
     * 未加 volatile 的 double write 在 JMM 下不保證原子性（可能 word-tear）。
     */
    public volatile double cachedTension = 0.0;

    /**
     * Create a cable simulation state from an immutable CableElement.
     *
     * Discretizes the cable into a chain of nodes along the straight line
     * from nodeA to nodeB, with the given spacing.
     *
     * @param element The cable element definition
     * @param key     The normalized storage key
     */
    public CableState(CableElement element, String key) {
        this.element = element;
        this.key = key;

        // Calculate node count based on rest length and spacing
        int segmentCount = Math.max(1, (int) Math.ceil(element.restLength() / NODE_SPACING));
        int nodeCount = Math.min(MAX_NODES, Math.max(MIN_NODES, segmentCount + 1));

        // #12 防禦性檢查：nodeCount >= 2 由 MIN_NODES 保證，但額外防護
        this.restSegmentLength = (nodeCount > 1)
            ? element.restLength() / (nodeCount - 1)
            : element.restLength();

        // XPBD: one lambda per segment (constraint)
        this.lambdas = new double[Math.max(1, nodeCount - 1)];

        // Compute mass per node from cable properties
        // total cable mass = density × area × length
        double totalMass = element.material().getDensity() * element.area() * element.restLength();
        double nodeMass = Math.max(0.1, totalMass / nodeCount);

        // Build node chain: linear interpolation from A to B
        BlockPos a = element.nodeA();
        BlockPos b = element.nodeB();
        double ax = a.getX() + 0.5, ay = a.getY() + 0.5, az = a.getZ() + 0.5;
        double bx = b.getX() + 0.5, by = b.getY() + 0.5, bz = b.getZ() + 0.5;

        List<CableNode> mutableNodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            double t = (double) i / (nodeCount - 1);
            double x = ax + t * (bx - ax);
            double y = ay + t * (by - ay);
            double z = az + t * (bz - az);

            boolean isFixed = (i == 0 || i == nodeCount - 1);
            BlockPos attachPos = (i == 0) ? a : (i == nodeCount - 1) ? b : null;

            mutableNodes.add(new CableNode(x, y, z, nodeMass, isFixed, attachPos));
        }
        this.nodesInternal = mutableNodes;
        this.nodes = Collections.unmodifiableList(mutableNodes);
    }

    /**
     * Reset all per-constraint lambdas to 0 at the start of each tick.
     */
    public void resetLambdas() {
        Arrays.fill(lambdas, 0.0);
    }

    /**
     * Calculate the total tension in the cable based on XPBD-derived positions.
     *
     * Uses average strain across all segments:
     *   T = E × A × ε_avg  (Hooke's law from XPBD result)
     *
     * Young's modulus is obtained from RMaterial.getYoungsModulusPa() which provides
     * a centralized approximation (cables are tension-only, so this is consistent).
     *
     * @return Tension in Newtons (≥ 0, tension-only)
     */
    public double calculateTension() {
        if (nodes.size() < 2) return 0;

        // Average strain across all segments
        double totalStrain = 0;
        int segCount = nodes.size() - 1;
        for (int i = 0; i < segCount; i++) {
            CableNode n1 = nodes.get(i);
            CableNode n2 = nodes.get(i + 1);
            double dist = distance(n1.position, n2.position);
            double segStrain = (dist - restSegmentLength) / restSegmentLength;
            totalStrain += segStrain;
        }
        double avgStrain = totalStrain / segCount;

        if (avgStrain <= 0) return 0;  // Slack

        // #3 fix: 使用 RMaterial.getYoungsModulusPa() 統一楊氏模量來源
        double E = element.material().getYoungsModulusPa();
        return E * element.area() * avgStrain;
    }

    /**
     * Get the maximum allowable tension (same as CableElement).
     */
    public double maxTension() {
        return element.maxTension();
    }

    /**
     * Get the last cached tension value (updated each tick by DefaultCableManager).
     * ★ review-fix #15: 外部應使用此方法查詢 tension，而非呼叫 calculateTension()。
     */
    public double getCachedTension() {
        return cachedTension;
    }

    /**
     * Check if the cable is broken (tension exceeds capacity).
     * Uses cached tension to avoid redundant computation.
     */
    public boolean isBroken() {
        double max = maxTension();
        return max > 0 && cachedTension > max;
    }

    /**
     * Euclidean distance between two 3D points.
     */
    static double distance(double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Get the number of simulation nodes.
     */
    public int nodeCount() {
        return nodes.size();
    }
}
