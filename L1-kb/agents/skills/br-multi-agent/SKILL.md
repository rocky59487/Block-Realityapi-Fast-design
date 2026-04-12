---
name: br-multi-agent
description: Coordinate multiple Kimi agents for the Block Reality project using a local filesystem message bus. Use when the user wants to dispatch tasks to standby agents, run multi-agent discussions, or parallelize work across Java mod, ML pipeline, C++ GPU, and architecture domains.
---

# Block Reality Multi-Agent Coordinator

This skill enables a single "master" Kimi to dispatch tasks to multiple "standby" Kimi agents running on the same machine. Agents communicate through a lightweight filesystem message broker.

## How it works in VS Code

When this skill is active, Kimi has access to MCP tools that manage the agent bus:

- `br_agent_send` — dispatch a task or discussion message
- `br_agent_poll` — collect replies from an agent's mailbox
- `br_agent_status` — check inbox/read counts
- `br_agent_list` — list known agents
- `br_agent_clear` — clear a mailbox
- `br_agent_start_daemon` — launch a standby agent daemon

**Always prefer using these MCP tools over Shell commands.** They are faster and safer.

## Quick Start

### 1. Ensure MCP is registered

The `br-multi-agent` MCP server should be registered in your Kimi config. The expected config is at `~/.kimi/mcp.json`:

```json
{
  "mcpServers": {
    "br-multi-agent": {
      "command": "python",
      "args": [
        "C:/Users/wmc02/Desktop/git/Block-Realityapi-Fast-design/.agents/skills/br-multi-agent/mcp_server.py"
      ]
    }
  }
}
```

If you add or edit this file, reload Kimi in VS Code (`Ctrl+Shift+P` → `Developer: Reload Window`) so the MCP server is picked up.

### 2. Start standby agents

Use `br_agent_start_daemon` to launch agents. Typical roles for this project:

- `java-mod` — Minecraft Forge mod (Java), PFSF, network packets
- `ml-pipeline` — Python JAX/Flax/ONNX training pipeline
- `cpp-gpu` — C++ Vulkan compute, PFSF shaders, NRD JNI
- `architect` — Cross-stack architecture, SPI, docs sync
- `doc-sync` — `AGENTS.md`, `CLAUDE.md`, `docs/` hierarchy

Example: start `java-mod` and `ml-pipeline` daemons.

### 3. Dispatch tasks

Use `br_agent_send` with `to` and `content`. Then use `br_agent_poll` on `coordinator` to collect responses.

## Agent Roles

See `references/ROLES.md` for full details.

## Coordination Patterns

### Pattern A: Direct Dispatch

1. Send a task to `java-mod` using `br_agent_send`.
2. Wait briefly, then `br_agent_poll` the `coordinator` inbox for the response.

### Pattern B: Parallel Dispatch

Send multiple `br_agent_send` calls to different roles, then poll `coordinator` once to gather all replies.

### Pattern C: Agent Discussion

Create a shared thread ID (e.g., `discuss-001`) and send `discuss` messages to multiple agents with the same `thread`. When polling `coordinator`, forward interesting replies back to other agents using the same `thread` so the conversation continues.

### Pattern D: Check before polling

Use `br_agent_status` on `coordinator` to see if any replies have arrived before polling.

## Constraints

- Each standby agent consumes one Kimi CLI invocation per message (`kimi --quiet`). Keep tasks scoped.
- Agents run in `--quiet` mode (auto-approving tool calls). Monitor their outputs via `br_agent_poll`.
- Messages are stored in `~/.br-agents/mailbox/` by default. Agents must run on the same machine (or share the directory).
