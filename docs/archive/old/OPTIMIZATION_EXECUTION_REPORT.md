# Block Reality v3.0 — 極限優化執行報告
# 目標：1200×1200×300 體素範圍全物理模擬

---

## 0. 現況分析與瓶頸定位

### 當前架構能力
| 指標 | 現況 (v2.0) | 目標 (v3.0) |
|------|-------------|-------------|
| 最大快照範圍 | 65,536 blocks (≈40³) | **432,000,000 blocks** (1200×1200×300) |
| 快照資料結構 | `RBlockState[]` 平坦1D陣列 | 需改為階層式稀疏結構 |
| 渲染方式 | 即時模式 `BufferUploader.drawWithShader()` | 需改為持久化 VAO/VBO + 增量更新 |
| 物理執行緒 | ForkJoinPool (cores-2) | 需改為空間分割並行 + SIMD 批次 |
| BFS 限制 | 65,536 blocks (快照上限) / 2,048 blocks (結構 BFS) / 50ms 時限 | 需改為階層式區域分析 |
| Sidecar | JSON-RPC over stdio | 需擴展為高頻二進制協議 |
| 光影支援 | 無 | 自訂渲染管線 + 自訂光影包 |

### 核心瓶頸分析

**瓶頸 1：記憶體（致命）**
```
432M blocks × sizeof(RBlockState) ≈ 432M × 16 bytes = 6.9 GB
→ 直接 OOM，JVM 預設堆 4GB 都不夠
```

**瓶頸 2：快照建構時間**
```
SnapshotBuilder.capture() 遍歷 ServerLevel
432M 次 level.getBlockState() → 數十秒等級，完全不可接受
```

**瓶頸 3：渲染（每幀重繪）**
```
HologramRenderer / StressHeatmapRenderer 使用即時模式
每幀重建 BufferBuilder → 432M 面 × 6 面/block = 2.6B 頂點/幀
→ GPU 直接爆炸
```

**瓶頸 4：BFS 結構分析**
```
UnionFindEngine 快照 BFS 上限 MAX_SNAPSHOT_BLOCKS = 65,536
結構 BFS 上限 structureBfsMaxBlocks = 2,048 (BRConfig)
BFS 時限 BFS_MAX_MS = 50ms
1200×1200 的樓板 = 1.44M blocks 的單一結構
→ 遠超所有 BFS 上限，完全無法分析
```

---

## 1. 第一層優化：稀疏體素資料結構（記憶體解決方案）

### 1.1 參考來源：Embeddium / Sodium 的 Chunk Section 設計

**原理：** Sodium 不會對整個世界做線性陣列，而是以 16³ Section 為單位管理。我們採用更激進的**三層稀疏八叉樹（Sparse Octree）**設計。

### 1.2 實作規劃：`SparseVoxelOctree`（SVO）

```
層級結構：
Level 0 (Root)    : 1200×1200×300 整體範圍
Level 1 (Region)  : 75×75×19 個 16³ Sections = ~107K nodes
Level 2 (Section) : 16×16×16 = 4096 blocks per section
Level 3 (Block)   : 個別 RBlockState
```

**關鍵設計：**

```java
// 新增檔案：com.blockreality.api.physics.sparse.SparseVoxelOctree

public class SparseVoxelOctree {
    // 空氣區段不分配記憶體 → 建築場景通常 80-95% 是空氣
    // 均質區段（同材質）壓縮為單一值

    private final Long2ObjectOpenHashMap<VoxelSection> sections;
    // key = packSectionPos(sx, sy, sz) → long

    static class VoxelSection {
        enum Type { EMPTY, HOMOGENEOUS, HETEROGENEOUS }
        Type type;
        RBlockState homogeneousState;  // HOMOGENEOUS 時使用
        RBlockState[] blocks;          // HETEROGENEOUS 時使用 (4096)
        short nonAirCount;             // 快速跳過空區段
    }
}
```

**記憶體估算（建築場景，假設 10% 非空氣）：**
```
總 Sections: 75 × 75 × 19 = 106,875
非空 Sections (10%): ~10,688
記憶體: 10,688 × 4096 × 16 bytes = 700 MB
→ 對比原始 6.9 GB，節省 90%
```

**對比 RWorldSnapshot 的改造：**

