# Block Reality — 系統櫃模組接入設計 & 全代碼邏輯審查

> 日期：2026-03-25
> 範圍：93 支 Java 源碼 × 系統櫃需求分析 × 邏輯正確性掃描

---

## Part A：系統櫃模組需求分析

### 核心挑戰

系統櫃模組的本質是 **「單方塊內含多子元件」**：一個 1m³ 的 Minecraft 方塊裡可以同時存放板材（面板、背板、層板）、腳鍊（鉸鏈）、隔板、抽屜軌道等結構子元件。

這與目前 Block Reality API 的 **1 BlockPos = 1 RMaterial = 1 BlockType** 模型存在架構差異。

### 現有 API 的限制與接入點

| 現有模型 | 系統櫃需要 | 差距 |
|---------|-----------|------|
| `RBlockEntity` 存 1 個 `RMaterial` | 需存 N 個子元件各自的材料 | 需要子元件列表 |
| `BlockType` 描述整塊方塊角色 | 需描述子元件角色（板材/鉸鏈/軌道） | 需要子類型系統 |
| `RBlockState` 每格 1 筆 | 需每格 N 筆（子元件展開） | 快照需擴展 |
| 物理引擎用 `BlockPos` 定位 | 子元件需更精確定位（方向/位置） | 需要元件座標 |
| `stressLevel` 每格 1 個值 | 每個子元件獨立應力 | 需要多值應力 |

### 建議架構：SubElement 抽象層

```
                    ┌─────────────────────────────────────┐
                    │         RBlockEntity (現有)          │
                    │  material: RMaterial                 │
                    │  blockType: BlockType                │
                    │  stressLevel: float                  │
                    │                                      │
                    │  ★ NEW: subElements: List<SubElement>│
                    │  ★ NEW: getEffectiveMaterial()       │
                    │  ★ NEW: getCompositeMass()           │
                    └─────────────────────────────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
    │ PanelElement  │    │ HingeElement │    │ RailElement  │
    │ face: NORTH   │    │ edge: NE     │    │ axis: Y      │
    │ mat: PLYWOOD  │    │ mat: STEEL   │    │ mat: STEEL   │
    │ thickness: 18 │    │ capacity: 5kg│    │ capacity: 25 │
    │ stress: 0.12  │    │ stress: 0.45 │    │ stress: 0.30 │
    └──────────────┘    └──────────────┘    └──────────────┘
```

---

### 具體設計方案

#### 1. `SubElement` 介面

```java
package com.blockreality.api.furniture;

/**
 * 子元件介面 — 一個 BlockPos 內的獨立結構部件。
 * 系統櫃模組的核心抽象。
 */
public interface SubElement {
    /** 子元件類型 ID (如 "panel", "hinge", "rail", "divider") */
    String getElementType();

    /** 子元件材料 */
    RMaterial getMaterial();

    /** 子元件佔據的面/邊/軸 */
    ElementPlacement getPlacement();

    /** 厚度 (mm) — 影響截面積計算 */
    float getThickness();

    /** 子元件承重能力 (kg) */
    double getLoadCapacity();

    /** 子元件當前應力 (0.0~1.0) */
    float getStress();
    void setStress(float stress);

    /** NBT 序列化 */
    CompoundTag save();
    void load(CompoundTag tag);
}
```

#### 2. `ElementPlacement` 枚舉

```java
public enum ElementPlacement {
    // 面板（佔據方塊的六個面之一）
    FACE_NORTH, FACE_SOUTH, FACE_EAST, FACE_WEST, FACE_UP, FACE_DOWN,
    // 邊緣（佔據方塊的十二條邊之一）
    EDGE_NE, EDGE_NW, EDGE_SE, EDGE_SW,
    EDGE_TOP_N, EDGE_TOP_S, EDGE_TOP_E, EDGE_TOP_W,
    EDGE_BOT_N, EDGE_BOT_S, EDGE_BOT_E, EDGE_BOT_W,
    // 軸向（佔據方塊的三個軸之一，如抽屜軌道）
    AXIS_X, AXIS_Y, AXIS_Z,
    // 全域（佔據整個方塊，如填充材料）
    FULL_BLOCK
}
```

#### 3. `RBlockEntity` 擴展（向後相容）

