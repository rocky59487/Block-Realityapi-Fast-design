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

    // ── v0.4 M2 — augmentation opcodes (consume PFSFAugmentationHost slots) ─
    //
    // All four share an island_id/kind/target/count/bounds prefix; the
    // dispatcher reads the DBB address and version via aug_query on the
    // native side. Unregistered slots are a silent no-op — call sites
    // always safe to push without checking whether the SPI is wired.

    /** @see NativePFSFBridge.PlanOp#AUG_SOURCE_ADD */
    public PFSFTickPlanner pushAugSourceAdd(int kind, long sourceAddr, int n,
                                              float lo, float hi) {
        final int args = 32;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.AUG_SOURCE_ADD, args);
        buf.putLong(islandId);   // arg @  0 — 64-bit island id
        buf.putInt(kind);        // arg @  8
        buf.putLong(sourceAddr); // arg @ 12 (misaligned; buffer is LE flat)
        buf.putInt(n);           // arg @ 20
        buf.putFloat(lo);        // arg @ 24
        buf.putFloat(hi);        // arg @ 28
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#AUG_COND_MUL */
    public PFSFTickPlanner pushAugCondMul(int kind, long condAddr, int n,
                                            float lo, float hi) {
        final int args = 32;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.AUG_COND_MUL, args);
        buf.putLong(islandId);
        buf.putInt(kind);
        buf.putLong(condAddr);
        buf.putInt(n);
        buf.putFloat(lo);
        buf.putFloat(hi);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#AUG_RCOMP_MUL */
    public PFSFTickPlanner pushAugRcompMul(int kind, long rcompAddr, int n,
                                             float lo, float hi) {
        final int args = 32;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.AUG_RCOMP_MUL, args);
        buf.putLong(islandId);
        buf.putInt(kind);
        buf.putLong(rcompAddr);
        buf.putInt(n);
        buf.putFloat(lo);
        buf.putFloat(hi);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#AUG_WIND_3D_BIAS */
    public PFSFTickPlanner pushAugWind3DBias(int kind, long condAddr, int n,
                                               float k, float lo, float hi) {
        final int args = 36;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.AUG_WIND_3D_BIAS, args);
        buf.putLong(islandId);
        buf.putInt(kind);
        buf.putLong(condAddr);
        buf.putInt(n);
        buf.putFloat(k);
        buf.putFloat(lo);
        buf.putFloat(hi);
        return this;
    }

    // ── v0.3e M2 compute pushers ────────────────────────────────────────
    //
    // Each push* method below enqueues one opcode whose args hold raw
    // int64 addresses into caller-owned direct buffers. Callers obtain
    // those addresses via {@link NativePFSFBridge#nativeDirectBufferAddress}
    // or LWJGL's {@code MemoryUtil.memAddress}. Zero is a sentinel for
    // "not supplied" where the underlying primitive supports it.
    //
    // The per-record arg_bytes prefix carries forward-compat: future
    // additions will grow these tails, and older dispatchers will simply
    // read the leading prefix they recognise. See pfsf_plan.h for the
    // canonical layout spec; these methods are the Java mirror.

    /** @see NativePFSFBridge.PlanOp#NORMALIZE_SOA6 */
    public PFSFTickPlanner pushNormalizeSoA6(long sourceAddr, long rcompAddr,
                                              long rtensAddr, long condAddr,
                                              long hydrationAddr, long outSigmaAddr,
                                              int n) {
        final int args = 52;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.NORMALIZE_SOA6, args);
        buf.putLong(sourceAddr);
        buf.putLong(rcompAddr);
        buf.putLong(rtensAddr);
        buf.putLong(condAddr);
        buf.putLong(hydrationAddr);   // zero = caller has no hydration field
        buf.putLong(outSigmaAddr);
        buf.putInt(n);
        buf.putInt(0);                // _pad, reserved for future flags
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#APPLY_WIND_BIAS */
    public PFSFTickPlanner pushApplyWindBias(long condAddr, int n,
                                              float windX, float windY, float windZ,
                                              float upwindFactor) {
        final int args = 32;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.APPLY_WIND_BIAS, args);
        buf.putLong(condAddr);
        buf.putInt(n);
        buf.putInt(0);                // _pad
        buf.putFloat(windX);
        buf.putFloat(windY);
        buf.putFloat(windZ);
        buf.putFloat(upwindFactor);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#COMPUTE_CONDUCTIVITY */
    public PFSFTickPlanner pushComputeConductivity(long condAddr, long rcompAddr,
                                                    long rtensAddr, long typeAddr,
                                                    int lx, int ly, int lz,
                                                    float windX, float windY, float windZ,
                                                    float upwindFactor) {
        final int args = 64;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.COMPUTE_CONDUCTIVITY, args);
        buf.putLong(condAddr);
        buf.putLong(rcompAddr);
        buf.putLong(rtensAddr);
        buf.putLong(typeAddr);
        buf.putInt(lx);
        buf.putInt(ly);
        buf.putInt(lz);
        buf.putInt(0);                // _pad
        buf.putFloat(windX);
        buf.putFloat(windY);
        buf.putFloat(windZ);
        buf.putFloat(upwindFactor);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#ARM_MAP */
    public PFSFTickPlanner pushArmMap(long membersAddr, long anchorsAddr,
                                       long outArmAddr,
                                       int lx, int ly, int lz) {
        final int args = 40;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.ARM_MAP, args);
        buf.putLong(membersAddr);
        buf.putLong(anchorsAddr);
        buf.putLong(outArmAddr);
        buf.putInt(lx);
        buf.putInt(ly);
        buf.putInt(lz);
        buf.putInt(0);                // _pad
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#ARCH_FACTOR */
    public PFSFTickPlanner pushArchFactor(long membersAddr, long anchorsAddr,
                                           long outArchAddr,
                                           int lx, int ly, int lz) {
        final int args = 40;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.ARCH_FACTOR, args);
        buf.putLong(membersAddr);
        buf.putLong(anchorsAddr);
        buf.putLong(outArchAddr);
        buf.putInt(lx);
        buf.putInt(ly);
        buf.putInt(lz);
        buf.putInt(0);                // _pad
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#PHANTOM_EDGES */
    public PFSFTickPlanner pushPhantomEdges(long membersAddr, long condAddr,
                                             long rcompAddr, long outInjectedAddr,
                                             int lx, int ly, int lz,
                                             float edgePenalty, float cornerPenalty) {
        final int args = 56;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.PHANTOM_EDGES, args);
        buf.putLong(membersAddr);
        buf.putLong(condAddr);
        buf.putLong(rcompAddr);
        buf.putLong(outInjectedAddr);  // zero = caller doesn't care about the count
        buf.putInt(lx);
        buf.putInt(ly);
        buf.putInt(lz);
        buf.putInt(0);                 // _pad
        buf.putFloat(edgePenalty);
        buf.putFloat(cornerPenalty);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#DOWNSAMPLE_2TO1 */
    public PFSFTickPlanner pushDownsample2to1(long fineAddr, long fineTypeAddr,
                                               long coarseAddr, long coarseTypeAddr,
                                               int lxf, int lyf, int lzf) {
        final int args = 48;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.DOWNSAMPLE_2TO1, args);
        buf.putLong(fineAddr);
        buf.putLong(fineTypeAddr);
        buf.putLong(coarseAddr);
        buf.putLong(coarseTypeAddr);
        buf.putInt(lxf);
        buf.putInt(lyf);
        buf.putInt(lzf);
        buf.putInt(0);                // _pad
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#TILED_LAYOUT */
    public PFSFTickPlanner pushTiledLayout(long linearAddr, long outAddr,
                                            int lx, int ly, int lz, int tile) {
        final int args = 32;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.TILED_LAYOUT, args);
        buf.putLong(linearAddr);
        buf.putLong(outAddr);
        buf.putInt(lx);
        buf.putInt(ly);
        buf.putInt(lz);
        buf.putInt(tile);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#CHEBYSHEV */
    public PFSFTickPlanner pushChebyshev(long outAddr, int iter, float rhoSpec) {
        final int args = 20;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.CHEBYSHEV, args);
        buf.putLong(outAddr);
        buf.putInt(iter);
        buf.putInt(0);                // _pad
        buf.putFloat(rhoSpec);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#CHECK_DIVERGENCE */
    public PFSFTickPlanner pushCheckDivergence(long stateAddr, long macroResidualsAddr,
                                                long outKindAddr, float maxPhiNow,
                                                int macroCount, float divergenceRatio,
                                                float dampingSettleThreshold) {
        final int args = 40;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.CHECK_DIVERGENCE, args);
        buf.putLong(stateAddr);
        buf.putLong(macroResidualsAddr);   // zero = no macro residuals
        buf.putLong(outKindAddr);
        buf.putFloat(maxPhiNow);
        buf.putInt(macroCount);
        buf.putFloat(divergenceRatio);
        buf.putFloat(dampingSettleThreshold);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#EXTRACT_FEATURES */
    public PFSFTickPlanner pushExtractFeatures(long residualsAddr, long out12Addr,
                                                int lx, int ly, int lz,
                                                int chebyshevIter,
                                                int oscillationCount, int dampingActive,
                                                int stableTickCount, int lodLevel,
                                                int lodDormant, int pcgAllocated,
                                                int macroCount,
                                                float rhoSpecOverride,
                                                float prevMaxMacroResidual) {
        final int args = 72;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.EXTRACT_FEATURES, args);
        buf.putLong(residualsAddr);
        buf.putLong(out12Addr);
        buf.putInt(lx);
        buf.putInt(ly);
        buf.putInt(lz);
        buf.putInt(chebyshevIter);
        buf.putInt(oscillationCount);
        buf.putInt(dampingActive);
        buf.putInt(stableTickCount);
        buf.putInt(lodLevel);
        buf.putInt(lodDormant);
        buf.putInt(pcgAllocated);
        buf.putInt(macroCount);
        buf.putInt(0);                // _pad
        buf.putFloat(rhoSpecOverride);
        buf.putFloat(prevMaxMacroResidual);
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#WIND_PRESSURE */
    public PFSFTickPlanner pushWindPressure(long outAddr, float windSpeed,
                                             float densityKgM3, boolean exposed) {
        final int args = 24;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.WIND_PRESSURE, args);
        buf.putLong(outAddr);
        buf.putFloat(windSpeed);
        buf.putFloat(densityKgM3);
        buf.putInt(exposed ? 1 : 0);
        buf.putInt(0);                // reserved for future Cp override
        return this;
    }

    /** @see NativePFSFBridge.PlanOp#TIMOSHENKO */
    public PFSFTickPlanner pushTimoshenko(long outAddr, float b, float h,
                                           int arm, float youngsGpa, float nu) {
        final int args = 28;
        ensureCapacity(OP_HEADER_BYTES + args);
        writeOpHeader(NativePFSFBridge.PlanOp.TIMOSHENKO, args);
        buf.putLong(outAddr);
        buf.putFloat(b);
        buf.putFloat(h);
        buf.putInt(arm);
        buf.putFloat(youngsGpa);
        buf.putFloat(nu);
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
