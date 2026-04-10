package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;

/**
 * XPBD Cable Node — Mutable simulation node for rope/cable physics.
 *
 * XPBD 纜索節點 — 繩索/纜索物理的可變模擬節點。
 *
 * #8 fix: 本類別不是執行緒安全的。position/velocity/prevPosition 陣列的內容
 * 在每個 tick 被 DefaultCableManager 修改。外部讀取（如渲染執行緒）
 * 應使用快照複製，不可直接存取此類別的欄位。
 *
 * Each cable is discretized into a chain of nodes. Fixed nodes (endpoints)
 * are anchored to BlockPos and do not move. Free nodes (interior) are
 * subject to gravity and XPBD distance constraints.
 *
 * XPBD (Extended Position Based Dynamics, Macklin & Müller MIG'16):
 *   The compliance parameter α decouples stiffness from iteration count,
 *   providing physically consistent behavior regardless of solver iterations.
 *
 * Node count per cable:
 *   - Short cables (≤ 5 blocks): 2 nodes per block segment
 *   - Long cables (> 5 blocks): 1 node per block segment (max 64)
 *   - Endpoints: always fixed = true
 *
 * @see CableState
 */
// TODO review-fix #20: 缺少單元測試。建議覆蓋：inverseMass()、applyGravity()、
//   predictPosition()、deriveVelocity()、dampVelocity()、validatePosition() NaN 重設、
//   getPositionSnapshot() 快照獨立性、fixed 節點不動不變式。
@NotThreadSafe
public final class CableNode {

    /** 3D position [x, y, z] in world coordinates */
    public final double[] position;

    /** Previous position (for velocity derivation after XPBD solve) */
    public final double[] prevPosition;

    /** 3D velocity [vx, vy, vz] in m/s */
    public final double[] velocity;

    /** Node mass (kg) — inverse mass w = 1/mass used in XPBD */
    public final double mass;

    /** Whether this node is fixed (anchored to a BlockPos, does not move) */
    public final boolean fixed;

    /** The BlockPos this node is attached to (only meaningful for fixed nodes) */
    final BlockPos attachPos;

    /**
     * Create a cable node.
     *
     * @param x         Initial X position
     * @param y         Initial Y position
     * @param z         Initial Z position
     * @param mass      Node mass in kg (> 0)
     * @param fixed     True if this node is anchored (does not move)
     * @param attachPos The block position (null for free interior nodes)
     */
    public CableNode(double x, double y, double z, double mass, boolean fixed, BlockPos attachPos) {
        this.position = new double[]{x, y, z};
        this.prevPosition = new double[]{x, y, z};
        this.velocity = new double[]{0, 0, 0};
        this.mass = mass;
        this.fixed = fixed;
        this.attachPos = attachPos;
    }

    /**
     * Get the inverse mass (w = 1/mass). Fixed nodes return 0 (infinite mass).
     *
     * @return Inverse mass, or 0 for fixed nodes
     */
    public double inverseMass() {
        return fixed ? 0.0 : 1.0 / mass;
    }

    /**
     * Save current position to prevPosition before XPBD solve.
     */
    public void savePrevPosition() {
        System.arraycopy(position, 0, prevPosition, 0, 3);
    }

    /**
     * Apply gravity acceleration for one timestep.
     *
     * @param dt Timestep in seconds
     */
    public void applyGravity(double dt) {
        if (fixed) return;
        // ★ review-fix #12: 使用共用常數
        velocity[1] -= PhysicsConstants.GRAVITY * dt;
    }

    /**
     * Predict position from velocity (explicit Euler integration).
     *
     * @param dt Timestep in seconds
     */
    public void predictPosition(double dt) {
        if (fixed) return;
        position[0] += velocity[0] * dt;
        position[1] += velocity[1] * dt;
        position[2] += velocity[2] * dt;
    }

    /**
     * Derive velocity from position change after XPBD solve.
     *
     * @param dt Timestep in seconds
     */
    public void deriveVelocity(double dt) {
        if (fixed) return;
        if (dt <= 0) return;
        velocity[0] = (position[0] - prevPosition[0]) / dt;
        velocity[1] = (position[1] - prevPosition[1]) / dt;
        velocity[2] = (position[2] - prevPosition[2]) / dt;
    }

    /**
     * Simple velocity damping to prevent energy buildup.
     *
     * @param factor Damping factor (0.0 = full damp, 1.0 = no damp)
     */
    public void dampVelocity(double factor) {
        if (fixed) return;
        velocity[0] *= factor;
        velocity[1] *= factor;
        velocity[2] *= factor;
    }

    /**
     * Check for NaN/Inf and reset to previous position if detected.
     *
     * @return true if position was valid, false if reset was needed
     */
    public boolean validatePosition() {
        for (int i = 0; i < 3; i++) {
            if (Double.isNaN(position[i]) || Double.isInfinite(position[i])) {
                System.arraycopy(prevPosition, 0, position, 0, 3);
                Arrays.fill(velocity, 0);
                return false;
            }
        }
        return true;
    }

    // ─── Public Getters (review-fix #17) ───

    /**
     * Get the node mass in kg.
     */
    public double getMass() {
        return mass;
    }

    /**
     * Whether this node is fixed (anchored to a BlockPos endpoint).
     */
    public boolean isFixed() {
        return fixed;
    }

    /**
     * Get the attached BlockPos (only meaningful for fixed endpoint nodes).
     * @return the attach position, or null for free interior nodes
     */
    public BlockPos getAttachPos() {
        return attachPos;
    }

    /**
     * Get a snapshot copy of the current position.
     * ★ review-fix #17: 渲染執行緒應使用此方法取得位置快照，
     * 而非直接存取 package-private 的 position 陣列（非執行緒安全）。
     *
     * @return a new double[3] copy of {x, y, z}
     */
    public double[] getPositionSnapshot() {
        return new double[]{ position[0], position[1], position[2] };
    }

    @Override
    public String toString() {
        return String.format("CableNode[pos=(%.2f,%.2f,%.2f), fixed=%s, mass=%.1f]",
            position[0], position[1], position[2], fixed, mass);
    }
}