| 舊設計 | 新設計 |
|--------|--------|
| `RBlockState[] blocks` (連續1D) | `Long2ObjectOpenHashMap<VoxelSection>` |
| `startX/Y/Z + sizeX/Y/Z` 邊界盒 | Section-based 稀疏存取 |
| `getBlock(x,y,z)` O(1) 直接索引 | `getBlock(x,y,z)` O(1) hash + offset |
| 固定記憶體 = volume × 16B | 動態記憶體 ∝ 非空體積 |
| `changedPositions` Set | Section-level dirty flags |

### 1.3 快照建構優化：增量式 + 延遲載入

```java
// 新增：IncrementalSnapshotBuilder

// Phase 1: 快速掃描 — 只建立 Section 索引，不讀取 block state
// 利用 Minecraft 的 LevelChunk.getSection() 檢查 isEmpty()
// 時間: O(Section 數量) ≈ 107K 次，< 5ms

// Phase 2: 延遲填充 — 首次存取 Section 時才讀取 block state
// 物理引擎觸及某 Section → 觸發 populateSection()
// 利用 PhysicsExecutor 多執行緒並行填充

// Phase 3: 增量同步 — 只更新 dirty sections
// ServerTickHandler 收集 BlockEvent → 標記 dirty sections
// 下次計算前只重建 dirty sections
```

---

## 2. 第二層優化：階層式物理引擎（計算解決方案）

### 2.1 參考來源：Valkyrien Skies 2 的 Physics World 分離

**VS2 核心思想：** 把物理世界從 Minecraft 世界完全分離，用獨立執行緒循環驅動。

### 2.2 實作規劃：`HierarchicalPhysicsEngine`

**三層分析架構：**

```
┌─────────────────────────────────────────────┐
│ Layer 3: Global Connectivity (每 5 秒)       │
│ ─ Region 級 (16³ Section) 的 Union-Find     │
│ ─ 判斷大型結構的整體連通性                     │
│ ─ 107K nodes → 毫秒級                       │
├─────────────────────────────────────────────┤
│ Layer 2: Section Stress (每 1 秒)            │
│ ─ Section 內部的梁/柱應力粗估                  │
│ ─ Coarse-grained FEM (每 Section = 1 node)  │
│ ─ 10K active sections → 百毫秒級             │
├─────────────────────────────────────────────┤
│ Layer 1: Block Detail (需要時)               │
│ ─ 玩家周圍 / dirty region 的精確分析          │
│ ─ 現有 UnionFindEngine + BeamStressEngine   │
│ ─ 局部 2048-8192 blocks → 50ms 以內         │
└─────────────────────────────────────────────┘
```

**Layer 3：Region-Level Union-Find**

```java
// 新增：RegionConnectivityEngine

// 每個 VoxelSection 視為一個「超級節點」
// Section 之間的邊：如果邊界面有連續方塊則連通
// 複雜度：O(Section 數量) ≈ O(107K) — 可在 < 10ms 完成

// 用途：
// 1. 快速判斷大型結構是否仍連接地面
// 2. 識別「結構島」(structural islands) 做並行分割
// 3. 倒塌事件：整個 island 脫離 → 觸發 Section-level 分析
```

**Layer 2：Coarse FEM Stress**

```java
// 新增：CoarseFEMEngine

// 把每個 Section 視為 FEM 的一個元素
// 材質屬性 = Section 內方塊的加權平均
// 求解 Section 級的應力場：
//   - 每個 Section → 1 個節點 (壓縮 4096:1)
//   - 節點數 ≈ 10K (活躍 Sections)
//   - 稀疏矩陣 CG/PCG 求解 → 100-500ms

// 結果用途：
// 1. StressHeatmapRenderer 的 LOD 顯示
// 2. 識別高應力 Section → 觸發 Layer 1 精確分析
// 3. 全域應力分佈概覽
```

**Layer 1：Detail Analysis（現有引擎升級）**

```java
// 修改：UnionFindEngine

// 移除全域 BFS 限制 (2048 blocks / 50ms)
// 改為 Section-bounded analysis:
//   - 一次分析 1 個 Section (4096 blocks) + 鄰居緩衝
//   - 結果向 Layer 2 彙報
//   - 多個 Section 可並行分析

// 修改：BeamStressEngine / ForceEquilibriumSolver
// 輸入改為 SVO Section 而非 RWorldSnapshot
// SOR 求解器加入 Section 邊界條件
```

