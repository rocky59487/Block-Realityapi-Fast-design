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
import os
import subprocess
import sys
from pathlib import Path


def _current_head_sha() -> str | None:
    """Return the current HEAD sha, or ``None`` if git is unavailable.

    Repins are policy events — the sha lets future readers see which
    commit's measurements the baseline reflects. Silently tolerates
    missing git (e.g. a tarball build) so the normal repin path on a
    CI runner still succeeds.
    """
    try:
        out = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True, timeout=5,
        )
        sha = out.stdout.strip()
        return sha if sha else None
    except (FileNotFoundError, subprocess.SubprocessError):
        return None


def _gh_error(message: str, title: str = "perf-gate") -> None:
    """Emit a GitHub Actions workflow-command annotation on stderr.

    These lines are captured by the runner and surface on the public
    ``/repos/<owner>/<repo>/check-runs/<id>/annotations`` endpoint, which
    is readable without admin rights. Used to make perf-gate failures
    diagnosable from outside the job-logs API (HTTP 403 without admin)."""
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    # Escape per https://docs.github.com/actions/using-workflows/workflow-commands
    safe = (
        message.replace("%", "%25")
        .replace("\r", "%0D")
        .replace("\n", "%0A")
    )
    safe_title = title.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title={safe_title}::{safe}", file=sys.stderr)


def load_json(p: Path) -> dict:
    try:
        with p.open("r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"error: failed to read {p}: {e}", file=sys.stderr)
        sys.exit(2)


def _match_runner(results: dict, baseline: dict) -> list[str]:
    """Return a list of mismatch messages (empty = ok). Exit-2 caller."""
    runner = baseline.get("runner", {})
    mismatches = []
    for key in ("os", "arch"):
        b_val = runner.get(key)
        r_val = results.get(key)
        if b_val is None or r_val is None:
            continue
        if b_val.lower() != r_val.lower():
            mismatches.append(
                f"runner.{key} mismatch: baseline={b_val!r} results={r_val!r}"
            )
    b_jvm = runner.get("jvm")
    r_jvm = results.get("jvm")
    if b_jvm and r_jvm:
        # baseline may contain a glob like "17.*" — compare major version only
        b_major = b_jvm.split(".")[0]
        r_major = str(r_jvm).split(".")[0]
        if b_major != r_major:
            mismatches.append(
                f"runner.jvm mismatch: baseline={b_jvm!r} results={r_jvm!r}"
            )
    return mismatches


def check(results: dict, baseline: dict, require_native: bool) -> int:
    runner_mismatches = _match_runner(results, baseline)
    if runner_mismatches:
        for m in runner_mismatches:
            print(f"error: {m}", file=sys.stderr)
            _gh_error(m, title="perf-gate runner-mismatch")
        print("error: results were produced on a different platform than the "
              "baseline — budgets are not comparable. "
              "Re-pin with --update-baseline on the correct runner.", file=sys.stderr)
        return 2

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

        w_exp = spec.get("work_size")
        w_obs = row.get("work_size")
        if w_exp is not None and w_obs is not None and int(w_exp) != int(w_obs):
            failures.append(
                f"{name}: work_size mismatch (baseline {w_exp} != results {w_obs})"
            )

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
            _gh_error(f)
        # Summary annotation — small enough to render on the PR check list
        _gh_error(
            f"perf-gate failed on {len(failures)} primitive(s); first: {failures[0]}",
            title="perf-gate summary",
        )
        return 1

    print("")
    print("PERF-GATE PASS")
    return 0


def update_baseline(results: dict, baseline_path: Path) -> int:
    """Repin baseline values from a fresh results.json run.

    Stores the raw observed values as the new baseline. check() is the
    side that applies `tolerance_pct` (limit = max*(1+tol); floor =
    min*(1-tol)), so baking the tolerance in here would double-count it
    and silently widen the gate by ~2*tol on every re-pin.

    Fields updated:
      java_ns_per_op_max   = observed java ns/op (raw)
      native_over_java_min = observed native_over_java ratio (raw,
                             skipped when the baseline entry is null
                             or the result row lacks a native column)
      pinned_on_commit     = current git HEAD sha (when available)

    Runner identity (runner.os / arch / jvm) is NEVER overwritten from
    results.json. Every baseline file is keyed to a single platform
    triple, so overwriting these fields from an off-triple repin would
    silently convert e.g. benchmarks/baselines/v0.4-linux-x64.json
    into a macos-arm64 baseline — future linux runs would then appear
    to regress. If the current results don't match the baseline's
    runner identity we refuse the repin and print remediation.
    """
    baseline = load_json(baseline_path)

    runner = baseline.get("runner") or {}
    if runner:
        mismatches = _match_runner(results, baseline)
        if mismatches:
            msg = (
                "refusing to repin: runner identity mismatch\n  "
                + "\n  ".join(mismatches)
                + f"\nRepin on the correct platform, or update the matching per-triple "
                  f"baseline file under benchmarks/baselines/."
            )
            print(f"error: {msg}", file=sys.stderr)
            _gh_error(msg, title="perf-gate repin")
            return 2

    by_name = {r["name"]: r for r in results["results"]}
    for spec in baseline["primitives"]:
        name = spec["name"]
        row = by_name.get(name)
        if row is None:
            print(f"warning: {name} not in results — baseline entry unchanged",
                  file=sys.stderr)
            continue
        j_obs = float(row["java_ns_per_op"])
        spec["java_ns_per_op_max"] = round(j_obs, 2)
        n_obs = row.get("native_over_java")
        # Populate `native_over_java_min` on first pin too — placeholder
        # baselines (mac/win) start with null and would otherwise stay null
        # forever, silently disabling the native speedup floor check even
        # after a successful native bench. When we have an observation,
        # take it verbatim (minus a small safety margin handled by `check`).
        if n_obs is not None:
            spec["native_over_java_min"] = round(float(n_obs), 2)

    # Populate runner identity only on first pin (no existing block).
    # Subsequent repins preserve the pinned identity — the guard above
    # already verified the current runner matches it.
    if not runner:
        for key in ("os", "arch", "jvm"):
            if key in results:
                baseline.setdefault("runner", {})[key] = results[key]

    sha = _current_head_sha()
    if sha is not None:
        baseline["pinned_on_commit"] = sha

    with baseline_path.open("w", encoding="utf-8") as fh:
        json.dump(baseline, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"Baseline repinned: {baseline_path}")
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
    ap.add_argument("--update-baseline", action="store_true",
                    help="Repin the baseline from the current results instead of "
                         "checking against it. Run this on the reference runner "
                         "after a deliberate performance change.")
    args = ap.parse_args()

    if not args.results.exists():
        msg = f"results file not found: {args.results}"
        print(f"error: {msg}", file=sys.stderr)
        _gh_error(msg, title="perf-gate io")
        return 2

    results = load_json(args.results)

    if args.update_baseline:
        return update_baseline(results, args.baseline)

    if not args.baseline.exists():
        msg = f"baseline file not found: {args.baseline}"
        print(f"error: {msg}", file=sys.stderr)
        _gh_error(msg, title="perf-gate io")
        return 2

    baseline = load_json(args.baseline)
    return check(results, baseline, args.require_native)


if __name__ == "__main__":
    sys.exit(main())
