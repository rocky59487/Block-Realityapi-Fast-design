#!/usr/bin/env python3
"""
agent_daemon.py — Block Reality Agent Daemon

Watches the br_broker mailbox for a specific agent role and dispatches
incoming tasks to Kimi CLI in --print mode. The daemon runs indefinitely
until interrupted.

Usage:
    python agent_daemon.py --role java-mod --work-dir "C:/.../Block Realityapi-Fast-design"
    python agent_daemon.py --role ml-pipeline --work-dir "C:/.../Block Realityapi-Fast-design"

Recommended startup (Windows PowerShell, background):
    Start-Process python -ArgumentList "agent_daemon.py","--role","java-mod","--work-dir","..." -WindowStyle Hidden
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def find_kimi_exe() -> str:
    """Locate kimi.exe on Windows or 'kimi' on Unix."""
    candidates = [
        Path.home() / "AppData" / "Roaming" / "Code" / "User" / "globalStorage" / "moonshot-ai.kimi-code" / "bin" / "kimi" / "kimi.exe",
        Path.home() / ".vscode" / "extensions" / "moonshot-ai.kimi-code" / "bin" / "kimi" / "kimi.exe",
        Path("kimi.exe"),
        Path("kimi"),
    ]
    for c in candidates:
        if isinstance(c, Path) and c.exists():
            return str(c)
    # Fallback to PATH
    return "kimi"


def run_kimi(prompt: str, work_dir: str, skills_dir: str = None, extra_args: list = None) -> str:
    """Invoke kimi --quiet -p with the given prompt. --quiet implies --print --yolo --final-message-only."""
    cmd = [find_kimi_exe(), "--quiet", "-p", prompt]
    if work_dir:
        cmd += ["-w", str(work_dir)]
    if skills_dir:
        cmd += ["--skills-dir", str(skills_dir)]
    if extra_args:
        cmd += extra_args
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    output = result.stdout
    if result.stderr:
        output += "\n[stderr] " + result.stderr
    return output


def broker_cmd(broker_path: Path, *args):
    result = subprocess.run([sys.executable, str(broker_path), *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return result.stdout


def build_system_prompt(role: str) -> str:
    """Build a concise system prompt for the agent role."""
    base = (
        f"You are the '{role}' specialist agent in the Block Reality multi-agent system.\n"
        "You are an expert software engineer working on the Block Reality project.\n"
        "Respond concisely but thoroughly. When using tools, prefer making minimal changes.\n"
        "Always cite file paths when referencing code or documentation.\n"
    )
    role_additions = {
        "java-mod": (
            "Your expertise: Minecraft Forge 1.20.1, Java 17, Gradle, LWJGL, Vulkan JNI, JUnit 5.\n"
            "You work in the 'Block Reality/api' and 'Block Reality/fastdesign' modules.\n"
            "Critical rule: fastdesign may depend on api, but api must NEVER reference fastdesign.\n"
            "Always annotate client-only classes with @OnlyIn(Dist.CLIENT).\n"
        ),
        "ml-pipeline": (
            "Your expertise: Python 3.10+, JAX/Flax, Optax, ONNX, pytest, FEM (hex8).\n"
            "You work in the 'brml/' directory. Follow ruff formatting (line length 100).\n"
            "Validate model shapes and ONNX contracts after any change.\n"
        ),
        "cpp-gpu": (
            "Your expertise: C++17/20, Vulkan Compute, CMake, NVIDIA NRD.\n"
            "You work in 'libpfsf/' and 'api/src/main/native/'.\n"
            "Ensure 26-connectivity stencil consistency across all PFSF shaders.\n"
        ),
        "architect": (
            "Your expertise: system architecture, module boundaries, SPI design, documentation sync.\n"
            "You oversee the entire Block Reality stack (Java / Python / C++).\n"
            "When proposing changes, always point out which docs (docs/L1-xxx, CLAUDE.md, AGENTS.md) must be updated.\n"
        ),
        "doc-sync": (
            "Your expertise: technical writing, markdown, AGENTS.md maintenance rules.\n"
            "When code changes affect documented behavior, update the corresponding docs/L1/L2/L3 files and CLAUDE.md.\n"
        ),
    }
    return base + role_additions.get(role, "")


def process_message(msg: dict, role: str, work_dir: str, skills_dir: str, broker_path: Path):
    """Handle a single incoming message by invoking Kimi CLI."""
    content = msg.get("content", "")
    thread_id = msg.get("thread_id", msg.get("id"))
    sender = msg.get("from", "coordinator")
    msg_type = msg.get("type", "task")

    system_prompt = build_system_prompt(role)
    prompt = f"{system_prompt}\n\n[{msg_type.upper()} from {sender} | thread {thread_id}]\n\n{content}\n\nProvide your response."

    # Run Kimi
    response = run_kimi(prompt, work_dir, skills_dir)

    # Send response back
    reply_to = msg.get("reply_to") or sender
    if reply_to == "system":
        reply_to = "coordinator"

    broker_cmd(
        broker_path,
        "send",
        "--from", role,
        "--to", reply_to,
        "--type", "response",
        "--content", response,
        "--thread", thread_id,
        "--reply-to", role,
    )


def main():
    parser = argparse.ArgumentParser(description="Block Reality Agent Daemon")
    parser.add_argument("--role", required=True, help="Agent role (e.g. java-mod, ml-pipeline, cpp-gpu)")
    parser.add_argument("--work-dir", default=".", help="Working directory passed to Kimi CLI")
    parser.add_argument("--skills-dir", default=None, help="Extra skills directory passed to Kimi CLI")
    parser.add_argument("--broker", default=None, help="Path to br_broker.py")
    parser.add_argument("--interval", type=int, default=3, help="Polling interval in seconds")
    parser.add_argument("--once", action="store_true", help="Process one message and exit")
    args = parser.parse_args()

    broker_path = Path(args.broker) if args.broker else Path(__file__).with_name("br_broker.py")

    # Announce presence
    broker_cmd(
        broker_path,
        "send",
        "--from", "system",
        "--to", args.role,
        "--type", "announce",
        "--content", f"Daemon for {args.role} started on this machine.",
    )

    print(f"[{args.role}] Daemon started. Polling every {args.interval}s...")
    sys.stdout.flush()

    while True:
        result = broker_cmd(broker_path, "poll", "--agent", args.role, "--limit", "1", "--mark-read")
        try:
            data = json.loads(result)
        except json.JSONDecodeError:
            time.sleep(args.interval)
            continue

        if data.get("count", 0) == 0:
            if args.once:
                print(f"[{args.role}] No messages. Exiting (--once).")
                break
            time.sleep(args.interval)
            continue

        msg = data["messages"][0]
        print(f"[{args.role}] Processing message {msg.get('id')} from {msg.get('from')}...")
        sys.stdout.flush()

        try:
            process_message(msg, args.role, args.work_dir, args.skills_dir, broker_path)
        except Exception as e:
            broker_cmd(
                broker_path,
                "send",
                "--from", args.role,
                "--to", msg.get("from", "coordinator"),
                "--type", "error",
                "--content", f"Daemon error while processing message: {e}",
                "--thread", msg.get("thread_id", msg.get("id")),
            )

        if args.once:
            print(f"[{args.role}] Message processed. Exiting (--once).")
            break


if __name__ == "__main__":
    main()
