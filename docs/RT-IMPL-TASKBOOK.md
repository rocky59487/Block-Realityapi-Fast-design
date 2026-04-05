# Block Reality — 光影完整實作任務書

> 目標：讓 RTX 5070 Ti（Blackwell SM 10.x）在 Minecraft Forge 1.20.1 中輸出
> 真實 Vulkan Ray Tracing 畫面，達到商業級 RADIANCE 品質。
> 起點：Phase 6 完成（VkImage 建好、管道接通，但 GPU 從未實際執行指令）。

---

## 封鎖點總覽

```
現在的問題不是架構，是執行層全是 stub：

BRVulkanDevice.beginSingleTimeCommands()  → no-op，return 0
BRVulkanDevice.cmdBindPipeline()          → no-op
BRVulkanDevice.cmdTraceRaysKHR()          → no-op
BRVulkanDevice.createRayTracingPipeline() → return 0L
BRVulkanDevice.compileGLSLtoSPIRV()       → return new byte[0]
BRVulkanDevice.createBuffer()             → return 0L
BRVulkanDevice.updateRTDescriptorSet()    → no-op

結果：readbackBuffer 永遠是空白幀，螢幕看不到光影。
```

---

## 階段規劃

| 階段 | 內容 | 封鎖程度 | 預估行數 |
|------|------|---------|---------|
| **P7-A** | SPIR-V 載入（shaderc 依賴 + 執行期編譯） | 🔴 最高 | ~80 |
| **P7-B** | Command buffer 核心（begin / end / submit） | 🔴 最高 | ~120 |
| **P7-C** | Buffer 分配（createBuffer / allocateAndBind） | 🔴 最高 | ~100 |
| **P7-D** | RT Pipeline 建立（vkCreateRayTracingPipelinesKHR） | 🔴 最高 | ~150 |
| **P7-E** | Descriptor set 更新（TLAS + image + UBO） | 🔴 最高 | ~120 |
| **P7-F** | cmdBindPipeline / cmdBindDescriptorSets / cmdTraceRaysKHR | 🔴 最高 | ~60 |
| **P7-G** | SBT 建立（getBufferDeviceAddress + handle copy） | 🟠 高 | ~80 |
| **P7-H** | VkRTAO dispatchAO 解注釋 + compute pipeline | 🟠 高 | ~100 |
| **P7-I** | 煙霧測試 + 第一幀驗證 | — | — |

完成 P7-A～F 後應有第一幀非黑色光影輸出。
完成 P7-A～H 後可達到 RTAO + 陰影 + 反射完整品質。

---

## P7-A：SPIR-V 載入（最先做）

### 問題
`compileGLSLtoSPIRV()` 回傳空 `byte[]`，導致所有 shader module 建立失敗，
RT pipeline 拿到的全是 null shader handle，`createRayTracingPipeline()` 必然失敗。

### 解法：加入 LWJGL shaderc + 執行期 GLSL→SPIR-V 編譯

#### 步驟 1：加依賴

**檔案：** `Block Reality/api/build.gradle`

在 `implementation "org.lwjgl:lwjgl-vulkan:3.3.5"` 後面加：

```gradle
implementation "org.lwjgl:lwjgl-shaderc:3.3.5"
runtimeOnly "org.lwjgl:lwjgl-shaderc:3.3.5:natives-windows"
runtimeOnly "org.lwjgl:lwjgl-shaderc:3.3.5:natives-linux"
runtimeOnly "org.lwjgl:lwjgl-shaderc:3.3.5:natives-macos"
```

#### 步驟 2：實作 compileGLSLtoSPIRV

**檔案：** `BRVulkanDevice.java`
**方法：** `compileGLSLtoSPIRV(String glslSource, String name)` → 回傳 `byte[]`

加 import：
```java
import org.lwjgl.util.shaderc.Shaderc;
import static org.lwjgl.util.shaderc.Shaderc.*;
```

