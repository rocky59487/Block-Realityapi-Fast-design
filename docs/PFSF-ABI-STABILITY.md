# PFSF ABI Stability Pledge

**Status**: in force from **v0.4.0-rc1** onwards. This document is the
canonical statement of what external consumers (Forge mods, tooling,
packagers) can rely on when linking against `libblockreality_pfsf`.

## Summary

`libpfsf` follows **SemVer**:

- **MAJOR** bump (`1.x.y → 2.0.0`) — a symbol is removed, renamed, or
  has its signature changed; a struct field is renamed, reordered, or
  resized; an existing `enum` value is renumbered.
- **MINOR** bump (`1.3.0 → 1.4.0`) — new symbol added; new enum value
  appended; new field appended to the end of a struct **behind a
  `struct_bytes` forward-compat marker**. Old consumers link
  and run unchanged.
- **PATCH** bump (`1.4.0 → 1.4.1`) — bugfix only; the exported shape is
  bit-identical. An old consumer cannot observe the difference.

The current ABI version is queryable at runtime in two forms — use the
contract-version form for compatibility gating:

```c
/* Canonical external probe — returns the semver string pinned in
 * pfsf_v1.abi.json (e.g. "1.5.0"). Statically allocated, do NOT free.
 * Added in v1.3. */
const char* contract = pfsf_abi_contract_version();

/* Internal implementation-phase counter. Historical; its value can
 * move independently of the contract semver, so do NOT compare it
 * against anything persistent. Keep for diagnostic logs only. */
uint32_t v = pfsf_abi_version();
uint32_t phase_major = (v >> 16) & 0xFF;
uint32_t phase_minor = (v >>  8) & 0xFF;
uint32_t phase_patch =  v        & 0xFF;
```

`pfsf_abi_contract_version()` is itself covered by this pledge — the
function signature (`const char* (void)`, returning a NUL-terminated
semver) will not change across any MAJOR bump without a full migration
guide.

## What the pledge covers

The pledge is **enforced** over exactly the surface that
`pfsf_v1.abi.json` pins and that `scripts/check_abi.py` validates on
every PR:

- **Exported symbols** — the `symbols` array in `pfsf_v1.abi.json`.
  Every name listed there must remain exported, with an unchanged
  signature, across MINOR and PATCH bumps. Adding a name is a MINOR
  bump; removing or renaming one is MAJOR.
- **Enum values** — the `enums` table in `pfsf_v1.abi.json`. Existing
  constants may not be renumbered; new constants may be appended under
  MINOR.
- **The semantics of every exported function covered by the snapshot** —
  specifically its thread-safety promise, its signal-safety promise
  (where relevant), and its return-code set.

PR#187 capy-ai R59 narrowed this clause. The previous wording promised
coverage of *every* symbol / struct / enum declared in
`L1-native/libpfsf/include/pfsf/*.h`, but the pinned snapshot covers
only a subset (e.g. currently excludes `pfsf_create`, `pfsf_init`,
`pfsf_shutdown`, `pfsf_destroy`, `pfsf_get_stats`, `pfsf_add_island`,
and struct layouts like `pfsf_config` / `pfsf_stats` /
`pfsf_material` / `pfsf_failure_event` / `pfsf_tick_result`) and
`check_abi.py` never validates struct layouts. Declaring the broader
surface frozen would have promised an enforcement CI couldn't
deliver. Expanding the snapshot + gate to cover the full header set
is tracked as follow-up work; once that lands the pledge text here
will expand in lock-step. Until then, treat anything outside
`pfsf_v1.abi.json` as **best-effort compatible across MINOR bumps but
not machine-gated**.

## What the pledge does NOT cover

- Headers under `L1-native/libpfsf/src/**` — those are internal and may
  change arbitrarily.
- The `pfsf_internal_*` symbol prefix — debugging aids, never ABI.
- Performance characteristics — a patch release may make things faster
  or slower within the bounds the perf-gate enforces.
- The exact wire format of `pfsf-crash-<pid>.trace` beyond the
  documented 64-byte `pfsf_trace_event` layout. Fields in the ASCII
  header may be added (consumers must tolerate unknown `key=value`
  pairs) but never removed.
- The contents of the opaque `pfsf_engine` handle.

## Forward-compatibility rules for struct evolution

New fields may be appended to the end of any public struct under
MINOR bumps **only** if:

1. The struct carries a `uint32_t struct_bytes` as its first member
   (v0.4 adds this to new structs; pre-v0.4 structs are frozen at
   their current size).
2. Consumers pass `sizeof(the_struct)` into that field at construction.
3. The library reads up to `min(struct_bytes, sizeof(internal))`,
   never further.
4. New fields have documented zero-init defaults that match legacy
   behaviour.

If any of these hold, older consumers remain correct; newer consumers
opt in by recompiling.

## When MAJOR bump is required

Any of the following forces MAJOR:

- Symbol removal (including renaming without a temporary alias).
- Function signature change (parameter count, parameter type, return
  type).
- Struct layout shift — renaming a field, reordering, resizing, or
  inserting a field anywhere but at the end.
- Changing the numeric value of an existing enum constant.
- Changing the semantics of a return code (reusing a code for a
  different condition).
- Changing a function's thread-safety / signal-safety promise in a
  way that breaks callers that relied on the old promise.

MAJOR bumps are announced with a full migration guide at
`docs/MIGRATION-v<old>-to-v<new>.md` and at least one MINOR release of
deprecation warnings where feasible.

## CI enforcement

The `.github/workflows/check-abi.yml` workflow runs on every PR:

- **headers-only** job (always runs): diffs the public headers
  against `pfsf_v1.abi.json` — any non-additive change fails the PR.
- **binary** job (runs on PR titles containing `[check-binary-abi]`):
  runs `abidw` on the built shared library per platform
  (linux-x64, win-x64) and compares against the committed snapshot.

A PR that requires a MAJOR bump **cannot** merge without manually
updating `pfsf_v1.abi.json` to the new major and adding a MIGRATION
doc — this is not bypassable by the bot.

## Version history

| Version | Landed in | Change |
|---------|-----------|--------|
| v1.0.0  | v0.3d     | Initial ABI |
| v1.1.0  | v0.3e M2  | +13 plan-buffer opcodes |
| v1.2.0  | v0.3e M5  | +3 crash-handler entry points |
| v1.3.0  | v0.4 M1   | +`pfsf_abi_contract_version` |
| v1.4.0  | v0.4 M2   | +4 SPI-augmentation plan opcodes |
| v1.5.0  | v0.4 M4   | +`pfsf_set_pcg_enabled` runtime toggle |

## Questions?

Open an issue at https://github.com/rocky59487/block-realityapi-fast-design
with the `abi-pledge` label. Before doing so, please:

1. Print `pfsf_abi_version()` and `pfsf_abi_contract_version()` from
   your process.
2. Include the output of `pfsf_build_info()` if available.
3. State exactly which symbol / struct / enum is in question, and
   whether the break is compile-time or runtime.
