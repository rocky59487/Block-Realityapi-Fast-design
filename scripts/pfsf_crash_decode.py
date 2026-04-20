#!/usr/bin/env python3
"""v0.4 M3f — decode a ``pfsf-crash-<pid>.trace`` dump into NDJSON.

The crash handler in ``L1-native/libpfsf/src/diag/crash.cpp`` writes an
ASCII header line followed by up to ``PFSF_CRASH_MAX_EVENTS`` (100) raw
64-byte ``pfsf_trace_event`` records. This script turns that binary
file into human-readable newline-delimited JSON so on-call can diff /
grep events straight out of the crash file without a debugger.

Wire format — must stay in lock-step with ``pfsf_trace.h``::

    ASCII: "PFSF-CRASH signo=<d> pid=<d> addr=0x<hex> events=<d>\\n"
    Body : events * struct pfsf_trace_event (64 B each)
            int64  epoch
            int32  stage
            int32  island_id
            int32  voxel_index
            int32  errno_val
            int16  level
            int16  _pad
            char   msg[36]       // UTF-8, NUL-terminated

Output schema mirrors ``vk_backend.cpp::write_trace_json`` so tooling
that already consumes the live drain can ingest the crash file without
translation.

Usage:
    pfsf_crash_decode.py dump.trace                     # stdout NDJSON
    pfsf_crash_decode.py dump.trace --output dump.jsonl
    pfsf_crash_decode.py dump.trace --header-only       # JSON header line
    pfsf_crash_decode.py dump.trace --summary           # header + count

Exit codes:
    0  success
    2  malformed header (missing prefix, unterminated, bad key=val)
    3  truncated body (events field disagrees with file length)
    4  IO error
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from pathlib import Path
from typing import Iterator


HEADER_PREFIX = b"PFSF-CRASH "
EVENT_FORMAT = "<qiiiihh36s"
EVENT_BYTES = struct.calcsize(EVENT_FORMAT)
assert EVENT_BYTES == 64, f"pfsf_trace_event must be 64B, got {EVENT_BYTES}"

HEADER_RE = re.compile(
    r"^PFSF-CRASH "
    r"signo=(?P<signo>-?\d+) "
    r"pid=(?P<pid>-?\d+) "
    r"addr=0x(?P<addr>[0-9a-fA-F]+) "
    r"events=(?P<events>-?\d+)$"
)


class DecodeError(Exception):
    """Raised when the dump file does not match the documented layout."""


def parse_header(raw: bytes) -> tuple[dict, int]:
    """Return (header_dict, body_offset). Raises DecodeError on mismatch."""
    lf = raw.find(b"\n")
    if lf < 0:
        raise DecodeError("header LF terminator not found — truncated file?")
    if not raw.startswith(HEADER_PREFIX):
        raise DecodeError(f"unexpected header prefix: {raw[:20]!r}")
    try:
        header_text = raw[:lf].decode("ascii")
    except UnicodeDecodeError as e:
        raise DecodeError(f"header is not ASCII: {e}") from e
    m = HEADER_RE.match(header_text)
    if m is None:
        raise DecodeError(f"header does not match schema: {header_text!r}")
    return {
        "signo":  int(m.group("signo")),
        "pid":    int(m.group("pid")),
        "addr":   int(m.group("addr"), 16),
        "events": int(m.group("events")),
    }, lf + 1


def decode_event(buf: bytes) -> dict:
    """Unpack one 64-byte record into the trace-event dict."""
    epoch, stage, island, voxel, errno_val, level, _pad, msg = \
        struct.unpack(EVENT_FORMAT, buf)
    nul = msg.find(b"\x00")
    if nul >= 0:
        msg = msg[:nul]
    try:
        text = msg.decode("utf-8")
    except UnicodeDecodeError:
        # A corrupted record should not abort the whole decode — fall back
        # to replacement and let the reader see it flagged.
        text = msg.decode("utf-8", errors="replace")
    return {
        "epoch":  epoch,
        "stage":  stage,
        "island": island,
        "voxel":  voxel,
        "errno":  errno_val,
        "level":  level,
        "msg":    text,
    }


def iter_events(body: bytes, expected: int) -> Iterator[dict]:
    if len(body) != expected * EVENT_BYTES:
        raise DecodeError(
            f"body length {len(body)} != events({expected}) * {EVENT_BYTES}")
    for i in range(expected):
        off = i * EVENT_BYTES
        yield decode_event(body[off:off + EVENT_BYTES])


def decode_file(path: Path) -> tuple[dict, list[dict]]:
    raw = path.read_bytes()
    header, body_off = parse_header(raw)
    events = list(iter_events(raw[body_off:], header["events"]))
    return header, events


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="Decode a pfsf-crash-<pid>.trace file into NDJSON.")
    ap.add_argument("input", type=Path, help="Path to .trace file.")
    ap.add_argument("-o", "--output", type=Path,
                    help="Write NDJSON to FILE (default: stdout).")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--header-only", action="store_true",
                      help="Emit only the header object and exit.")
    mode.add_argument("--summary", action="store_true",
                      help="Emit header + event count (no per-event lines).")
    args = ap.parse_args(argv)

    try:
        header, events = decode_file(args.input)
    except DecodeError as e:
        print(f"pfsf_crash_decode: {e}", file=sys.stderr)
        return 2 if "header" in str(e) else 3
    except OSError as e:
        print(f"pfsf_crash_decode: {e}", file=sys.stderr)
        return 4

    out = args.output.open("w", encoding="utf-8") if args.output else sys.stdout
    try:
        if args.header_only:
            json.dump({"header": header}, out)
            out.write("\n")
        elif args.summary:
            json.dump({"header": header, "event_count": len(events)}, out)
            out.write("\n")
        else:
            json.dump({"header": header}, out)
            out.write("\n")
            for e in events:
                json.dump(e, out)
                out.write("\n")
    finally:
        if args.output:
            out.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
