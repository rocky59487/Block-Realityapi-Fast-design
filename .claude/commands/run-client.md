啟動 Minecraft 開發客戶端。

可用模式：
- `fastdesign`（預設）— 載入 Fast Design + API
- `api` — 僅載入 API 模組

步驟：
1. 確認啟動模式
2. 切換至 `Block Reality/` 目錄
3. 執行對應 Gradle 指令：
   - fastdesign: `./gradlew :fastdesign:runClient`
   - api: `./gradlew :api:runClient`
4. 監控啟動日誌，報告是否成功載入
5. 如啟動失敗，分析錯誤並提出修復建議

常見問題：
- 如果出現 AT（Access Transformer）錯誤，先執行 `./gradlew :api:jar` 重建
- 如果 sidecar 連線失敗，確認 Node.js 18+ 已安裝
- 記憶體不足時調整 `gradle.properties` 中的 `-Xmx` 值

用法: /run-client [fastdesign|api]
$ARGUMENTS
