# Migration Guide — v0.3d → v0.3e (PFSF)

This guide is for **external mod authors** who build against the PFSF
native surface (`libblockreality_pfsf`, headers under
`L1-native/libpfsf/include/pfsf/`). It covers only the ABI / behavioural
deltas that affect callers; internal refactors are documented in the
`CHANGELOG.md` v0.3e section.

If you integrate with PFSF exclusively through the Java `PFSFEngine` /
`IPFSFRuntime` / `ModuleRegistry` surface, **you have nothing to do**.
v0.3e keeps every Java seam bit-for-bit compatible with v0.3d.

---

## ABI bump — v1.0.0 → v1.2.0 (additive only)

v0.3e lands two MINOR additive bumps on top of v0.3d's `v1.0.0` baseline:

| Bump | Landed in | Adds |
|------|-----------|------|
| **v1.0 → v1.1** | M2 (plan opcode completeness) | 12 new `pfsf_plan_opcode` enumerators; `pfsf_compute_conductivity` promoted from stub to live entry |
| **v1.1 → v1.2** | M5 (async-signal-safe crash dump) | `pfsf_install_crash_handler`, `pfsf_uninstall_crash_handler`, `pfsf_dump_now_for_test` |

**Guarantee**: no symbols removed, no struct layouts changed. A v0.3d
consumer linking against the v0.3e shared library continues to work
unchanged, provided you do not attempt to use the new enumerators or
entry points.

**Detection at runtime**: prefer `pfsf_abi_version()` and
`pfsf_has_feature("crash.dump")` over compile-time ifdefs so a single
binary handles both versions.

```c
if (pfsf_abi_version() >= ((1u << 16) | (2u << 8))) {
    pfsf_install_crash_handler();
}
```

## What's different for callers

### 1. Plan buffer is now the fast path (M2 + M3)

The per-primitive JNI entry points (`nativeNormalizeSoA6`,
`nativeApplyWindBias`, `nativeChebyshevOmega`, …) still exist and still
work — they remain a stable back-door for tests, parity harnesses, and
golden-vector reference code.

For tick-hot paths, **prefer assembling a `pfsf_tick_plan` and issuing a
single `pfsf_tick_dbb` call**. The engine amortises boundary cost across
opcodes within the plan; the 50k-voxel surrogate tick lands at
≥1.4× the Java reference on our ubuntu-22.04 baseline because of this
amortisation. Per-primitive JNI calls do not benefit from it.

The new opcodes (all 12 cover the v0.3d Phase 1–4 primitive set) live in
`pfsf_plan.h` and are documented in the header. Each opcode has a
bit-exact Java reference in `PFSFTickPlanner` that you can cross-check.

### 2. Crash handler is opt-in and chain-safe (M5)

v0.3e ships an async-signal-safe SIGSEGV/SIGABRT handler. It is **not**
installed by default — you must call `pfsf_install_crash_handler()` at
process start to arm it. The handler:

- writes a truncated trace ring to `<cwd>/pfsf-crash-<pid>.trace`
- delegates to whatever previous handler was installed (chain-safe —
  the JVM's `hs_err_pid.log` still appears)
- honours `BR_PFSF_NO_SIGNAL=1` as a one-shot disable switch

If you already have a custom crash handler, install it **before**
PFSF's; the PFSF handler records state, then re-raises.

### 3. Crash-dump Java surface

`NativePFSFBridge` gains three helpers:

- `nativeCrashInstall()` — mirror of the C entry
- `nativeCrashUninstall()` — tear down before process exit to restore
  prior handlers cleanly
- `nativeCrashDumpForTest(String path, int signo, long faultAddr)` —
  force-writes a trace file without raising a signal (used by
  `PFSFCrashHandlerTest`)

### 4. `pfsf_compute_conductivity` is live (M2)

The Phase 3 stub is gone. Callers that previously guarded this entry
behind `pfsf_has_feature("compute.conductivity")` can drop the guard on
v0.3e. The C++ implementation is bit-exact against `PFSFConductivity.java`
(golden-vector verified).

### 5. Performance regression gate

`benchmarks/baselines/v0.3e-linux-x64.json` pins four primitives:
`normalize_soa6_64k`, `chebyshev_table_64`, `apply_wind_bias_64k`,
`tick50k_surrogate`. The nightly `perf-gate` workflow fails CI on any
>5% regression or any `native_over_java` dropping below floor.

If you package-ship a derivative build of `libblockreality_pfsf`,
re-pin the baseline on your runner with
`scripts/pfsf_perf_gate.py --update-baseline` (hypothetical; pin by
hand if you prefer a reviewable diff).

### 6. Citation gate on external source

`scripts/check_citations.py` now enforces the `@cite` / `@algorithm` /
`@maps_to` triad across `L1-native/libpfsf/**` and the PFSF Java
package. Fork builds that add new `PFSF_API` symbols must carry a
provenance annotation (one of `@cite`, `@algorithm`, `@see`, `@maps_to`)
or land the new symbol in `CITATION_EXEMPT`.

The bibliography at `docs/L1-api/L2-physics/bibliography.md` is a
build artifact — `python3 scripts/check_citations.py
--emit-bibliography <path>` rewrites it.

## What's the same

- 11-method `PFSFEngine` static facade — signatures frozen
- `IPFSFRuntime` seam — no new methods
- 9 SPI interfaces (`IThermalManager`, `ICableManager`, …) —
  behaviour unchanged
- 15+ golden-vector JUnit tests — still pass
- 26-connectivity stencil, σ-max normalisation, hField writer ownership,
  Fluid 1-tick lag — invariants maintained
- Java reference path (`*JavaRef` suites) — still live; weekly parity
  gate verifies no drift

## Recommended upgrade checklist

1. Rebuild against the v0.3e `libblockreality_pfsf` and confirm startup
   logs show `ABI version 1.2.0`.
2. If you currently call per-primitive entries on the tick hot path,
   migrate to plan buffer opcodes (see `PFSFTickPlanner` for a reference
   assembler).
3. Opt into the crash handler if your mod benefits from post-mortem
   traces: `pfsf_install_crash_handler()` early in world load, match
   with a `uninstall` on shutdown.
4. Run your existing integration tests — if they pass, you're done.
5. Optional: add a `pfsf_has_feature("compute.conductivity")` removal
   pass to your code base; the feature is unconditionally present on
   v0.3e.

## Where to get help

- Issues on `rocky59487/block-realityapi-fast-design`
- `docs/L1-api/L2-physics/bibliography.md` for the formula provenance
- `docs/L1-api/L2-physics/L3-pfsf.md` for engine-level documentation
