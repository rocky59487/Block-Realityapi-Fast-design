package com.blockreality.api.physics.pfsf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v0.3d Phase 7 — Java-side consumer for the native trace ring buffer.
 *
 * <p>The native side emits structured 64-byte events at opcode
 * boundaries / errors; Java drains them at a cadence of its choice
 * (per tick, per failure, on demand) and routes into SLF4J or a mod
 * metrics collector. Emission is cheap enough for always-on ERROR
 * + WARN levels in production.</p>
 *
 * <p>All methods no-op cleanly when {@code compute.v7} isn't available
 * — callers never have to guard on the feature probe themselves.</p>
 */
public final class PFSFTrace {

    /** On-wire event size. Frozen at 64 bytes for ABI v1. */
    public static final int EVENT_BYTES = 64;

    private PFSFTrace() {}

    /** Structured trace record after Java parsing. */
    public static final class Event {
        public final long   epoch;
        public final int    stage;
        public final int    islandId;
        public final int    voxelIndex;
        public final int    errnoVal;
        public final short  level;
        public final String msg;

        Event(long epoch, int stage, int islandId, int voxelIndex,
              int errnoVal, short level, String msg) {
            this.epoch      = epoch;
            this.stage      = stage;
            this.islandId   = islandId;
            this.voxelIndex = voxelIndex;
            this.errnoVal   = errnoVal;
            this.level      = level;
            this.msg        = msg;
        }

        @Override public String toString() {
            return String.format(
                    "[PFSF-TRACE lvl=%d epoch=%d island=%d stage=%d voxel=%d err=%d] %s",
                    level, epoch, islandId, stage, voxelIndex, errnoVal, msg);
        }
    }

    public static boolean isAvailable() { return NativePFSFBridge.hasComputeV7(); }

    /** Set the emission threshold; events below are dropped at emit-time. */
    public static void setLevel(int level) {
        if (!NativePFSFBridge.hasComputeV7()) return;
        try {
            NativePFSFBridge.nativeTraceSetLevel(level);
        } catch (UnsatisfiedLinkError ignored) { }
    }

    public static int getLevel() {
        if (!NativePFSFBridge.hasComputeV7()) return NativePFSFBridge.TraceLevel.OFF;
        try {
            return NativePFSFBridge.nativeTraceGetLevel();
        } catch (UnsatisfiedLinkError e) {
            return NativePFSFBridge.TraceLevel.OFF;
        }
    }

    /** Emit one event into the ring. No-op when compute.v7 absent or level filtered. */
    public static void emit(int level, long epoch, int stage, int islandId,
                              int voxelIndex, int errnoVal, String msg) {
        if (!NativePFSFBridge.hasComputeV7()) return;
        try {
            NativePFSFBridge.nativeTraceEmit((short) level, epoch, stage, islandId,
                    voxelIndex, errnoVal, msg == null ? "" : msg);
        } catch (UnsatisfiedLinkError ignored) { }
    }

    /** @return current event count in the ring. */
    public static int size() {
        if (!NativePFSFBridge.hasComputeV7()) return 0;
        try {
            return NativePFSFBridge.nativeTraceSize();
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** Drop every queued event without reading. */
    public static void clear() {
        if (!NativePFSFBridge.hasComputeV7()) return;
        try {
            NativePFSFBridge.nativeTraceClear();
        } catch (UnsatisfiedLinkError ignored) { }
    }

    /**
     * Drain up to {@code capacity} events, parsing each into an
     * {@link Event}. Allocates a single direct ByteBuffer of
     * {@code capacity * 64} bytes per call; cache the buffer in a
     * tick-local field if you drain every tick.
     */
    public static List<Event> drain(int capacity) {
        if (!NativePFSFBridge.hasComputeV7() || capacity <= 0) {
            return Collections.emptyList();
        }
        ByteBuffer out = ByteBuffer.allocateDirect(capacity * EVENT_BYTES)
                                    .order(ByteOrder.LITTLE_ENDIAN);
        int n;
        try {
            n = NativePFSFBridge.nativeTraceDrain(out, capacity);
        } catch (UnsatisfiedLinkError e) {
            return Collections.emptyList();
        }
        if (n <= 0) return Collections.emptyList();

        List<Event> events = new ArrayList<>(n);
        byte[] msgBuf = new byte[36];
        for (int i = 0; i < n; i++) {
            int base = i * EVENT_BYTES;
            long epoch      = out.getLong(base);
            int  stage      = out.getInt(base + 8);
            int  islandId   = out.getInt(base + 12);
            int  voxelIndex = out.getInt(base + 16);
            int  errnoVal   = out.getInt(base + 20);
            short level     = out.getShort(base + 24);
            // skip 2-byte pad at base+26
            out.position(base + 28);
            out.get(msgBuf, 0, 36);
            int len = 0;
            while (len < 36 && msgBuf[len] != 0) len++;
            String msg = new String(msgBuf, 0, len, StandardCharsets.UTF_8);
            events.add(new Event(epoch, stage, islandId, voxelIndex,
                                 errnoVal, level, msg));
        }
        return events;
    }
}
