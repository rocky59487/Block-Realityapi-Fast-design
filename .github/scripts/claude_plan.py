#!/usr/bin/env python3
"""
01 · Claude 計畫生成腳本

讀取 Issue 內容 + CLAUDE.md，呼叫 Claude Opus API，
將生成的技術計畫以 [CLAUDE_PLAN] 標記發布到 Issue comment。
"""

import json
import os
import sys
import urllib.request
import urllib.error
import time

# ── 環境變數 ──────────────────────────────────────────────────────────
ANTHROPIC_API_KEY = os.environ["ANTHROPIC_API_KEY"]
GITHUB_TOKEN      = os.environ["GITHUB_TOKEN"]
REPO              = os.environ["GITHUB_REPOSITORY"]
ISSUE_NUMBER      = os.environ["ISSUE_NUMBER"]

CLAUDE_MODEL = "claude-opus-4-6"
MAX_TOKENS   = 8192
RETRY_COUNT  = 3

# ── GitHub API 工具函數 ───────────────────────────────────────────────

def gh_get(path: str) -> dict:
    url = f"https://api.github.com/repos/{REPO}/{path}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "Accept": "application/vnd.github+json",
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
                time.sleep(attempt * 10)
            else:
                raise


# ── 讀取 CLAUDE.md 作為上下文 ──────────────────────────────────────────

def load_claude_md() -> str:
    for path in ["CLAUDE.md", ".claude/CLAUDE.md", "docs/CLAUDE.md"]:
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                return f.read()
    return ""


# ── 主流程 ────────────────────────────────────────────────────────────

def main():
    print(f"🤖 Claude 正在分析 Issue #{ISSUE_NUMBER}...")

    # 取得 Issue 完整資訊
    issue = gh_get(f"issues/{ISSUE_NUMBER}")
    title  = issue.get("title", "")
    body   = issue.get("body", "") or "(無描述)"
    author = issue.get("user", {}).get("login", "unknown")
    labels = [lb["name"] for lb in issue.get("labels", [])]

    print(f"  Title: {title}")
    print(f"  Author: {author}")
    print(f"  Labels: {', '.join(labels)}")

    claude_md = load_claude_md()

    # ── System prompt ─────────────────────────────────────────────────
    system = f"""你是 Block Reality 專案（Minecraft Forge 1.20.1 物理模擬引擎）的技術主架構師。

你的職責：分析 GitHub Issue，制定詳細的技術實作計畫，供 Jules AI 代理執行。
計畫必須足夠精確，Jules 無需額外提問即可正確實作。

以下是專案的完整技術規範（CLAUDE.md）：
<project_context>
{claude_md}
</project_context>

計畫格式要求：
1. 使用繁體中文（技術術語可用英文）
2. 步驟必須具體到「修改哪個檔案的哪個方法，加入什麼邏輯」
3. 包含驗收標準的 checkbox 清單
4. 結尾必須加上精確標記 [CLAUDE_PLAN_READY]"""

    # ── User prompt ───────────────────────────────────────────────────
    user = f"""請分析並為以下 Issue 制定技術實作計畫：

**Issue #{ISSUE_NUMBER}**: {title}
**作者**: {author}
**現有標籤**: {', '.join(labels)}

**Issue 內容**:
{body}

---

請依以下結構制定計畫：

## 📋 任務分析
（核心需求 1-3 句、技術影響範圍、受影響的模組）

## 🏗️ 技術方案
（選擇的實作策略及理由；若有多方案，說明為何選擇此方案）

## 📁 涉及的檔案清單
每個檔案一行，格式：`路徑` — 修改內容說明

## 🔧 Jules 實作步驟
（給 Jules 的逐步指令，每步包含：要做什麼、程式碼邏輯、在哪個檔案）

確保每一步都有明確的「檔案路徑 + 方法名稱 + 修改邏輯」，Jules 不需要猜測。

## ⚠️ 注意事項
（參照 CLAUDE.md 的常見陷阱，列出此任務特別需要注意的項目）

## ✅ 驗收標準
Jules 完成後，以下必須成立：
- [ ] 建置成功（./gradlew build 通過）
- [ ] [根據任務新增的驗收條件]

[CLAUDE_PLAN_READY]"""

    # ── 呼叫 Claude ────────────────────────────────────────────────────
    plan_text = call_claude(system, user)
    print("✅ Claude 計畫生成完成")

    # ── 發布 comment ──────────────────────────────────────────────────
    comment_body = f"""## 🤖 Claude 技術計畫

> **Issue**: #{ISSUE_NUMBER} · {title}
> **生成時間**: GitHub Actions 自動生成
> **狀態**: ⏳ 等待 Jules 實作

---

{plan_text}

---
<!-- WORKFLOW_MARKER: [CLAUDE_PLAN] -->
*由 Claude (`{CLAUDE_MODEL}`) 自動生成。Jules 將依此計畫進行實作。*
*若計畫有誤，請直接在此 comment 下方 reply 修正意見，然後重新加上 `needs-plan` 標籤重跑。*"""

    result = gh_post(f"issues/{ISSUE_NUMBER}/comments", {"body": comment_body})
    print(f"✅ 計畫已發布: {result.get('html_url', '(no url)')}")


if __name__ == "__main__":
    main()
