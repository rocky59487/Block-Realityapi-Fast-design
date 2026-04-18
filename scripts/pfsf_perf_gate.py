#!/usr/bin/env python3
"""
v0.3e M6 — PFSF perf-gate.

Compares a PfsfBenchmark run (build/pfsf-bench/results.json) against a
pinned baseline (benchmarks/baselines/<triple>.json) and exits non-zero
on regression.

Policy (per baseline entry):

  java_ns_per_op  <=  java_ns_per_op_max  * (1 + tolerance_pct/100)
      -- fails if the Java reference path itself regressed, which would
         silently invalidate the native_over_java ratio check.

  native_over_java  >=  native_over_java_min  * (1 - tolerance_pct/100)
      -- skipped when the runner doesn't have the .so (native_ns_per_op
         is null in results). Emits a warning instead of failing so
         Java-only runners stay green.

Usage:

    python3 scripts/pfsf_perf_gate.py \\
        --results build/pfsf-bench/results.json \\
        --baseline benchmarks/baselines/v0.3e-linux-x64.json

Exit codes:
    0 — all primitives within budget
    1 — regression detected (message on stderr)
    2 — bad arguments / missing file
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_json(p: Path) -> dict:
    try:
        with p.open("r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"error: failed to read {p}: {e}", file=sys.stderr)
        sys.exit(2)


def check(results: dict, baseline: dict, require_native: bool) -> int:
    tol = float(baseline.get("tolerance_pct", 5.0)) / 100.0
    native_loaded = bool(results.get("native_loaded", False))

    by_name = {r["name"]: r for r in results["results"]}
    failures: list[str] = []
    warnings: list[str] = []

    for spec in baseline["primitives"]:
        name = spec["name"]
        row = by_name.get(name)
        if row is None:
            failures.append(f"{name}: not present in results")
            continue

        j_max = float(spec["java_ns_per_op_max"])
        j_obs = float(row["java_ns_per_op"])
        j_lim = j_max * (1.0 + tol)
        if j_obs > j_lim:
            failures.append(
                f"{name}: java_ns_per_op {j_obs:.2f} > limit {j_lim:.2f} "
                f"(baseline max {j_max:.2f} + {tol*100:.1f}%)"
            )
        else:
            print(
                f"[ok] {name}: java_ns_per_op {j_obs:.2f} "
                f"(limit {j_lim:.2f})"
            )

        n_min_raw = spec.get("native_over_java_min")
        n_obs = row.get("native_over_java")

        if n_min_raw is None:
            # Native check disabled for this primitive.
            continue

        if n_obs is None:
            msg = (
                f"{name}: native_over_java unavailable "
                f"(native_loaded={native_loaded}) — native speedup check skipped"
            )
            if require_native:
                failures.append(msg + " [--require-native]")
            else:
                warnings.append(msg)
            continue

        n_min = float(n_min_raw)
        n_lim = n_min * (1.0 - tol)
        if float(n_obs) < n_lim:
            failures.append(
                f"{name}: native_over_java {n_obs:.2f} < floor {n_lim:.2f} "
                f"(baseline min {n_min:.2f} - {tol*100:.1f}%)"
            )
        else:
            print(
                f"[ok] {name}: native_over_java {n_obs:.2f} "
                f"(floor {n_lim:.2f})"
            )

    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)

    if failures:
        print("", file=sys.stderr)
        print("PERF-GATE FAIL", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print("")
    print("PERF-GATE PASS")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results", required=True, type=Path,
                    help="Path to build/pfsf-bench/results.json")
    ap.add_argument("--baseline", required=True, type=Path,
                    help="Path to benchmarks/baselines/<triple>.json")
    ap.add_argument("--require-native", action="store_true",
                    help="Treat a missing native_over_java column as failure "
                         "instead of warning. CI runners that built the .so "
                         "should pass this.")
    args = ap.parse_args()

    if not args.results.exists():
        print(f"error: results file not found: {args.results}", file=sys.stderr)
        return 2
    if not args.baseline.exists():
        print(f"error: baseline file not found: {args.baseline}", file=sys.stderr)
        return 2

    results  = load_json(args.results)
    baseline = load_json(args.baseline)
    return check(results, baseline, args.require_native)


if __name__ == "__main__":
    sys.exit(main())
