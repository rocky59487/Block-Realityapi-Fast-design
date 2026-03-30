# ModuleRegistry 與 ICommandProvider

> 所屬：L1-api > L2-spi

## 概述

`ModuleRegistry` 是 Block Reality 擴展性系統的中央樞紐，以執行緒安全的單例模式管理所有 SPI 提供者、材料註冊表與服務實例。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `ModuleRegistry` | `com.blockreality.api.spi` | 中央模組註冊表（`@ThreadSafe` 單例） |
| `ICommandProvider` | `com.blockreality.api.spi` | 自訂 Brigadier 指令註冊接口 |
| `IRenderLayerProvider` | `com.blockreality.api.spi` | 自訂客戶端渲染層接口 |

## ModuleRegistry

### API 存取慣例

所有公開方法為 `static`，內部委派到 `INSTANCE`。呼叫端統一使用：
```java
ModuleRegistry.getCableManager();
ModuleRegistry.registerCommandProvider(myProvider);
```

### 註冊方法

| 方法 | 說明 |
|------|------|
| `registerCommandProvider(ICommandProvider)` | 註冊指令提供者 |
| `registerRenderLayerProvider(IRenderLayerProvider)` | 註冊渲染層（客戶端） |
| `registerBlockTypeExtension(IBlockTypeExtension)` | 註冊方塊類型擴展 |
| `setCableManager(ICableManager)` | 替換纜索管理器 |
| `setLoadPathManager(ILoadPathManager)` | 替換荷載路徑管理器 |
| `setFusionDetector(IFusionDetector)` | 替換 RC 融合偵測器 |

### 查詢方法

| 方法 | 回傳 |
|------|------|
| `getMaterialRegistry()` | `IMaterialRegistry` |
| `getCuringManager()` | `ICuringManager` |
| `getCableManager()` | `ICableManager` |
| `getLoadPathManager()` | `ILoadPathManager` |
| `getFusionDetector()` | `IFusionDetector` |
| `getCommandProviders()` | `List<ICommandProvider>` |
| `getRegistrySummary()` | 格式化診斷字串 |

### 執行緒安全

- 列表使用 `CopyOnWriteArrayList`
- 可替換服務使用 `volatile` 欄位
- 材料註冊表使用 `ConcurrentHashMap`
- 啟動時自動載入所有 `DefaultMaterial` 枚舉值

## ICommandProvider

```java
public interface ICommandProvider {
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher);
    String getModuleId();
}
```

在 `RegisterCommandsEvent` 階段由 ModuleRegistry 呼叫所有已註冊的 provider。

### 渲染事件分派

`fireRenderEvent(RenderLevelStageEvent)` — 遍歷所有 `IRenderLayerProvider`，呼叫 `isEnabled()` 為 true 者的 `onRenderLevelStage()`，帶例外安全防護。

## 關聯接口

- 依賴 → [ICableManager / ICuringManager](L3-cable-curing.md)
- 依賴 → [IMaterialRegistry / IFusionDetector](L3-material-spi.md)
- 被依賴 ← 所有模組初始化程式碼