實作（取代現有 stub）：
```java
public static byte[] compileGLSLtoSPIRV(String glslSource, String name) {
    long compiler = shaderc_compiler_initialize();
    if (compiler == 0L) {
        LOGGER.error("[SPIR-V] shaderc_compiler_initialize failed");
        return new byte[0];
    }
    long options = shaderc_compile_options_initialize();
    shaderc_compile_options_set_target_env(options,
        shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
    shaderc_compile_options_set_optimization_level(options,
        shaderc_optimization_level_performance);

    // 根據副檔名判斷 shader 類型
    int kind = shadercKindFromName(name);

    long result = shaderc_compile_into_spv(compiler,
        glslSource, kind, name, "main", options);
    shaderc_compile_options_release(options);
    shaderc_compiler_release(compiler);

    int status = shaderc_result_get_compilation_status(result);
    if (status != shaderc_compilation_status_success) {
        LOGGER.error("[SPIR-V] {} compile error: {}", name,
            shaderc_result_get_error_message(result));
        shaderc_result_release(result);
        return new byte[0];
    }

    long spvPtr  = shaderc_result_get_bytes(result);
    long spvSize = shaderc_result_get_length(result);
    byte[] spv = new byte[(int) spvSize];
    MemoryUtil.memByteBuffer(spvPtr, (int) spvSize).get(spv);
    shaderc_result_release(result);
    LOGGER.info("[SPIR-V] {} compiled OK ({} bytes)", name, spvSize);
    return spv;
}

private static int shadercKindFromName(String name) {
    if (name.endsWith(".rgen")) return shaderc_raygen_shader;
    if (name.endsWith(".rmiss"))return shaderc_miss_shader;
    if (name.endsWith(".rchit"))return shaderc_closesthit_shader;
    if (name.endsWith(".rahit"))return shaderc_anyhit_shader;
    if (name.endsWith(".comp")) return shaderc_compute_shader;
    if (name.endsWith(".vert")) return shaderc_vertex_shader;
    if (name.endsWith(".frag")) return shaderc_fragment_shader;
    return shaderc_glsl_infer_from_source;
}
```

#### 步驟 3：實作 createShaderModule

**方法：** `createShaderModule(long device, byte[] spv)` → 回傳 `long`（VkShaderModule handle）

```java
public static long createShaderModule(long device, byte[] spv) {
    if (spv == null || spv.length == 0) return 0L;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        ByteBuffer spvBuf = stack.malloc(spv.length).put(spv).flip();
        VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
            .pCode(spvBuf);
        LongBuffer pModule = stack.mallocLong(1);
        int r = vkCreateShaderModule(vkDeviceObj, info, null, pModule);
        if (r != VK_SUCCESS) {
            LOGGER.error("[ShaderModule] vkCreateShaderModule failed: {}", r);
            return 0L;
        }
        return pModule.get(0);
    }
}
```

**注意：** `BRVulkanRT.init()` 已呼叫 `compileGLSLtoSPIRV` + `createShaderModule`，
這兩個方法實作完後 shader 編譯鏈自動接通。

---

## P7-B：Command Buffer 核心

### 問題
`beginSingleTimeCommands()` 回傳 0，`cmdTraceRaysKHR()` 對 null handle 操作 → crash 或 no-op。

### 實作

**檔案：** `BRVulkanDevice.java`

加 import（如未加）：
```java
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
```

#### beginSingleTimeCommands

```java
public static long beginSingleTimeCommands(long device) {
    if (!initialized || vkDeviceObj == null) return 0L;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1);
        PointerBuffer pCb = stack.mallocPointer(1);
        if (vkAllocateCommandBuffers(vkDeviceObj, allocInfo, pCb) != VK_SUCCESS) {
            LOGGER.error("[CMD] vkAllocateCommandBuffers failed");
            return 0L;
        }
        VkCommandBuffer cb = new VkCommandBuffer(pCb.get(0), vkDeviceObj);
        vkBeginCommandBuffer(cb,
            VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
        return pCb.get(0); // 回傳 raw handle
    } catch (Exception e) {
        LOGGER.error("[CMD] beginSingleTimeCommands failed", e);
        return 0L;
    }
}
```

#### endSingleTimeCommands

```java
public static void endSingleTimeCommands(long device, long cmdHandle) {
    if (!initialized || vkDeviceObj == null || cmdHandle == 0L) return;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        VkCommandBuffer cb = new VkCommandBuffer(cmdHandle, vkDeviceObj);
        vkEndCommandBuffer(cb);

        VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pCommandBuffers(stack.pointers(cb));
        vkQueueSubmit(vkQueueObj, submit, VK_NULL_HANDLE);
        vkQueueWaitIdle(vkQueueObj);
        vkFreeCommandBuffers(vkDeviceObj, commandPool, cb);
    } catch (Exception e) {
        LOGGER.error("[CMD] endSingleTimeCommands failed", e);
    }
}
```

