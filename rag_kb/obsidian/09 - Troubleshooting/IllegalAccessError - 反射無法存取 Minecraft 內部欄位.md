---
id: "trouble:access-transformer-not-applied"
type: troubleshooting
tags: ["troubleshooting", "access-transformer", "reflection", "build"]
severity: "medium"
---

# 🚑 IllegalAccessError / 反射無法存取 Minecraft 內部欄位

> [!failure] 症狀
> 執行期 IllegalAccessError，嘗試反射存取 Minecraft 內部欄位失敗。原因：修改了 accesstransformer.cfg 但沒有重建 jar，導致 AT 沒有被打包進去。解決：執行 ./gradlew :api:jar 重建後再執行。

> [!danger] Severity: `medium`

> [!tip] 相關資訊
> 🚨 Severity: `medium`
> 📎 Related Files:
>   - `Block Reality/api/src/main/resources/META-INF/accesstransformer.cfg`
