#!/usr/bin/env python3
"""
br_broker.py — Block Reality Multi-Agent Message Broker

A lightweight filesystem-based message bus for coordinating multiple
Kimi agents on the same machine. Messages are stored as JSON files
in a shared mailbox directory.

Usage:
    python br_broker.py send --from <agent> --to <agent> --type <type> --content "..."
    python br_broker.py poll --agent <agent> [--limit N] [--mark-read]
    python br_broker.py watch --agent <agent> [--mark-read] [--interval SEC]
    python br_broker.py list
    python br_broker.py status --agent <agent>
    python br_broker.py clear --agent <agent>
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

DEFAULT_MAILBOX = Path.home() / ".br-agents" / "mailbox"


def get_mailbox() -> Path:
    return Path(os.environ.get("BR_MAILBOX", str(DEFAULT_MAILBOX)))


def ensure_dir(p: Path) -> Path:
    p.mkdir(parents=True, exist_ok=True)
    return p


def cmd_send(args):
    mailbox = get_mailbox()
    msg_id = f"{int(time.time() * 1000)}-{os.urandom(4).hex()}"
    msg = {
        "id": msg_id,
        "from": args.from_agent,
        "to": args.to,
        "type": args.type,
        "content": args.content,
        "timestamp": time.time(),
        "thread_id": args.thread or msg_id,
        "reply_to": args.reply_to or args.from_agent,
    }
    inbox = ensure_dir(mailbox / args.to / "inbox")
    path = inbox / f"{msg_id}.json"
    path.write_text(json.dumps(msg, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"status": "sent", "message_id": msg_id, "path": str(path)}, ensure_ascii=False), flush=True)


def cmd_poll(args):
    mailbox = get_mailbox()
    inbox = ensure_dir(mailbox / args.agent / "inbox")
    files = sorted(inbox.glob("*.json"), key=lambda p: p.stat().st_mtime)
    if args.mark_read:
        read_dir = ensure_dir(mailbox / args.agent / "read")
    messages = []
    for f in files[: args.limit]:
        try:
            msg = json.loads(f.read_text(encoding="utf-8"))
            messages.append(msg)
            if args.mark_read:
                dest = read_dir / f.name
                if dest.exists():
                    dest.unlink()
                f.rename(dest)
        except Exception:
            continue
    print(json.dumps({"count": len(messages), "messages": messages}, indent=2, ensure_ascii=False))


def cmd_watch(args):
    mailbox = get_mailbox()
    inbox = ensure_dir(mailbox / args.agent / "inbox")
    known = set(p.name for p in inbox.glob("*.json"))
    while True:
        files = sorted(inbox.glob("*.json"), key=lambda p: p.stat().st_mtime)
        for f in files:
            if f.name not in known:
                try:
                    msg = json.loads(f.read_text(encoding="utf-8"))
                except Exception:
                    continue
                print(json.dumps(msg, ensure_ascii=False))
                sys.stdout.flush()
                if args.mark_read:
                    read_dir = ensure_dir(mailbox / args.agent / "read")
                    dest = read_dir / f.name
                    if dest.exists():
                        dest.unlink()
                    f.rename(dest)
                return
        known = set(p.name for p in files)
        time.sleep(args.interval)


def cmd_list(args):
    mailbox = get_mailbox()
    ensure_dir(mailbox)
    agents = sorted([d.name for d in mailbox.iterdir() if d.is_dir()])
    print(json.dumps({"agents": agents}, ensure_ascii=False))


def cmd_status(args):
    mailbox = get_mailbox()
    agent_dir = mailbox / args.agent
    inbox_dir = agent_dir / "inbox"
    read_dir = agent_dir / "read"
    inbox_count = len(list(inbox_dir.glob("*.json"))) if inbox_dir.exists() else 0
    read_count = len(list(read_dir.glob("*.json"))) if read_dir.exists() else 0
    print(json.dumps({"agent": args.agent, "inbox": inbox_count, "read": read_count}, ensure_ascii=False))


def cmd_clear(args):
    mailbox = get_mailbox()
    inbox_dir = mailbox / args.agent / "inbox"
    read_dir = mailbox / args.agent / "read"
    removed = 0
    for d in (inbox_dir, read_dir):
        if d.exists():
            for f in d.glob("*.json"):
                f.unlink()
                removed += 1
    print(json.dumps({"status": "cleared", "agent": args.agent, "removed": removed}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(prog="br_broker.py", description="Block Reality Multi-Agent Message Broker")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # send
    p_send = subparsers.add_parser("send", help="Send a message to an agent")
    p_send.add_argument("--from", dest="from_agent", required=True)
    p_send.add_argument("--to", required=True)
    p_send.add_argument("--type", default="task")
    p_send.add_argument("--content", required=True)
    p_send.add_argument("--thread")
    p_send.add_argument("--reply-to")
    p_send.set_defaults(func=cmd_send)

    # poll
    p_poll = subparsers.add_parser("poll", help="Poll messages for an agent")
    p_poll.add_argument("--agent", required=True)
    p_poll.add_argument("--limit", type=int, default=10)
    p_poll.add_argument("--mark-read", action="store_true")
    p_poll.set_defaults(func=cmd_poll)

    # watch
    p_watch = subparsers.add_parser("watch", help="Block until a new message arrives")
    p_watch.add_argument("--agent", required=True)
    p_watch.add_argument("--mark-read", action="store_true")
    p_watch.add_argument("--interval", type=int, default=2)
    p_watch.set_defaults(func=cmd_watch)

    # list
    p_list = subparsers.add_parser("list", help="List all registered agents")
    p_list.set_defaults(func=cmd_list)

    # status
    p_status = subparsers.add_parser("status", help="Show message counts for an agent")
    p_status.add_argument("--agent", required=True)
    p_status.set_defaults(func=cmd_status)

    # clear
    p_clear = subparsers.add_parser("clear", help="Clear all messages for an agent")
    p_clear.add_argument("--agent", required=True)
    p_clear.set_defaults(func=cmd_clear)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
