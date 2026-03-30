開發與除錯 MctoNurbs TypeScript sidecar。

可用操作：
- `build` — 建置 sidecar
- `test` — 執行 sidecar 測試
- `start` — 啟動 RPC 伺服器
- `debug` — 除錯模式分析

步驟：
1. 切換至 `MctoNurbs-review/` 目錄
2. 根據操作執行：
   - build: `npm run build`
   - test: `npm test`
   - start: `npm start`
   - debug: 檢查 `src/rpc-server.ts` 和 `src/index.ts` 的錯誤處理
3. 報告結果

架構說明：
- JSON-RPC 2.0 協議，透過 stdin/stdout 通訊
- 已註冊方法：`ping`、`dualContouring`
- 雙路徑管線：smoothing=0 用 GreedyMesh，smoothing>0 用 SDF+DualContouring
- 限制：最大 10,000 方塊、解析度 1-4、逾時 30 秒

關鍵檔案：
- `src/index.ts` — 入口點（雙模式：RPC / CLI）
- `src/rpc-server.ts` — JSON-RPC 伺服器
- `src/pipeline.ts` — 主轉換管線
- `src/greedy-mesh.ts` — 快速網格化
- `src/sdf/` — SDF 網格系統
- `src/dc/` — 雙輪廓面重建
- `src/cad/` — OpenCASCADE 整合

用法: /sidecar [build|test|start|debug]
$ARGUMENTS