### 2.3 並行策略：空間分割 + Work-Stealing

```java
// 新增：SpatialPartitionExecutor

// 1. 將 1200×1200×300 分成 N 個獨立「物理區域」
//    - 利用 Layer 3 的連通性分析
//    - 不連通的結構 → 完全獨立並行
//    - 連通的大結構 → 按 Section column 分割

// 2. ForkJoinPool + RecursiveTask
//    - 每個 Section 的 Layer 1 分析 = 一個 RecursiveTask
//    - Work-stealing 自動負載平衡

// 3. 優先級佇列
//    - 玩家周圍 Section → 高優先級
//    - 剛修改的 Section → 中優先級
//    - 遠處 Section → 低優先級，可跳過
```

---

## 3. 第三層優化：持久化渲染管線（GPU 解決方案）

### 3.1 參考來源：Embeddium / Sodium 的 Chunk Render 設計

**Sodium 核心思想：**
- 每個 Chunk Section 編譯成一個持久化 VBO
- 只在 Section 內容變更時重建該 VBO
- 使用 `glMultiDrawElementsIndirect` 一次繪製所有可見 Section

### 3.2 實作規劃：`PersistentRenderPipeline`

**取代現有即時模式渲染器：**

```java
// 新增：com.blockreality.api.client.render.PersistentRenderPipeline

public class PersistentRenderPipeline {

    // ═══ 核心：每個 Section 一個持久化 VBO ═══

    // Section → GPU Buffer 映射
    private final Long2ObjectOpenHashMap<SectionRenderData> sectionBuffers;

    static class SectionRenderData {
        int vaoId;          // OpenGL VAO
        int vboId;          // OpenGL VBO (頂點資料)
        int iboId;          // OpenGL IBO (索引資料)
        int vertexCount;
        int indexCount;
        boolean dirty;      // 需要重建
        long lastUpdateTick;
    }

    // ═══ 建構流程 ═══

    // 1. Section 變更 → 標記 dirty
    // 2. 背景執行緒：編譯 Section mesh → CPU-side ByteBuffer
    //    - 只處理非空氣面（內部面剔除）
    //    - Greedy Meshing：合併相鄰同材質面 → 減少 60-80% 頂點
    // 3. 主執行緒：glBufferSubData() 上傳 → < 1ms per section

    // ═══ 繪製流程 ═══

    // 每幀：
    // 1. 視錐體剔除 (Frustum Culling) → 排除不可見 Section
    // 2. 遮擋剔除 (Occlusion Culling) → 排除被擋住的 Section
    // 3. glMultiDrawElements() → 一次繪製所有可見 Section
    // 4. 不需要每幀重建任何 buffer！
}
```

### 3.3 Greedy Meshing 演算法

```
原始：每個方塊 6 面 × 4 頂點 = 24 頂點/block
       1M blocks → 24M 頂點 → GPU 爆炸

Greedy Meshing 後：合併相鄰同材質面為大矩形
       典型建築牆面 100 blocks → 1 矩形 4 頂點
       頂點減少 95-99%

演算法：
  for each axis (X, Y, Z):
    for each slice:
      建立 2D mask（哪些面需要繪製）
      掃描合併矩形區域 (greedy expand)
      輸出合併後的 quad
```

### 3.4 渲染器改造清單

| 現有渲染器 | 改造方案 |
|------------|---------|
| `StressHeatmapRenderer` | 改用 Section VBO + per-vertex color attribute |
| `HologramRenderer` | 改用持久化 VBO，只在藍圖移動時重建 |
| `GhostBlockRenderer` | 保持即時模式（數量少，不是瓶頸）|
| `AnchorPathRenderer` | 改用 GL_LINES VBO + instanced drawing |
| `SelectionOverlayRenderer` | 保持即時模式 |
| `TransformGizmoRenderer` | 保持即時模式 |

### 3.5 LOD 渲染策略

