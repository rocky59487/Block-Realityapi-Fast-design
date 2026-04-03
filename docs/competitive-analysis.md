# Block Reality 競品分析與超越路線圖

> 分析日期：2026-04-03
> 研究範圍：Iris Shaders、Complementary Unbound/Reimagined、BSL Shaders、OptiFine、LabPBR 標準

---

## 一、競品技術本質：一個共同的致命弱點

所有現有 Minecraft 光影競品，無論畫面多精美，底層架構都建立在同一個 1990 年代的假設上：

> **「渲染器不知道這是什麼東西，只能看著貼圖猜。」**

### Iris / OptiFine 的設定系統

Iris 的所有玩家自訂機制來自 `shaders.properties` 純文字設定檔：
- 開發者在 GLSL shader 裡定義變數
- 在 properties 中宣告 slider 的值範圍（預設一組固定值）
- 玩家在遊戲內看到的「滑桿」，只是循環點擊預設值的按鈕

這代表**沒有任何 shader 作者能讓玩家拖動一條曲線、連接一條節點線、或根據遊戲內的語意資訊（這是混凝土還是鋼？）動態改變渲染參數**。

### LabPBR 的材質系統

LabPBR 是業界最先進的 Minecraft PBR 標準，但它的限制清晰：
- PBR 資料（光滑度、金屬感、次表面散射）**烘焙進貼圖**
- 無法根據遊戲狀態（養護進度、應力比例、施工階段）動態變化
- 需要搭配對應的資源包（Resource Pack）才能生效
- 一旦貼圖輸出，資料就是靜態的

### 競品的設定 UI

| 競品 | 設定介面 | 自訂深度 | 即時預覽 | 風格預設 |
|------|---------|---------|---------|---------|
| Iris + shader pack | 分類滑桿 + 循環按鈕 | 中（受開發者限制） | 有，但需重啟著色器 | 依 pack 而定 |
| OptiFine | 同上，更舊 | 低 | 部份支援 | 無 |
| Complementary | 分頁滑桿，Potato→Ultra | 中 | 有 | 5 個效能級別 |
| BSL Shaders | 詳細分類滑桿 | 中高 | 有 | 無藝術風格 |
| **Block Reality** | **節點圖 + 卡片選擇器** | **無上限** | **有** | **8 種藝術風格** |

---

## 二、Block Reality 的結構性優勢：競品根本無法複製的三個能力

這是分析的核心。以下三項優勢不是「做得更好」，而是**競品架構上無法擁有**的能力。

### 優勢 A：知道「這個方塊是什麼材料」

Block Reality 的 `MaterialRegistry` 中每種材料都有完整的工程資料：

```
混凝土：抗壓強度 40 MPa，楊氏模量 30 GPa，密度 2400 kg/m³
鋼筋：降伏強度 420 MPa，楊氏模量 200 GPa
木材：強度 11 MPa，楊氏模量 12 GPa，各向異性材料
```

**Iris / OptiFine 知道嗎？不知道。** 它們只能看見貼圖的 RGB 值。
**LabPBR 知道嗎？不知道。** 它只能看貼圖的 smoothness/F0 通道。

Block Reality 可以利用這些資料自動套用正確的 PBR 參數，**不需要任何資源包**。

### 優勢 B：知道「這個結構現在的物理狀態」

`ForceEquilibriumSolver`（SOR 迭代求解器）輸出每個節點的應力利用率（Utilization Ratio）。
`CuringManager` 追蹤每塊混凝土的養護進度（0%→100%）。
`CollapseManager` 知道哪些元素正在崩塌中。

**沒有任何光影模組知道這些。** 它們渲染的是「外觀」，不是「狀態」。

### 優勢 C：知道「這個結構的施工進度」

Blueprint（藍圖模式）→ 施工中 → 養護中 → 完工 → 損壞——每個階段都有不同的語意，可以驅動不同的渲染效果，這在其他任何 Minecraft 模組中不存在。

---

## 三、五個殺手級差異化功能（優先實作順序）

### 🔴 P0：材料感知 PBR 自動配置（Material-Aware Auto-PBR）

**問題**：LabPBR 需要資源包，普通玩家不會安裝。
**Block Reality 方案**：直接查詢 `MaterialRegistry`，自動為每種材料套用正確的 PBR 節點配置，零資源包依賴。

實作位置：`api/material/MaterialPBRProfile.java`（新增）、整合進 `DefaultMaterial`

```
混凝土方塊   → roughness 0.85，metallic 0.0，SSS 啟用（養護中顏色偏亮）
鋼筋/鋼鐵    → roughness 0.25，metallic 1.0，各向異性 0.6
RC 複合材料  → 混合 PBR（97% 混凝土 + 3% 鋼筋的 weighted average）
木材         → roughness 0.70，metallic 0.0，各向異性 0.4
玻璃/釉      → 折射 + SSR 強制啟用，透明渲染
```

**對玩家的體感**：裝了 Block Reality 的世界，混凝土就是混凝土的質感、鋼就是鋼的質感，不需要額外安裝任何東西。

---

### 🔴 P0：結構應力熱力圖渲染（Structural Stress Heatmap Overlay）

**這是全 Minecraft 生態中獨一無二的功能。**

`ForceEquilibriumSolver` 輸出的每個節點應力利用率（0.0→1.0+）直接驅動顏色 Overlay：

```
0.0 – 0.5   →  藍綠色（安全餘裕充足）
0.5 – 0.8   →  黃色（接近設計載重）
0.8 – 1.0   →  橘紅色（危險，接近降伏）
> 1.0        →  深紅色閃爍（已超過強度極限）
```

