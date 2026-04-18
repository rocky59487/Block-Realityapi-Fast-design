#!/usr/bin/env python3
"""Emit GitHub Actions ``::error::`` annotations for failing JUnit testcases.

Run from a workflow step after a build/test failure. Scans ``*.xml`` under
``--results-dir`` (recursively), emits one ``::error title=<title>::`` per
``<failure>`` or ``<error>`` node it finds. Up to ``--max`` annotations (truncates
the rest with a summary). If no failures are found, emits a single annotation
explaining the job likely died before JUnit output was produced.

Kept separate from ``pfsf_perf_gate.py`` so workflow ``run:`` blocks do not
need multi-line Python heredocs (YAML + bash + heredoc indentation is a
minefield — a misplaced space in the ``PY`` terminator silently becomes an
unterminated heredoc and the step exits with bash code 2 rather than hitting
the intended failure message).
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def gh_error(message: str, title: str) -> None:
    safe = (
        message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    )
    safe_title = (
        title.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    )
    print(f"::error title={safe_title}::{safe}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--results-dir", required=True,
                    help="Root dir to scan for JUnit XML (typically "
                         "api/build/test-results)")
    ap.add_argument("--title", default="parity-test",
                    help="Annotation title")
    ap.add_argument("--name-filter", default="",
                    help="Substring that must appear in the XML filename "
                         "(e.g. 'PfsfBenchmark'). Empty = no filter.")
    ap.add_argument("--max", type=int, default=20,
                    help="Maximum number of annotations to emit before "
                         "truncating")
    ap.add_argument("--fallback-message", default=None,
                    help="Message to emit if no <failure>/<error> found")
    args = ap.parse_args()

    if os.environ.get("GITHUB_ACTIONS") != "true":
        # Nothing to do outside CI; print a hint so local runs don't look silent.
        print("emit_junit_annotations: not running under GitHub Actions, no-op.",
              file=sys.stderr)
        return 0

    pattern = os.path.join(args.results_dir, "**", "*.xml")
    files = glob.glob(pattern, recursive=True)
    if args.name_filter:
        files = [f for f in files if args.name_filter in os.path.basename(f)]

    total = 0
    for xml in files:
        try:
            tree = ET.parse(xml)
        except Exception as e:  # noqa: BLE001
            gh_error(f"could not parse {xml}: {e}",
                     title=f"{args.title}-xml-parse")
            continue
        for tc in tree.getroot().iter("testcase"):
            for kind in ("failure", "error"):
                node = tc.find(kind)
                if node is None:
                    continue
                cls = tc.get("classname", "?")
                name = tc.get("name", "?")
                msg = (node.get("message") or node.text or "").strip()
                first = msg.splitlines()[0] if msg else ""
                gh_error(f"{cls}.{name}: {first[:400]}", title=args.title)
                total += 1
                if total >= args.max:
                    gh_error(f"truncated after {total} failures",
                             title=args.title)
                    return 0

    if total == 0:
        msg = args.fallback_message or (
            "failure occurred but no JUnit <failure>/<error> nodes found "
            "under " + args.results_dir +
            " — likely compile/gradle flake before tests ran"
        )
        gh_error(msg, title=args.title)
    return 0


if __name__ == "__main__":
    sys.exit(main())
