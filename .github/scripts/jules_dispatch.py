#!/usr/bin/env python3
"""
02 · Jules 任務派遣腳本

從 Issue comments 找到 Claude 的 [CLAUDE_PLAN] comment，
格式化後發布 @jules 任務 comment 觸發 Jules 開始實作。
"""

import json
import os
import sys
import urllib.request
import urllib.error

# ── 環境變數 ──────────────────────────────────────────────────────────
GITHUB_TOKEN = os.environ["GITHUB_TOKEN"]
REPO         = os.environ["GITHUB_REPOSITORY"]
ISSUE_NUMBER = os.environ["ISSUE_NUMBER"]
ISSUE_TITLE  = os.environ.get("ISSUE_TITLE", f"Issue #{ISSUE_NUMBER}")

# ── GitHub API 工具函數 ───────────────────────────────────────────────

def gh_get(path: str) -> object:
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


# ── 找 Claude 計畫 ────────────────────────────────────────────────────

def find_claude_plan(issue_number: str) -> tuple[str | None, str | None]:
    """
    搜尋 Issue comments，找到含 [CLAUDE_PLAN] 的 comment。
    回傳 (comment_url, plan_text)，找不到回傳 (None, None)。
    """
    comments = gh_get(f"issues/{issue_number}/comments?per_page=100&sort=created&direction=desc")
    for comment in comments:
        body = comment.get("body", "")
        # 支援兩種標記格式
        if "[CLAUDE_PLAN]" in body or "WORKFLOW_MARKER: [CLAUDE_PLAN]" in body:
            return comment.get("html_url"), body
    return None, None


def extract_plan_content(raw_comment: str) -> str:
    """
    從 comment 原文中提取實際計畫內容（去掉 header/footer 樣板）。
    """
    lines = raw_comment.split("\n")
    start = 0
    end = len(lines)

    for i, line in enumerate(lines):
        if line.startswith("## 📋 任務分析") or line.startswith("## Task Analysis"):
            start = i
            break

    for i in range(len(lines) - 1, -1, -1):
        line = lines[i]
        if "WORKFLOW_MARKER" in line or "由 Claude" in line:
            end = i
            break

    return "\n".join(lines[start:end]).strip()


# ── 主流程 ────────────────────────────────────────────────────────────

def main():
    print(f"🤖 正在為 Issue #{ISSUE_NUMBER} 派遣 Jules...")

    # 取得 Issue 完整資訊（確認分支命名用）
    issue = gh_get(f"issues/{ISSUE_NUMBER}")
    title = issue.get("title", ISSUE_TITLE)

    # 找到 Claude 的計畫
    plan_url, plan_raw = find_claude_plan(ISSUE_NUMBER)

    if plan_raw is None:
        print("❌ 找不到含 [CLAUDE_PLAN] 的 comment！", file=sys.stderr)
        print("請確認 01-claude-plan.yml 已正確執行。", file=sys.stderr)
        # 仍然發送一個基本任務給 Jules
        plan_content = f"請根據 Issue #{ISSUE_NUMBER} 的描述進行實作：{title}"
    else:
        plan_content = extract_plan_content(plan_raw)
        print(f"✅ 找到 Claude 計畫: {plan_url}")

    # ── 格式化 Jules 任務 comment ──────────────────────────────────────
    # Jules 需要看到清晰的任務描述，以及預期的 PR branch 命名慣例
    jules_comment = f"""@jules

## 任務指派

請根據以下 Claude 的技術計畫，在此 repo 中實作所需的程式碼變更。

**Issue**: #{ISSUE_NUMBER} — {title}
**計畫來源**: {plan_url or f'Issue #{ISSUE_NUMBER} 的 comments'}

---

{plan_content}

---

## 實作要求

1. **Branch 命名**: `jules/issue-{ISSUE_NUMBER}-{title[:30].lower().replace(' ', '-').replace('/', '-')}`
2. **Commit 規範**: 每個邏輯單元一個 commit，訊息以繁體中文或英文清楚描述變更
3. **PR 說明**: PR body 必須包含 `Closes #{ISSUE_NUMBER}` 以連結此 Issue
4. **建置驗證**: 確認 `./gradlew build --no-daemon` 在 `Block Reality/` 目錄下成功
5. **不要修改**: 以下檔案除非計畫明確要求，否則不要動：
   - `.github/workflows/` 任何 workflow 檔案
   - `CLAUDE.md`
   - `gradle-wrapper.properties`（版本已固定）

## 專案特殊規則

- `api/` 模組不得引用 `fastdesign/` 的任何類別（單向依賴）
- 物理單位：MPa（強度）、GPa（楊氏模量）、kg/m³（密度）
- 客戶端程式碼需加 `@OnlyIn(Dist.CLIENT)`
- 若修改 PFSF shader：所有 smoother/PCG stencil 必須保持 26 連通一致性

完成後請開 PR，我們的 CI 會自動通知 Claude 進行審核。

<!-- WORKFLOW_MARKER: [JULES_TASK] -->"""

    result = gh_post(f"issues/{ISSUE_NUMBER}/comments", {"body": jules_comment})
    print(f"✅ Jules 任務已派遣: {result.get('html_url', '(no url)')}")
    print(f"   Jules 收到通知後將開始實作 Issue #{ISSUE_NUMBER}")


if __name__ == "__main__":
    main()
