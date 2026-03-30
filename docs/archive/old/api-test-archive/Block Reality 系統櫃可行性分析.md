# Block Reality — 系統櫃模組可行性分析

> 日期：2026-03-25
> 核心問題：單方塊內放多元件可行嗎？隔板能放東西嗎？系統櫃所有功能都能實現嗎？

---

## 1. 單方塊內多元件：可行嗎？

### 結論：✅ 完全可行

Minecraft Forge 1.20.1 的 `BlockEntity` 可以存儲任意結構的 NBT 數據，沒有「一個方塊只能有一種材料」的硬性限制。限制來自 Block Reality API 目前的設計選擇（`RBlockEntity` 只存一個 `RMaterial`），而非引擎限制。

### 技術原理

```
一個 Minecraft 方塊 (1m³ = 100cm × 100cm × 100cm)

┌─────────────────────────────────────┐
│ RBlockEntity                        │
│                                     │
│  ┌───────┐  ┌───────────────────┐  │
│  │ 側板   │  │                   │  │  ← PanelElement(FACE_WEST, 18mm)
│  │ 18mm  │  │    空腔            │  │
│  │ 橡木   │  │    (可放置物品)    │  │
│  │       │  │                   │  │
│  └───────┘  └───────────────────┘  │
│  ─────────────────────────────────  │  ← ShelfElement(FACE_DOWN, 18mm)
│         底板 18mm 塑合板            │
└─────────────────────────────────────┘
```

一個方塊的 `RBlockEntity` 可以同時包含：

| 子元件 | 佔據位置 | 材料 | 厚度 |
|--------|---------|------|------|
| 左側板 | FACE_WEST | 橡木 | 18mm |
| 右側板 | FACE_EAST | 橡木 | 18mm |
| 頂板 | FACE_UP | 塑合板 | 25mm |
| 底板 | FACE_DOWN | 塑合板 | 18mm |
| 背板 | FACE_NORTH | 薄板 | 3mm |
| 層板 | 自訂高度 | 塑合板 | 18mm |

### Minecraft 先例

Forge 生態中已有多個模組實現了「單方塊多元件」：

| 模組 | 做法 | 證明 |
|------|------|------|
| **Chisels & Bits** | 一個方塊可拆成 16×16×16 = 4096 個子方塊 | 子方塊級精度完全可行 |
| **Carpenter's Blocks** | 一個方塊可放不同材質的面板 | 面板系統可行 |
| **Little Tiles** | 一個方塊可包含數十個微型構件 | 多元件架構可行 |
| **Immersive Engineering** | 多方塊結構含不同子功能 | 結構+功能組合可行 |
| **Storage Drawers** | 一個方塊可存多種物品 | 子格物品欄可行 |

### Block Reality API 需要的改動

改動量：**中等**（不影響核心物理引擎）

1. `RBlockEntity` 新增 `List<SubElement> subElements` 欄位
2. `SubElement` 介面定義子元件屬性
3. NBT 序列化擴展（ListTag 存子元件）
4. `getEffectiveMaterial()` 提供向後相容的等效材料

物理引擎（UnionFindEngine、SPHStressEngine、LoadPathEngine）**不需修改**——它們繼續以 BlockPos 粒度工作，透過 `getEffectiveMaterial()` 取得等效物理參數。

---

## 2. 隔板能放東西嗎？

### 結論：✅ 完全可行

### 技術方案：子元件物品欄 (Sub-Inventory)

```java
public class ShelfElement implements SubElement {
    // 子元件本身的結構資料
    private RMaterial material;
    private float thickness;
    private ElementPlacement placement;

    // ★ 物品欄 — 隔板上可以放東西
    private ItemStackHandler inventory;  // Forge 的 IItemHandler 實作
    private float maxLoadCapacity;       // 最大承重 (kg)
    private float currentItemLoad;       // 目前物品總重

    /** 物品放上去時，加入重量到物理系統 */
    public boolean tryInsertItem(ItemStack stack) {
        float itemWeight = getItemWeight(stack);
        if (currentItemLoad + itemWeight > maxLoadCapacity) {
            return false;  // 超重！
        }
        if (inventory.insertItem(0, stack, false).isEmpty()) {
            currentItemLoad += itemWeight;
            // ★ 關鍵：通知物理系統重量變化
            parentBlockEntity.addLoad(itemWeight);
            return true;
        }
        return false;
    }
}
```

