#!/usr/bin/env python3
"""
03 · Claude PR 審核腳本

取得 PR diff + 原始 Claude 計畫，呼叫 Claude Opus 進行審核，
提交 GitHub 正式 PR Review（APPROVE 或 REQUEST_CHANGES）。
審核結果寫入 /tmp/claude_review_decision 供 workflow 讀取標籤用。
"""

import json
import os
import re
import sys
import time
import urllib.request
import urllib.error

# ── 環境變數 ──────────────────────────────────────────────────────────
ANTHROPIC_API_KEY = os.environ["ANTHROPIC_API_KEY"]
GITHUB_TOKEN      = os.environ["GITHUB_TOKEN"]
REPO              = os.environ["GITHUB_REPOSITORY"]
PR_NUMBER         = os.environ["PR_NUMBER"]
LINKED_ISSUE      = os.environ.get("LINKED_ISSUE", "")

CLAUDE_MODEL = "claude-opus-4-6"
MAX_TOKENS   = 8192
RETRY_COUNT  = 3
MAX_DIFF_CHARS = 30_000   # 超出時截斷，避免 token 超限

# ── GitHub API ────────────────────────────────────────────────────────

def gh_get(path: str, accept: str = "application/vnd.github+json") -> object:
    url = f"https://api.github.com/repos/{REPO}/{path}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "Accept": accept,
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def gh_post(path: str, body: dict) -> dict:
    url = f"https://api.github.com/repos/{REPO}/{path}"
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {GITHUB_TOKEN}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


# ── Anthropic API ─────────────────────────────────────────────────────

