引導新增自訂材料到 Block Reality 材料系統。

需要使用者提供的資訊：
- 材料名稱（英文 ID + 顯示名稱）
- 工程屬性：抗壓強度 (MPa)、抗拉強度 (MPa)、抗剪強度 (MPa)、楊氏模量 (GPa)、密度 (kg/m³)
- 是否為結構材料

步驟：
1. 向使用者確認材料屬性（如未提供）
2. 在 `DefaultMaterial.java` 中新增枚舉值（如為預設材料），或使用 `CustomMaterial.Builder`
3. 在 `BlockTypeRegistry` 中確認映射
4. 如需要節點編輯器支援，在 `fastdesign/client/node/impl/material/base/` 下建立對應 Node 類別
5. 撰寫 JUnit 5 測試確認材料屬性正確
6. 更新語言檔案（`assets/blockreality/lang/`）

參考路徑：
- `Block Reality/api/src/main/java/com/blockreality/api/material/DefaultMaterial.java`
- `Block Reality/api/src/main/java/com/blockreality/api/material/CustomMaterial.java`
- `Block Reality/api/src/main/java/com/blockreality/api/material/BlockTypeRegistry.java`
- `Block Reality/api/src/test/java/com/blockreality/api/material/`

$ARGUMENTS