```java
// ─── 系統櫃子元件支援（向後相容） ───
@Nullable
private List<SubElement> subElements = null;

/** 是否含子元件（系統櫃模式） */
public boolean hasSubElements() {
    return subElements != null && !subElements.isEmpty();
}

/** 取得子元件列表（不可變視圖） */
public List<SubElement> getSubElements() {
    return subElements == null ? List.of() : Collections.unmodifiableList(subElements);
}

/** 新增子元件 */
public void addSubElement(SubElement element) {
    if (subElements == null) subElements = new ArrayList<>(4);
    subElements.add(element);
    setChanged();
    syncToClient();
}

/** 取得等效材料 — 子元件的加權平均（供物理引擎向後相容） */
public RMaterial getEffectiveMaterial() {
    if (!hasSubElements()) return material;
    // 以質量加權計算等效 R 值
    double totalMass = 0, wComp = 0, wTens = 0, wShear = 0;
    for (SubElement se : subElements) {
        double m = se.getMaterial().getDensity() * (se.getThickness() / 1000.0);
        totalMass += m;
        wComp += se.getMaterial().getRcomp() * m;
        wTens += se.getMaterial().getRtens() * m;
        wShear += se.getMaterial().getRshear() * m;
    }
    if (totalMass <= 0) return material;
    return DynamicMaterial.ofCustom(
        "furniture_composite",
        wComp / totalMass,
        wTens / totalMass,
        wShear / totalMass,
        totalMass
    );
}

/** 取得複合質量 (kg) */
public float getCompositeMass() {
    if (!hasSubElements()) return getSelfWeight();
    float mass = 0;
    for (SubElement se : subElements) {
        mass += (float)(se.getMaterial().getDensity() * (se.getThickness() / 1000.0));
    }
    return mass;
}
```

#### 4. NBT 序列化擴展

在 `saveAdditional()` 和 `load()` 中加入子元件存取：

```java
// saveAdditional():
if (subElements != null && !subElements.isEmpty()) {
    ListTag elementList = new ListTag();
    for (SubElement se : subElements) {
        elementList.add(se.save());
    }
    tag.put("br_sub_elements", elementList);
}

// load():
if (tag.contains("br_sub_elements")) {
    ListTag elementList = tag.getList("br_sub_elements", Tag.TAG_COMPOUND);
    subElements = new ArrayList<>(elementList.size());
    for (int i = 0; i < elementList.size(); i++) {
        subElements.add(SubElementFactory.fromTag(elementList.getCompound(i)));
    }
}
```

#### 5. 物理引擎相容策略

| 引擎 | 相容方案 |
|------|---------|
| **UnionFindEngine** | 不需修改 — 仍以 BlockPos 為粒度，子元件內部連通隱含 |
| **SupportPathAnalyzer** | `getEffectiveMaterial()` 提供等效材料，BFS 粒度不變 |
| **SPHStressEngine** | 同上 — 以等效材料的 structuralFactor 參與計算 |
| **LoadPathEngine** | `getCompositeMass()` 取代 `getSelfWeight()` 計算總質量 |
| **ForceEquilibriumSolver** | 等效材料 → 等效剛度，無需改動求解器核心 |
| **BeamElement** | 若需子元件級精度，可為每個 SubElement 建 micro-beam |

**關鍵設計原則：向後相容等效。** 舊引擎看到的是 `getEffectiveMaterial()` 返回的等效材料，不需知道內部有多少子元件。系統櫃模組可以在上層實作更精確的子元件級應力分析。

#### 6. SPI 擴展點

```java
// 新增 SPI 介面，讓系統櫃模組註冊自訂子元件類型
package com.blockreality.api.spi;

public interface ISubElementProvider {
    /** 此 provider 支援的子元件類型 ID 集合 */
    Set<String> getSupportedTypes();

    /** 從 NBT 反序列化子元件 */
    SubElement fromTag(CompoundTag tag);

    /** 驗證子元件在指定 placement 是否合法 */
    boolean validatePlacement(SubElement element, RBlockEntity host);
}
```

在 `ModuleRegistry` 中新增：
```java
public void registerSubElementProvider(ISubElementProvider provider) { ... }
public SubElement deserializeSubElement(CompoundTag tag) { ... }
```

---

## Part B：全代碼邏輯正確性審查

### 嚴重度定義

| 等級 | 定義 |
|------|------|
| 🔴 HIGH | 導致錯誤計算結果或資料損壞 |
| 🟡 MEDIUM | 特定條件下產生不正確行為 |
| 🟢 LOW | 程式碼品質問題，不影響正確性 |

---

### BUG-R6-1 🔴 UnionFindEngine Y 軸邊距誤套用

