# FDv1 Comprehensive Audit Report

**Project:** Block Reality + Fast Design
**Version:** v1.1.0-alpha (FDv1)
**Date:** 2026-03-26
**Scope:** All Java sources under `com.blockreality.api` + `com.blockreality.fastdesign`
**Methodology:** Static code review, line-by-line verification of flagged findings

---

## Executive Summary

Audited 50+ Java files across API and FD layers. Identified **10 actionable issues** (1 Critical, 3 High, 4 Medium, 2 Low). 7 issues from the previous audit session were confirmed fixed. 2 flagged findings were verified as false positives.

---

## Findings

### CRITICAL

#### C1 — ConstructionHudOverlay: Inverted Null Check (HUD Never Renders)
- **File:** `fastdesign/client/ConstructionHudOverlay.java` line 48
- **Code:** `if (event.getOverlay() != null) { return; }`
- **Impact:** Construction zone HUD overlay is **completely non-functional**. The condition is inverted — it returns early when overlay exists, meaning render code is never reached.
- **Fix:** Change `!= null` to `== null`

---

### HIGH

#### H1 — HologramSyncPacket: Buffer Desync on Null Blueprint
- **File:** `fastdesign/network/HologramSyncPacket.java` lines 79-82
- **Code:** LOAD case reads `blueprintTag` from buffer; if null, returns early without reading `originX/Y/Z` (3 ints). Encoder always writes all 4 fields.
- **Impact:** Null blueprint tag causes packet buffer misalignment, corrupting all subsequent packets in the same batch.
- **Fix:** Read all fields before null check, or throw decode exception.

#### H2 — FdActionPacket: Corrupted Packets Default to UNDO
- **File:** `fastdesign/network/FdActionPacket.java` lines 106-115
- **Code:** Invalid action ordinal silently creates `new FdActionPacket(Action.UNDO, "")` instead of rejecting.
- **Impact:** Malformed/tampered packets silently execute UNDO instead of being rejected. Masks protocol errors.
- **Fix:** Throw `IllegalArgumentException` on invalid ordinal.

#### H3 — BeamStressEngine.beamKey(): Long Overflow in Hash
- **File:** `api/sph/BeamStressEngine.java` line 386
- **Code:** `return la < lb ? (la * 31 + lb) : (lb * 31 + la);`
- **Impact:** `BlockPos.asLong()` returns large values; `*31` can overflow `long`, producing hash collisions and phantom beam connections.
- **Fix:** Use `Long.hashCode()` or XOR-based combination: `la ^ (lb * 0x9E3779B97F4A7C15L)`

---

### MEDIUM

#### M1 — MultiBlockCalculator.computeMirror(): Silent No-Op on Invalid Axis
- **File:** `api/placement/MultiBlockCalculator.java` lines 172-176
- **Code:** Switch on `axis` char handles 'x', 'y', 'z' but has no default case.
- **Impact:** Invalid axis char silently returns the original position unmirrored, masking caller bugs.
- **Fix:** Add `default -> throw new IllegalArgumentException("Invalid mirror axis: " + axis)`

#### M2 — NurbsExporter: Process Resource Leak on Timeout
- **File:** `fastdesign/sidecar/NurbsExporter.java` lines 117-158
- **Code:** Process streams not explicitly closed when `waitFor()` times out. `destroyForcibly()` called but streams may linger.
- **Impact:** Leaked file descriptors under timeout conditions.
- **Fix:** Add `process.getInputStream().close()` / `getErrorStream().close()` / `getOutputStream().close()` in finally block.

#### M3 — Rebar Spacing: No Server-Side Validation
- **File:** `fastdesign/command/FdCommandRegistry.java` (rebarGrid handler)
- **Code:** Client validates spacing in `ControlPanelState` (`Math.max(1, Math.min(16, spacing))`), but server accepts any value.
- **Impact:** Packet manipulation can bypass client limits.
- **Fix:** Add `FastDesignConfig.getRebarSpacingMax()` check in server handler.

#### M4 — Preview3DRenderer: Large Selection Performance
- **File:** `fastdesign/client/Preview3DRenderer.java` lines 256-261
- **Code:** Iterates all blocks in selection, calling `getBlockState()` per block, capped at 2000 renders but uncapped iteration.
- **Impact:** Lag spikes in CAD mode with large selections (e.g., 100x100x100 = 1M iterations).
- **Fix:** Early exit if `sel.volume() > 10000` before entering the loop.

---

### LOW

#### L1 — FastDesignScreen: Null Blueprint Fields in Display
- **File:** `fastdesign/client/FastDesignScreen.java` lines 286-301
- **Code:** `blueprint.getName()` etc. displayed without null check — shows "null" text.
- **Fix:** Null-coalesce: `name != null ? name : "(unnamed)"`

#### L2 — DefaultCableManager: Documented Thread Unsafety
- **File:** `api/physics/DefaultCableManager.java`
- **Code:** `@NotThreadSafe` annotation present. Node positions mutated during tick without synchronization.
- **Status:** Documented and acceptable for server-tick-only access pattern. Noting for future multi-thread consideration.

---

## Verified False Positives

| Flagged Issue | Verdict | Reason |
|---|---|---|
| CableState.calculateTension() div/zero | **DENIED** | `segCount` guaranteed > 0 by `nodes.size() < 2` guard |
| BeamStressEngine null material (line 176) | **DENIED** | No immediate null dereference at that line |
| ClientSelectionHolder volatile race | **SAFE** | Correct local variable snapshot pattern |

---

## Previously Fixed (Confirmed)

| Issue | Fix Applied |
|---|---|
| UndoManager thread safety | ConcurrentHashMap + ConcurrentLinkedDeque rewrite |
| UndoManager memory leak | PlayerLoggedOutEvent cleanup |
| GhostBlockRenderer depth | enableDepthTest + depthMask(false) |
| MultiBlockCalculator O(n^2) dedup | LinkedHashSet |
| FdCommandRegistry IOException | catch(Exception) |
| FdExtendedCommands lambda non-final | final String axisLower |
| NurbsExporter SidecarException | catch(Exception) |
| SelectionWandHandler null deref | Null guards added |
| FdExtendedCommands arch div/zero | spanX == 0 guard |

---

## Fix Plan (Priority Order)

| Priority | ID | Action | Estimated Risk |
|---|---|---|---|
| 1 | C1 | Fix ConstructionHudOverlay null check | Trivial |
| 2 | H1 | Fix HologramSyncPacket buffer desync | Low |
| 3 | H2 | Reject invalid FdActionPacket ordinals | Low |
| 4 | H3 | Fix BeamStressEngine.beamKey() overflow | Low |
| 5 | M1 | Add default case to computeMirror switch | Trivial |
| 6 | M2 | Close NurbsExporter process streams | Low |
| 7 | M3 | Add server-side rebar spacing validation | Trivial |
| 8 | M4 | Add volume cap to Preview3DRenderer | Trivial |
| 9 | L1 | Null-coalesce blueprint display fields | Trivial |
