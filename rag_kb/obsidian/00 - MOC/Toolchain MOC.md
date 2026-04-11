---
id: moc-toolchain-moc
type: "moc"
tags: [moc]
---

# Toolchain MOC

## 07 - Toolchain

| 標題 | 檔案 |
|------|------|
| [[Docker 多階段建置]] | `Docker 多階段建置.md` |
| [[GitHub Actions CI 自動化]] | `GitHub Actions CI 自動化.md` |
| [[fix_imports.py — 修復 network 封包中的 client-only import]] | `fix_imports.py — 修復 network 封包中的 client-only import.md` |
| [[fix_only_in_client.py — 自動補上 @OnlyIn(Dist.CLIENT)]] | `fix_only_in_client.py — 自動補上 @OnlyIn(Dist.CLIENT).md` |
| [[fix_registry.py — 自動將未註冊節點加入 NodeRegistry]] | `fix_registry.py — 自動將未註冊節點加入 NodeRegistry.md` |
| [[quick-install.bat — Windows 快速安裝腳本]] | `quick-install.bat — Windows 快速安裝腳本.md` |
| [[start-trainer 一鍵啟動 ML 訓練環境]] | `start-trainer 一鍵啟動 ML 訓練環境.md` |
| [[常用 Gradle 任務速查]] | `常用 Gradle 任務速查.md` |
| [[開發除錯與分析工具建議]] | `開發除錯與分析工具建議.md` |

## Build

| 標題 | 檔案 |
|------|------|
| [[Forge 版本與 Minecraft 版本對應]] | `Forge 版本與 Minecraft 版本對應.md` |
| [[GitHub Actions CI 流程]] | `GitHub Actions CI 流程.md` |
| [[gradle.properties 關鍵設定]] | `gradle.properties 關鍵設定.md` |
| [[mergedJar 任務 — 產出 mpd.jar]] | `mergedJar 任務 — 產出 mpd.jar.md` |
| [[settings.gradle — 子專案與 Forge bootstrap]] | `settings.gradle — 子專案與 Forge bootstrap.md` |
| [[執行 JUnit 5 測試]] | `執行 JUnit 5 測試.md` |
| [[執行 Minecraft 客戶端]] | `執行 Minecraft 客戶端.md` |
| [[部署到開發實例（PrismLauncher）]] | `部署到開發實例（PrismLauncher）.md` |

## 🔍 Dataview 動態查詢

> [!info] 需安裝 Dataview 外掛才能看到動態結果

```dataview
TABLE type, summary
FROM "07 - Toolchain" OR "07 - Toolchain/Build"
WHERE type != "moc"
SORT file.name ASC
LIMIT 50
```

---

返回 [[Home]]