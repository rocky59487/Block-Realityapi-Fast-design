#!/usr/bin/env python3
"""
v0.3d Phase 8 — ABI v1 stability gate.

Cross-checks the frozen ABI snapshot at
    L1-native/libpfsf/abi/pfsf_v1.abi.json
against:

  (a) header-declared symbols — every symbol in the snapshot must
      appear in at least one pfsf_*.h as a `PFSF_API` declaration.
      Catches accidental removals before anyone rebuilds.

  (b) header-declared enum values — every {enum, name, value} triple
      in the snapshot must match the actual #define / enum body.
      Catches silent value renumbering.

  (c) optional: a compiled `libblockreality_pfsf.{so,dll,dylib}` —
      when --lib is supplied, `nm -D` / `dumpbin` / `otool -TV` is
      invoked and every snapshot symbol must be present. CI drives
      this path on nightly builds; local dev can skip it.

Exit codes:
    0  clean — no ABI drift detected
    1  drift detected (one or more symbols / enums / structs diverged)
    2  tooling error (missing files, malformed snapshot, …)

Designed to run without project-specific deps (stdlib only) so it
works on every platform in the nightly matrix.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path


REPO_ROOT  = Path(__file__).resolve().parent.parent
SNAPSHOT   = REPO_ROOT / "L1-native/libpfsf/abi/pfsf_v1.abi.json"
HEADER_DIR = REPO_ROOT / "L1-native/libpfsf/include/pfsf"


# ── helpers ────────────────────────────────────────────────────────────

def read_headers() -> str:
    """Concatenate every public header into one blob for grep-friendly scans."""
    blob_parts = []
    for path in sorted(HEADER_DIR.glob("pfsf*.h")):
        blob_parts.append(path.read_text(encoding="utf-8"))
    return "\n".join(blob_parts)


def extract_declared_symbols(headers: str) -> set[str]:
    """Pull every PFSF_API function declaration from the headers."""
    # Match:  PFSF_API <return-type> pfsf_<name>(
    pattern = re.compile(
        r"PFSF_API\s+[A-Za-z0-9_\s\*\(\)]+?\s+(pfsf_[A-Za-z0-9_]+)\s*\(",
        re.MULTILINE,
    )
    return set(pattern.findall(headers))


_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT_RE  = re.compile(r"//.*?$", re.MULTILINE)


def _strip_comments(s: str) -> str:
    s = _BLOCK_COMMENT_RE.sub("", s)
    s = _LINE_COMMENT_RE.sub("", s)
    return s


def extract_enum_values(headers: str) -> dict[str, dict[str, int]]:
    """Parse `typedef enum { ... } name;` blocks into {enum_name: {key: int}}."""
    out: dict[str, dict[str, int]] = {}
    pattern = re.compile(
        r"typedef\s+enum\s*\{([^}]*)\}\s*(pfsf_[A-Za-z0-9_]+)\s*;",
        re.MULTILINE | re.DOTALL,
    )
    for body, name in pattern.findall(headers):
        entries: dict[str, int] = {}
        next_auto = 0
        # Strip C / C++ comments up front so commas inside them don't
        # confuse the per-entry split.
        cleaned = _strip_comments(body)
        for raw in cleaned.split(","):
            line = raw.strip()
            if not line:
                continue
            if "=" in line:
                key, val = [t.strip() for t in line.split("=", 1)]
                try:
                    # Accept decimal / hex / signed forms
                    val = val.rstrip("u").rstrip("U")
                    parsed = int(val, 0)
                except ValueError:
                    continue
                entries[key] = parsed
                next_auto = parsed + 1
            else:
                key = line
                entries[key] = next_auto
                next_auto += 1
        if entries:
            out[name] = entries
    return out


def nm_symbols(lib: Path) -> set[str]:
    """Run the platform-appropriate symbol dumper, return the pfsf_* subset."""
    if sys.platform.startswith("linux") or sys.platform.startswith("freebsd"):
        cmd = ["nm", "-D", "--defined-only", str(lib)]
    elif sys.platform == "darwin":
        cmd = ["nm", "-gU", str(lib)]
    elif sys.platform.startswith("win"):
        cmd = ["dumpbin", "/exports", str(lib)]
    else:
        raise RuntimeError(f"unsupported platform: {sys.platform}")

    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=True)
    except FileNotFoundError as exc:
        raise RuntimeError(f"symbol dumper not found: {cmd[0]}") from exc

    return {
        tok
        for line in proc.stdout.splitlines()
        for tok in line.split()
        if tok.startswith("pfsf_")
    }


# ── main ───────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description="libpfsf ABI v1 stability gate")
    ap.add_argument("--snapshot", type=Path, default=SNAPSHOT,
                     help="path to pfsf_v1.abi.json (default: repo-relative)")
    ap.add_argument("--lib", type=Path, default=None,
                     help="optional path to compiled libblockreality_pfsf.so|dll|dylib")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if not args.snapshot.is_file():
        print(f"error: snapshot not found at {args.snapshot}", file=sys.stderr)
        return 2

    try:
        snap = json.loads(args.snapshot.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"error: snapshot is not valid JSON: {e}", file=sys.stderr)
        return 2

    headers_blob = read_headers()
    declared     = extract_declared_symbols(headers_blob)
    enum_defs    = extract_enum_values(headers_blob)

    drift: list[str] = []

    # (a) symbol coverage
    for sym in snap.get("symbols", []):
        if sym not in declared:
            drift.append(f"symbol missing from headers: {sym}")

    # (b) enum value parity
    for enum_name, values in snap.get("enums", {}).items():
        parsed = enum_defs.get(enum_name)
        if parsed is None:
            drift.append(f"enum not found in headers: {enum_name}")
            continue
        for key, expected in values.items():
            actual = parsed.get(key)
            if actual is None:
                drift.append(f"enum value removed: {enum_name}::{key}")
            elif actual != expected:
                drift.append(
                    f"enum value drifted: {enum_name}::{key} "
                    f"snapshot={expected} header={actual}"
                )

    # (c) optional dynamic-symbol check
    if args.lib is not None:
        if not args.lib.exists():
            print(f"error: --lib target not found: {args.lib}", file=sys.stderr)
            return 2
        try:
            live = nm_symbols(args.lib)
        except RuntimeError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        for sym in snap.get("symbols", []):
            if sym not in live:
                drift.append(f"symbol missing from binary: {sym}")

    if args.verbose:
        print(f"[abi-check] headers scanned: {HEADER_DIR}")
        print(f"[abi-check] declared pfsf_* symbols: {len(declared)}")
        print(f"[abi-check] snapshot symbols:        {len(snap.get('symbols', []))}")
        print(f"[abi-check] header enums discovered: {len(enum_defs)}")

    if drift:
        print("ABI drift detected — commit bumped pfsf_v1 without snapshot update:")
        for line in drift:
            print(f"  - {line}")
        return 1

    print(f"ABI v{snap.get('abi_version', '?')} stable — "
          f"{len(snap.get('symbols', []))} symbols, "
          f"{len(snap.get('enums', {}))} enums verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
