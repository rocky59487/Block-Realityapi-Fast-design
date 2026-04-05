# Block Reality — Vulkan Ray Tracing 技術研究與設計文件

**狀態：** 研究階段（非最終設計）
**日期：** 2026-03-28
**前提：** 現有管線為 OpenGL 3.3+ Tier 0 後處理疊加架構，42 子系統

---

## 1. 可行性分析

### 1.1 Minecraft Forge + Vulkan 共存

Minecraft 1.20.1 使用 LWJGL 3，其中**已包含完整的 Vulkan 綁定**（`org.lwjgl.vulkan.*`）。
Forge mod 可以直接呼叫 Vulkan API，無需額外依賴。

**關鍵限制：**
- Minecraft 的主渲染迴圈使用 OpenGL，**不能直接替換**
- 需要 GL/VK 互操作（interop）或完全獨立的渲染路徑
- Forge 沒有提供 Vulkan hook，需要 Mixin 注入

### 1.2 先例：Radiance Mod

Radiance（by spiralhalo）是目前唯一在 Minecraft 中實現 Vulkan RT 的 mod：
- **方案：** 完全替換 Minecraft 的 OpenGL 管線為 Vulkan
- **代價：** 與所有 shader mod（Iris/OptiFine）不相容
- **技術：** 使用 LWJGL Vulkan 綁定，自建 swapchain + render pass
- **RT 支援：** VK_KHR_ray_tracing_pipeline + hardware BLAS/TLAS

### 1.3 硬體需求

| 功能 | 最低 GPU | Vulkan Extension |
|------|---------|-----------------|
| Vulkan 基礎 | GTX 600+ | Vulkan 1.0 |
| RT Pipeline | RTX 2060 | VK_KHR_ray_tracing_pipeline |
| Acceleration Structure | RTX 2060 | VK_KHR_acceleration_structure |
| Ray Query (inline RT) | RTX 2060 / RX 6600 | VK_KHR_ray_query |

---

## 2. 架構選項

### Option A：完全 Vulkan 替換

替換 Minecraft 的整個 OpenGL 管線為 Vulkan。

```
Minecraft World Data → [Vulkan Pipeline] → Swapchain → Screen
                         ↓
                    BLAS/TLAS → RT Shaders → Denoiser → Composite
```

| 優點 | 缺點 |
|------|------|
| 完全控制渲染管線 | 工作量巨大（6-12 個月） |
| 最佳效能 | 與 Iris/OptiFine 不相容 |
| 原生 RT 支援 | 需重寫所有 42 個子系統 |

**評估：不推薦。** 與現有架構衝突太大。

### Option B：混合 GL+VK（推薦）

保留現有 OpenGL 管線，僅用 Vulkan 執行 RT pass，透過 GL/VK interop 共享紋理。

```
[OpenGL Pipeline (現有 42 子系統)]
    ↓ GL_EXT_memory_object
    ↓ export GBuffer depth + normal + albedo
[Vulkan RT Pass]
    ↓ BLAS/TLAS → Ray Generation → Hit/Miss
    ↓ SVGF Denoise
    ↓ export RT result texture
    ↓ VK_KHR_external_memory → GL texture
[OpenGL Composite]
    ↓ 混合 RT 結果到現有後處理鏈
```

**GL/VK Interop 關鍵 API：**
```java
// Vulkan 端：export VkImage 為 external memory
VkExternalMemoryImageCreateInfo extInfo = VkExternalMemoryImageCreateInfo.calloc()
    .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
    .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);

// OpenGL 端：import 為 GL texture
int memObj = GL11.glGenTextures(); // placeholder
EXTMemoryObject.glImportMemoryFdEXT(memObj, size,
    GL_HANDLE_TYPE_OPAQUE_FD_EXT, fd);
```

| 優點 | 缺點 |
|------|------|
| 增量開發，不破壞現有管線 | Interop 有同步開銷 |
| 保持 Tier 0/1/2 相容性 | 需要 Nvidia/AMD 驅動支援 interop |
| RT 作為可選疊加層 | 較複雜的資源管理 |

**評估：推薦。** 與 BRRenderTier 配合，Tier 3 時啟用 RT pass。

### Option C：OpenGL Compute Shader RT（軟體 RT）

在 GL 4.3 compute shader 中手動實現 BVH 遍歷和光線追蹤。