**位置：** `UnionFindEngine.java` 第 331-333 行

**問題：** 浮空方塊偵測的「有效區域」(effectZone) 對 Y 軸也套用了 scanMargin，但垂直方向不應有邊距限制。結構可以在任何高度浮空。

**現狀：**
```java
boolean inEffectZone = (lx >= scanMargin && lx < sizeX - scanMargin &&
                        ly >= scanMargin && ly < sizeY - scanMargin &&  // ← 問題
                        lz >= scanMargin && lz < sizeZ - scanMargin);
```

**影響：** 快照頂部和底部的浮空方塊不會被偵測到。若 scanMargin=4、snapshot 高度=16，則 Y=[0,3] 和 Y=[12,15] 的浮空方塊完全被忽略。

**修復：**
```java
boolean inEffectZone = (lx >= scanMargin && lx < sizeX - scanMargin &&
                        ly >= 0 && ly < sizeY &&  // Y 軸無邊距
                        lz >= scanMargin && lz < sizeZ - scanMargin);
```

---

### BUG-R6-2 🔴 RCFusionDetector 材料角色判定邏輯缺陷

**位置：** `RCFusionDetector.java` 第 112-120 行

**問題：** 以 `Rtens` 值的大小判定哪個是鋼筋、哪個是混凝土。但 `calculateFusionMaterial()` 接收的是兩個 `RMaterial`，不是 `BlockType`。

**邏輯漏洞：**
- 自訂高強度混凝土可能 Rtens > 普通鋼筋 → 角色互換
- `DynamicMaterial.ofCustom()` 可建立任意 Rtens 的材料

**現狀：**
```java
if (matA.getRtens() > matB.getRtens()) {
    rebar = matA;    // 假設 Rtens 高的是鋼筋
    concrete = matB;
}
```

**影響：** 自訂材料場景下，RC 融合公式中的 concrete/rebar 角色互換，compBoost 會套用到錯誤的材料上。

**修復方案：** 傳入 `BlockType` 而非僅靠 Rtens 判定：
```java
private static RMaterial calculateFusionMaterial(
        RMaterial matA, BlockType typeA,
        RMaterial matB, BlockType typeB,
        boolean hasHoneycomb) {
    RMaterial rebar, concrete;
    if (typeA == BlockType.REBAR) {
        rebar = matA; concrete = matB;
    } else {
        rebar = matB; concrete = matA;
    }
    // ...
}
```

---

### BUG-R6-3 🔴 LoadPathEngine chunk unload 時載重重設錯誤

**位置：** `LoadPathEngine.java` 第 488-489 行

**問題：** 區塊卸載時將 `currentLoad` 重設為 `selfWeight`，但此值會被 NBT 儲存。區塊重新載入後，parent 方塊不知子方塊的載重已被重設 → 支撐鏈載重資料不一致。

**現狀：**
```java
rbe.setSupportParent(null);
rbe.setCurrentLoad(rbe.getSelfWeight());  // ← 問題
```

**影響：** 多區塊結構跨區塊邊界時，卸載/重載後父節點仍記錄舊載重，子節點被重設 → 整棵支撐樹的載重加總不守恆。

**修復方案：** 區塊卸載時不修改 `currentLoad`（讓 NBT 保留原值），改由區塊載入時重算：
```java
// onChunkUnload: 只斷開指標，不動載重
rbe.setSupportParent(null);
// 不呼叫 setCurrentLoad — 讓 NBT 保留

// onChunkLoad (新增): 重建支撐鏈後重新傳播載重
LoadPathEngine.rebuildSupportChain(level, rbe.getBlockPos());
```

---

### BUG-R6-4 🟡 BeamStressEngine 載重傳播只走垂直方向

**位置：** `BeamStressEngine.java` 第 256-259 行

**問題：** 載重累積只從 `pos.below()` 傳播（純垂直瀑布），完全忽略水平懸臂臂的荷載傳遞。對於系統櫃這種以水平層板為主的結構，水平方向的載重分配至關重要。

**影響：** 水平層板上方的載重不會正確傳遞到兩端支撐點。

**修復方向：** 增加水平鄰居載重分配（需配合 `LoadPathEngine` 的 supportParent 資訊）。

---

### BUG-R6-5 🟡 BeamStressEngine 彎矩公式假設均勻分布

**位置：** `BeamStressEngine.java` 第 286-289 行