### 核心互動流程

```
玩家把物品放到隔板上
    │
    ▼
ShelfElement.tryInsertItem(stack)
    │
    ├─ 檢查承重 → 超過上限 → 拒絕 + 顯示「超重」提示
    │
    ├─ 插入物品欄
    │
    ├─ 計算物品重量 → 加到 RBlockEntity.currentLoad
    │
    └─ 觸發 LoadPathEngine.propagateLoadDown()
        │
        ├─ 載重沿支撐樹向下傳播
        │
        └─ 如果總載重超過結構強度
            │
            └─ CollapseManager.queueCollapse() → 櫃子塌了！
```

### 支撐的交互方式

| 交互 | 實作方式 |
|------|---------|
| 右鍵隔板 → 打開物品欄 | GUI 子元件級交互（參考 Storage Drawers） |
| 放入物品 → 增加載重 | `RBlockEntity.addLoad()` → 支撐樹傳播 |
| 取出物品 → 減少載重 | `RBlockEntity.removeLoad()` → 支撐樹更新 |
| 超重 → 層板變形/崩塌 | `stressLevel > threshold` → 觸發 collapse |
| 不均勻載重 → 傾斜 | 可選：子元件級應力分析 |

### 物品重量系統

Minecraft 原版物品沒有「重量」概念，但可以擴展：

```java
// 方案 A：基於材料的預設重量
public static float getItemWeight(ItemStack stack) {
    Block block = Block.byItem(stack.getItem());
    if (block != Blocks.AIR) {
        // 方塊類物品：查 VanillaMaterialMap 取密度
        RMaterial mat = VanillaMaterialMap.getMaterial(block);
        return (float) mat.getDensity() * stack.getCount();
    }
    // 非方塊物品：預設 0.5kg（可由 data pack 覆寫）
    return 0.5f * stack.getCount();
}

// 方案 B：Config 或 data pack 自訂
// item_weights.json:
// { "minecraft:iron_block": 7874.0, "minecraft:diamond": 3.5 }
```

---

## 3. 系統櫃所有功能一覽

### 結論：✅ 全部可實現

| 功能 | 現有 API 支援 | 額外需要 | 可行性 |
|------|-------------|---------|--------|
| **側板/頂底板** | SubElement + RMaterial | 面板渲染模型 | ✅ |
| **背板（薄板）** | SubElement 厚度=3mm | 無 | ✅ |
| **層板（可調高度）** | ElementPlacement 自訂 Y 偏移 | ShelfElement | ✅ |
| **隔板放物品** | IItemHandler + 載重計算 | 子元件物品欄 | ✅ |
| **門片（鉸鏈開關）** | HingeElement + BlockState 旋轉 | 動畫系統 | ✅ |
| **抽屜（滑軌）** | RailElement + 滑動狀態 | 滑軌動畫 | ✅ |
| **腳鍊（調整腳）** | SubElement FACE_DOWN | 高度調整 | ✅ |
| **踢腳板** | SubElement FACE_DOWN 前緣 | 裝飾渲染 | ✅ |
| **轉角櫃** | 多方塊結構 + L 型 SubElement | 角度連接 | ✅ |
| **頂櫃/吊櫃** | isAnchored=true（牆壁錨定）| AnchorContinuityChecker | ✅ |
| **堆疊組合** | 多 RBlock 垂直 + 結構檢查 | LoadPathEngine | ✅ |
| **承重計算** | getEffectiveMaterial() | 已有 | ✅ |
| **超重崩塌** | CollapseManager | 已有 | ✅ |
| **應力熱圖** | StressHeatmapRenderer | 已有 | ✅ |
| **藍圖系統** | Blueprint + HologramRenderer | 已有 | ✅ |
| **R 值掃描** | AnchorPathRenderer（已修復色彩）| 已有 | ✅ |

### 詳細設計：門片鉸鏈

```java
public class HingeElement implements SubElement {
    private ElementPlacement hingeSide;  // 鉸鏈在哪一邊
    private float openAngle;             // 目前開角 (0=關閉, 90=全開)
    private float maxAngle;              // 最大開角
    private int hingeCount;              // 鉸鏈數量（影響承重）

    // 鉸鏈承重 = 單鉸鏈容量 × 數量
    // 超過 → 門片下垂 → stress 上升 → 視覺歪斜
    public double getLoadCapacity() {
        return SINGLE_HINGE_CAPACITY * hingeCount;
    }
}
```

