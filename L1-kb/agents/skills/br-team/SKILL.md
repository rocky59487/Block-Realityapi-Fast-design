# BR-Team: Block Reality Multi-Agent Orchestration Skill

> **Trigger**: User prefixes a request with `team:` or `@team`  
> **Scope**: Block Reality project (Minecraft Forge mod + ML pipeline + docs)  
> **Hard Limit**: Max 5 concurrent sub-agents via `Task` tool.

---

## 1. Activation Rule

Activate this skill when the user says something like:
- `team: 幫我...`
- `@team 處理...`
- `用 team 模式...`
- `全部完成我要拿到一個專業的模組團隊繼續完成...`

When active, **you (the main Kimi agent) become the Team Orchestrator**. Do NOT attempt to solve the task alone. Follow the protocol below.

---

## 2. Pre-Flight: Read Context

Before decomposing, **always** read these files if they exist:

| File | Why |
|------|-----|
| `AGENTS.md` | Architecture rules, unit conventions, client/server split |
| `CLAUDE.md` | Build commands, doc maintenance rules, SPI contracts |
| `BIFROST.md` | ML pipeline & training conventions |
| `README.md` | High-level project map |

If the task touches code in a specific layer, also read the relevant `docs/L1-*/L2-*/L3-*.md` files.

---

## 3. Decomposition Rules

Break the user's request into **1–5 independent sub-tasks**. Each sub-task must be:

- **Well-scoped**: One concern only (e.g., "implement Java class", "update docs", "fix shader", "add test").
- **Parallelizable**: Does not block on another sub-agent's output.
- **Verifiable**: Has a clear done criterion.
- **Bounded**: Can be completed by reading ≤ 5 files. If larger, split into multiple agents by directory or file group.

### ⚠️ Anti-Pattern: Analysis Paralysis

**Never** give a sub-agent a task like:
> "Analyze the entire codebase and find all issues..."
> "First understand the architecture, then..."
> "Check all dependencies recursively..."

This causes agents to loop forever reading files. **Always** give a concrete deliverable and a hard file-read limit.

---

## 4. Sub-Agent Archetypes

Pick the archetype that best fits each sub-task. Use the exact role name in the sub-agent prompt.

| Archetype | Expertise | Typical Output |
|-----------|-----------|----------------|
| `java-modder` | Forge 1.20.1, Java 17, Gradle, PFSF GPU physics | `.java` files, shader patches |
| `python-ml` | JAX/Flax, ONNX, `brml/` pipeline, FEM ground-truth | `.py` files, model configs |
| `cpp-gpu` | Vulkan compute, `libpfsf/`, NRD JNI | `.cpp`/`.h`/`.glsl` files |
| `doc-writer` | `docs/` L1/L2/L3 structure, Markdown, cross-referencing | Updated `.md` files |
| `qa-reviewer` | JUnit 5, pytest, architecture rule enforcement | Test files, review report |
| `build-fix` | Gradle, CMake, Docker, CI workflows | `build.gradle`, `.yml` fixes |

---

## 5. Skill Matching

Each sub-agent may need additional skills. In the `Task` prompt, explicitly tell the sub-agent:

> "If you need specialized knowledge (e.g., how to use `find-skills`), consult the skill at `.agents/skills/<skill-name>/SKILL.md`."

For Block Reality, common skill hints:
- `find-skills` — if the sub-agent doesn't know how to do something Kimi-native.
- `kimi-cli-help` — if the sub-agent needs to explain CLI behavior to the user.

Do **not** assume sub-agents can see this `br-team` skill.

---

## 6. Blackboard Protocol

All sub-agents write to a **shared session workspace** so results remain coherent.

### Directory
```
.team_session/<timestamp>_<short_task_name>/
```

Use the helper script to initialize:
```bash
python .agents/skills/br-team/session_manager.py init "<task_name>"
```

### Required Files
Each sub-agent MUST append or write to these files:

