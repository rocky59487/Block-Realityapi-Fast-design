---
id: "trouble:gradle-daemon-memory"
type: troubleshooting
tags: ["troubleshooting", "gradle", "oom", "build"]
severity: "low"
---

# 🚑 Gradle build 記憶體不足 (OOM)

> [!failure] 症狀
> 編譯過程中 Java heap space OOM。原因：本專案設定 -Xmx3G，對於某些完整重建可能不足。解決：暫時修改 gradle.properties 中的 org.gradle.jvmargs=-Xmx3G 為 -Xmx4G 或 -Xmx5G；或分階段編譯（先 :api:build 再 :fastdesign:build）。

> [!danger] Severity: `low`

> [!tip] 相關資訊
> 🚨 Severity: `low`
> 📎 Related Files:
>   - `Block Reality/gradle.properties`

## 🔗 Related Notes
- [[build]]
