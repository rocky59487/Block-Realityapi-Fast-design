# PFSF Golden-Vector Fixtures

This directory holds snapshot JSON files used by `GoldenParityTest` to
verify the Java reference path stays bit-identical to the native libpfsf
path (once Phase 1–8 kernels land) and, during Phase 0, to smoke-test
the fixture I/O layer itself.

## Schema (v1)

```json
{
  "schema_version": 1,
  "fixture_id": "cantilever_5x20x1_v2",
  "description": "5x20x1 cantilever anchored at y=0.",
  "recorded_at": "2026-04-17T12:00:00Z",
  "git_sha": "4a5c9c8",
  "dims": { "lx": 5, "ly": 20, "lz": 1 },
  "anchors": [[0,0,0],[1,0,0],[2,0,0],[3,0,0],[4,0,0]],
  "materials": {
    "voxels":   "<base64 int32[N]>",
    "registry": [{"id":1,"name":"concrete_c30","rcomp":30.0,"rtens":3.0}]
  },
  "fluid_pressure": "<base64 float32[N]>",
  "curing":         "<base64 float32[N]>",
  "wind":           [5.0, 0.0, 0.0],
  "ticks":          1000,
  "expected_stress":   "<base64 float32[N]>",
  "expected_failures": [{"pos":[2,15,0],"type":"CANTILEVER_COLLAPSE","tick":847}],
  "tolerances":        {"stress_abs": 1e-5, "failure_tick": 5}
}
```

## Capture workflow

1. Run a server with `-Dblockreality.native.pfsf=false` (Java ref path,
   authoritative during Phase 0–5).
2. `/br pfsf dump <islandId>` — writes a snapshot next to the world save.
   (Command scaffolding lands in Phase 5. Until then, fixtures are
   synthesised from `GoldenParityTest.syntheticFixture(...)` helpers.)
3. Copy the JSON into this directory.
4. The fixture is immediately consumed by `GoldenParityTest`; no
   registration required beyond the file being present.

## Naming conventions

- Lowercase `snake_case` with dimension hint: `cantilever_5x20x1_v2.json`.
- Append `_vN` when re-recording after a legitimate formula update
  (keep the old fixture one version for regression reference).

## Phase 0 stance

No real fixtures are committed during Phase 0 — `GoldenParityTest`
synthesises deterministic micro-fixtures in-memory so CI stays green
while the native kernels are being brought up. Real fixtures start
landing in Phase 1 as individual formulas acquire native
implementations.
