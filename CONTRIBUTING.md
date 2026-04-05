# Block Reality — 貢獻指南

感謝你有興趣貢獻 Block Reality！以下是讓你的 PR 順利合併的指引。

---

## 目錄

1. [開始貢獻](#開始貢獻)
2. [開發環境設定](#開發環境設定)
3. [程式碼規範](#程式碼規範)
4. [提交 Pull Request](#提交-pull-request)
5. [回報問題](#回報問題)
6. [授權說明](#授權說明)

---

## 開始貢獻

1. **Fork** 本倉庫，克隆至本地
2. 在 `feature/你的功能名稱` 分支上開發（不要直接推到 `main`）
3. 開發完成後提交 Pull Request

---

## 開發環境設定

### 先決條件

| 工具 | 版本 |
|------|------|
| JDK | 17（由 Gradle Toolchain 自動下載） |
| Gradle | 8.8（使用 `gradlew` wrapper） |
| Node.js | 18+（TypeScript sidecar 用） |

### 快速開始

```bash
# 克隆倉庫
git clone https://github.com/your-org/block-reality.git
cd block-reality/Block\ Reality

# 建置兩個子模組
./gradlew build

# 執行 Fast Design 客戶端（含 API）
./gradlew :fastdesign:runClient

# 建置 TypeScript sidecar
cd ../MctoNurbs-review && npm install && npm run build
```

詳細指令見 [CLAUDE.md](CLAUDE.md)。

---

## 程式碼規範

### Java

- 使用 **Java 17** 功能（records、sealed、text blocks 等皆可）
- 套件前綴：`com.blockreality.api.*`（基礎層）/ `com.blockreality.fastdesign.*`（擴充層）
- **嚴禁** `fastdesign` 引用 `api` 以外的類別（依賴方向單向）
- 公開 API 方法須標註 `@Nonnull` / `@Nullable`（JSR-305）
- 物理量單位：強度 MPa，楊氏模量 GPa，密度 kg/m³
- 測試使用 **JUnit 5**，容忍度 ≤ 5%，效能閾值 ≤ 1 秒

### TypeScript（Sidecar）

- 嚴格模式（`strict: true`）
- 使用 `vitest` 撰寫測試
- JSON-RPC 方法新增須同步更新 `CLAUDE.md` IPC 表格

### 提交訊息格式

```
<類型>(<範圍>): <簡短說明>

[可選的詳細說明]

[可選的破壞性變更說明]
```

類型：`feat` / `fix` / `refactor` / `test` / `docs` / `perf` / `chore`

範例：
```
fix(physics): 修正樑彎矩公式 L/4 → L/8 (P1)
feat(ui): 新增節點搜尋面板分類樹狀選單 (UI-2)
```

---

## 提交 Pull Request

1. 確認 `./gradlew test` 全部通過
2. 確認 `npm test`（sidecar）全部通過
3. 更新相關文檔（`docs/` 下的分層文檔，見 `CLAUDE.md` 文檔維護規範）
4. PR 標題簡短（< 70 字），使用上方格式
5. PR 描述說明：**為什麼**這樣改（而非只說做了什麼）

### PR 檢查清單

- [ ] 通過所有 Java 測試（`./gradlew test`）
- [ ] 通過所有 TypeScript 測試（`npm test`）
- [ ] 物理數值使用正確單位（MPa / GPa / kg/m³）
- [ ] 客戶端類別加上 `@OnlyIn(Dist.CLIENT)`
- [ ] 新增公開 API 已標 `@Nonnull` / `@Nullable`
- [ ] 相關文檔已同步更新

---

## 回報問題

請使用 GitHub Issues，並包含：

- **Minecraft 版本** 與 **Forge 版本**
- **mod 版本**（mpd.jar 版本號）
- **復現步驟**（越詳細越好）
- **錯誤日誌**（`logs/latest.log` 相關部分）
- **截圖**（如有視覺異常）

---

## 授權說明

本專案採用 **GPL-3.0** 授權。提交 PR 即代表你同意你的貢獻以相同授權釋出。

詳見 [LICENSE](LICENSE)。