**問題：** 水平梁使用 `M = q × L²/8`（均布載重簡支梁彎矩公式），但實際載重可能不均勻 — 系統櫃的層板一端放重物一端空載是常見情境。

**影響：** 不均勻載重時彎矩計算偏低，可能低估實際應力。

**修復方向：** 可選方案：
- 使用 `M = (qA × L² + qB × L²) / 12`（不等端荷固端梁近似）
- 或引入 `SubElement` 級載重位置資訊後精確計算

---

### BUG-R6-6 🟡 DynamicMaterial RC 密度比固定 80:20

**位置：** `DynamicMaterial.java` 第 75 行

**問題：** RC 融合密度用硬編碼 `concrete × 0.8 + rebar × 0.2`，不反映實際鋼筋配比。

**現狀：**
```java
double rcDensity = concrete.getDensity() * 0.8 + rebar.getDensity() * 0.2;
```

**影響：** 實際工程中混凝土佔 95-98% 體積（鋼筋僅 2-5%）。目前的 80:20 嚴重高估鋼筋密度貢獻，導致 RC 節點質量偏高約 15%。

**修復建議：** 改為可配置或基於合理工程比例：
```java
double rebarVolumeRatio = 0.03;  // 3% 鋼筋含量（典型值）
double rcDensity = concrete.getDensity() * (1 - rebarVolumeRatio)
                 + rebar.getDensity() * rebarVolumeRatio;
```
或從 BRConfig 讀取 `rc_fusion.rebar_volume_ratio`。

---

### BUG-R6-7 🟡 SupportPathAnalyzer BFS 預算耗盡靜默處理

**位置：** `SupportPathAnalyzer.java`

**問題：** 當 BFS 因 `maxBlocks` 或 `maxMs` 而提前終止時，未訪問的方塊被標記為 `stress=0.5f, stable=true` — 這可能隱藏實際的結構問題。

**影響：** 大型結構中，遠端方塊可能是真正的失效點但被靜默標記為「穩定」。

**修復建議：** 將 BFS 耗盡標記為 `UNCERTAIN` 而非 `stable`：
```java
// 未訪問方塊
stressMap.put(pos, 0.5f);
// 應標記為 uncertain，而非 stable
uncertainSet.add(pos);  // 新增 uncertain 集合
```

---

### BUG-R6-8 🟢 LoadPathEngine 承載力計算重複

**位置：** `LoadPathEngine.java` 第 344 行 & 第 404 行

**問題：** `mat.getRcomp() * 1e6 * BLOCK_CROSS_SECTION_AREA` 在兩處獨立計算，維護風險。

**修復：** 抽取為共用方法：
```java
private static double calculateCompressiveCapacity(RMaterial mat) {
    return mat.getRcomp() * 1e6 * BLOCK_CROSS_SECTION_AREA;
}
```

---

### BUG-R6-9 🟢 ForceEquilibriumSolver 水平方向只做 4 向

**位置：** `ForceEquilibriumSolver.java`

**問題：** 水平載重分配只考慮 NORTH/SOUTH/EAST/WEST 四方向，不含對角線（8 向）。對於系統櫃的斜撐/角碼連接，可能偏保守。

**影響：** 低 — 4 向在大多數場景足夠，對角結構少見。但系統櫃有斜撐需求時會低估水平傳力。

---

### BUG-R6-10 🟢 AnchorPathRenderer 錨定/非錨定不區分色彩

**位置：** `AnchorPathRenderer.java` 第 21 行

**問題：** 所有路徑統一橙色，想法規格要求有效=綠色、無效=紅色。

**修復：** 根據路徑末端是否抵達錨定點切換顏色：
```java
float r = isAnchored ? 0.2f : 1.0f;
float g = isAnchored ? 0.8f : 0.2f;
```

---

## Part C：修復優先級與系統櫃接入路徑

### 修復優先級排序

| 優先級 | 編號 | 問題 | 修復難度 | 影響系統櫃 |
|--------|------|------|---------|-----------|
| P0 | R6-1 | UnionFind Y 邊距 | 1 行改動 | ✅ 直接影響 |
| P0 | R6-2 | RC 融合角色判定 | 介面改動 | ✅ 自訂材料必須 |
| P0 | R6-3 | Chunk unload 載重 | 中等改動 | ✅ 跨區塊結構 |
| P1 | R6-4 | 梁垂直傳播限制 | 中等改動 | ✅ 水平層板 |
| P1 | R6-5 | 均布彎矩假設 | 中等改動 | ✅ 不均載重 |
| P1 | R6-6 | RC 密度比 | 1 行改動 | 間接影響 |
| P1 | R6-7 | BFS 預算靜默 | 小改動 | 間接影響 |
| P2 | R6-8 | 承載力重複 | 重構 | ⚪ 代碼品質 |
| P2 | R6-9 | 4 向限制 | 未來考慮 | ⚪ 斜撐場景 |
| P2 | R6-10 | 路徑色彩 | 小改動 | ⚪ 視覺優化 |

