# BR Multi-Agent Protocol

## Message Format

Messages are JSON files stored in the filesystem mailbox (`~/.br-agents/mailbox/`).

```json
{
  "id": "<unique-id>",
  "from": "<sender-role>",
  "to": "<recipient-role>",
  "type": "task | question | response | error | announce | discuss",
  "content": "<markdown string>",
  "timestamp": 1234567890.0,
  "thread_id": "<thread-id>",
  "reply_to": "<role to reply to>"
}
```

## Message Types

- `task` — A concrete work item. The recipient should do the work and reply with `response`.
- `question` — A specific technical question. The recipient should answer with `response`.
- `response` — The result or answer to a previous `task` or `question`.
- `error` — An error occurred while processing a message.
- `announce` — System or presence announcement (e.g., daemon started).
- `discuss` — Open-ended discussion point. Other agents may join the thread.

## Thread Model

- Every message belongs to a `thread_id`.
- If not provided, the broker sets `thread_id` to the message `id`.
- Replies must preserve the original `thread_id` so the coordinator can correlate them.

## CLI Commands (br_broker.py)

```bash
# Send a message
python br_broker.py send --from coordinator --to java-mod --type task --content "Fix the import in X.java"

# Poll unread messages
python br_broker.py poll --agent coordinator --limit 10 --mark-read

# Block until a new message arrives
python br_broker.py watch --agent coordinator --mark-read

# List all agents that have mailboxes
python br_broker.py list

# Show counts for an agent
python br_broker.py status --agent java-mod

# Clear an agent's mailbox
python br_broker.py clear --agent java-mod
```

## Daemon Behavior

- Each agent daemon (`agent_daemon.py`) polls its inbox at a fixed interval.
- When a message arrives, the daemon invokes `kimi --print --yolo -p "<prompt>"`.
- The prompt includes the agent's system prompt plus the message content.
- The daemon sends the Kimi output back to `reply_to` as a `response` message.

## Coordinator Conventions

- The `coordinator` is the default name for the main Kimi instance.
- When dispatching tasks, set `reply_to` to `coordinator` unless you want agents to talk directly to each other.
- To hold a discussion between agents, send `discuss` messages to each agent with the same `thread_id` and let them reply to the thread.
