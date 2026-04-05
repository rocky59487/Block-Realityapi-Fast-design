# 輸出節點

> 所屬：L1-fastdesign > L2-node-editor

## 概述

輸出節點（Category E）負責將節點圖配置匯出為檔案，以及提供效能監測儀表板，共 8 個節點。

## 匯出 (export/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `ConfigExportNode` | output.export.ConfigExport | TOML 配置匯出 |
| `NodeGraphExportNode` | output.export.NodeGraphExport | 節點圖 JSON 匯出 |
| `PresetExportNode` | output.export.PresetExport | 預設匯出 |

### ConfigExportNode 詳細
- **輸入**: allConfigs(STRUCT)
- **輸出**: tomlContent(ENUM), filePath(ENUM)
- **說明**: 將節點圖配置匯出為 ForgeConfigSpec 格式的 TOML 檔案，由 `TomlExportGenerator` 實際生成

## 效能監測 (monitor/)

| 類別 | typeId | 說明 |
|------|--------|------|
| `GPUProfilerNode` | output.monitor.GPUProfiler | GPU 效能分析 |
| `MemoryProfilerNode` | output.monitor.MemoryProfiler | 記憶體分析 |
| `NetworkProfilerNode` | output.monitor.NetworkProfiler | 網路效能分析 |
| `PassProfilerNode` | output.monitor.PassProfiler | 渲染 Pass 計時 |
| `PhysicsProfilerNode` | output.monitor.PhysicsProfiler | 物理引擎計時 |

## 關聯接口
- 依賴 → [TomlExportGenerator](L3-binding.md)（TOML 生成邏輯）
- 依賴 → [NodeGraphIO](index.md)（序列化）
