---
id: "tool:gradle-tasks-summary"
type: tool
tags: ["tool", "gradle", "build", "cheatsheet"]
---

# 🛠️ 常用 Gradle 任務速查

## 📝 說明
完整建置：./gradlew build。合併 JAR：./gradlew mergedJar（輸出 mpd.jar）。執行客戶端：./gradlew :fastdesign:runClient。執行伺服器：./gradlew :api:runServer。執行測試：./gradlew test 或 ./gradlew :api:test --tests '類別名'。部署到 PrismLauncher：./gradlew :api:copyToDevInstance。

> [!tip] 使用資訊
> 📎 Related Files:
>   - `Block Reality/build.gradle`
>   - `Block Reality/gradle.properties`

## 🔗 Related Notes
- [[build]]
- [[copy]]
- [[test]]