| File | Purpose |
|------|---------|
| `blackboard.md` | Status updates: `## <AgentName> — <Status>` with bullet points of findings/decisions |
| `artifacts.list` | Absolute or relative paths of every file created or modified |
| `warnings.md` | Any architecture concerns, missing registrations, or untested changes |

### Blackboard Entry Template
```markdown
## <Archetype> (<AgentName>) — <DONE|IN_PROGRESS|BLOCKED>
- **Task**: <one-line summary>
- **Key Decision**: <e.g., "Added rtens /= sigmaMax in PFSFDataBuilder">
- **Files touched**: `<path1>`, `<path2>`
- **Blocked on**: <none | another agent's output>
```

Sub-agents should **write** their entry exactly once before exiting.

---

## 7. Execution Flow (Orchestrator)

```
1. READ  → AGENTS.md, CLAUDE.md, relevant docs (≤ 3 files)
2. PLAN  → Decompose into 1–5 tasks, assign archetypes
3. INIT  → Create .team_session/<id>/ directory + empty blackboard.md
4. SPAWN → Launch all sub-agents in parallel via Task tool
5. WAIT  → Collect all results
6. REVIEW→ Run consistency checks (see §9)
7. INTEGRATE → If clean, synthesize final report
8. RETRY → If critical issues found, respawn specific agents (max 1 round)
```

---

## 8. Sub-Agent Prompt Template (CRITICAL)

Every `Task` prompt **must** contain the following sections. Omitting any of these is the #1 cause of stuck agents.

### 8.1 Header — Role Lock
```
You are a <Archetype> specialist for the Block Reality project.
Your ONLY job is: <one-sentence task description>
```

### 8.2 Context — Exactly What They Need
```
Relevant files/directories:
- <path 1>
- <path 2>
- <path 3>

Key architecture rule from AGENTS.md:
<verbatim quote of the relevant rule>
```

### 8.3 Hard Boundaries — The Anti-Stuck Guardrails
```
RULES:
1. You have a MAXIMUM of 5 files you may read. Choose wisely. Do NOT read dependencies of dependencies.
2. You have a MAXIMUM of 10 minutes of wall-clock time. If running out of time, deliver your best partial result.
3. Do NOT run `./gradlew build` or any long compilation unless explicitly told to.
4. Do NOT run git commit, git push, git reset, or git rebase.
5. Output-first: Start working toward your deliverable immediately. Do NOT write long preliminary analyses.
```

### 8.4 Deliverable — What Success Looks Like
```
DELIVERABLE:
<exact description of what to produce>

Write your result to the blackboard at:
<blackboard_path>/blackboard.md

Format:
## <Archetype> — DONE
- **Task**: ...
- **Result**: ...
- **Files touched**: ...
```

### 8.5 Done Criterion
```
You are DONE when:
- <checklist item 1>
- <checklist item 2>
- Blackboard entry is written
```

---

## 9. Review Checklist (Orchestrator)

After all sub-agents return, read the blackboard and artifacts. Check for:

### Cross-Agent Consistency
- [ ] If a Java interface changed, does the doc-writer update the corresponding L3 doc?
- [ ] If a new node class was added, is it registered in `NodeRegistry`?
- [ ] If a client-only class was added/modified, does it have `@OnlyIn(Dist.CLIENT)`?
- [ ] If `sigmaMax` normalization was added, are **all** new threshold buffers divided?
- [ ] If a shader stencil weight changed, are C++ and Java shaders synchronized?

### Architecture Compliance
- [ ] No `api` → `fastdesign` references (must be one-way).
- [ ] No server-side code imports from `.../client/` packages.
- [ ] Network packets use FQN or lazy resolution for client classes.
- [ ] Physical units are MPa / GPa / kg/m³ (not Pa).

### Testing
- [ ] New logic has at least one test or a `warnings.md` justification.

### ⚠️ Review Policy: Do Not Perfection-Chase
If a sub-agent's output is **good enough** (meets the task goal, no architectural violations), **accept it**. Do NOT respawn agents for:
- Minor formatting differences
- Missing comments
- Style preferences