---

## P7-C：Buffer 分配

### 問題
SBT buffer、Camera UBO、staging buffer 的建立全都回傳 0L。
`BRVulkanRT.init()` 的 SBT 建立路徑全部失敗。

### 實作

**檔案：** `BRVulkanDevice.java`

#### createBuffer

```java
/**
 * 建立 VkBuffer，不分配記憶體。
 * @param size  位元組大小
 * @param usage VkBufferUsageFlags（例如 SHADER_BINDING_TABLE | SHADER_DEVICE_ADDRESS）
 * @return VkBuffer handle，或 0L 表示失敗
 */
public static long createBuffer(long device, long size, int usage) {
    if (!initialized || vkDeviceObj == null) return 0L;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        LongBuffer pBuf = stack.mallocLong(1);
        int r = vkCreateBuffer(vkDeviceObj,
            VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
            null, pBuf);
        if (r != VK_SUCCESS) {
            LOGGER.error("[Buffer] vkCreateBuffer failed: {}", r);
            return 0L;
        }
        return pBuf.get(0);
    }
}
```

#### allocateAndBindBuffer

```java
/**
 * 為已建立的 buffer 分配並綁定 VkDeviceMemory。
 * @param buffer   VkBuffer handle（來自 createBuffer）
 * @param memProps 所需記憶體屬性（例如 DEVICE_LOCAL 或 HOST_VISIBLE | HOST_COHERENT）
 * @return VkDeviceMemory handle，或 0L 表示失敗
 */
public static long allocateAndBindBuffer(long device, long buffer, int memProps) {
    if (!initialized || vkDeviceObj == null || buffer == 0L) return 0L;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(vkDeviceObj, buffer, reqs);

        int typeIdx = findMemoryType(reqs.memoryTypeBits(), memProps);
        if (typeIdx < 0) {
            LOGGER.error("[Buffer] findMemoryType failed (filter={}, props={})",
                reqs.memoryTypeBits(), memProps);
            return 0L;
        }

        // 若 usage 包含 SHADER_DEVICE_ADDRESS，需要 VkMemoryAllocateFlagsInfo
        VkMemoryAllocateFlagsInfo flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
            .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);

        LongBuffer pMem = stack.mallocLong(1);
        int r = vkAllocateMemory(vkDeviceObj,
            VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(flagsInfo.address())
                .allocationSize(reqs.size())
                .memoryTypeIndex(typeIdx),
            null, pMem);
        if (r != VK_SUCCESS) {
            LOGGER.error("[Buffer] vkAllocateMemory failed: {}", r);
            return 0L;
        }
        vkBindBufferMemory(vkDeviceObj, buffer, pMem.get(0), 0L);
        return pMem.get(0);
    }
}
```

#### getBufferDeviceAddress

```java
/**
 * 取得 buffer 的 GPU 裝置位址（用於 SBT region 計算）。
 * 需要 buffer 建立時包含 VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT。
 */
public static long getBufferDeviceAddress(long device, long buffer) {
    if (!initialized || vkDeviceObj == null || buffer == 0L) return 0L;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        return vkGetBufferDeviceAddress(vkDeviceObj,
            VkBufferDeviceAddressInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                .buffer(buffer));
    }
}
```

**注意：** `vkGetBufferDeviceAddress` 在 VK12 static import 中（已在 imports 清單內）。

---

## P7-D：RT Pipeline 建立

### 問題
`createRayTracingPipeline()` / `createRayTracingPipelineWithAnyHit()` 回傳 0L。
`BRVulkanRT.init()` 後 `rtPipeline == 0L`，`traceRays()` dispatch 沒有 pipeline 可綁定。

### 實作

**檔案：** `BRVulkanDevice.java`
加 import：`import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;`（已存在）

#### createRayTracingPipelineWithAnyHit（主要路徑）