```
距離 0-64:   Full detail — 每個 block face
距離 64-256: Section LOD — Greedy meshed sections
距離 256-600: Region LOD — 每 4×4×4 sections 合併
距離 600+:   Impostor — 預渲染 billboard sprite

記憶體分配：
  Full detail VBO pool: 512 MB
  Section LOD pool: 128 MB
  Region LOD pool: 32 MB
  Impostor atlas: 16 MB
  ─────────────────
  Total GPU: ~688 MB (RTX 3060 有 12GB，綽綽有餘)
```

---

## 4. 第四層優化：Sidecar 升級為高效能計算後端

### 4.1 現況

```
SidecarBridge → JSON-RPC over stdio → Node.js
問題：JSON 序列化 432M blocks ≈ 數 GB 文字 → 完全不可行
```

### 4.2 升級方案：二進制協議 + SharedArrayBuffer

**Phase 1：二進制 RPC**

```java
// 修改：SidecarBridge

// 協議升級：JSON-RPC → Custom Binary Protocol
// Header: [4B magic][4B length][4B method_id][4B request_id]
// Body: MessagePack / FlatBuffers 序列化

// 體素資料傳輸：
// 不傳完整 blocks，傳壓縮差分：
//   - RLE 編碼空氣區段
//   - Delta encoding 變更區段
//   - 典型壓縮比 50:1 ~ 100:1
```

**Phase 2：SharedMemory（終極方案）**

```java
// 使用 JNI / Panama FFI → mmap 共享記憶體
// Java 寫入 SVO → Sidecar 直接讀取
// 零拷貝，零序列化
// 延遲 < 1μs（對比 JSON-RPC 的 1-10ms）
```

**Phase 3：可選 Rust/C++ 後端**

```
Node.js Sidecar 適合：NURBS 擬合、幾何邏輯
不適合：大規模矩陣運算、FEM 求解

未來可替換為 Rust 執行檔：
  - nalgebra / ndarray 做矩陣運算
  - rayon 做資料並行
  - 效能提升 10-50x vs Node.js
```

---

## 5. 第五層優化：犧牲相容性的激進策略

### 5.1 設計哲學

```
Block Reality v3.0 不是一個「友好的生態模組」。
它是一個「完全掌控渲染管線的獨立引擎」。

原則：
1. 不讓任何外部模組觸碰我們的渲染管線
2. 不讓任何外部模組影響我們的物理計算
3. 所有需要的功能，直接移植進來統一對接 BR API
4. 與 Forge 事件系統的交互最小化
```

### 5.2 渲染管線隔離

```java
// 新增：BRRenderPipelineManager

// 1. 劫持 RenderLevelStageEvent 的最高優先級
//    EventPriority.HIGHEST + 排除其他模組的渲染注入

// 2. 自訂 RenderType 管線
//    不使用 Minecraft 的 RenderType.translucent()
//    自建 BRRenderType，完全控制 shader / blend / depth state

// 3. 自訂 BufferSource
//    不走 Minecraft 的 MultiBufferSource.BufferSource
//    自建記憶體池，避免與其他模組的 buffer 競爭

// 4. 直接操作 OpenGL
//    繞過 Blaze3D 的抽象層
//    直接 glBindVertexArray / glDrawElements
//    消除 Blaze3D 的 state tracking overhead
```

### 5.3 物理引擎隔離

```java
// 修改：BlockPhysicsEventHandler

// 攔截所有方塊變更事件
// 如果變更來自非 BR 來源 → 記錄但不觸發完整重算
// 只有 BR 自己的操作才觸發 Layer 1-3 分析

// 修改：RBlockEntity
// 不再使用 Forge capability system 暴露資料
// 改用 BR 內部直接存取，省去 capability 查詢開銷
```

### 5.4 需要內建移植的功能

| 功能 | 原本依賴 | 移植方案 |
|------|---------|---------|
| Chunk 渲染優化 | Embeddium | 移植 Section-based VBO 架構 |
| 光影 | Iris/Oculus | 自建光影管線（見第 6 節）|
| 效能監控 | Spark | 內建 profiler HUD |
| 記憶體管理 | ModernFix | 自建 off-heap 記憶體池 |

---

## 6. 第六層：自訂光影管線（BR Shader Pipeline）

### 6.1 為什麼需要自建光影

