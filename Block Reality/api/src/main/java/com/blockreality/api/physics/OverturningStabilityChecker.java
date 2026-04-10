package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Gravity-driven overturning / seesaw stability checker.
 *
 * <h3>Physical model — support polygon approach</h3>
 * <ol>
 *   <li>The <em>support polygon</em> is approximated as the AABB of the anchor block
 *       set projected onto the XZ plane.</li>
 *   <li>The stability margin is:
 *       <pre>
 *       centroid_x = (anchorMinX + anchorMaxX + 1) / 2
 *       centroid_z = (anchorMinZ + anchorMaxZ + 1) / 2
 *       inradius   = min(anchorMaxX - anchorMinX + 1,
 *                        anchorMaxZ - anchorMinZ + 1) / 2
 *                    (minimum half-width of the support base, clamped to ≥ 0.5 m)
 *       offset_xz  = √((com_x − centroid_x)² + (com_z − centroid_z)²)
 *       margin     = (inradius − offset_xz) / inradius
 *       </pre></li>
 *   <li>Dead-band: margin {@literal >} DEADBAND → STABLE; 0 {@literal <} margin ≤ DEADBAND
 *       → MARGINAL (logged, not triggered); margin ≤ 0 → TIPPING.</li>
 *   <li>Tipping angular velocity:
 *       <pre>
 *       overhang = max(offset_xz − inradius, 0)
 *       ω = ANGULAR_SCALE × √(g × overhang / max(com_height, 1.0))
 *       </pre>
 *       The axis of rotation is horizontal, perpendicular to the tipping direction.
 *   </li>
 * </ol>
 *
 * <p>Sensitivity calibration:
 * <ul>
 *   <li>DEADBAND = 0.15 — CoM must overshoot the support edge by 15% before tipping
 *       triggers, preventing jitter from single-block placements.</li>
 *   <li>ANGULAR_SCALE = 0.70 — damping factor so tall structures tip slowly (feels
 *       physical) rather than snapping over instantly.</li>
 * </ul>
 */
public final class OverturningStabilityChecker {

    /** Dead-band ratio: CoM must exceed support edge by this fraction before TIPPING. */
    public static final float DEFAULT_DEADBAND = 0.15f;

    /** Angular velocity damping scale (0–1; lower = slower tip). */
    private static final double ANGULAR_SCALE = 0.70;

    private OverturningStabilityChecker() {}

    /** Stability classification. */
    public enum State {
        /** CoM well inside support polygon — no action needed. */
        STABLE,
        /** CoM slightly outside dead-band — warn only, do not trigger collapse. */
        MARGINAL,
        /** CoM beyond support edge + dead-band — trigger overturning collapse. */
        TIPPING
    }

    /**
     * Result carrier for a stability check.
     *
     * @param state          classification
     * @param comX           CoM world X
     * @param comY           CoM world Y
     * @param comZ           CoM world Z
     * @param tipDirX        tipping direction unit vector X (XZ plane)
     * @param tipDirZ        tipping direction unit vector Z (XZ plane)
     * @param overhang       distance CoM projects beyond support edge (m), 0 if not tipping
     * @param angularVelX    initial angular velocity component X (rad/s)
     * @param angularVelZ    initial angular velocity component Z (rad/s)
     */
    public record Result(
        State  state,
        double comX, double comY, double comZ,
        double tipDirX, double tipDirZ,
        double overhang,
        double angularVelX, double angularVelZ
    ) {
        /** Convenience factory for the no-action case. */
        public static Result stable(double cx, double cy, double cz) {
            return new Result(State.STABLE, cx, cy, cz, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Perform a stability check.
     *
     * @param com      island centre of mass from {@link StructureIslandRegistry.StructureIsland#getCoM}
     * @param anchors  set of anchor blocks (from AnchorContinuityChecker)
     * @param deadband fraction dead-band (use {@link #DEFAULT_DEADBAND} or BRConfig value)
     * @return stability result; never null
     */
    public static Result check(double[] com, Set<BlockPos> anchors, double deadband) {
        if (anchors == null || anchors.isEmpty()) {
            return Result.stable(com[0], com[1], com[2]);
        }

        // ─── 1. Build anchor AABB in XZ ───
        int minAX = Integer.MAX_VALUE, maxAX = Integer.MIN_VALUE;
        int minAZ = Integer.MAX_VALUE, maxAZ = Integer.MIN_VALUE;
        int minAY = Integer.MAX_VALUE;
        for (BlockPos a : anchors) {
            minAX = Math.min(minAX, a.getX()); maxAX = Math.max(maxAX, a.getX());
            minAZ = Math.min(minAZ, a.getZ()); maxAZ = Math.max(maxAZ, a.getZ());
            minAY = Math.min(minAY, a.getY());
        }

        // ─── 2. Support polygon centroid + inradius ───
        // +1 accounts for the 1m block width so a single-block base has inradius = 0.5 m
        double centX   = (minAX + maxAX + 1) * 0.5;
        double centZ   = (minAZ + maxAZ + 1) * 0.5;
        double inradius = Math.min(maxAX - minAX + 1, maxAZ - minAZ + 1) * 0.5;
        inradius = Math.max(inradius, 0.5); // single-block minimum

        double cx = com[0], cy = com[1], cz = com[2];
        double dx = cx - centX;
        double dz = cz - centZ;
        double offset = Math.sqrt(dx * dx + dz * dz);

        double margin = (inradius - offset) / inradius;

        // ─── 3. Classify ───
        if (margin > deadband) {
            return Result.stable(cx, cy, cz);
        }

        double tipDirX = (offset < 1e-9) ? 1.0 : dx / offset;
        double tipDirZ = (offset < 1e-9) ? 0.0 : dz / offset;
        double overhang = Math.max(offset - inradius, 0.0);
        double comHeight = Math.max(cy - minAY, 1.0);

        // ω = scale × √(g × overhang / h_com) — pendulum approximation
        double omegaMag = ANGULAR_SCALE
            * Math.sqrt(PhysicsConstants.GRAVITY * overhang / comHeight);

        // Tipping axis is horizontal, perpendicular to tipDir:
        // if tipping toward +X, rotation is around Z axis (angVelZ)
        // angVelX =  tipDirZ × ω,  angVelZ = -tipDirX × ω
        double angVelX = tipDirZ * omegaMag;
        double angVelZ = -tipDirX * omegaMag;

        State state = (margin <= 0.0) ? State.TIPPING : State.MARGINAL;
        return new Result(state, cx, cy, cz, tipDirX, tipDirZ, overhang, angVelX, angVelZ);
    }
}
