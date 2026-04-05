# IFC P21 寫入器（IfcWriter）

> 所屬：L1-sidecar / L2-ifc / L3-ifc-writer

## 概述

純 TypeScript IFC 4.x STEP P21 格式寫入器，無外部依賴。提供實體 ID 管理、屬性型別輔助函式，以及完整 P21 檔案文字生成。

## 關鍵類別

| 類別 / 函式 | 套件路徑 | 說明 |
|------------|---------|------|
| `IfcWriter` | `src/ifc/ifc-writer.ts` | 核心 P21 寫入器，管理實體 ID 與輸出行 |
| `ifcGuid()` | `src/ifc/ifc-writer.ts` | 生成符合 IFC base64 規範的 22 字元 GlobalId |
| `str(s)` | `src/ifc/ifc-writer.ts` | P21 字串屬性（加引號、逸出單引號） |
| `real(n)` | `src/ifc/ifc-writer.ts` | P21 浮點數（至少含一個小數位） |
| `int(n)` | `src/ifc/ifc-writer.ts` | P21 整數屬性 |
| `enumVal(e)` | `src/ifc/ifc-writer.ts` | P21 枚舉（`.ENUMVALUE.`） |
| `ref(id)` | `src/ifc/ifc-writer.ts` | P21 實體參照（`#id`） |
| `refList(ids)` | `src/ifc/ifc-writer.ts` | P21 實體清單（`(#1,#2,...)`） |
| `typed(type, val)` | `src/ifc/ifc-writer.ts` | 型別化值（`IFCLENGTHMEASURE(1.0)`） |

## 核心方法（IfcWriter）

| 方法 | 參數 | 回傳 | 說明 |
|------|------|------|------|
| `add(type, ...attrs)` | type: string, attrs: string[] | number (ID) | 新增 IFC 實體，自動分配 ID |
| `addAt(id, type, ...attrs)` | id: number, type: string | number | 指定 ID 新增實體 |
| `allocId()` | — | number | 預留 ID（不新增實體） |
| `build(filename, author?, org?)` | strings | string | 生成完整 P21 文字 |

## P21 檔案格式

```
ISO-10303-21;
HEADER;
FILE_DESCRIPTION(('ViewDefinition [CoordinationView]'),'2;1');
FILE_NAME('export.ifc','2024-01-01T00:00:00',('Author'),('Org'),'App','IFC4','');
FILE_SCHEMA(('IFC4'));
ENDSEC;
DATA;
#1=IFCPERSON($,'BlockReality','System',$,$,$,$,$);
#2=...
ENDSEC;
END-ISO-10303-21;
```

## IFC GlobalId 格式

IFC 使用 22 字元 IFC base64 編碼（buildingSMART 規範）：
字母表：`0-9A-Za-z_$`（64 個字元，每字元 = 6 bits，22 × 6 = 132 bits）

## 關聯

- [L2-ifc/index.md](index.md) — 父模組
- [L3-ifc-structural-export.md](L3-ifc-structural-export.md) — 使用此寫入器的結構匯出邏輯
