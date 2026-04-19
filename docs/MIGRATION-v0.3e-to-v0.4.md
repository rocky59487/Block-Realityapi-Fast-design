# Migration Guide — v0.3e → v0.4 (PFSF)

This guide is for **external mod authors** who build against the PFSF
native surface (`libblockreality_pfsf`, headers under
`L1-native/libpfsf/include/pfsf/`). It covers only the ABI / behavioural
deltas that affect callers; internal refactors are documented in
`Block Reality/CHANGELOG.md`.

If you integrate with PFSF exclusively through the Java `PFSFEngine` /
`IPFSFRuntime` / `ModuleRegistry` surface, **you have nothing to do**.
v0.4 keeps every Java seam bit-for-bit compatible with v0.3e.

---

## ABI bump — v1.2.0 → v1.4.0 (additive only)

v0.4 lands two MINOR additive bumps on top of v0.3e's `v1.2.0`:

| Bump | Landed in | Adds |
|------|-----------|------|
| **v1.2 → v1.3** | M1 (multi-platform CI) | `pfsf_abi_contract_version` — runtime probe for the ABI-stability pledge |
| **v1.3 → v1.4** | M2 (SPI augmentation) | 4 new `pfsf_plan_opcode` enumerators — `PFSF_OP_AUG_SOURCE_ADD` / `AUG_COND_MUL` / `AUG_RCOMP_MUL` / `AUG_WIND_3D_BIAS` |

**Guarantee**: no symbols removed, no struct layouts changed, no enum
values renumbered. A v0.3e consumer linking against the v0.4 shared
library continues to work unchanged provided you do not consume the new
opcodes. See `docs/PFSF-ABI-STABILITY.md` for the formal pledge.

**Detection at runtime**: prefer `pfsf_abi_version()` over compile-time
ifdefs.

```c
uint32_t v = pfsf_abi_version();
uint32_t major = (v >> 16) & 0xFF;
uint32_t minor = (v >>  8) & 0xFF;
if (major == 1 && minor >= 4) {
    /* safe to push PFSF_OP_AUG_* opcodes */
}
```

---

## What's different for callers

### 1. Multi-platform native packaging (M1)

v0.4 ships pre-built binaries for **two** platforms (was one):

- `linux-x64` — Ubuntu 22.04 GLIBC 2.35, x86-64-v3
- `win-x64`   — Windows 10+, MSVC /arch:AVX2

`mpd.jar` (the merged mod jar) now bundles both under
`META-INF/native/<triple>/`.  `NativePFSFBridge` extracts the matching
binary to `java.io.tmpdir/blockreality-native-<sha>/` on first touch and
calls `System.load`.  No action needed — it's transparent.

> **macOS / Apple-Silicon was dropped from v0.4 scope.**  The code still
> builds cleanly under MoltenVK locally, but no signed binaries ship this
> cycle.  See `.github/workflows/build.yml` — re-enabling is a single
> matrix entry once MoltenVK's FP32 reduction-order drift is
> characterised.

If you previously used `System.loadLibrary("blockreality_pfsf")` directly
instead of going through `NativePFSFBridge`, you still can — that entry
point is unchanged.  But note the bundled binary is no longer guaranteed
to be on `java.library.path` on Windows; `NativePFSFBridge.isAvailable()`
encapsulates the correct lookup order.

### 2. SPI augmentation fields now reach the solver (M2)

In v0.3e the 9 augmentation kinds (`THERMAL_FIELD`, `FLUID_PRESSURE`,
`EM_FIELD`, `CURING_FIELD`, `WIND_FIELD_3D`, `FUSION_MASK`,
`MATERIAL_OVR`, `LOADPATH_HINT`, `TENSION_OVERRIDE`) had registration
infrastructure but zero opcode consumption — the solver never read the
DBBs you published.

v0.4 wires four **generic** opcodes that drain the aug registry
(`pfsf_aug_query`) at the appropriate hook point:

| Opcode | Effect | Consuming kinds |
|--------|--------|-----------------|
| `PFSF_OP_AUG_SOURCE_ADD`   | `source[i] += slot[i]`                     | THERMAL / FLUID / EM / CURING |
| `PFSF_OP_AUG_COND_MUL`     | `cond[d·N+i] *= slot[i]`                   | FUSION / MATERIAL_OVR |
| `PFSF_OP_AUG_RCOMP_MUL`    | `rcomp[i] *= slot[i]` (clamped [0,2])      | CURING (negative side) |
| `PFSF_OP_AUG_WIND_3D_BIAS` | `cond[d·N+i] *= 1 ± k·dot(dir, wind[i])`   | WIND_FIELD_3D |

`LOADPATH_HINT` and `TENSION_OVERRIDE` stay on the Java-only
`PFSFAugmentationHost.query(kind)` path — they were always hints /
direct-override, never hot-loop inputs.

**Migration**: if you were publishing DBBs and wondering why they had no
effect, upgrade to v0.4 and implement an `AbstractAugBinder<T>` in
`com.blockreality.api.physics.pfsf.augbind.*`.  The 4 shipped binders
(`ThermalAugBinder` / `FluidAugBinder` / `EMAugBinder` /
`CuringAugBinder` + 4 more) are end-to-end examples.

**Bit-eq guarantee**: `AugmentationOffTest` proves that when no
augmentation is published, the golden fixtures produce a
bit-identical result against the v0.3e.1 baseline. Adding a binder is
strictly opt-in.

### 3. Fixture capture and replay tooling (M3)

New developer tooling that does not affect production callers, but the
entry points are public:

- **`/br pfsf dump <islandId>`** / **`/br pfsf dumpAll`** — in-game
  command that writes a schema-v1 fixture JSON per island to
  `<world>/pfsf-fixtures/`.  Useful for filing regression reports.
- **`scripts/generate_canonical_fixtures.py`** — procedural generator
  for the 20 canonical fixtures (cantilever / arch / column / smoke …).
- **`L1-native/libpfsf/src/example/pfsf_cli`** — fixture replay tool
  with `--backend=vk` (GPU) and `--backend=cpu` (no-GPU numeric verify)
  modes.
- **`scripts/pfsf_crash_decode.py`** — decodes the binary
  `pfsf-crash-<pid>.trace` produced by the crash handler into
  newline-delimited JSON.
- **`/br debug pfsf`** — now appends a per-island LOD /
  macro-residual table (lod, stable-ticks, oscillation-count, active
  macro-block ratio, last-tick max macro residual).

None of these change the ABI or solver behaviour; they are
purely diagnostic.

---

## Checklist for v0.3e → v0.4 upgrade

- [ ] Run `pfsf_abi_version()` → expect `((1u << 16) | (4u << 8))` on
      v0.4 builds. Anything under `v1.2` means a stale library is being
      loaded.
- [ ] If your mod ships its own bundled native: rebuild against the
      v0.4 headers (additive opcodes only — no breakage expected).
- [ ] If your mod publishes SPI augmentation DBBs: expect the values to
      actually reach the solver now.  Review any clamping/normalisation
      assumptions — RCOMP_MUL clamps to `[0, 2]` with a trace-warn.
- [ ] If you shipped a macOS .dylib alongside Block Reality: v0.4 no
      longer includes one in `mpd.jar`; build locally or wait for
      v0.4.1.
- [ ] No action required for pure-Java integrations.
