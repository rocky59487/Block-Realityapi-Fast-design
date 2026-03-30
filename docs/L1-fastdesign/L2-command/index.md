# 指令系統

> 所屬：[L1-fastdesign](../index.md)

## 概述

Fast Design 指令系統提供以 `/fd` 為根的 Brigadier 指令樹，涵蓋選取、建築、藍圖、全息投影、剪貼簿、NURBS 匯出等操作。另有獨立的 `/br_blueprint`（藍圖管理）與 `/br_zone`（施工區域）指令。所有指令需要權限等級 2（OP）。系統包含兩套撤銷引擎：全量快照式 `UndoManager` 與差異式 `DeltaUndoManager`。

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-fd-commands](L3-fd-commands.md) | `/fd` 指令註冊表、所有子指令、`/br_blueprint` 藍圖指令、`/br_zone` 施工區域指令、`HologramCommand` 全息投影指令、`FdExtendedCommands` 擴充指令 |
| [L3-undo](L3-undo.md) | `UndoManager` 全量快照撤銷與 `DeltaUndoManager` 差異式撤銷/重做引擎 |