```java
public static long createRayTracingPipelineWithAnyHit(long device,
        long layout, long rgen, long miss, long chit, long ahit, int maxRecursion) {
    if (!initialized || vkDeviceObj == null) return 0L;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        // 4 個 shader stage：RAYGEN, MISS, CLOSEST_HIT, ANY_HIT
        VkPipelineShaderStageCreateInfo.Buffer stages =
            VkPipelineShaderStageCreateInfo.calloc(4, stack);

        long entryPoint = stack.UTF8("main");

        stages.get(0)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR)
            .module(rgen).pName(entryPoint);
        stages.get(1)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_MISS_BIT_KHR)
            .module(miss).pName(entryPoint);
        stages.get(2)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR)
            .module(chit).pName(entryPoint);
        stages.get(3)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_ANY_HIT_BIT_KHR)
            .module(ahit).pName(entryPoint);

        // 3 個 shader group：GENERAL(raygen) | GENERAL(miss) | TRIANGLES_HIT(chit+ahit)
        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
            VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);

        groups.get(0) // raygen
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(0)
            .closestHitShader(VK_SHADER_UNUSED_KHR)
            .anyHitShader(VK_SHADER_UNUSED_KHR)
            .intersectionShader(VK_SHADER_UNUSED_KHR);
        groups.get(1) // miss
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(1)
            .closestHitShader(VK_SHADER_UNUSED_KHR)
            .anyHitShader(VK_SHADER_UNUSED_KHR)
            .intersectionShader(VK_SHADER_UNUSED_KHR);
        groups.get(2) // hit group（chit index=2, ahit index=3）
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
            .generalShader(VK_SHADER_UNUSED_KHR)
            .closestHitShader(2)
            .anyHitShader(3)
            .intersectionShader(VK_SHADER_UNUSED_KHR);

        VkRayTracingPipelineCreateInfoKHR.Buffer pipelineInfo =
            VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
        pipelineInfo.get(0)
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
            .pStages(stages)
            .pGroups(groups)
            .maxPipelineRayRecursionDepth(maxRecursion)
            .layout(layout);

        LongBuffer pPipeline = stack.mallocLong(1);
        int r = vkCreateRayTracingPipelinesKHR(
            vkDeviceObj,
            VK_NULL_HANDLE,  // deferredOperation = none
            VK_NULL_HANDLE,  // pipelineCache = none
            pipelineInfo, null, pPipeline);
        if (r != VK_SUCCESS) {
            LOGGER.error("[RTPipeline] vkCreateRayTracingPipelinesKHR failed: {}", r);
            return 0L;
        }
        LOGGER.info("[RTPipeline] created: handle={}", pPipeline.get(0));
        return pPipeline.get(0);
    } catch (Exception e) {
        LOGGER.error("[RTPipeline] createRayTracingPipelineWithAnyHit failed", e);
        return 0L;
    }
}
```

#### getRayTracingShaderGroupHandles

```java
public static void getRayTracingShaderGroupHandles(long device, long pipeline,
        int firstGroup, int groupCount, int handleSize, java.nio.ByteBuffer data) {
    if (!initialized || vkDeviceObj == null || pipeline == 0L) return;
    int r = vkGetRayTracingShaderGroupHandlesKHR(vkDeviceObj,
        pipeline, firstGroup, groupCount, data);
    if (r != VK_SUCCESS) {
        LOGGER.error("[SBT] vkGetRayTracingShaderGroupHandlesKHR failed: {}", r);
    }
}
```

---

## P7-E：Descriptor Set 更新

### 問題
`updateRTDescriptorSet()` 是 no-op，TLAS 和 RT output image 從未綁定到著色器。
Shader 的 binding 0（TLAS）和 binding 1（u_RTOutput storage image）永遠是 null。

### 前置知識：Descriptor Set Layout

BRVulkanRT.init() 建立的 layout 有 12 個 binding：

| Binding | 類型 | 用途 |
|---------|------|------|
| 0 | ACCELERATION_STRUCTURE_KHR | TLAS |
| 1 | STORAGE_IMAGE | u_RTOutput（RGBA16F） |
| 2 | COMBINED_IMAGE_SAMPLER | GBuffer Depth |
| 3 | COMBINED_IMAGE_SAMPLER | GBuffer Normal |
| 4 | UNIFORM_BUFFER | CameraUBO |
| 5 | STORAGE_BUFFER | Material SSBO |
| 6 | STORAGE_BUFFER | Vertex buffer |
| 7 | STORAGE_BUFFER | Index buffer |
| 8 | STORAGE_BUFFER | Light buffer |
| 9 | STORAGE_BUFFER | SVDAG |
| 10 | COMBINED_IMAGE_SAMPLER | 環境貼圖 |
| 11 | COMBINED_IMAGE_SAMPLER | 藍噪聲貼圖 |

