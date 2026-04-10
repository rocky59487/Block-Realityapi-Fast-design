package com.blockreality.api.fragment;

import com.blockreality.api.physics.PhysicsConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/**
 * Per-tick rigid body physics for a disconnected structure fragment.
 *
 * Physics model
 * ─────────────
 *   Translation : semi-implicit Euler   v += a·Δt,  x += v·Δt
 *   Rotation    : quaternion integration q ← q ⊗ exp(½ω·Δt)
 *   Gravity     : 9.81 m/s² (from PhysicsConstants.GRAVITY)
 *   Air drag    : linear coefficient k = 0.02 per tick
 *   Restitution : e = 0.25  (partially elastic bounce)
 *   Friction    : μ_k = 0.6 (kinetic, applied at contact)
 *   Rolling     : angular impulse from off-centre contact point (τ = r × F)
 *   Sleep       : specific KE < 0.01 J/kg AND on-ground → freeze
 *
 * Collision detection
 * ───────────────────
 *   • Broad-phase : entity AABB vs world solid blocks (checked at 9 ground sample points)
 *   • Narrow-phase: per-ground-point penetration depth resolved with vertical impulse
 *   • Side walls  : 4 lateral AABB edge samples
 *
 * This avoids copying VS/Create by:
 *   1. No ship-assembly or mechanical connection — purely consequence-driven
 *   2. Impulse-based (not constraint-solver) → simpler, Minecraft-tick-friendly
 *   3. Voxel-aware rolling via discrete contact sampling, not continuous GJK/EPA
 */
public class StructureRigidBody {

    // ─── Simulation constants ───
    static final double DRAG            = 0.02;   // linear drag per tick (fraction of velocity lost)
    static final double ANG_DRAG        = 0.04;   // angular drag per tick
    static final double RESTITUTION     = 0.25;   // coefficient of restitution (bounce)
    static final double FRICTION_MU     = 0.60;   // kinetic friction coefficient
    static final double SLEEP_KE_LIMIT  = 0.01;   // J/kg — specific KE threshold for sleep
    static final double DT              = PhysicsConstants.TICK_DT;   // 0.05 s/tick
    static final int    MAX_TICKS       = 600;     // 30 s hard lifetime

    // ─── Physics state ───

    /** World-space CoM position (m). */
    public double px, py, pz;

    /** World-space velocity (m/s). */
    public double vx, vy, vz;

    /**
     * Orientation quaternion [qx, qy, qz, qw] (normalised).
     * Identity = (0, 0, 0, 1).
     */
    public double qx, qy, qz, qw;

    /** Angular velocity in world frame (rad/s). */
    public double wx, wy, wz;

    // ─── Fragment geometry ───

    /** Total mass (kg). */
    public final double mass;

    /** Inverse scalar moment of inertia (simplified: average of principal moments). */
    private final double invI;

    /**
     * Fragment half-extents (m) — used for AABB broad-phase.
     * Computed once from the block set; expands slightly under rotation via conservativeRadius.
     */
    public final double halfW, halfH, halfD;

    /**
     * Conservative bounding sphere radius: encloses all possible orientations of the AABB.
     * halfExtent_sphere = sqrt(halfW² + halfH² + halfD²).
     */
    private final double conservativeRadius;

    // ─── Lifecycle ───
    public boolean sleeping   = false;
    public boolean onGround   = false;
    public int     ticksAlive = 0;

    // ─── Ground sample offsets (3×3 grid at bottom face) ───
    private static final double[] GROUND_SX = {-0.45, 0.0, 0.45, -0.45, 0.0, 0.45, -0.45, 0.0, 0.45};
    private static final double[] GROUND_SZ = {-0.45,-0.45,-0.45,  0.0,  0.0,  0.0,  0.45, 0.45, 0.45};

    // ─── Constructor ───

