---
id: "trouble:gradle-lockfile"
type: troubleshooting
tags: ["troubleshooting", "gradle", "build", "lockfile"]
severity: "medium"
---

# 🚑 Gradle dependency lockfile 錯誤

> [!failure] 症狀
> ./gradlew build 報錯，提到 dependency lock state is out of date。原因：gradle.properties 中 disableLocking=true 被關閉，或 lockfile 與實際依賴不符。解決：執行 ./gradlew dependencies --write-locks 重新生成 lockfile；或直接確保 gradle.properties 中 disableLocking=true。

> [!danger] Severity: `medium`

> [!tip] 相關資訊
> 🚨 Severity: `medium`
> 📎 Related Files:
>   - `Block Reality/gradle.properties`

## 🔗 Related Notes
- [[build]]
- [[write]]
