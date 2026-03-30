執行 Block Reality 的測試套件。

可用的測試範圍：
- `all` — 所有 Java 測試（預設）
- `api` — 僅 API 模組測試
- `fastdesign` — 僅 FastDesign 模組測試
- `sidecar` — TypeScript sidecar 測試
- `<ClassName>` — 指定單一測試類別名稱

步驟：
1. 確認測試範圍
2. 切換至對應目錄並執行：
   - all: `cd "Block Reality" && ./gradlew test`
   - api: `cd "Block Reality" && ./gradlew :api:test`
   - fastdesign: `cd "Block Reality" && ./gradlew :fastdesign:test`
   - sidecar: `cd MctoNurbs-review && npm test`
   - 指定類別: `cd "Block Reality" && ./gradlew :api:test --tests "com.blockreality.api.*.<ClassName>"`
3. 分析測試結果
4. 如有失敗的測試，讀取失敗測試的原始碼和相關實作，分析失敗原因

用法: /test [all|api|fastdesign|sidecar|<ClassName>]
$ARGUMENTS