```
問題：
1. Iris/Oculus 會完全替換渲染管線 → 我們的 VBO 管線會被覆蓋
2. 標準 shader pack 不認識 BR 的自訂頂點屬性（應力值、材質 ID）
3. 我們需要光影「看得懂」建築材質（混凝土 vs 鋼鐵 vs 木材的反射不同）
4. 應力熱圖需要在光影模式下保持可見

解法：自建一個「懂 BR API 的光影管線」
```

### 6.2 架構設計：`BRShaderPipeline`

```
┌──────────────────────────────────────────┐
│         BR Shader Pipeline               │
│                                          │
│  Pass 1: G-Buffer (Deferred Rendering)   │
│  ├─ Albedo + Material ID                 │
│  ├─ Normal + Roughness                   │
│  ├─ Position + Depth                     │
│  ├─ Stress Value (BR 專用通道)            │
│  └─ Block Type ID (BR 專用通道)           │
│                                          │
│  Pass 2: Shadow Map                      │
│  ├─ Cascaded Shadow Maps (4 cascades)    │
│  └─ Contact shadows (screen-space)       │
│                                          │
│  Pass 3: Lighting                        │
│  ├─ PBR Lighting (Cook-Torrance BRDF)    │
│  ├─ Per-material properties:             │
│  │   ├─ Concrete: rough, low specular    │
│  │   ├─ Steel: smooth, high specular     │
│  │   ├─ Glass: transparent, reflective   │
│  │   ├─ Timber: medium rough, warm tone  │
│  │   └─ Brick: textured, medium rough    │
│  ├─ Ambient Occlusion (SSAO/GTAO)       │
│  └─ Screen-Space Reflections (SSR)       │
│                                          │
│  Pass 4: Composite                       │
│  ├─ Stress Heatmap Overlay (可切換)       │
│  ├─ Hologram Glow Effect                 │
│  ├─ Bloom (for steel/glass highlights)   │
│  ├─ Tone Mapping (ACES)                  │
│  └─ Anti-Aliasing (TAA)                  │
└──────────────────────────────────────────┘
```

### 6.3 自訂頂點格式

```java
// 新增：BRVertexFormat

// 標準 Minecraft: POSITION(3f) + COLOR(4B) + UV(2f) + LIGHT(2s) + NORMAL(3B)
// BR 擴展:       + STRESS(1f) + MATERIAL_ID(1i) + BLOCK_META(1i)

// Vertex stride: 32 bytes (standard) + 12 bytes (BR) = 44 bytes
// 透過 vertex attribute pointer 傳遞給 shader

// Shader 端：
// layout(location = 5) in float v_stress;
// layout(location = 6) in int v_materialId;
// layout(location = 7) in int v_blockMeta;
```

### 6.4 材質感知 PBR

```glsl
// fragment shader 片段

uniform sampler2D materialLUT;  // 材質查找表紋理

struct MaterialPBR {
    vec3 albedo;
    float roughness;
    float metallic;
    float emission;
    float subsurface;    // 木材的次表面散射
    float clearcoat;     // 玻璃的透明塗層
};

MaterialPBR getMaterialPBR(int materialId) {
    // 從 LUT 紋理讀取，每個材質 1 行像素
    vec4 row0 = texelFetch(materialLUT, ivec2(0, materialId), 0);
    vec4 row1 = texelFetch(materialLUT, ivec2(1, materialId), 0);
    // ... 解碼為 MaterialPBR
}

// 應力視覺化（光影模式下）
vec3 applyStressOverlay(vec3 color, float stress, bool showStress) {
    if (!showStress) return color;
    vec3 stressColor = mix(
        vec3(0.0, 0.0, 1.0),  // blue (safe)
        mix(vec3(1.0, 1.0, 0.0), vec3(1.0, 0.0, 0.0),
            smoothstep(0.3, 0.7, stress)),  // yellow→red
        smoothstep(0.0, 0.3, stress)
    );
    return mix(color, stressColor, 0.4);  // 40% overlay blend
}
```

---

## 7. 實施階段與時程

### Phase 1：資料層重構（基礎）

