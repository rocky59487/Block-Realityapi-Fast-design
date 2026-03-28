package com.blockreality.fastdesign.client;

import com.blockreality.api.blueprint.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 藍圖全息投影狀態 — v3fix §3.1
 */
@OnlyIn(Dist.CLIENT)
public class HologramState {

    private record Snapshot(Blueprint blueprint, BlockPos origin, BlockPos offset, int rotationY, boolean visible) {}

    private static volatile Snapshot current = new Snapshot(null, BlockPos.ZERO, BlockPos.ZERO, 0, false);

    public static void load(Blueprint bp, BlockPos playerPos) {
        current = new Snapshot(bp, playerPos.immutable(), BlockPos.ZERO, 0, true);
    }

    public static void clear() {
        current = new Snapshot(null, BlockPos.ZERO, BlockPos.ZERO, 0, false);
    }

    public static boolean isActive() {
        Snapshot snap = current;
        return snap.blueprint != null && snap.visible;
    }

    public static void setOffset(int dx, int dy, int dz) {
        Snapshot snap = current;
        current = new Snapshot(
            snap.blueprint, snap.origin,
            new BlockPos(snap.offset.getX() + dx, snap.offset.getY() + dy, snap.offset.getZ() + dz),
            snap.rotationY, snap.visible
        );
    }

    public static void rotate() {
        Snapshot snap = current;
        current = new Snapshot(snap.blueprint, snap.origin, snap.offset,
            (snap.rotationY + 90) % 360, snap.visible);
    }

    public static void toggleVisible() {
        Snapshot snap = current;
        current = new Snapshot(snap.blueprint, snap.origin, snap.offset,
            snap.rotationY, !snap.visible);
    }

    public static Blueprint getBlueprint() { return current.blueprint; }
    public static boolean isVisible() { return current.visible; }
    public static int getRotationY() { return current.rotationY; }

    public static BlockPos getWorldPos(int relX, int relY, int relZ) {
        Snapshot snap = current;
        int rx = relX, rz = relZ;
        switch (snap.rotationY) {
            case 90 -> { rx = relZ; rz = -relX; }
            case 180 -> { rx = -relX; rz = -relZ; }
            case 270 -> { rx = -relZ; rz = relX; }
        }
        return new BlockPos(
            snap.origin.getX() + snap.offset.getX() + rx,
            snap.origin.getY() + snap.offset.getY() + relY,
            snap.origin.getZ() + snap.offset.getZ() + rz
        );
    }

    public static BlockPos getEffectiveOrigin() {
        Snapshot snap = current;
        return new BlockPos(
            snap.origin.getX() + snap.offset.getX(),
            snap.origin.getY() + snap.offset.getY(),
            snap.origin.getZ() + snap.offset.getZ()
        );
    }
}