### 實作（最小可工作版：只更新 binding 0 + 1）

```java
public static void updateRTDescriptorSet(long device, long set,
        long tlas, long imageView) {
    if (!initialized || vkDeviceObj == null || set == 0L) return;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        // ── Binding 0: TLAS（VkWriteDescriptorSetAccelerationStructureKHR）───
        VkWriteDescriptorSetAccelerationStructureKHR tlasWrite = null;
        VkWriteDescriptorSet.Buffer writes;

        if (tlas != 0L) {
            LongBuffer pTlas = stack.longs(tlas);
            tlasWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                .pAccelerationStructures(pTlas);

            writes = VkWriteDescriptorSet.calloc(imageView != 0L ? 2 : 1, stack);
            writes.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .pNext(tlasWrite.address())
                .dstSet(set)
                .dstBinding(0)
                .descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
        } else {
            writes = VkWriteDescriptorSet.calloc(imageView != 0L ? 1 : 0, stack);
        }

        // ── Binding 1: Storage Image（RT output）──────────────────────────────
        if (imageView != 0L) {
            int writeIdx = (tlas != 0L) ? 1 : 0;
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                .imageView(imageView)
                .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(writeIdx)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set)
                .dstBinding(1)
                .descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(imgInfo);
        }

        if (writes.remaining() > 0) {
            vkUpdateDescriptorSets(vkDeviceObj, writes, null);
            LOGGER.debug("[DS] updateRTDescriptorSet: tlas={}, imageView={}", tlas, imageView);
        }
    } catch (Exception e) {
        LOGGER.error("[DS] updateRTDescriptorSet failed", e);
    }
}
```

**注意：** `VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR` 在 `KHRAccelerationStructure` static import 中。

---

## P7-F：Render 指令錄製

### 問題
`cmdBindPipeline`、`cmdBindDescriptorSets`、`cmdTraceRaysKHR` 全是 no-op，
即使 pipeline 和 descriptor set 都建好了，GPU 也不會收到任何指令。

### 實作

**檔案：** `BRVulkanDevice.java`

#### cmdBindPipeline

```java
public static void cmdBindPipeline(long cmdHandle, int bindPoint, long pipeline) {
    if (!initialized || vkDeviceObj == null || cmdHandle == 0L || pipeline == 0L) return;
    vkCmdBindPipeline(new VkCommandBuffer(cmdHandle, vkDeviceObj), bindPoint, pipeline);
}
```

#### cmdBindDescriptorSets

```java
public static void cmdBindDescriptorSets(long cmdHandle, int bindPoint,
        long layout, int firstSet, long descriptorSet) {
    if (!initialized || vkDeviceObj == null || cmdHandle == 0L || descriptorSet == 0L) return;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        vkCmdBindDescriptorSets(
            new VkCommandBuffer(cmdHandle, vkDeviceObj),
            bindPoint, layout, firstSet,
            stack.longs(descriptorSet),
            null // no dynamic offsets
        );
    }
}
```

#### cmdTraceRaysKHR

**關鍵：** 現有 stub 簽名用 raw long 傳 addr/stride/size，需包裝成 `VkStridedDeviceAddressRegionKHR`。

現有呼叫方式（在 BRVulkanRT.traceRays）：
```java
BRVulkanDevice.cmdTraceRaysKHR(commandBuffer,
    sbtAddress + raygenRegionOffset, raygenRegionStride, raygenRegionSize,
    sbtAddress + missRegionOffset,   missRegionStride,   missRegionSize,
    sbtAddress + hitRegionOffset,    hitRegionStride,    hitRegionSize,
    0, 0, 0, // callable（未使用）
    width, height, 1);
```