**Maximum 1 retry round** for critical issues only.

---

## 10. Final Output Format

Present the user with:

1. **Summary** — What was accomplished in 2–3 sentences.
2. **Agent Roster** — Table of who did what.
3. **Files Changed** — Collated from all `artifacts.list` files.
4. **Warnings** — Anything from `warnings.md` that requires user attention.
5. **Next Steps** (optional) — Suggested follow-up tasks.

---

## 11. Troubleshooting: Why Agents Get Stuck

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| Agent reads 20+ files | Task scope too broad / no file limit | Add "MAX 5 files" rule to prompt |
| Agent writes long analysis but no code | Prompt says "analyze" instead of "implement" | Use action-first deliverable wording |
| Agent waits for another agent | Tasks are not actually parallelizable | Redesign decomposition |
| Agent runs `./gradlew build` and times out | No explicit build prohibition | Add "Do NOT run gradle build" rule |
| Orchestrator respawns 3+ times | Review criteria too strict | Simplify checklist, accept good-enough |
| Agent loops reading skills/docs | Unclear if skill is needed | Give a direct task, mention skills only as fallback |

---

## 12. Example

### User Input
```
team: 統一 api 模組中 Jacobi、RBGS、PCG matvec 的 26-stencil 權重，並更新對應文件
```

### Orchestrator Plan
1. **java-modder** — Audit Java-side shaders (`assets/blockreality/shaders/compute/pfsf/rbgs_smooth.comp.glsl`, `jacobi_smooth.comp.glsl`, `pcg_matvec.comp.glsl`). Unify weights. Max 3 files.
2. **cpp-gpu** — Audit `libpfsf/src/solver/` shaders. Match Java weights. Max 3 files.
3. **doc-writer** — Update `docs/L1-api/L2-physics/L3-pfsf-shaders.md` with the new weight table. Max 2 files.

### Sub-Agent 1 Prompt (java-modder)
```
You are a java-modder specialist for the Block Reality project.
Your ONLY job is: unify the 26-stencil weights in the three PFSF Java shaders to match the spec.

Relevant files:
- Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/rbgs_smooth.comp.glsl
- Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/jacobi_smooth.comp.glsl
- Block Reality/api/src/main/resources/assets/blockreality/shaders/compute/pfsf/pcg_matvec.comp.glsl

Key architecture rule from AGENTS.md:
"RBGS、Jacobi、PCG matvec 必須使用完全相同的 26 連通 stencil：6 面鄰居 ×1.0，12 邊鄰居 ×0.35，8 角鄰居 ×0.15"

RULES:
1. You have a MAXIMUM of 5 files you may read. Do NOT read beyond these three shaders unless you need a backup.
2. You have a MAXIMUM of 10 minutes. Deliver partial results if time is short.
3. Do NOT run `./gradlew build`.
4. Do NOT run git commit, git push, git reset, or git rebase.
5. Output-first: edit the files immediately. Do NOT write a long essay first.

DELIVERABLE:
Edit the stencil weight constants in the three shaders so they all use 1.0, 0.35, 0.15.
Write your result to:
.team_session/20260412_164051_stencil_unify/blackboard.md

Format:
## java-modder — DONE
- **Task**: Unify Java PFSF shader stencil weights
- **Result**: <what you changed>
- **Files touched**: <list>
```

### Review
Orchestrator checks:
- Java `0.35/0.15` weights? Yes.
- C++ matches? Yes.
- Docs mention both Java and C++ paths? Yes.

No retries needed. Output final report.

---

## 13. Safety & Constraints

- **Max 5 concurrent `Task` calls** at any time.
- **Sub-agents have no cross-turn memory** — every prompt must include full context.
- **Never let sub-agents run git mutations** (`commit`, `push`, `reset`, `rebase`).
- **Stay inside the working directory** unless explicitly authorized.
- **Respect GPL-3.0 license** — do not commit binaries.