def call_claude(system: str, user: str) -> str:
    body = {
        "model": CLAUDE_MODEL,
        "max_tokens": MAX_TOKENS,
        "system": system,
        "messages": [{"role": "user", "content": user}],
    }
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            req = urllib.request.Request(
                "https://api.anthropic.com/v1/messages",
                data=json.dumps(body).encode(),
                headers={
                    "x-api-key": ANTHROPIC_API_KEY,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=180) as resp:
                data = json.loads(resp.read())
            return data["content"][0]["text"]
        except urllib.error.URLError as e:
            print(f"[Attempt {attempt}/{RETRY_COUNT}] API error: {e}", file=sys.stderr)
            if attempt < RETRY_COUNT:
                time.sleep(attempt * 15)
            else:
                raise


# ── 資料收集 ──────────────────────────────────────────────────────────

def get_pr_diff(pr_number: str) -> str:
    """取得 PR 的 unified diff。"""
    url = f"https://api.github.com/repos/{REPO}/pulls/{pr_number}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "Accept": "application/vnd.github.diff",   # 直接回傳 diff 格式
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(req, timeout=60) as resp:
        diff = resp.read().decode("utf-8", errors="replace")
    if len(diff) > MAX_DIFF_CHARS:
        diff = diff[:MAX_DIFF_CHARS] + f"\n... [diff 超過 {MAX_DIFF_CHARS} 字元，已截斷] ..."
    return diff


def find_claude_plan(issue_number: str) -> str:
    """從 Issue comments 找 [CLAUDE_PLAN] comment 的內容。"""
    if not issue_number:
        return "(找不到連結的 Issue)"
    try:
        comments = gh_get(f"issues/{issue_number}/comments?per_page=100")
        for comment in reversed(comments):   # 從最新往回找
            body = comment.get("body", "")
            if "[CLAUDE_PLAN]" in body or "WORKFLOW_MARKER: [CLAUDE_PLAN]" in body:
                # 提取計畫內容
                lines = body.split("\n")
                start = next((i for i, l in enumerate(lines) if l.startswith("## 📋")), 0)
                end   = next((i for i in range(len(lines)-1, -1, -1)
                              if "WORKFLOW_MARKER" in lines[i]), len(lines))
                return "\n".join(lines[start:end]).strip()
    except Exception as e:
        print(f"Warning: 無法取得 Issue #{issue_number} comments: {e}", file=sys.stderr)
    return "(找不到原始計畫)"


def load_claude_md() -> str:
    for path in ["CLAUDE.md", ".claude/CLAUDE.md"]:
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                return f.read()
    return ""


# ── 解析審核決策 ──────────────────────────────────────────────────────

def parse_decision(review_text: str) -> str:
    """
    從 Claude 的審核回覆中解析決策。
    必須找到 [APPROVE] 或 [REQUEST_CHANGES] 標記。
    預設為 REQUEST_CHANGES（保守）。
    """
    if "[APPROVE]" in review_text:
        return "APPROVE"
    if "[REQUEST_CHANGES]" in review_text:
        return "REQUEST_CHANGES"
    # 語義分析作為 fallback
    text_lower = review_text.lower()
    approve_signals   = ["✅ 審核通過", "ok", "approved", "lgtm", "looks good", "沒有問題"]
    reject_signals    = ["需要修改", "request changes", "❌", "問題", "錯誤", "bug"]
    approve_count = sum(1 for s in approve_signals if s in text_lower)
    reject_count  = sum(1 for s in reject_signals if s in text_lower)
    return "APPROVE" if approve_count > reject_count else "REQUEST_CHANGES"


# ── 主流程 ────────────────────────────────────────────────────────────

def main():
    print(f"🤖 Claude 正在審核 PR #{PR_NUMBER}...")

    # 取得 PR 資訊
    pr = gh_get(f"pulls/{PR_NUMBER}")
    pr_title  = pr.get("title", "")
    pr_body   = pr.get("body", "") or ""
    pr_author = pr.get("user", {}).get("login", "unknown")
    pr_branch = pr.get("head", {}).get("ref", "")
    pr_sha    = pr.get("head", {}).get("sha", "")

    print(f"  PR: {pr_title}")
    print(f"  Author: {pr_author} | Branch: {pr_branch}")
    print(f"  Linked Issue: #{LINKED_ISSUE or '(none)'}")

    # 收集資料
    diff        = get_pr_diff(PR_NUMBER)
    claude_plan = find_claude_plan(LINKED_ISSUE)
    claude_md   = load_claude_md()

    print(f"  Diff: {len(diff)} chars | Plan: {len(claude_plan)} chars")

    # ── System prompt ─────────────────────────────────────────────────
    system = f"""你是 Block Reality 專案（Minecraft Forge 1.20.1 物理模擬引擎）的技術審核員。

你的職責：審核 Jules AI 提交的 PR，確認實作是否正確、完整、符合計畫。

以下是專案完整規範（CLAUDE.md）：
<project_context>
{claude_md}
</project_context>

審核原則：
1. 嚴格對照「原始計畫」驗收標準
2. 確認程式碼安全性（無 command injection、XSS、SQL injection 等）
3. 確認架構規範：api/ 不引用 fastdesign/；客戶端類別有 @OnlyIn；物理單位正確
4. PFSF 相關：26 連通一致性、sigmaMax 正規化、hField 寫入權
5. 若整體正確但有小缺失（格式/注釋），可以 APPROVE 並在 comment 標注建議
6. 若有功能性錯誤、架構違規或計畫項目未完成，必須 REQUEST_CHANGES

審核結尾必須包含以下其中一個標記：
- `[APPROVE]` — 若實作正確，可以合併
- `[REQUEST_CHANGES]` — 若需要修改才能合併

不要輸出「我會...」「讓我...」等說明，直接給出審核結果。"""

    # ── User prompt ───────────────────────────────────────────────────
    user = f"""請審核以下 PR：

**PR #{PR_NUMBER}**: {pr_title}
**作者**: {pr_author}
**Branch**: {pr_branch}
**連結 Issue**: #{LINKED_ISSUE or 'N/A'}

---

## 原始 Claude 計畫
（這是 Jules 被要求實作的計畫）

{claude_plan}

---

## PR 說明（Jules 的說明）

{pr_body[:3000] if pr_body else "(無說明)"}

---

## 程式碼變更（unified diff）

```diff
{diff}
```

---

請依以下格式審核：

## 🔍 審核結果

### ✅ 符合計畫的部分
（列出正確實作的項目）

### ❌ 問題與缺失
（具體說明每個問題，包含：問題所在行、預期行為、實際行為）

### ⚠️ 建議改進（非阻斷性）
（可選改進，不影響合併決策）

### 📋 驗收標準核查
（逐一核查計畫的驗收條件，標出 ✅ 已達成 / ❌ 未達成）

---

## 最終決策

（一句話總結決策理由）

[APPROVE] 或 [REQUEST_CHANGES]"""

    # ── 呼叫 Claude ────────────────────────────────────────────────────
    review_text = call_claude(system, user)
    decision    = parse_decision(review_text)

    print(f"✅ 審核完成，決策: {decision}")

    # ── 格式化 PR review comment ──────────────────────────────────────
    decision_emoji = "✅ 審核通過" if decision == "APPROVE" else "🔄 請修改後重新提交"

    review_body = f"""## 🤖 Claude 技術審核

> **PR**: #{PR_NUMBER} · {pr_title}
> **審核模型**: `{CLAUDE_MODEL}`
> **決策**: {decision_emoji}

---

{review_text}

---
<!-- WORKFLOW_MARKER: [CLAUDE_REVIEW] decision={decision} -->
*由 Claude 自動審核。若有疑義，可在此 comment 下方 reply，然後重新加上 `claude-reviewing` 標籤觸發重新審核。*"""

    # ── 提交 GitHub 正式 PR Review ────────────────────────────────────
    github_event = "APPROVE" if decision == "APPROVE" else "REQUEST_CHANGES"

    review_payload = {
        "commit_id": pr_sha,
        "body": review_body,
        "event": github_event,
    }

    try:
        result = gh_post(f"pulls/{PR_NUMBER}/reviews", review_payload)
        print(f"✅ PR Review 已提交: {result.get('html_url', '(no url)')}")
    except urllib.error.HTTPError as e:
        # 若無法提交正式 Review（例如是自己的 PR），改貼 comment
        error_body = e.read().decode() if hasattr(e, "read") else str(e)
        print(f"⚠️ 無法提交正式 Review ({e.code}): {error_body}", file=sys.stderr)
        print("改為貼 issue comment...", file=sys.stderr)
        gh_post(f"issues/{PR_NUMBER}/comments", {"body": review_body})
        print("✅ 已改為貼 comment")

    # 額外若 APPROVE 則補一句 ok comment（人類可見的簡短確認）
    if decision == "APPROVE":
        ok_body = "✅ ok — 審核通過，可以合併。"
        gh_post(f"issues/{PR_NUMBER}/comments", {"body": ok_body})

    # ── 寫入決策檔案（供 workflow step 讀取標籤用）────────────────────
    with open("/tmp/claude_review_decision", "w") as f:
        f.write(decision)

    print(f"決策已寫入 /tmp/claude_review_decision: {decision}")


if __name__ == "__main__":
    main()
