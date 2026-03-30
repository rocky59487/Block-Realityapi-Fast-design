# 節點圖核心系統

> 所屬：[L1-api](../index.md)

## 概述

Block Reality 的節點圖系統提供視覺化程式設計基礎架構，以 DAG（有向無環圖）管理節點間的資料流。
核心層定義節點、埠、連線與排程器的基礎抽象，供 `fastdesign/client/node/` 中的 90+ 節點實作繼承使用。
支援拓撲排序評估、自動型別轉換與 dirty 傳播優化。

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-node-graph](L3-node-graph.md) | BRNode、NodeGraph、Wire、NodePort、PortType |
| [L3-evaluate](L3-evaluate.md) | EvaluateScheduler 排程器 |