```glsl
// Compute shader pseudo-code
layout(local_size_x = 8, local_size_y = 8) in;

struct Ray { vec3 origin, direction; };
struct BVHNode { vec4 minBound, maxBound; int leftChild, rightChild, primitiveIndex; };

layout(std430, binding = 0) readonly buffer BVH { BVHNode nodes[]; };
layout(rgba16f, binding = 0) uniform writeonly image2D rtOutput;

bool intersectAABB(Ray ray, vec3 bmin, vec3 bmax, out float tmin) { ... }

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    Ray ray = generateCameraRay(pixel);

    // BVH stack traversal
    int stack[32]; int stackPtr = 0;
    stack[stackPtr++] = 0; // root

    float closestT = 1e30;
    int hitPrimitive = -1;

    while (stackPtr > 0) {
        int nodeIdx = stack[--stackPtr];
        BVHNode node = nodes[nodeIdx];

        float tmin;
        if (!intersectAABB(ray, node.minBound.xyz, node.maxBound.xyz, tmin)) continue;
        if (tmin > closestT) continue;

        if (node.primitiveIndex >= 0) {
            // Leaf — test primitive
            closestT = tmin;
            hitPrimitive = node.primitiveIndex;
        } else {
            stack[stackPtr++] = node.leftChild;
            stack[stackPtr++] = node.rightChild;
        }
    }

    vec4 color = (hitPrimitive >= 0) ? shadePrimitive(ray, hitPrimitive, closestT) : vec4(0);
    imageStore(rtOutput, pixel, color);
}
```

| 優點 | 缺點 |
|------|------|
| 無 Vulkan 依賴 | 比硬體 RT 慢 10-50x |
| GL 4.3 即可（Tier 1+） | 僅適合簡單效果（shadow, AO） |
| 可立即開始實作 | GI 不可行（太慢） |

**評估：作為 Tier 1-2 的 fallback RT 方案。**

---

## 3. BVH 設計（Minecraft 體素世界）

### 3.1 層級結構

```
Scene TLAS (Top-Level Acceleration Structure)
├── Chunk Section BLAS (16x16x16) × N
│   └── Greedy Meshed AABBs (from GreedyMesher output)
├── Entity BLAS × M
│   └── Per-entity triangle mesh
└── Block Entity BLAS × K
    └── Custom geometry (chisel shapes etc.)
```

### 3.2 BLAS 建構策略

**從 GreedyMesher 輸出：**
- 每個合併的面 → 1 個 AABB primitive
- 典型 16x16x16 section: 100-500 AABBs（比 4096 原始方塊少 90%+）
- BLAS 建構時間: ~0.1ms per section (hardware accelerated)

**增量更新：**
- Block change → mark section dirty
- Dirty section → rebuild BLAS (async, 1-2 frames delay)
- TLAS → update instance transform only (O(1) per change)

### 3.3 記憶體預算

| 項目 | 預估 VRAM |
|------|----------|
| BLAS per section | ~50 KB |
| 1024 block 視距 (4096 sections) | ~200 MB BLAS |
| TLAS | ~1 MB |
| RT output texture (1080p) | ~16 MB |
| Denoiser buffers | ~64 MB |
| **總計** | **~280 MB** |

---

## 4. RT 效果優先級

### 4.1 RT Shadows（Phase RT-1，最高優先）

取代級聯陰影（CSM），消除 cascade 接縫和 peter-panning。

**Ray Generation Shader：**
```glsl
// 每像素發射 1 根 shadow ray 到光源
rayQueryEXT rq;
rayQueryInitializeEXT(rq, tlas, gl_RayFlagsTerminateOnFirstHitEXT,
    0xFF, worldPos, 0.001, lightDir, lightDistance);
rayQueryProceedEXT(rq);
float shadow = (rayQueryGetIntersectionTypeEXT(rq, true) ==
    gl_RayQueryCommittedIntersectionNoneEXT) ? 1.0 : 0.0;
```

**效能：** ~0.5ms @ 1080p, 1 spp, RTX 3060

### 4.2 RT Reflections（Phase RT-2）

取代 SSR，支援離屏反射和多次彈射。

**效能：** ~1.0ms @ 1080p, 1 spp

### 4.3 RT Ambient Occlusion（Phase RT-3）

取代 GTAO/SSAO，精確近距離遮蔽。

**效能：** ~0.3ms @ 1080p, 1 spp

