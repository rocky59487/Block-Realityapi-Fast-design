package com.blockreality.api.physics.pfsf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * v0.3d Phase 6 — fluent assembler for tick plan buffers.
 *
 * <p>A tick plan is a single direct ByteBuffer composed of length-
 * prefixed opcode records; see {@code pfsf_plan.h} for the binary
 * layout. This builder lets orchestrator code queue every action for a
 * given tick and then flush the whole thing to the native dispatcher
 * in one JNI call — dissolving the per-primitive boundary cost that
 * v0.3c still paid.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * PFSFTickPlanner plan = PFSFTickPlanner.forIsland(buf.getIslandId())
 *         .pushClearAugIsland()
 *         .pushFireHook(NativePFSFBridge.HookPoint.PRE_SOURCE, epoch)
 *         .pushFireHook(NativePFSFBridge.HookPoint.POST_SCAN, epoch);
 * plan.execute();  // single JNI call
 * }</pre>
 *
 * <p>Instances are not thread-safe; each tick should allocate (or
 * reuse a thread-local) planner and drain it. The underlying
 * ByteBuffer grows on demand when opcodes overflow the initial
 * reserve; callers that want to eliminate steady-state allocations
 * should size the planner via {@link #forIsland(int, int)}.</p>
 */
public final class PFSFTickPlanner {

    /** Default opcode reserve — enough for ~8 hook fires + clears. */
    private static final int DEFAULT_RESERVE_BYTES = 256;

    private static final int HEADER_BYTES    = 16;
    private static final int OP_HEADER_BYTES = 4;
    private static final int PLAN_MAGIC      = 0x46534650; // "PFSF" read LE

    private ByteBuffer buf;
    private int        opCount;
    private final int  islandId;

    private PFSFTickPlanner(int islandId, int reserveBytes) {
        this.islandId = islandId;
        this.buf = ByteBuffer.allocateDirect(Math.max(reserveBytes, HEADER_BYTES))
                             .order(ByteOrder.LITTLE_ENDIAN);
        writeHeaderSkeleton();
    }

    // ── factory ─────────────────────────────────────────────────────────

    public static PFSFTickPlanner forIsland(int islandId) {
        return new PFSFTickPlanner(islandId, DEFAULT_RESERVE_BYTES);
    }

    public static PFSFTickPlanner forIsland(int islandId, int reserveBytes) {
        return new PFSFTickPlanner(islandId, reserveBytes);
    }

    // ── opcode pushers ──────────────────────────────────────────────────

    public PFSFTickPlanner pushNoOp() {
        writeOpHeader(NativePFSFBridge.PlanOp.NO_OP, 0);
        return this;
    }

    /** Test-only: increments the dispatcher's atomic counter by delta. */
    public PFSFTickPlanner pushIncrCounter(int delta) {
        ensureCapacity(OP_HEADER_BYTES + 4);
        writeOpHeader(NativePFSFBridge.PlanOp.INCR_COUNTER, 4);
        buf.putInt(delta);
        return this;
    }

    /** Clear one augmentation slot (see {@link NativePFSFBridge.AugKind}). */
    public PFSFTickPlanner pushClearAug(int kind) {
        ensureCapacity(OP_HEADER_BYTES + 4);
        writeOpHeader(NativePFSFBridge.PlanOp.CLEAR_AUG, 4);
        buf.putInt(kind);
        return this;
    }

    /** Clear every augmentation slot attached to this plan's island. */
    public PFSFTickPlanner pushClearAugIsland() {
        writeOpHeader(NativePFSFBridge.PlanOp.CLEAR_AUG_ISLAND, 0);
        return this;
    }

    /** Fire the registered hook at the given point (see {@link NativePFSFBridge.HookPoint}). */
    public PFSFTickPlanner pushFireHook(int point, long epoch) {
        ensureCapacity(OP_HEADER_BYTES + 12);
        writeOpHeader(NativePFSFBridge.PlanOp.FIRE_HOOK, 12);
        buf.putInt(point);
        buf.putLong(epoch);
        return this;
    }

    // ── accessors / execution ───────────────────────────────────────────

    /** @return current byte size of the assembled plan (header + ops). */
    public int size() { return buf.position(); }

    /** @return number of opcodes queued so far. */
    public int opCount() { return opCount; }

    /** @return underlying direct buffer (positioned at size, limit unchanged). */
    public ByteBuffer buffer() { return buf; }

    /**
     * Ship the plan to the native dispatcher.
     *
     * @param outResult may be null; otherwise int[4] = {executed, failedIndex, errorCode, hookFireCount}
     * @return {@code PFSFResult} code
     */
    public int execute(int[] outResult) {
        if (!NativePFSFBridge.hasComputeV6()) {
            return NativePFSFBridge.PFSFResult.ERROR_NOT_INIT;
        }
        finaliseHeader();
        return NativePFSFBridge.nativePlanExecute(buf, buf.position(), outResult);
    }

    /** Convenience — discards detailed result. */
    public int execute() { return execute(null); }

    /** Reset to an empty plan for reuse within the same island. */
    public PFSFTickPlanner reset() {
        buf.clear();
        opCount = 0;
        writeHeaderSkeleton();
        return this;
    }

    // ── internals ───────────────────────────────────────────────────────

    private void writeHeaderSkeleton() {
        buf.putInt(PLAN_MAGIC);        // magic
        buf.putShort((short) 1);       // version
        buf.putShort((short) 0);       // flags
        buf.putInt(islandId);          // island_id
        buf.putInt(0);                 // opcode_count — patched at finalise
    }

    private void finaliseHeader() {
        final int saved = buf.position();
        buf.putInt(12, opCount);       // overwrite the header slot in place
        buf.position(saved);           // leave the cursor where we found it
    }

    private void writeOpHeader(int opcode, int argBytes) {
        ensureCapacity(OP_HEADER_BYTES);
        buf.putShort((short) opcode);
        buf.putShort((short) argBytes);
        opCount++;
    }

    private void ensureCapacity(int extra) {
        if (buf.remaining() >= extra) return;
        final int saved = buf.position();
        final int need  = saved + extra;
        int cap = buf.capacity() * 2;
        while (cap < need) cap *= 2;
        ByteBuffer grown = ByteBuffer.allocateDirect(cap).order(ByteOrder.LITTLE_ENDIAN);
        buf.flip();
        grown.put(buf);
        buf = grown;
    }
}
