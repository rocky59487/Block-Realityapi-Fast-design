#!/usr/bin/env python3
"""
BR-Team Session Manager
Helper for the Orchestrator to initialize and manage the shared blackboard.
"""

import argparse
import datetime
import os
import re
import sys
from pathlib import Path


def get_project_root() -> Path:
    """Find the project root by looking for AGENTS.md."""
    p = Path.cwd()
    for parent in [p] + list(p.parents):
        if (parent / "AGENTS.md").exists():
            return parent
    return p


def sanitize_name(name: str) -> str:
    return re.sub(r"[^\w\-]+", "_", name).strip("_")[:40]


def init_session(task_name: str) -> Path:
    root = get_project_root()
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    session_dir = root / ".team_session" / f"{ts}_{sanitize_name(task_name)}"
    session_dir.mkdir(parents=True, exist_ok=True)

    (session_dir / "blackboard.md").write_text(
        f"# BR-Team Blackboard\n\n**Session**: {session_dir.name}\n\n",
        encoding="utf-8",
    )
    (session_dir / "artifacts.list").write_text("", encoding="utf-8")
    (session_dir / "warnings.md").write_text("# Warnings\n\n", encoding="utf-8")

    print(session_dir)
    return session_dir


def append_blackboard(session_dir: str, agent_name: str, status: str, content: str):
    bb = Path(session_dir) / "blackboard.md"
    entry = (
        f"\n## {agent_name} — {status}\n"
        f"{content}\n"
    )
    with bb.open("a", encoding="utf-8") as f:
        f.write(entry)


def add_artifact(session_dir: str, path: str):
    af = Path(session_dir) / "artifacts.list"
    with af.open("a", encoding="utf-8") as f:
        f.write(f"{path}\n")


def add_warning(session_dir: str, warning: str):
    wf = Path(session_dir) / "warnings.md"
    with wf.open("a", encoding="utf-8") as f:
        f.write(f"- {warning}\n")


def main():
    parser = argparse.ArgumentParser(description="BR-Team Session Manager")
    sub = parser.add_subparsers(dest="cmd")

    p_init = sub.add_parser("init", help="Initialize a new team session")
    p_init.add_argument("task_name", help="Short task identifier")

    p_bb = sub.add_parser("bb", help="Append to blackboard")
    p_bb.add_argument("session_dir")
    p_bb.add_argument("agent_name")
    p_bb.add_argument("status")
    p_bb.add_argument("content")

    p_art = sub.add_parser("artifact", help="Add artifact path")
    p_art.add_argument("session_dir")
    p_art.add_argument("path")

    p_warn = sub.add_parser("warn", help="Add warning")
    p_warn.add_argument("session_dir")
    p_warn.add_argument("warning")

    args = parser.parse_args()

    if args.cmd == "init":
        init_session(args.task_name)
    elif args.cmd == "bb":
        append_blackboard(args.session_dir, args.agent_name, args.status, args.content)
    elif args.cmd == "artifact":
        add_artifact(args.session_dir, args.path)
    elif args.cmd == "warn":
        add_warning(args.session_dir, args.warning)
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