    public StructureRigidBody(StructureFragment frag, Set<BlockPos> localBlocks) {
        this.mass = frag.totalMass();

        // Initial state from fragment
        px = frag.comX(); py = frag.comY(); pz = frag.comZ();
        vx = frag.velX(); vy = frag.velY(); vz = frag.velZ();
        wx = frag.angVelX(); wy = frag.angVelY(); wz = frag.angVelZ();
        qx = 0; qy = 0; qz = 0; qw = 1; // identity rotation

        // Compute AABB half-extents from local block set (relative to CoM)
        double minLX = Double.MAX_VALUE, maxLX = -Double.MAX_VALUE;
        double minLY = Double.MAX_VALUE, maxLY = -Double.MAX_VALUE;
        double minLZ = Double.MAX_VALUE, maxLZ = -Double.MAX_VALUE;
        double Ixx = 0, Iyy = 0, Izz = 0;
        double voxelMass = mass / Math.max(1, localBlocks.size());

        for (BlockPos lp : localBlocks) {
            // Local position: corner at (lp.x, lp.y, lp.z), centre at +0.5
            double lx = lp.getX(), ly = lp.getY(), lz = lp.getZ();
            minLX = Math.min(minLX, lx); maxLX = Math.max(maxLX, lx + 1.0);
            minLY = Math.min(minLY, ly); maxLY = Math.max(maxLY, ly + 1.0);
            minLZ = Math.min(minLZ, lz); maxLZ = Math.max(maxLZ, lz + 1.0);
            // Inertia: parallel-axis theorem, unit-cube I_cm = m*(b²+c²)/12 + m*d²
            double cx = lx + 0.5, cy = ly + 0.5, cz = lz + 0.5;
            Ixx += voxelMass * (cy*cy + cz*cz + (1.0 + 1.0) / 12.0);
            Iyy += voxelMass * (cx*cx + cz*cz + (1.0 + 1.0) / 12.0);
            Izz += voxelMass * (cx*cx + cy*cy + (1.0 + 1.0) / 12.0);
        }

        halfW = (maxLX - minLX) * 0.5;
        halfH = (maxLY - minLY) * 0.5;
        halfD = (maxLZ - minLZ) * 0.5;
        conservativeRadius = Math.sqrt(halfW * halfW + halfH * halfH + halfD * halfD);
        double Iavg = Math.max((Ixx + Iyy + Izz) / 3.0, 0.001);
        invI = 1.0 / Iavg;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Main tick — returns true if the body should be removed (settled)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Advance physics by one tick.
     *
     * @param level  server level for world-block queries
     * @return true  if this fragment should settle (remove entity, place rubble)
     */
    public boolean tick(ServerLevel level) {
        if (sleeping) return false;
        ticksAlive++;
        if (ticksAlive >= MAX_TICKS) return true;

        // ─── 1. Gravity + linear drag ───
        vy -= PhysicsConstants.GRAVITY * DT;
        double dragFactor = 1.0 - DRAG;
        vx *= dragFactor;
        vy *= dragFactor;
        vz *= dragFactor;

        // ─── 2. Tentative position ───
        double nx = px + vx * DT;
        double ny = py + vy * DT;
        double nz = pz + vz * DT;

        // ─── 3. Collision resolution ───
        onGround = false;

        // Ground detection: sample 9 points at bottom face
        for (int s = 0; s < 9; s++) {
            double sampleX = nx + GROUND_SX[s] * halfW;
            double sampleZ = nz + GROUND_SZ[s] * halfD;
            double groundY = ny - halfH;

            BlockPos groundPos = new BlockPos(
                (int) Math.floor(sampleX),
                (int) Math.floor(groundY - 0.05),
                (int) Math.floor(sampleZ)
            );

            if (isSolid(level, groundPos)) {
                double floorTop = groundPos.getY() + 1.0;
                double penetration = floorTop - groundY;
                if (penetration > 0 && penetration < 2.0) {
                    ny += penetration;

                    // Relative contact point offset from CoM (local, pre-rotation approx)
                    double rcx = GROUND_SX[s] * halfW;
                    double rcy = -halfH;
                    double rcz = GROUND_SZ[s] * halfD;

                    // Velocity at contact point: v_c = v_CoM + ω × r
                    double vcx = vx + (wy * rcz - wz * rcy);
                    double vcz = vz + (wz * rcx - wx * rcz);
                    double vcy = vy + (wx * rcy - wy * rcx);

                    if (vcy < 0) {
                        // ─── Normal impulse (bounce) ───
                        // j_n = -(1+e)*v_cn / (1/m + (r×n)·I⁻¹·(r×n))
                        // Simplified: inertia term = invI * |r_xz|²
                        double rPerpSq = rcx * rcx + rcz * rcz;
                        double effMassInv = 1.0 / mass + invI * rPerpSq;
                        double jn = -(1.0 + RESTITUTION) * vcy / effMassInv;

                        vy += jn / mass;

                        // Angular impulse from normal: Δω = I⁻¹ (r × n·jn)
                        // n = (0,1,0), r × n·jn = (rcz*jn, 0, -rcx*jn)
                        wx += invI * (rcz * jn);
                        wz += invI * (-rcx * jn);

                        // ─── Friction impulse (rolling driver) ───
                        // Tangential velocity at contact
                        double vtx = vcx;
                        double vtz = vcz;
                        double vtSpd = Math.sqrt(vtx * vtx + vtz * vtz);
                        if (vtSpd > 1e-6) {
                            double frictionMag = FRICTION_MU * Math.abs(jn);
                            // Cap friction to not reverse tangential motion
                            frictionMag = Math.min(frictionMag, vtSpd * mass);
                            double jfx = -vtx / vtSpd * frictionMag;
                            double jfz = -vtz / vtSpd * frictionMag;

                            // Linear friction
                            vx += jfx / mass;
                            vz += jfz / mass;

                            // Rolling torque: Δω = I⁻¹ (r × f)
                            // r × f = (rcy*jfz - rcz*0, rcz*jfx - rcx*jfz, rcx*0 - rcy*jfx)
                            wx += invI * (rcy * jfz);
                            wy += invI * (rcz * jfx - rcx * jfz);
                            wz += invI * (-rcy * jfx);
                        }

                        onGround = true;
                    }
                }
            }
        }

        // Side collision: X axis
        for (int sign : new int[]{-1, 1}) {
            double sampleX = nx + sign * (halfW + 0.05);
            BlockPos sidePos = new BlockPos(
                (int) Math.floor(sampleX),
                (int) Math.floor(ny),
                (int) Math.floor(nz)
            );
            if (isSolid(level, sidePos)) {
                nx -= sign * 0.1;
                vx *= -RESTITUTION;
                wx += sign * wy * 0.3; // impart spin from side hit
            }
        }

        // Side collision: Z axis
        for (int sign : new int[]{-1, 1}) {
            double sampleZ = nz + sign * (halfD + 0.05);
            BlockPos sidePos = new BlockPos(
                (int) Math.floor(nx),
                (int) Math.floor(ny),
                (int) Math.floor(sampleZ)
            );
            if (isSolid(level, sidePos)) {
                nz -= sign * 0.1;
                vz *= -RESTITUTION;
                wz += sign * wx * 0.3;
            }
        }

        // ─── 4. Apply resolved position ───
        px = nx; py = ny; pz = nz;

        // ─── 5. Angular drag ───
        double angDragFactor = 1.0 - ANG_DRAG;
        wx *= angDragFactor;
        wy *= angDragFactor;
        wz *= angDragFactor;

        // ─── 6. Integrate rotation (quaternion exponential map) ───
        // q_new = q ⊗ (cos(θ/2) + sin(θ/2)*n̂)   where θ = |ω|·Δt
        double angSpeed = Math.sqrt(wx*wx + wy*wy + wz*wz);
        if (angSpeed > 1e-8) {
            double halfAngle = angSpeed * DT * 0.5;
            double sinH = Math.sin(halfAngle);
            double cosH = Math.cos(halfAngle);
            double nx2 = wx / angSpeed * sinH;
            double ny2 = wy / angSpeed * sinH;
            double nz2 = wz / angSpeed * sinH;
            double nw2 = cosH;
            // q = q_old ⊗ q_delta
            double newQx = qw*nx2 + qx*nw2 + qy*nz2 - qz*ny2;
            double newQy = qw*ny2 - qx*nz2 + qy*nw2 + qz*nx2;
            double newQz = qw*nz2 + qx*ny2 - qy*nx2 + qz*nw2;
            double newQw = qw*nw2 - qx*nx2 - qy*ny2 - qz*nz2;
            double norm = Math.sqrt(newQx*newQx + newQy*newQy + newQz*newQz + newQw*newQw);
            if (norm > 1e-10) {
                qx = newQx / norm; qy = newQy / norm;
                qz = newQz / norm; qw = newQw / norm;
            }
        }

        // ─── 7. Sleep check ───
        double ke = 0.5 * mass * (vx*vx + vy*vy + vz*vz)
                  + 0.5 * (wx*wx + wy*wy + wz*wz) / invI;
        if (onGround && ke / mass < SLEEP_KE_LIMIT) {
            sleeping = true;
        }

        return false;
    }

    /** World AABB for Minecraft entity bounds update. Expands conservatively under rotation. */
    public AABB worldAABB() {
        // Under rotation the OBB corners can extend up to conservativeRadius from CoM.
        // Use sphere-AABB as a safe upper bound (no need for exact OBB transform).
        double r = conservativeRadius;
        return new AABB(px - r, py - r, pz - r, px + r, py + r, pz + r);
    }

    // ─── Helpers ───

    private static boolean isSolid(ServerLevel level, BlockPos pos) {
        // Out-of-range or client-side → treat as non-solid to avoid crash
        if (!level.isLoaded(pos)) return false;
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
