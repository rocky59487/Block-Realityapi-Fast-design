#!/usr/bin/env python3
"""
plugin_wrapper.py — Kimi Plugin tool wrapper for br_broker.py

Reads JSON from stdin, invokes br_broker.py, and returns JSON on stdout.
"""

import json
import os
import subprocess
import sys
from pathlib import Path


def get_broker_path() -> Path:
    """Find br_broker.py relative to this script."""
    return Path(__file__).with_name("scripts").joinpath("br_broker.py")


def run_broker(*args):
    broker = get_broker_path()
    result = subprocess.run(
        [sys.executable, str(broker), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout, result.stderr


def main():
    params = json.load(sys.stdin)
    action = params.get("action")

    if action == "send":
        stdout, stderr = run_broker(
            "send",
            "--from", params.get("from", "coordinator"),
            "--to", params["to"],
            "--type", params.get("type", "task"),
            "--content", params["content"],
            "--thread", params.get("thread", ""),
            "--reply-to", params.get("reply_to", params.get("from", "coordinator")),
        )
    elif action == "poll":
        stdout, stderr = run_broker(
            "poll",
            "--agent", params["agent"],
            "--limit", str(params.get("limit", 10)),
        )
    elif action == "watch":
        stdout, stderr = run_broker(
            "watch",
            "--agent", params["agent"],
            "--interval", str(params.get("interval", 2)),
        )
    elif action == "status":
        stdout, stderr = run_broker("status", "--agent", params["agent"])
    elif action == "list":
        stdout, stderr = run_broker("list")
    elif action == "clear":
        stdout, stderr = run_broker("clear", "--agent", params["agent"])
    elif action == "start_daemon":
        role = params["role"]
        work_dir = params.get("work_dir", ".")
        skills_dir = params.get("skills_dir")
        daemon = Path(__file__).with_name("scripts").joinpath("agent_daemon.py")
        cmd = [
            sys.executable, str(daemon),
            "--role", role,
            "--work-dir", str(work_dir),
        ]
        if skills_dir:
            cmd += ["--skills-dir", str(skills_dir)]
        # Start detached / in background
        if os.name == "nt":
            subprocess.Popen(cmd, creationflags=subprocess.CREATE_NEW_CONSOLE)
        else:
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        stdout = json.dumps({"status": "daemon_started", "role": role}, ensure_ascii=False)
        stderr = ""
    else:
        print(json.dumps({"error": f"Unknown action: {action}"}))
        return

    # Try to parse stdout as JSON; if not JSON, wrap it
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError:
        data = {"raw_output": stdout}

    if stderr:
        data["_stderr"] = stderr.strip()

    print(json.dumps(data, ensure_ascii=False))


if __name__ == "__main__":
    main()
