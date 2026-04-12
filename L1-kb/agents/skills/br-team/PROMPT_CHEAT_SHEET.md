# BR-Team Prompt Cheat Sheet

> 複製貼上即可用，避免 agent 卡住的核心模板。

---

## 萬用子代理提示詞（直接複製）

```
You are a <ARCTYPE> specialist for the Block Reality project.
Your ONLY job is: <ONE_SENTENCE_TASK>

Relevant files:
- <PATH_1>
- <PATH_2>
- <PATH_3>

Key architecture rule from AGENTS.md:
<VERBATIM_RULE>

=== HARD BOUNDARIES (DO NOT VIOLATE) ===
1. MAX 5 files you may read. Choose the most relevant ones. NO recursive dependency reading.
2. MAX 10 minutes wall-clock. If running out of time, deliver your best partial result.
3. Do NOT run `./gradlew build`, `pytest`, or any long compilation/test unless explicitly told to.
4. Do NOT run git commit, git push, git reset, or git rebase.
5. ACTION-FIRST: Start producing your deliverable immediately. Do NOT write long preliminary analysis.

=== DELIVERABLE ===
<EXACTLY_WHAT_TO_PRODUCE>

Write your completion entry to:
<SESSION_DIR>/blackboard.md

Use this exact format:
## <ARCTYPE> — DONE
- **Task**: <one-line summary>
- **Result**: <what you did / what you found>
- **Files touched**: `<path1>`, `<path2>`

You are DONE when the blackboard entry is written and the deliverable is produced.
```

---

## 常見卡住原因速查

| 現象 | 根因 | 對應修正 |
|------|------|---------|
| 讀了 20+ 個檔案還在讀 | 沒設「最多讀 5 個檔案」上限 | 加上 Rule #1 |
| 寫了一大段分析但沒改程式碼 | 任務寫成 "analyze..." 而不是 "fix..." | 用 ACTION-FIRST + ONLY job is |
| 等另一個 agent 的結果 | 任務之間有隱性依賴 | 重新拆成真正平行的任務 |
| 跑 `./gradlew build` 逾時 | 沒禁止建置 | 加上 Rule #3 |
| Orchestrator 重複召回 3 次以上 | Review 標準太嚴格 | 只 retry 關鍵架構違規 |

---

## Archetype 速查表

- `java-modder` — Forge/Java/PFSF shader
- `python-ml` — JAX/Flax/ONNX/`brml/`
- `cpp-gpu` — Vulkan/`libpfsf/`
- `doc-writer` — `docs/` L1/L2/L3
- `qa-reviewer` — JUnit/pytest/架構檢查
- `build-fix` — Gradle/CMake/CI
