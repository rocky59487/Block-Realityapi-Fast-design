package com.blockreality.fastdesign.client;

import com.blockreality.api.blueprint.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 藍圖全息投影狀態 — v3fix §3.1
 * 管理並維護客戶端全息投影 (Hologram) 的全域狀態。
 *
 * <p>範例套用:
 * <pre>
 * // 載入並顯示藍圖
 * HologramState.load(myBlueprint, playerPos);
 *
 * // 隱藏/顯示切換
 * HologramState.toggleVisible();
 *
 * // 獲取目前繪製時的偏移量與原點
 * BlockPos origin = HologramState.getEffectiveOrigin();
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public class HologramState {

    private record Snapshot(Blueprint blueprint, BlockPos origin, BlockPos offset, int rotationY, boolean visible) {}

    private static volatile Snapshot current = new Snapshot(null, new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), 0, false);
    /** Incremented on every state change; HologramRenderer uses this to detect dirty VBO. */
    private static volatile int version = 0;

    /**
     * 載入並啟動藍圖全息投影。
     * @param bp 要投影的藍圖資料
     * @param playerPos 投影的初始原點（通常為玩家位置）
     */
    public static void load(Blueprint bp, BlockPos playerPos) {
        current = new Snapshot(bp, playerPos.immutable(), BlockPos.ZERO, 0, true);
        version++;
    }

    public static void clear() {
        current = new Snapshot(null, BlockPos.ZERO, BlockPos.ZERO, 0, false);
        version++;
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
        version++;
    }

    public static void rotate() {
        Snapshot snap = current;
        current = new Snapshot(snap.blueprint, snap.origin, snap.offset,
            (snap.rotationY + 90) % 360, snap.visible);
        version++;
    }

    public static void toggleVisible() {
        Snapshot snap = current;
        current = new Snapshot(snap.blueprint, snap.origin, snap.offset,
            snap.rotationY, !snap.visible);
        version++;
    }

    /** Returns a monotonically increasing counter; changes on any state mutation. */
    public static int getVersion() { return version; }

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