### 詳細設計：抽屜滑軌

```java
public class DrawerRailElement implements SubElement {
    private RailType railType;  // BALL_BEARING, ROLLER, SOFT_CLOSE
    private float extension;     // 0.0=收回, 1.0=全伸
    private float maxWeight;     // 滑軌額定承重

    public enum RailType {
        BALL_BEARING(15.0f),   // 15kg 三節式
        ROLLER(25.0f),         // 25kg 鋼珠式
        SOFT_CLOSE(20.0f);     // 20kg 緩衝式

        final float defaultCapacity;
        RailType(float cap) { this.defaultCapacity = cap; }
    }
}
```

### 詳細設計：可調層板

```java
public class AdjustableShelfElement implements SubElement {
    private float yOffset;       // Y 偏移量 (0.0~1.0，對應方塊內高度)
    private float[] pinHoles;    // 可用的孔位 (如 [0.1, 0.2, 0.3, ...])
    private int currentPin;      // 目前插在哪個孔位
    private ItemStackHandler inventory;  // 層板上的物品

    /** 玩家用棒釘調整高度 */
    public void adjustHeight(int pinIndex) {
        this.currentPin = pinIndex;
        this.yOffset = pinHoles[pinIndex];
        // 重新計算承重路徑
        parentBlockEntity.setChanged();
    }
}
```

---

## 4. 物理正確性保證

### 系統櫃的載重傳導路徑

```
          物品 (5kg)
            │
            ▼
    ┌──── 層板 ────┐
    │  stress=0.15 │
    └──┬────────┬──┘
       │        │
       ▼        ▼
    左側板    右側板
    stress    stress
    = 0.08   = 0.08
       │        │
       ▼        ▼
    ┌──── 底板 ────┐
    │  stress=0.20 │
    └──┬────────┬──┘
       │        │
       ▼        ▼
     腳座A    腳座B
     (anchor) (anchor)
     ← 地板 →
```

### 計算不衝突的原因

| 機制 | 系統櫃場景 | 不衝突原因 |
|------|-----------|-----------|
| `getEffectiveMaterial()` | 多子元件 → 等效材料 | 物理引擎只看等效值 |
| `getCompositeMass()` | 多子元件質量加總 | 取代 `getSelfWeight()` |
| `LoadPathEngine` | 載重沿側板 → 底板 → 腳座 | 現有 BFS 支撐樹完全適用 |
| `UnionFindEngine` | 櫃體=連通結構 | BlockPos 粒度足夠 |
| `CollapseManager` | 超重→結構失效→崩塌 | 直接復用現有機制 |

### 唯一需要注意的點

**截面積計算：** 現有引擎假設截面積 = 1m²（整塊方塊），但系統櫃的側板只有 18mm 厚（截面積 = 0.018m²）。需要讓 `getEffectiveMaterial()` 根據子元件厚度折算等效強度：

```java
// 18mm 側板在 1m 方塊中的等效強度
// 實際承載力 = Rcomp × 0.018m² (板材截面)
// 等效在 1m² 中 = Rcomp × 0.018 (折算)
double effectiveRcomp = material.getRcomp() * (thickness / 1000.0);
```

這確保物理引擎用 1m² 截面計算時，得到的結果等同於用真實截面計算。

---

## 5. 總結

| 問題 | 回答 |
|------|------|
| 單方塊多元件可行嗎？ | **✅ 完全可行** — BlockEntity NBT 無限制，Forge 生態已有成熟先例 |
| 隔板能放東西嗎？ | **✅ 完全可行** — IItemHandler + 載重計算 + 支撐樹傳播 |
| 系統櫃所有功能都能實現嗎？ | **✅ 全部可行** — 16 項功能均有明確技術方案 |
| 需要重寫物理引擎嗎？ | **不需要** — 透過 getEffectiveMaterial() 向後相容 |
| 預估開發量 | SubElement API：1-2 週 / 系統櫃模組本體：3-4 週 |

Block Reality API 的架構為系統櫃模組提供了堅實的基礎。核心物理引擎、載重傳導、崩塌系統、應力渲染全部可以直接復用，只需要在 `RBlockEntity` 上加一層子元件抽象。