### 4.4 RT Global Illumination（Phase RT-4）

取代 VCT，完整路徑追蹤。最昂貴但視覺提升最大。

**效能：** ~3-5ms @ 1080p, 1 spp + SVGF denoise

---

## 5. SVGF 降噪器設計

1 spp（每像素一根光線）的 RT 結果極度噪聲，需要時空降噪。

### 5.1 管線

```
RT Raw (1 spp, noisy)
  → Temporal Accumulation（使用 motion vector 重投影歷史幀）
  → Variance Estimation（估算局部方差）
  → Spatial Filter（5 次 à-trous wavelet filter，edge-stopping）
  → Output (denoised)
```

### 5.2 Edge-Stopping 函數

```glsl
float w_depth = exp(-abs(depth_center - depth_neighbor) / (sigma_depth + 1e-6));
float w_normal = pow(max(0, dot(n_center, n_neighbor)), sigma_normal);
float w_luminance = exp(-abs(lum_center - lum_neighbor) / (sigma_luminance * sqrt(variance) + 1e-6));
float weight = w_depth * w_normal * w_luminance;
```

---

## 6. 實作路線圖

| 階段 | 內容 | 預估複雜度 | 依賴 |
|------|------|-----------|------|
| **RT-0** | Vulkan 裝置初始化 + GL/VK interop 驗證 | 高（3-4 週） | LWJGL Vulkan |
| **RT-1** | BVH 建構（BLAS from GreedyMesher, TLAS scene） | 高（3-4 週） | RT-0 |
| **RT-2** | RT Shadows（ray query in compute/fragment） | 中（2-3 週） | RT-1 |
| **RT-3** | RT Reflections | 中（2-3 週） | RT-1 |
| **RT-4** | RTAO | 中（1-2 週） | RT-1 |
| **RT-5** | SVGF Denoiser | 高（3-4 週） | RT-2/3/4 |
| **RT-6** | RT GI（path tracing） | 很高（4-6 週） | RT-5 |
| **RT-C** | Option C: Compute Shader RT fallback | 中（2-3 週） | BRGPUCulling |

**總計：** 約 18-26 週（Option B 完整路徑）
**建議：** 先實作 RT-0 + RT-1 + RT-2（shadow），驗證架構可行後再擴展。

---

## 7. 與現有架構的整合點

```
BRRenderTier.Tier.TIER_3 啟用時：
  AFTER_SOLID_BLOCKS:
    └─ BRVulkanRT.updateTLAS()          ← 增量更新場景
  AFTER_TRANSLUCENT_BLOCKS:
    └─ BRVulkanRT.traceRays()           ← dispatch RT
    └─ BRVulkanRT.denoise()             ← SVGF
    └─ BRVulkanRT.exportToGL()          ← interop 匯出
    └─ executeCompositeChain()          ← 現有後處理使用 RT 結果
```

**需要新增的類：**
- `BRVulkanDevice.java` — VkInstance/Device/Queue 管理
- `BRVulkanRT.java` — RT pipeline, SBT, dispatch
- `BRVulkanBVH.java` — BLAS/TLAS 建構與更新
- `BRVulkanInterop.java` — GL/VK 紋理共享
- `BRSVGFDenoiser.java` — 時空降噪器

---

## 8. 關鍵參考資料

- VK_KHR_ray_tracing_pipeline specification
- NVIDIA Vulkan Ray Tracing Tutorial (vk_raytracing_tutorial_KHR)
- LWJGL Vulkan bindings documentation
- SVGF: "Spatiotemporal Variance-Guided Filtering" (Schied et al., HPG 2017)
- Radiance mod architecture (spiralhalo)
- SEUS PTGI (screen-space path tracing in OpenGL — Option C reference)
- "Ray Tracing Gems" Chapter 25: Hybrid Rendering

---

## 9. 建議決策

**短期（1-2 個月）：** 實作 Option C（Compute Shader RT）作為 Tier 1-2 的軟體 RT fallback。
可立即獲得 RT shadows 和 RTAO，不需 Vulkan。

**中期（3-6 個月）：** 實作 Option B Phase RT-0 ~ RT-2（Vulkan 初始化 + BVH + RT shadows）。
驗證 GL/VK interop 架構。

**長期（6-12 個月）：** 完成 Option B 全部階段（reflections, GI, SVGF）。