實作：
```java
public static void cmdTraceRaysKHR(long cmdHandle,
        long rgenAddr, long rgenStride, long rgenSize,
        long missAddr, long missStride, long missSize,
        long hitAddr,  long hitStride,  long hitSize,
        long callAddr, long callStride, long callSize,
        int width, int height, int depth) {
    if (!initialized || vkDeviceObj == null || cmdHandle == 0L) return;
    try (MemoryStack stack = MemoryStack.stackPush()) {
        VkStridedDeviceAddressRegionKHR rgen = VkStridedDeviceAddressRegionKHR.calloc(stack)
            .deviceAddress(rgenAddr).stride(rgenStride).size(rgenSize);
        VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
            .deviceAddress(missAddr).stride(missStride).size(missSize);
        VkStridedDeviceAddressRegionKHR hit  = VkStridedDeviceAddressRegionKHR.calloc(stack)
            .deviceAddress(hitAddr).stride(hitStride).size(hitSize);
        VkStridedDeviceAddressRegionKHR call = VkStridedDeviceAddressRegionKHR.calloc(stack)
            .deviceAddress(callAddr).stride(callStride).size(callSize);

        vkCmdTraceRaysKHR(
            new VkCommandBuffer(cmdHandle, vkDeviceObj),
            rgen, miss, hit, call,
            width, height, depth);
    }
}
```

---

## P7-G：SBT 建立

SBT（Shader Binding Table）是 RT dispatch 的跳表，必須在正確記憶體中。

### BRVulkanRT.init() 的 SBT 建立邏輯（現有，但依賴 stub）

```java
// 現有程式碼：
long sbtBuffer  = BRVulkanDevice.createBuffer(device, sbtSize,
    0x100 /* SBT */ | 0x200 /* DEVICE_ADDRESS */ | 0x4 /* TRANSFER_DST */);
long sbtMemory  = BRVulkanDevice.allocateAndBindBuffer(device, sbtBuffer,
    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
```

P7-C 實作後 `createBuffer` 和 `allocateAndBindBuffer` 就會是真實的，
SBT 建立應該自動工作。但需確認以下 usage flag 值：

| Flag | 值 | 用途 |
|------|----|------|
| `VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR` | `0x400` | SBT |
| `VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT` | `0x20000` | GPU 位址查詢 |
| `VK_BUFFER_USAGE_TRANSFER_DST_BIT` | `0x2` | host→device copy |

**修正：** BRVulkanRT.init() 的 usage flag 0x100 是錯的。需要更新為：

```java
int sbtUsage = VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR  // 0x400
             | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT      // 0x20000
             | VK_BUFFER_USAGE_TRANSFER_DST_BIT;              // 0x2
```

**檔案：** `BRVulkanRT.java`，找到 SBT buffer 建立那段，更新 usage 參數。

---

## P7-H：VkRTAO 解注釋

### 目標
讓 RTAO（環境遮蔽）compute shader 實際執行，提供接觸陰影和環境深度感。

**檔案：** `VkRTAO.java`

#### 步驟 1：createComputePipeline — 載入 rtao.comp.glsl

```java
private long createComputePipeline(long device, long layout) {
    // 讀取磁碟上的 GLSL（已存在於 resources/assets/blockreality/shaders/rt/ada/rtao.comp.glsl）
    String glsl = loadShaderSource("rt/ada/rtao.comp.glsl");
    if (glsl == null) return 0L;

    byte[] spv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "rtao.comp");
    if (spv.length == 0) return 0L;

    long shaderModule = BRVulkanDevice.createShaderModule(device, spv);
    if (shaderModule == 0L) return 0L;

    try (MemoryStack stack = MemoryStack.stackPush()) {
        LongBuffer pPipeline = stack.mallocLong(1);
        int r = VK10.vkCreateComputePipelines(BRVulkanDevice.getVkDeviceObj(),
            VK_NULL_HANDLE,
            VkComputePipelineCreateInfo.calloc(1, stack).get(0)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main")))
                .layout(layout),
            null, pPipeline);
        vkDestroyShaderModule(BRVulkanDevice.getVkDeviceObj(), shaderModule, null);
        if (r != VK_SUCCESS) return 0L;
        return pPipeline.get(0);
    }
}

private String loadShaderSource(String resourcePath) {
    try {
        java.io.InputStream is = getClass().getResourceAsStream(
            "/assets/blockreality/shaders/" + resourcePath);
        if (is == null) { LOGGER.error("[RTAO] shader not found: {}", resourcePath); return null; }
        return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
        LOGGER.error("[RTAO] failed to load shader {}", resourcePath, e);
        return null;
    }
}
```

#### 步驟 2：dispatchAO — 解注釋指令錄製

