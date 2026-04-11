---
id: "arch:access-transformer"
type: architecture
tags: ["forge", "access-transformer", "build"]
---

# 📄 Access Transformer 注意事項

## 📖 內容
修改 accesstransformer.cfg 後須執行 ./gradlew :api:jar 重建，否則反射存取會在執行期失敗。

> [!warning] 注意
> 忘記重建 jar 會導致 IllegalAccessException

> [!tip] 相關資訊
> ⚠️ **WARNING**: 忘記重建 jar 會導致 IllegalAccessException