呈現方式有三種模式可在「進階」分頁切換：
- **Off**：正常渲染
- **Overlay**：半透明熱力圖疊加在正常畫面上
- **X-Ray**：穿透牆面顯示所有結構的應力狀態

實作位置：新增 `StressHeatmapNode`（postfx 類別）、`StressDataBuffer`（api/physics/）

**使用場景**：玩家用 `/fd` 做完一棟建築，按一鍵切換到熱力圖模式，立刻看到哪個角落的樑需要加固。這不只是光影，這是工程輔助工具。

---

### 🟠 P1：施工階段渲染系統（Construction Phase Rendering）

每個 `RStructure` 有明確的施工狀態，用節點驅動對應的視覺效果：

| 階段 | 視覺效果 | 節點 |
|------|---------|------|
| 藍圖模式 | 半透明藍色全息線框（現有 HologramRenderer） | `BlueprintPhaseNode`（新增） |
| 施工中（鋼筋已放） | 橘色金屬光澤，無混凝土紋理 | 材料感知 PBR |
| 養護中（0→100%） | 混凝土顏色從偏亮白 → 正常灰（`CuringManager` 驅動） | `CuringVisualNode`（新增） |
| 完工 | 正常 PBR，根據材料自動配置 | 材料感知 PBR |
| 損壞/超載 | 裂紋貼花 + 應力紅色 | `DamageOverlayNode`（新增） |

---

### 🟠 P1：社群風格包分享生態系（Style Pack Ecosystem）

Iris 的「shader pack」是巨大的 GLSL 程式碼檔，需要圖形學知識才能創作。
Block Reality 的 StylePreset 是純粹的 JSON 節點參數，**任何玩家都能創作並分享**。

實作步驟：
1. `StylePreset` → JSON 序列化（已有 `portOverrides` Map）
2. 產生 **8 位風格碼**（Base62 壓縮 JSON），可貼到 Discord 分享
3. 在風格選擇畫面加入「輸入風格碼」欄位，一鍵匯入
4. 長期：對接 Modrinth API，在遊戲內瀏覽社群風格包

這創造了一個 Iris 無法複製的分發護城河：玩家創作的風格包 = Block Reality 生態系的黏著力。

---

### 🟡 P2：建築視覺化模式（Architectural Visualization Mode）

專為「展示建築成果」設計的渲染模式，對標 Unreal Engine ArchViz：

- **正交視圖**（`BRViewportManager` 已支援）+ 乾淨無 Bloom 的硬線條光影
- **剖面切割**：在指定平面切開建築，顯示內部結構
- **黃金時刻固定光源**：無論 Minecraft 時間，固定最佳光線角度
- **材料圖例覆蓋**：右下角顯示畫面中各材料的色碼圖例
- **尺寸標注 HUD**：顯示選取結構的 XYZ 尺寸（整合現有 `StructuralSafetyHud`）

配合現有的 NURBS/STEP/IFC 匯出，這讓 Block Reality 成為 Minecraft 中唯一可以做**專業建築簡報**的模組。

---

## 四、節點圖 vs 競品設定系統：視覺化對比

```
Iris shaders.properties（競品）
────────────────────────────────
BLOOM_ENABLE=true
BLOOM_STRENGTH=0.5      # 只能在 [0.1, 0.3, 0.5, 0.8, 1.0] 之間選
TONEMAP_MODE=ACES       # 只能選 ACES / Reinhard / Uncharted2

結果：玩家點擊按鈕循環值，看不見參數之間的關係


Block Reality 節點圖
────────────────────────────────────────────────────────────────
[混凝土材料節點] ──roughness──► [PBR 組合節點] ──► [Tonemap節點]
                                      ▲                    │
[應力資料節點] ──utilization──► [熱力圖節點]              ▼
                                                    [最終輸出]
[日夜時間節點] ──timeOfDay──► [大氣節點]

結果：玩家看見參數如何流動、可以任意組合、效果即時變化
```

---

## 五、實作優先矩陣

| 功能 | 衝擊力 | 實作難度 | 是否獨有 | 優先級 |
|------|-------|---------|---------|-------|
| 材料感知 PBR 自動配置 | ★★★★★ | 中 | ✅ 是 | P0 |
| 應力熱力圖渲染 | ★★★★★ | 中高 | ✅ 是 | P0 |
| 施工階段渲染 | ★★★★☆ | 中 | ✅ 是 | P1 |
| 社群風格包分享 | ★★★★☆ | 低 | ✅ 是 | P1 |
| 建築視覺化模式 | ★★★☆☆ | 中 | ✅ 是 | P2 |
| 8 種藝術風格預設 | ★★★☆☆ | 低（已完成）| ⚠️ 部份 | ✅ 已完成 |
| 三分頁設定畫面 | ★★★☆☆ | 中（已完成）| ⚠️ 部份 | ✅ 已完成 |

---

## 六、一句話總結競品差異

> 所有競品都在問：「這個貼圖長什麼樣？」
> Block Reality 可以問：「這個結構是混凝土還是鋼？養護到幾成？應力利用率多少？正在蓋還是蓋好了？」
>
> 這不是「更好的光影」，這是「第一個懂建築的光影引擎」。

---

*Sources consulted: [Iris Shaders](https://www.irisshaders.dev/) · [Iris Shader Properties Docs](https://shaders.properties/current/reference/shadersproperties/shader_settings/) · [LabPBR Material Standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard) · [Complementary Shaders](https://www.complementary.dev/shaders/) · [BSL Shaders](https://capttatsu.com/bslshaders/) · [Best Minecraft Shaders 2026](https://www.onlyalok.com/2026/03/best-minecraft-shaders-2026-top-10.html)*
