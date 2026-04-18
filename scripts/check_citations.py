#!/usr/bin/env python3
"""
v0.3e M7 — citation gate.

Walks every public C/C++ header, compute implementation, and Java solver
source under scope, and validates that the `@cite` / `@formula` /
`@maps_to` triad is well-formed.

The rules (all enforceable without a bibliography DB):

  R1 — every @cite entry must match

           @cite <Author> (<Year>). "<Title>". <Journal/Venue[, vol, pages]>.

       Multi-line cites are supported: continuation lines that start with
       doxygen markers (``*``, ``///``, ``//``) are joined to the opener.
       The Year field accepts a 4-digit year OR a standards identifier
       containing at least one digit (e.g. ``EN 1991-1-4``, ``ISO 6946``).

  R2 — every @maps_to must point at a file that exists (either absolute
       repo path or relative to the repo root). The `:methodName()` or
       `:lineRange` suffix is accepted and ignored for existence check.
       `@maps_to (new …)` markers that describe a net-new C API with no
       prior Java counterpart are skipped — enclose the entire target in
       parentheses to opt out of the existence check.

  R3 — every C API function declared with `PFSF_API` in a header under
       L1-native/libpfsf/include/pfsf/ must have at least one provenance
       annotation within the doxygen block immediately preceding the
       declaration. Valid annotations are: @cite, @algorithm, @see,
       @maps_to. Pure structural helpers (type getters, version probes,
       lifecycle, flag queries) are exempt via an allowlist.

Optional aggregator pass (--emit-bibliography PATH): writes a Markdown
bibliography deduped by (author, year, title) to the given path.

Exit codes:
    0  all citations well-formed
    1  one or more violations (written to stderr)
    2  tooling error
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent

# Source roots to scan. Order matters only for deterministic output.
SOURCE_ROOTS = [
    REPO_ROOT / "L1-native" / "libpfsf" / "include",
    REPO_ROOT / "L1-native" / "libpfsf" / "src",
    REPO_ROOT / "Block Reality" / "api" / "src" / "main" / "java"
        / "com" / "blockreality" / "api" / "physics" / "pfsf",
]

SOURCE_GLOBS = ("*.h", "*.hpp", "*.c", "*.cpp", "*.java", "*.glsl")

# PFSF_API decls that don't need a citation — trivial probes, engine
# lifecycle, IO plumbing, and hook/aug helpers with no numerical content.
CITATION_EXEMPT = {
    # version / feature probes
    "pfsf_abi_version",
    "pfsf_has_feature",
    "pfsf_build_info",
    "pfsf_version",
    # engine lifecycle
    "pfsf_create",
    "pfsf_init",
    "pfsf_shutdown",
    "pfsf_destroy",
    "pfsf_is_available",
    "pfsf_get_stats",
    # world-state callbacks (pure glue — formulas live on the Java side)
    "pfsf_set_material_lookup",
    "pfsf_set_anchor_lookup",
    "pfsf_set_fill_ratio_lookup",
    "pfsf_set_curing_lookup",
    "pfsf_set_wind",
    # island membership / change notifications
    "pfsf_add_island",
    "pfsf_remove_island",
    "pfsf_notify_block_change",
    "pfsf_mark_full_rebuild",
    # tick / readback plumbing — algorithmic cites live in the compute
    # primitives called via the plan buffer
    "pfsf_tick",
    "pfsf_read_stress",
    "pfsf_register_island_buffers",
    "pfsf_register_island_lookups",
    "pfsf_register_stress_readback",
    "pfsf_tick_dbb",
    "pfsf_drain_callbacks",
    "pfsf_get_sparse_upload_buffer",
    "pfsf_notify_sparse_updates",
    # augmentation / hook / plan SPI seams
    "pfsf_register_augmentation",
    "pfsf_clear_augmentation",
    "pfsf_aug_register",
    "pfsf_aug_clear",
    "pfsf_aug_clear_island",
    "pfsf_aug_query",
    "pfsf_aug_island_count",
    "pfsf_hook_set",
    "pfsf_hook_fire",
    "pfsf_hook_clear",
    "pfsf_hook_clear_island",
    "pfsf_set_hook",
    "pfsf_plan_execute",
    "pfsf_plan_test_counter_read_reset",
    "pfsf_plan_test_hook_install",
    "pfsf_plan_test_hook_count_read_reset",
    # tracing / crash observability
    "pfsf_trace_emit",
    "pfsf_drain_trace",
    "pfsf_drain_trace_dbb",
    "pfsf_set_trace_level",
    "pfsf_set_trace_level_global",
    "pfsf_get_trace_level_global",
    "pfsf_trace_size",
    "pfsf_trace_clear",
    "pfsf_install_crash_handler",
    "pfsf_uninstall_crash_handler",
    "pfsf_dump_now_for_test",
    # macro-block / step plumbing — cites documented on the diagnostic
    # entries they forward to
    "pfsf_macro_block_active",
    "pfsf_macro_active_ratio",
    "pfsf_recommend_steps",
    # morton — the encoder/decoder pair is trivial bit-twiddling;
    # the algorithmic cite sits on pfsf_tiled_layout_build
    "pfsf_morton_encode",
    "pfsf_morton_decode",
    "pfsf_tiled_layout_build",
}


# ── regexes ─────────────────────────────────────────────────────────────

# "@cite Author (YearOrStandard). "Title". ..."
# Year accepts 4-digit year OR any non-empty parenthesised identifier
# containing at least one digit (for standards: EN 1991-1-4, ISO 6946, …).
# Title accepts ASCII double-quotes or unicode curly quotes.
CITE_RE = re.compile(
    r"@cite\s+"
    r"(?P<author>[^(]+?)\s+"
    r"\((?P<year>[^)]*\d[^)]*)\)\.?\s*"
    r"[\"\u201c\u201d](?P<title>[^\"\u201c\u201d]+)[\"\u201c\u201d]"
    r"(?P<rest>[^\n\r]*)"
)

# @maps_to path.java[:method()]  (strip `:...` before stat). The target
# is a single whitespace-free token; anything after (em-dash commentary,
# line ranges with spaces, etc.) is descriptive and ignored.
MAPS_TO_RE = re.compile(r"@maps_to\s+(?P<target>\S+)")

# A PFSF_API function declaration — same regex as check_abi.py.
PFSF_API_RE = re.compile(
    r"PFSF_API\s+[A-Za-z0-9_\s\*\(\)]+?\s+(pfsf_[A-Za-z0-9_]+)\s*\(",
    re.MULTILINE,
)

# The doxygen block that immediately precedes a PFSF_API decl is the one
# ending just before its first character. We match `/**...*/` blocks and
# remember their span; then for each API we find the block whose end is
# closest above the decl.
DOXY_BLOCK_RE = re.compile(r"/\*\*.*?\*/", re.DOTALL)

# Continuation-line strip pattern used when joining multi-line @cite
# entries. Drops leading doxygen markers (``*`` / ``///`` / ``//``) and
# collapses the remaining whitespace.
CONT_STRIP_RE = re.compile(r"^\s*(?:\*+|///?)\s*")


# ── helpers ─────────────────────────────────────────────────────────────

def iter_source_files(roots: Iterable[Path]) -> Iterable[Path]:
    for root in roots:
        if not root.exists():
            continue
        for pattern in SOURCE_GLOBS:
            yield from root.rglob(pattern)


def short(p: Path) -> str:
    try:
        return str(p.relative_to(REPO_ROOT))
    except ValueError:
        return str(p)


def is_cite_continuation(line: str) -> bool:
    """True if this comment line continues the previous @cite entry.

    Rejects lines that start a new tag (``@foo``), close the doxygen
    block (``*/``), or that are entirely blank.
    """
    stripped = CONT_STRIP_RE.sub("", line).rstrip()
    if not stripped:
        return False
    if stripped.startswith("*/"):
        return False
    if stripped.startswith("@"):
        return False
    return True


def collect_cite_entries(lines: list[str]) -> list[tuple[int, str]]:
    """Return (lineno, joined_text) for every @cite entry in the file.

    Joins continuation lines so a single logical cite spanning N source
    lines validates against CITE_RE as one text.
    """
    entries: list[tuple[int, str]] = []
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        if "@cite " in line or line.strip().endswith("@cite"):
            start = i
            # Strip the leading doxygen marker so '@cite' starts early.
            parts = [CONT_STRIP_RE.sub("", line).rstrip()]
            j = i + 1
            while j < n and is_cite_continuation(lines[j]):
                parts.append(CONT_STRIP_RE.sub("", lines[j]).rstrip())
                j += 1
            entries.append((start + 1, " ".join(parts)))
            i = j
            continue
        i += 1
    return entries


def validate_cite_text(text: str) -> tuple[bool, str]:
    """Return (ok, reason) for a joined @cite text."""
    m = CITE_RE.search(text)
    if not m:
        return False, "not matching '@cite <Author> (Year). \"Title\". …'"
    year_field = m.group("year").strip()
    # Accept either a plausible 4-digit year or a standards identifier
    # containing at least one digit.
    year_match = re.fullmatch(r"\d{4}", year_field)
    if year_match:
        year = int(year_field)
        if year < 1500 or year > 2100:
            return False, f"implausible year {year}"
    else:
        # Standards-style identifier is fine as long as it has a digit.
        if not re.search(r"\d", year_field):
            return False, "year/standard identifier missing digit"
    title = m.group("title").strip()
    if len(title) < 3:
        return False, "title too short"
    return True, ""


def validate_maps_to_target(raw: str) -> tuple[bool, str]:
    # Strip trailing punctuation and the method/line specifier.
    clean = raw.strip().rstrip(".,;")
    # `@maps_to (new … — no prior Java)` sentinel for C APIs with no
    # Java counterpart. Treat anything wrapped in parens (or opening
    # with `(`) as a descriptive marker, not a path.
    if clean.startswith("("):
        return True, ""
    # Split on first ':' so "Foo.java:method()" → "Foo.java"
    path_part = clean.split(":", 1)[0]
    # Strip any trailing parenthesised commentary glued to the path.
    path_part = re.split(r"\s*\(", path_part, maxsplit=1)[0].strip()
    if not path_part:
        return True, ""
    candidate = Path(path_part)
    if candidate.is_absolute() and candidate.exists():
        return True, ""
    for root in [REPO_ROOT,
                  REPO_ROOT / "Block Reality" / "api" / "src" / "main" / "java",
                  REPO_ROOT / "Block Reality" / "api" / "src" / "main" / "java"
                      / "com" / "blockreality" / "api" / "physics" / "pfsf",
                  REPO_ROOT / "L1-native" / "libpfsf" / "src",
                  REPO_ROOT / "L1-native" / "libpfsf" / "include"]:
        if (root / candidate).exists():
            return True, ""
        if "/" not in path_part:
            for hit in root.rglob(path_part):
                if hit.exists():
                    return True, ""
                break
    return False, f"target not found on disk: {path_part}"


def collect_doxygen_blocks(text: str) -> list[tuple[int, int, str]]:
    """Return (start, end, content) for each /** ... */ block."""
    return [(m.start(), m.end(), m.group(0)) for m in DOXY_BLOCK_RE.finditer(text)]


def preceding_block(blocks: list[tuple[int, int, str]], pos: int) -> str | None:
    best = None
    for (s, e, content) in blocks:
        if e <= pos and (best is None or e > best[1]):
            best = (s, e, content)
    return best[2] if best else None


# ── main ────────────────────────────────────────────────────────────────

PROVENANCE_TAGS = ("@cite", "@algorithm", "@see", "@maps_to")


def block_has_provenance(block: str) -> bool:
    return any(tag in block for tag in PROVENANCE_TAGS)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--emit-bibliography", type=Path, default=None,
                    help="write aggregated Markdown bibliography to this path")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    files = list(iter_source_files(SOURCE_ROOTS))
    if args.verbose:
        print(f"[check-citations] scanning {len(files)} source file(s)")

    citations: list[tuple[str, int, str, str, str]] = []
    violations: list[str] = []

    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, FileNotFoundError):
            continue

        lines = text.splitlines()

        # R1 + bibliography aggregation — multi-line aware.
        for (lineno, entry) in collect_cite_entries(lines):
            ok, reason = validate_cite_text(entry)
            if not ok:
                violations.append(f"{short(path)}:{lineno}: malformed @cite — {reason}")
            else:
                m = CITE_RE.search(entry)
                assert m is not None
                citations.append((
                    short(path), lineno,
                    m.group("author").strip().rstrip(","),
                    m.group("year").strip(),
                    m.group("title").strip(),
                ))

        # R2
        for lineno, line in enumerate(lines, start=1):
            if "@maps_to" not in line:
                continue
            m = MAPS_TO_RE.search(line)
            if not m:
                violations.append(f"{short(path)}:{lineno}: malformed @maps_to")
                continue
            ok, reason = validate_maps_to_target(m.group("target"))
            if not ok:
                violations.append(f"{short(path)}:{lineno}: @maps_to {reason}")

        # R3 — only applies to headers under L1-native/libpfsf/include/pfsf
        if path.suffix in (".h", ".hpp") and "libpfsf/include/pfsf" in path.as_posix():
            blocks = collect_doxygen_blocks(text)
            for m in PFSF_API_RE.finditer(text):
                fn = m.group(1)
                if fn in CITATION_EXEMPT:
                    continue
                block = preceding_block(blocks, m.start())
                if not block:
                    violations.append(
                        f"{short(path)}: PFSF_API {fn} has no doxygen header — "
                        "add @cite, @algorithm, @see, or @maps_to")
                    continue
                if not block_has_provenance(block):
                    violations.append(
                        f"{short(path)}: PFSF_API {fn} doxygen lacks "
                        "@cite/@algorithm/@see/@maps_to — "
                        "document the reference or justification")

    if args.emit_bibliography is not None:
        dedup: dict[tuple[str, str, str], list[str]] = defaultdict(list)
        for (src, _ln, author, year, title) in citations:
            dedup[(author, year, title)].append(src)
        args.emit_bibliography.parent.mkdir(parents=True, exist_ok=True)
        with args.emit_bibliography.open("w", encoding="utf-8") as f:
            f.write("# PFSF Bibliography\n\n")
            f.write("Auto-generated from `@cite` entries by "
                    "`scripts/check_citations.py --emit-bibliography`. "
                    "Do not edit by hand.\n\n")
            sorted_keys = sorted(dedup.keys(),
                                  key=lambda t: (t[1], t[0].lower(), t[2].lower()))
            for (author, year, title) in sorted_keys:
                sources = sorted(set(dedup[(author, year, title)]))
                f.write(f"- **{author} ({year})** — *{title}*  \n")
                f.write(f"  <sup>cited by: {', '.join(sources)}</sup>\n")
            f.write(f"\n---\n\n_{len(dedup)} unique works cited across "
                    f"{sum(len(v) for v in dedup.values())} source lines._\n")
        if args.verbose:
            print(f"[check-citations] wrote bibliography to {short(args.emit_bibliography)}")

    if args.verbose:
        print(f"[check-citations] @cite occurrences: {len(citations)}")
        print(f"[check-citations] violations:        {len(violations)}")

    if violations:
        print("", file=sys.stderr)
        print("CITATION GATE FAIL", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1

    print(f"CITATION GATE PASS — {len(citations)} @cite entries verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
