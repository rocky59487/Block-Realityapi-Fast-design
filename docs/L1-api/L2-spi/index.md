# SPI 擴展點系統

> 所屬：[L1-api](../index.md)

## 概述

Block Reality 的 SPI（Service Provider Interface）擴展點系統透過 `ModuleRegistry` 提供統一的
模組註冊與查詢機制。外部模組可實作這些接口來擴展物理行為、材料系統、指令與渲染層，
所有註冊操作皆為執行緒安全。

## 擴展點總覽

| 接口 | 用途 | 預設實作 |
|------|------|---------|
| `IFusionDetector` | RC 融合偵測 | `RCFusionDetector` |
| `ICableManager` | 纜索張力物理 | `DefaultCableManager` |
| `ICuringManager` | 混凝土養護追蹤 | `DefaultCuringManager` |
| `ILoadPathManager` | 荷載路徑傳遞 | `LoadPathEngine` |
| `IMaterialRegistry` | 材料中央註冊表 | 內建 ConcurrentHashMap |
| `ICommandProvider` | Brigadier 指令註冊 | -- |
| `IRenderLayerProvider` | 客戶端渲染層 | -- |
| `IBlockTypeExtension` | 自訂方塊類型行為 | -- |

## 子文檔

| 文檔 | 說明 |
|------|------|
| [L3-module-registry](L3-module-registry.md) | ModuleRegistry 中心與 ICommandProvider |
| [L3-cable-curing](L3-cable-curing.md) | ICableManager、ICuringManager 與預設實作 |
| [L3-material-spi](L3-material-spi.md) | IMaterialRegistry、IFusionDetector、IBlockTypeExtension、ILoadPathManager |