```
目標：SVO 替換 RWorldSnapshot
涉及檔案：
  - 新增 SparseVoxelOctree.java
  - 新增 VoxelSection.java
  - 修改 SnapshotBuilder.java (capture() 方法) → IncrementalSnapshotBuilder.java
  - 修改 UnionFindEngine.java（適配 SVO 介面）
  - 修改 PhysicsExecutor.java（Section-based 任務分割）
  - 修改 BRConfig.java（新增 SVO 相關配置）

測試：
  - 現有 18 個測試類別全通過（介面相容）
  - 新增 SVO 正確性測試（get/set/iterate）
  - 新增記憶體用量基準測試

產出：能承載 1200×1200×300 的稀疏世界快照，記憶體 < 1GB
```

### Phase 2：階層式物理引擎

```
目標：三層物理分析架構
涉及檔案：
  - 新增 RegionConnectivityEngine.java (Layer 3)
  - 新增 CoarseFEMEngine.java (Layer 2)
  - 修改 UnionFindEngine.java (Layer 1 Section-bounded)
  - 修改 BeamStressEngine.java (Section 介面)
  - 修改 ForceEquilibriumSolver.java (Section 邊界條件)
  - 新增 SpatialPartitionExecutor.java
  - 修改 ResultApplicator.java (Section-based batch apply)

測試：
  - 模擬 1200×1200 樓板的結構分析
  - 驗證 Layer 3→2→1 的一致性
  - 效能基準：全域分析 < 2 秒

產出：能分析 432M block 範圍的結構完整性
```

### Phase 3：持久化渲染管線

```
目標：Section VBO + Greedy Meshing + LOD
涉及檔案：
  - 新增 PersistentRenderPipeline.java
  - 新增 SectionMeshCompiler.java (Greedy Meshing)
  - 新增 BRRenderType.java
  - 新增 FrustumCuller.java
  - 修改 StressHeatmapRenderer.java → SectionStressRenderer.java
  - 修改 HologramRenderer.java → PersistentHologramRenderer.java
  - 修改 AnchorPathRenderer.java (instanced drawing)

測試：
  - 視覺正確性（截圖比對）
  - 效能基準：60 FPS @ 1200×1200×300 可見範圍
  - GPU 記憶體使用 < 1GB

產出：GPU-efficient 的持久化渲染，支援全範圍 LOD
```

### Phase 4：Sidecar 升級

```
目標：二進制協議 + 共享記憶體
涉及檔案：
  - 修改 SidecarBridge.java (BinaryRPC)
  - 修改 sidecar.ts (binary protocol handler)
  - 新增 SharedMemoryBridge.java (Panama FFI)
  - 新增 VoxelSerializer.java (RLE + delta encoding)

測試：
  - 傳輸 10M blocks 的延遲 < 100ms
  - 共享記憶體正確性驗證

產出：Java ↔ Sidecar 的高效資料通道
```

### Phase 5：光影管線

```
目標：BR-aware deferred rendering + PBR
涉及檔案：
  - 新增 BRShaderPipeline.java
  - 新增 shaders/gbuffer.vsh / .fsh
  - 新增 shaders/shadow.vsh / .fsh
  - 新增 shaders/lighting.vsh / .fsh
  - 新增 shaders/composite.vsh / .fsh
  - 新增 BRVertexFormat.java
  - 新增 MaterialLUTGenerator.java
  - 修改所有渲染器適配新管線

測試：
  - 材質正確反射（混凝土 vs 鋼鐵 vs 玻璃）
  - 應力熱圖在光影模式下正確顯示
  - 效能基準：40 FPS @ 1080p + 全光影

產出：建築級光影渲染，材質感知，應力視覺化整合
```

### Phase 6：極限調校

```
目標：最後 20% 的效能榨取
內容：
  - SIMD 向量化（Vector API / JNI）用於 BFS 和矩陣運算
  - Off-heap 記憶體池（Unsafe / Panama）減輕 GC 壓力
  - GPU Compute Shader 做並行 Union-Find（如果 OpenGL 4.3 可用）
  - Profile-guided 的 JIT 暖機路徑
  - Section 級 LOD 的自適應調整

測試：
  - 壓力測試 72 小時連續運行
  - GC 暫停 < 10ms (ZGC)
  - 總記憶體 < 4GB
```

---

## 8. 技術風險與緩解策略

