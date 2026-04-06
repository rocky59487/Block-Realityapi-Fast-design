# IBinder 綁定系統

> 所屬：L1-fastdesign > L2-node-editor

## 概述

`IBinder<T>` 介面定義了節點圖端口值與 runtime 物件之間的雙向資料綁定。`LivePreviewBridge` 在每幀渲染前協調所有 Binder，實現節點修改即時預覽。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `IBinder<T>` | `com.blockreality.fastdesign.client.node.binding.IBinder` | 綁定介面（bind/apply/pull/isDirty） |
| `LivePreviewBridge` | `com.blockreality.fastdesign.client.node.binding.LivePreviewBridge` | 即時預覽橋接器（單例） |
| `RenderConfigBinder` | `com.blockreality.fastdesign.client.node.binding.RenderConfigBinder` | 渲染配置綁定（Category A → MutableRenderConfig） |
| `MaterialBinder` | `com.blockreality.fastdesign.client.node.binding.MaterialBinder` | 材料綁定（Category B → MaterialContext） |
| `PhysicsBinder` | `com.blockreality.fastdesign.client.node.binding.PhysicsBinder` | 物理綁定（Category C → BRConfig） |
| `ShaderBinder` | `com.blockreality.fastdesign.client.node.binding.ShaderBinder` | Shader Uniform 綁定（渲染節點 → UniformContext） |
| `FastDesignConfigBinder` | `com.blockreality.fastdesign.client.node.binding.FastDesignConfigBinder` | FastDesign 配置綁定（Category D → FastDesignConfig） |
| `MutableRenderConfig` | `com.blockreality.fastdesign.client.node.binding.MutableRenderConfig` | BRRenderConfig 可變鏡像（volatile 欄位） |
| `TomlExportGenerator` | `com.blockreality.fastdesign.client.node.binding.TomlExportGenerator` | TOML 配置匯出生成器 |

## IBinder 介面方法

### `bind(NodeGraph)`
- **說明**: 掃描節點圖，建立端口→欄位映射

### `apply(T target)`
- **說明**: 將節點端口值推送到 runtime 目標物件

### `pull(T target)`
- **說明**: 從 runtime 目標物件拉回值到節點端口（初始化同步）

### `isDirty()` / `clearDirty()`
- **說明**: 查詢/清除是否有綁定值變更

### `bindingCount()`
- **說明**: 回傳已建立的綁定數量

## LivePreviewBridge

### `bindGraph(NodeGraph)`
- **說明**: 綁定節點圖，建立所有 Binder（render, material, physics, shader, fdConfig），啟用 `MutableRenderConfig` 覆蓋

### `unbind()`
- **說明**: 解除綁定，重置渲染配置為預設值

### `onRenderLevelStage(RenderLevelStageEvent)`
- **觸發時機**: `AFTER_SKY` 階段
- **說明**: 限流 16ms（60fps），依序呼叫各 Binder 的 `apply()`

### 綁定對應

| Binder | 目標 Runtime 物件 | 節點分類 |
|--------|-------------------|----------|
| `RenderConfigBinder` | `MutableRenderConfig` | render |
| `MaterialBinder` | `MaterialContext` | material, blending |
| `PhysicsBinder` | `BRConfig` | physics |
| `ShaderBinder` | `UniformContext` | render (uniform 候選) |
| `FastDesignConfigBinder` | `FastDesignConfig` (static) | tool |

## MutableRenderConfig

`BRRenderConfig` 使用 `static final`（JIT 內聯），無法在 runtime 修改。此類以 `volatile` 欄位鏡像所有配置值，供節點系統覆蓋。保持 `fastdesign → api` 單向依賴。

## 關聯接口
- 依賴 → API 層 `BRConfig`、`BRRenderConfig`
- 依賴 → [所有節點分類](index.md)
- 被依賴 ← [NodeCanvasScreen](L3-canvas.md)（畫布開啟時啟動綁定）