```java
public int dispatchAO(int depthTex, int normalTex, Matrix4f invProjView, long tlas, long frameIndex) {
    if (!initialized || computePipeline == 0L) return 0;
    long device = BRVulkanDevice.getVkDevice();
    if (device == 0L || tlas == 0L) return 0;

    long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
    if (cmd == 0L) return 0;

    float aoRadius = BRRTSettings.getInstance().getAoRadius();

    BRVulkanDevice.cmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
    BRVulkanDevice.cmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
        pipelineLayout, 0, descriptorSet);

    // Push constants: aoRadius(4) + frameIndex(4) + padding(8) = 16 bytes
    // (需要 cmdPushConstants — P7-G 後補充)

    int groupsX = (outputWidth  + 7) / 8;
    int groupsY = (outputHeight + 7) / 8;

    VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
    VkQueue  vkQ   = BRVulkanDevice.getVkQueueObj();
    if (vkDev != null) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);
            KHRRayTracingPipeline.vkCmdBindPipeline(cb,   // 直接 LWJGL（AO 用 compute）
                VK10.VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
            VK10.vkCmdDispatch(cb, groupsX, groupsY, 1);
        }
    }

    BRVulkanDevice.endSingleTimeCommands(device, cmd);
    return outputAoTex;
}
```

---

## P7-I：煙霧測試流程

完成 P7-A～F 後的驗證步驟（不需要真實 RTX 硬體，先驗證初始化）：

### 測試 1：shader 編譯
```bash
./gradlew :api:runClient
```
觀察 log 看是否出現：
```
[SPIR-V] raygen.rgen compiled OK (NNNN bytes)
[SPIR-V] miss.rmiss compiled OK (NNNN bytes)
[SPIR-V] closesthit.rchit compiled OK (NNNN bytes)
[RTPipeline] created: handle=XXXXXXXX
```
若看到 `compile error`：→ 檢查 GLSL 語法，可能是 inline GLSL 字串中有 Java 轉義問題。

### 測試 2：第一幀輸出
觀察：
```
[Phase6] RT output image ready: 1920×1080, image=X, memory=Y, ...
[Phase6D] CPU readback: pixels non-null
```
使用 `/fd debug rt` 指令（若已實作）或直接看 F3 選單的 RT 時間統計。

### 測試 3：畫面驗證
螢幕上應出現：
- **陰影**：方塊側面有方向性暗部（RT shadow ray）
- **微型陰影**：接觸面有 AO（若 P7-H 完成）
- **反射**：若 `RTEffect.REFLECTIONS` 啟用，平滑面有環境反射

---

## 已知風險

| 風險 | 可能原因 | 緩解 |
|------|---------|------|
| SPIR-V 編譯失敗 | inline GLSL 用 Java 字串寫，需要 `\\n` 正確換行 | 改用磁碟 .glsl 檔讀取（resources 已有） |
| vkCreateRayTracingPipelinesKHR 回傳 ERROR_FEATURE_NOT_PRESENT | 驅動未啟用 rayTracingPipeline feature | 確認 BRVulkanDevice.createLogicalDevice() 有開啟 VkPhysicalDeviceRayTracingPipelineFeaturesKHR |
| SBT alignment 錯誤 | handleAlignedSize 計算偏差 | 使用 vkGetPhysicalDeviceRayTracingPipelinePropertiesKHR 取得 shaderGroupBaseAlignment |
| VMA 衝突 | lwjgl-vma 3.3.5 和手動 vkAllocateMemory 混用 | 先不用 VMA，全用手動分配，後期統一遷移 |
| Descriptor set 驗證層報錯 | TLAS descriptor 需要 VkWriteDescriptorSetAccelerationStructureKHR | P7-E 已處理，確認 pNext chain 正確 |

---

## 完成後的品質里程碑

```
P7-A+B+C+D+E+F 完成 → 第一幀有光影（RT shadows）
P7-G            完成 → SBT 正確，所有著色器 group 命中
P7-H            完成 → RTAO，接觸陰影，環境深度感

後續（P8 範圍）：
→ ReSTIR DI/GI   : 間接照明、彩色 bouncing
→ NRD 降噪       : 商業級低雜訊（取代 SVGF）
→ Blackwell DLSS : 升解析度 + Multi-Frame Generation
→ GPU Voxelizer  : Phase 3-D，BVH 品質 10× 提升
```

---

*任務書版本：2026-04-01*
*基於：Phase 6 完成後的 codebase 掃描*
