package com.blockreality.api.spi;

import com.blockreality.api.fragment.StructureFragment;
import net.minecraft.server.level.ServerLevel;

/**
 * No-op VS2 bridge used when Valkyrien Skies 2 is not installed.
 *
 * <p>Always returns {@code false} from {@link #assembleAsShip}, causing
 * {@link com.blockreality.api.fragment.StructureFragmentManager} to fall back
 * to the built-in {@code StructureFragmentEntity + StructureRigidBody}.
 *
 * <p>Registered automatically at mod init; replaced by
 * {@link com.blockreality.api.vs2.VS2ShipBridge} if VS2 is detected.
 */
public final class NoOpVS2Bridge implements IVS2Bridge {

    public static final NoOpVS2Bridge INSTANCE = new NoOpVS2Bridge();

    private NoOpVS2Bridge() {}

    @Override
    public boolean isAvailable() { return false; }

    @Override
    public boolean assembleAsShip(ServerLevel level, StructureFragment fragment) { return false; }
}