### 系統櫃模組接入路徑（建議順序）

```
Phase 1 — 修復基礎 (P0 修復)
  ├─ 修復 BUG R6-1, R6-2, R6-3
  └─ 確保現有引擎邏輯正確

Phase 2 — SubElement API
  ├─ 新增 SubElement 介面 + ElementPlacement 枚舉
  ├─ RBlockEntity 加入 subElements 列表
  ├─ getEffectiveMaterial() + getCompositeMass()
  ├─ NBT 序列化支援
  └─ SPI: ISubElementProvider

Phase 3 — 物理引擎適配
  ├─ LoadPathEngine 使用 getCompositeMass()
  ├─ 修復 BeamStressEngine 水平傳播 (R6-4)
  ├─ 修復彎矩公式 (R6-5)
  └─ 系統櫃專用子元件應力分析器

Phase 4 — 渲染與交互
  ├─ 系統櫃子元件渲染（面板/鉸鏈/軌道各自模型）
  ├─ 錨定路徑色彩修復 (R6-10)
  └─ 子元件級應力熱圖
```

---

## Part D：計算衝突掃描結果

以下為全代碼中 **不同引擎間的計算一致性** 檢查：

### D-1 ✅ 材料強度單位一致

所有引擎均使用 MPa 作為強度單位，Pa 作為模量單位。`RMaterial` 介面的 Javadoc 已明確標注。

### D-2 ✅ 密度單位一致

所有引擎均使用 kg/m³，`getSelfWeight()` = `getDensity()` × 1m³。

### D-3 ✅ structuralFactor 來源唯一

`BlockType.getStructuralFactor()` 是 SPHStressEngine 和 BlockTypeRegistry 的唯一資料來源（Single Source of Truth）。

### D-4 ⚠️ 截面積假設不一致

| 引擎 | 截面積假設 |
|------|-----------|
| `BeamElement` | `BLOCK_AREA = 1.0` (1 m²) |
| `LoadPathEngine` | `BLOCK_CROSS_SECTION_AREA = 1.0` (1 m²) |
| `ForceEquilibriumSolver` | 隱含 1m² （質量/密度推導） |
| **SubElement 需要** | **< 1m²**（18mm 面板 ≠ 1m² 截面） |

→ 系統櫃接入時，子元件的截面積必須從 `SubElement.getThickness()` 推導，不能使用 `BLOCK_AREA = 1.0`。

### D-5 ⚠️ FNV-1a 指紋計算中的材料 ID

`ForceEquilibriumSolver` 的 warm-start cache 使用 `material.getMaterialId().hashCode()` 作為指紋的一部分。如果系統櫃的 `getEffectiveMaterial()` 每次回傳不同的 `DynamicMaterial` 實例（即使值相同），`getMaterialId()` 可能變化 → 快取命中率降低。

→ **建議：** `getEffectiveMaterial()` 應快取結果，只在 subElements 列表變更時重算。

### D-6 ✅ 事件觸發順序正確

`BlockPhysicsEventHandler` 的事件處理順序：
放置 → RC 融合 → 錨定檢查 → 載重路徑 → 結構分析

破壞 → RC 降級 → 載重移除 → 支撐重算 → 浮空偵測

依賴關係正確，無循環。

---

## 結論

1. **系統櫃模組可透過 SubElement 抽象層接入，不需重寫核心物理引擎。** 關鍵是 `getEffectiveMaterial()` 和 `getCompositeMass()` 兩個向後相容橋接方法。

2. **發現 3 個 🔴 HIGH 嚴重度邏輯缺陷**（R6-1 Y 邊距、R6-2 材料角色、R6-3 載重重設），建議立即修復。

3. **計算衝突方面**，截面積假設 (D-4) 和指紋快取 (D-5) 是系統櫃接入時需要特別注意的兩個點。

4. **整體代碼品質高**，93 支源碼中只發現 10 個問題（3 HIGH + 4 MEDIUM + 3 LOW），且大部分修復難度低。
