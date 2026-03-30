根據使用者指定的目標建置 Block Reality 模組。

可用的建置目標：
- `full` — 完整建置（API + FastDesign）
- `api` — 僅建置 API 模組
- `fastdesign` — 僅建置 FastDesign 模組
- `merged` — 建置合併 JAR（mpd.jar，可放入 mods/）
- `sidecar` — 建置 TypeScript sidecar

步驟：
1. 確認使用者要建置的目標（預設為 `full`）
2. 切換至 `Block Reality/` 目錄
3. 根據目標執行對應 Gradle 指令：
   - full: `./gradlew build`
   - api: `./gradlew :api:jar`
   - fastdesign: `./gradlew :fastdesign:jar`
   - merged: `./gradlew mergedJar`
   - sidecar: `cd MctoNurbs-review && npm run build`
4. 檢查建置結果，報告是否成功
5. 如果失敗，分析錯誤訊息並提出修復建議

用法: /build [full|api|fastdesign|merged|sidecar]
$ARGUMENTS
