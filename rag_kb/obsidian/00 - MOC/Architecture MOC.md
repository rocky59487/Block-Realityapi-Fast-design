---
id: moc-architecture-moc
type: "moc"
tags: [moc]
---

# Architecture MOC

## 01 - Architecture

| 標題 | 檔案 |
|------|------|
| [[Access Transformer 注意事項]] | `Access Transformer 注意事項.md` |
| [[Sidecar RPC 與共享記憶體橋接]] | `Sidecar RPC 與共享記憶體橋接.md` |
| [[套件前綴與命名規範]] | `套件前綴與命名規範.md` |
| [[客戶端/伺服器端分離（極重要）]] | `客戶端-伺服器端分離（極重要）.md` |
| [[專案目錄佈局總覽]] | `專案目錄佈局總覽.md` |
| [[常用建置與測試指令]] | `常用建置與測試指令.md` |
| [[模組邊界與依賴方向]] | `模組邊界與依賴方向.md` |
| [[網路封包設計模式]] | `網路封包設計模式.md` |

## Rules

| 標題 | 檔案 |
|------|------|
| [[26 連通一致性（PFSF Shader）]] | `26 連通一致性（PFSF Shader）.md` |
| [[FNO phi 正規化規則]] | `FNO phi 正規化規則.md` |
| [[Forge 事件優先級]] | `Forge 事件優先級.md` |
| [[Gradle Daemon 設定]] | `Gradle Daemon 設定.md` |
| [[RC 融合比例固定]] | `RC 融合比例固定.md` |
| [[hField 寫入權規則]] | `hField 寫入權規則.md` |
| [[sigmaMax 正規化約定（PFSF 核心）]] | `sigmaMax 正規化約定（PFSF 核心）.md` |
| [[常見陷阱：client 類別缺少 @OnlyIn(Dist.CLIENT)]] | `常見陷阱：client 類別缺少 @OnlyIn(Dist.CLIENT).md` |
| [[常見陷阱：封包中直接 import client 類別]] | `常見陷阱：封包中直接 import client 類別.md` |
| [[常見陷阱：新增節點後忘記註冊]] | `常見陷阱：新增節點後忘記註冊.md` |
| [[流體系統預設關閉與延遲]] | `流體系統預設關閉與延遲.md` |
| [[物理單位強制約定]] | `物理單位強制約定.md` |
| [[節點 Port 類型匹配]] | `節點 Port 類型匹配.md` |

## 🔍 Dataview 動態查詢

> [!info] 需安裝 Dataview 外掛才能看到動態結果

```dataview
TABLE type, summary
FROM "01 - Architecture" OR "01 - Architecture/Rules"
WHERE type != "moc"
SORT file.name ASC
LIMIT 50
```

---

返回 [[Home]]