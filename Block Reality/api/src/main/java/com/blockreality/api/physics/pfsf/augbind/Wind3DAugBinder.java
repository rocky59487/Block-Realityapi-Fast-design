package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import com.blockreality.api.spi.IWindManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.nio.FloatBuffer;

/**
 * v0.4 M2e — WIND_FIELD_3D augmentation binder.
 *
 * <p>Publishes a 3-component wind vector {@code (vx, vy, vz)} per voxel
 * in m/s. The dispatcher's {@code OP_AUG_WIND_3D_BIAS} opcode consumes
 * this DBB to bias the directional conductivity:
 * {@code cond[d][i] *= 1 ± k·dot(dir[d], wind[i])}.
 *
 * <p>Sources:
 * <ol>
 *   <li>If {@link IWindManager} is registered, per-voxel wind speed is
 *       read from it and multiplied by the engine's current uniform
 *       wind direction — good enough for localised wind tunnels.</li>
 *   <li>Otherwise, the engine's global {@code currentWindVec} is applied
 *       uniformly to every voxel.</li>
 *   <li>If neither is available the binder is inactive and the slot is
 *       cleared.</li>
 * </ol>
 */
public final class Wind3DAugBinder extends AbstractAugBinder {

    /** 3 floats × 4 bytes per voxel. */
    private static final int STRIDE = 3 * Float.BYTES;

    public Wind3DAugBinder() {
        super(NativePFSFBridge.AugKind.WIND_FIELD_3D, STRIDE);
    }

    @Override
    protected boolean isActive() {
        return ModuleRegistry.getWindManager() != null
                || PFSFEngine.getCurrentWindVec() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        Vec3 globalWind = PFSFEngine.getCurrentWindVec();
        IWindManager wind = ModuleRegistry.getWindManager();
        if (wind == null && globalWind == null) return false;

        /* Pre-compute uniform direction (unit vector) from whatever
         * global wind vector is available. If both the manager and the
         * global are missing we'd have short-circuited above. */
        double dx = globalWind != null ? globalWind.x : 0.0;
        double dy = globalWind != null ? globalWind.y : 0.0;
        double dz = globalWind != null ? globalWind.z : 0.0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1e-9) { dx /= len; dy /= len; dz /= len; }
        else            { dx = 1.0;  dy = 0.0;  dz = 0.0; }
        float baseSpeed = (float) len;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int voxelBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    float speed;
                    if (wind != null) {
                        speed = wind.getWindSpeedAt(probe);
                    } else {
                        speed = baseSpeed;
                    }
                    if (speed == 0.0f) continue;

                    int idx = (voxelBase + x) * 3;
                    out.put(idx,     (float) (speed * dx));
                    out.put(idx + 1, (float) (speed * dy));
                    out.put(idx + 2, (float) (speed * dz));
                    any = true;
                }
            }
        }
        return any;
    }
}