| 風險 | 嚴重度 | 緩解策略 |
|------|--------|---------|
| OpenGL 直接操作與 Forge 衝突 | 高 | 用 `GL11.glPushAttrib/PopAttrib` 保護狀態；在 BR 渲染前後完整保存/恢復 GL state |
| SVO 記憶體碎片化 | 中 | Section 使用 pool allocator；定期壓縮空 Section |
| Greedy Meshing 的 seam artifacts | 中 | Section 邊界擴展 1 block 重疊區域 |
| 光影效能不足 | 高 | 提供品質級別選項（Low/Medium/High/Ultra）；Low 模式只做陰影不做 SSR |
| 多執行緒物理的 race condition | 高 | Section 級鎖 + 不可變快照設計已經防止大部分問題 |
| 與 Forge mod loader 更新不相容 | 低 | 我們不依賴 Forge 渲染 API，只用事件鉤子，耦合極低 |

---

## 9. 效能預期對比

| 場景 | v2.0 (現況) | v3.0 (預期) |
|------|-------------|-------------|
| 最大分析範圍 | 40×40×40 (64K blocks) | 1200×1200×300 (432M blocks) |
| 快照建構時間 | 5-50ms (全量) | 1-5ms (增量) |
| 結構分析時間 | 50ms (2048 blocks) | 2s (全域 Layer 3) + 50ms (局部 Layer 1) |
| 應力計算時間 | 50ms (64K blocks) | 500ms (Coarse FEM) + 50ms (精確 Section) |
| 渲染 FPS | 60 FPS @ 1K blocks | 60 FPS @ 100K 可見 blocks (LOD) |
| GPU 記憶體 | < 10 MB | 500 MB - 1 GB |
| 系統記憶體 | 200 MB | 1-2 GB |
| 光影 FPS | N/A | 40-60 FPS @ 1080p |

---

## 10. 參考模組借鑒清單

| 模組 | 借鑒內容 | 移植方式 |
|------|---------|---------|
| **Embeddium/Sodium** | Section VBO 架構、Greedy Meshing、Frustum/Occlusion Culling | 重寫核心邏輯，對接 BR API |
| **Valkyrien Skies 2** | Physics World 分離、多執行緒物理循環 | 參考架構設計，自建 HierarchicalPhysicsEngine |
| **Iris Shaders** | Deferred rendering pass 結構、Shadow mapping | 參考 pass 設計，自建材質感知光影 |
| **ModernFix** | Off-heap 記憶體管理、啟動優化 | 移植記憶體池到 SVO 層 |
| **Distant Horizons** | LOD 生成演算法、遠景渲染 | 參考 LOD 策略，整合到 Section 渲染 |
| **C²ME (Concurrent Chunk Management)** | 並行 chunk 操作、執行緒安全 chunk 存取 | 參考並行模式，用於 IncrementalSnapshotBuilder |

### 推薦論文/資源

| 資源 | 用途 |
|------|------|
| 《Minecraft Clone in Rust and Vulkan》碩士論文 | 體素資料結構 + 多執行緒渲染 |
| 《Efficient Sparse Voxel Octrees》(Laine & Karras, 2010) | SVO 壓縮與遍歷 |
| 《A Survey on Bounding Volume Hierarchies for Ray Tracing》 | 空間加速結構 |
| Sodium 源碼 `src/main/java/me/jellysquid/mods/sodium/client/render/chunk/` | Section VBO 實作細節 |
| VS2 源碼 `common/src/main/kotlin/org/valkyrienskies/mod/common/` | Physics World 分離架構 |

---

## 11. 結論

Block Reality v3.0 的優化策略可以總結為一句話：

> **用稀疏資料結構解決記憶體問題，用階層式分析解決計算問題，用持久化 VBO 解決渲染問題，用自建光影解決視覺問題。犧牲一切外部相容性，換取對 1200×1200×300 範圍的完全掌控。**

這不是漸進式優化，而是架構級重寫。但由於 v2.0 的 Snapshot Layer 解耦設計已經為此鋪好了路——計算層不知道 Minecraft 的存在，這讓我們可以把 `RWorldSnapshot` 換成 `SparseVoxelOctree` 而不影響上層邏輯。

最終，Block Reality 將不再是一個「Minecraft 模組」，而是一個「寄生在 Minecraft 上的獨立結構工程引擎」，擁有自己的資料結構、物理引擎、渲染管線、和光影系統。
