引導新增節點到 Block Reality 節點編輯器系統。

需要使用者提供的資訊：
- 節點名稱與分類（材料/物理/渲染/工具/輸出）
- 輸入/輸出 Port 定義
- 節點行為描述

步驟：
1. 確認節點分類和 Port 定義
2. 在 `fastdesign/client/node/impl/<category>/` 下建立新節點類別：
   - 繼承 `BRNode`
   - 定義 `InputPort` 和 `OutputPort`
   - 實作 `evaluate()` 方法
3. 在 `NodeRegistry` 中註冊節點
4. 設定 `NodeGroup` 和 `NodeColor` 分類
5. 如需 Binder 對接，實作或擴展 `IBinder<T>` 介面
6. 撰寫測試（如適用）
7. 更新語言檔案

參考路徑：
- `Block Reality/api/src/main/java/com/blockreality/api/node/BRNode.java` — 基底類別
- `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/NodeRegistry.java`
- `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/impl/` — 現有節點範例
- `Block Reality/fastdesign/src/main/java/com/blockreality/fastdesign/client/node/binding/IBinder.java`

節點分類對應目錄：
- 材料: `impl/material/`（base/blending/operation/shape/visualization）
- 物理: `impl/physics/`（collapse/load/result/solver）
- 渲染: `impl/render/`（lighting/lod/pipeline/postfx/water/weather）
- 工具: `impl/tool/`（input/placement/selection/ui）
- 輸出: `impl/output/`（export/monitor）

$ARGUMENTS
