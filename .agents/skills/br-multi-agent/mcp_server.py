#!/usr/bin/env python3
"""
mcp_server.py — MCP stdio server for Block Reality multi-agent coordination.

Exposes tools:
  br_agent_send
  br_agent_poll
  br_agent_status
  br_agent_list
  br_agent_clear
  br_agent_start_daemon

Usage (registered in MCP config):
  {
    "mcpServers": {
      "br-multi-agent": {
        "command": "python",
        "args": ["C:/Users/.../.agents/skills/br-multi-agent/mcp_server.py"]
      }
    }
  }
"""

import json
import os
import subprocess
import sys
from pathlib import Path

BROKER = Path(__file__).with_name("scripts").joinpath("br_broker.py")
DAEMON = Path(__file__).with_name("scripts").joinpath("agent_daemon.py")


def broker(*args):
    result = subprocess.run(
        [sys.executable, str(BROKER), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout.strip(), result.stderr.strip()


def tool_send(params: dict):
    out, err = broker(
        "send",
        "--from", params.get("from", "coordinator"),
        "--to", params["to"],
        "--type", params.get("type", "task"),
        "--content", params["content"],
        "--thread", params.get("thread", ""),
        "--reply-to", params.get("reply_to", params.get("from", "coordinator")),
    )
    return out or err or "{}"


def tool_poll(params: dict):
    out, err = broker(
        "poll",
        "--agent", params["agent"],
        "--limit", str(params.get("limit", 10)),
    )
    return out or err or "{}"


def tool_status(params: dict):
    out, err = broker("status", "--agent", params["agent"])
    return out or err or "{}"


def tool_list(_params: dict):
    out, err = broker("list")
    return out or err or "{}"


def tool_clear(params: dict):
    out, err = broker("clear", "--agent", params["agent"])
    return out or err or "{}"


def tool_start_daemon(params: dict):
    role = params["role"]
    work_dir = params.get("work_dir", ".")
    skills_dir = params.get("skills_dir")
    cmd = [sys.executable, str(DAEMON), "--role", role, "--work-dir", str(work_dir)]
    if skills_dir:
        cmd += ["--skills-dir", str(skills_dir)]
    if os.name == "nt":
        subprocess.Popen(cmd, creationflags=subprocess.CREATE_NEW_CONSOLE)
    else:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    return json.dumps({"status": "daemon_started", "role": role}, ensure_ascii=False)


TOOLS = [
    {
        "name": "br_agent_send",
        "description": "Send a message to a standby agent. Use this when dispatching a task or starting a discussion.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "from": {"type": "string", "description": "Sender role (default: coordinator)"},
                "to": {"type": "string", "description": "Recipient agent role, e.g. java-mod, ml-pipeline, cpp-gpu, architect, doc-sync"},
                "type": {"type": "string", "description": "Message type: task, question, discuss, announce (default: task)"},
                "content": {"type": "string", "description": "Message content / instructions"},
                "thread": {"type": "string", "description": "Thread ID for grouping related messages"},
                "reply_to": {"type": "string", "description": "Role that should receive replies (default: coordinator)"}
            },
            "required": ["to", "content"]
        }
    },
    {
        "name": "br_agent_poll",
        "description": "Poll unread messages for a given agent (usually coordinator). Returns up to N recent messages without deleting them.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "agent": {"type": "string", "description": "Agent whose mailbox to poll"},
                "limit": {"type": "integer", "description": "Max messages to fetch (default: 10)"}
            },
            "required": ["agent"]
        }
    },
    {
        "name": "br_agent_status",
        "description": "Show inbox/read message counts for an agent.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "agent": {"type": "string", "description": "Agent to check"}
            },
            "required": ["agent"]
        }
    },
    {
        "name": "br_agent_list",
        "description": "List all agents that currently have mailboxes.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "br_agent_clear",
        "description": "Clear all messages for an agent. Use sparingly, typically at the start of a new session.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "agent": {"type": "string", "description": "Agent to clear"}
            },
            "required": ["agent"]
        }
    },
    {
        "name": "br_agent_start_daemon",
        "description": "Start a standby agent daemon for a given role. The daemon will poll its mailbox and invoke Kimi CLI automatically.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "role": {"type": "string", "description": "Agent role to start, e.g. java-mod, ml-pipeline, cpp-gpu, architect, doc-sync"},
                "work_dir": {"type": "string", "description": "Working directory for Kimi CLI (default: current directory)"},
                "skills_dir": {"type": "string", "description": "Extra skills directory for Kimi CLI"}
            },
            "required": ["role"]
        }
    }
]


HANDLERS = {
    "br_agent_send": tool_send,
    "br_agent_poll": tool_poll,
    "br_agent_status": tool_status,
    "br_agent_list": tool_list,
    "br_agent_clear": tool_clear,
    "br_agent_start_daemon": tool_start_daemon,
}


def send_message(msg: dict):
    payload = json.dumps(msg)
    sys.stdout.write(f"Content-Length: {len(payload)}\r\n\r\n{payload}")
    sys.stdout.flush()


def read_message():
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        line = line.decode("utf-8").strip()
        if line == "":
            break
        key, value = line.split(":", 1)
        headers[key.strip()] = value.strip()
    length = int(headers.get("Content-Length", 0))
    if length == 0:
        return None
    raw_bytes = sys.stdin.buffer.read(length)
    raw = raw_bytes.decode("utf-8")
    return json.loads(raw)


def main():
    while True:
        msg = read_message()
        if msg is None:
            break
        msg_id = msg.get("id")
        method = msg.get("method")

        if method == "initialize":
            send_message({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "serverInfo": {"name": "br-multi-agent", "version": "1.0.0"}
                }
            })
        elif method == "initialized":
            continue
        elif method == "tools/list":
            send_message({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {"tools": TOOLS}
            })
        elif method == "tools/call":
            params = msg.get("params", {})
            name = params.get("name")
            arguments = params.get("arguments", {})
            handler = HANDLERS.get(name)
            if handler is None:
                send_message({
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "error": {"code": -32601, "message": f"Unknown tool: {name}"}
                })
                continue
            try:
                result_text = handler(arguments)
                # Ensure it's valid-ish JSON string for content
                try:
                    json.loads(result_text)
                    content = [{"type": "text", "text": result_text}]
                except json.JSONDecodeError:
                    content = [{"type": "text", "text": result_text}]
                send_message({
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "result": {"content": content, "isError": False}
                })
            except Exception as e:
                send_message({
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "result": {
                        "content": [{"type": "text", "text": json.dumps({"error": str(e)})}],
                        "isError": True
                    }
                })
        else:
            send_message({
                "jsonrpc": "2.0",
                "id": msg_id,
                "error": {"code": -32601, "message": f"Method not found: {method}"}
            })


if __name__ == "__main__":
    main()
