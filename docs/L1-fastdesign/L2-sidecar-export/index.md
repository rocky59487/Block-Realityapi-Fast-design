# NURBS/STEP 匯出橋接

> 所屬：[L1-fastdesign](../index.md)

## 概述

Fast Design 的 Sidecar 匯出層負責將遊戲內的選取區域方塊資料收集、封裝為 JSON-RPC 請求，透過 API 層的 `SidecarBridge` 傳送至 MctoNurbs TypeScript sidecar 進行 NURBS/STEP 幾何轉換。支援雙路徑匯出：完全體素（Greedy Mesh）與曲面化（SDF + Dual Contouring）。

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-nurbs-bridge](L3-nurbs-bridge.md) | `NurbsExporter` 匯出器：選項配置、方塊資料收集、RPC 呼叫 |
