---
id: "tool:github-actions"
type: tool
tags: ["tool", "ci", "github-actions", "automation"]
---

# 🛠️ GitHub Actions CI 自動化

## 📝 說明
.github/workflows/build.yml 在 push / pull_request 時觸發 Gradle build 與測試。失敗時自動重試 3 次並上傳測試報告 artifact。.github/scripts/ 下包含供 Claude/Jules 使用的自動化 workflow 腳本。

> [!tip] 使用資訊
> 📎 Related Files:
>   - `.github/workflows/build.yml`
>   - `.github/scripts/`

## 🔗 Related Notes
- [[Action]]
- [[build]]
- [[pull]]
- [[push]]
