# Block Reality v0.2a Audit Report

**Date**: 2026-04-08

## 1. Version Unification & Comment Cleanup
All fragmented version markers (`v2.1`, `v3`, `v4-fix`, `R3-8 fix`, `P0 重構`, `Phase 3`, `Phase 6`) have been standardized to `v0.2a` across the codebase, specifically in:
- `PFSFEngineInstance.java`
- `VulkanComputeContext.java`
- `SidecarBridge.java`
- `PFSFPipelineFactory.java`
- `PFSFEngine.java`
- `BRVulkanDevice.java`
- `PFSFAsyncCompute.java`

Additionally, outdated or overly verbose comments (e.g., legacy triple-buffering logic, old stub markers) have been cleaned up to maintain a professional code standard.

## 2. Concurrency & Thread Safety
Several severe concurrency defects have been resolved:
- **StructureIslandRegistry.java**: The misleading `@ThreadSafe` annotation was removed. Replaced it with actual synchronization by adding the `synchronized` keyword to `addMember`, `removeMember`, and `recalculateBounds` methods in the `StructureIsland` internal class.
- **SidecarBridge.java**: Addressed the lock race condition in `call()`. The logic was refactored to safely release the read lock, acquire a write lock if reconnection is needed, double-check the state, and then revert back to the read lock.

## 3. Performance & Memory Allocation
GC pressure and memory leaks during tick cycles were mitigated:
- **PFSFEngineInstance.java**: The local `batch` and `callbacks` `ArrayList`s created per-tick were promoted to class-level member variables. By calling `.clear()` each tick, we significantly reduce garbage collection overhead under heavy load.
- Replaced the ambiguous `Consumer<Void>` callback interface with `Runnable`.
- Introduced a robust `try-catch` wrapper around `PFSFAsyncCompute.submitBatch`. Even if the submission fails, the catch block ensures that `finalBuf.release()` is executed for all queued items, sealing a critical VRAM leak.

## 4. Ghost Features & Stubs
- **BRVulkanDevice.java**:
  - Cleaned up the "stubs" for `exportRTOutputMemoryFd` and `exportVKDoneSemaphoreFd` since these methods now properly delegate to their respective implementations in `BRVulkanRT.java`.
  - Addressed the `buildBLASWithOMM` function. It remains unimplemented because Forge 1.20.1 strictly enforces LWJGL 3.3.1, which lacks the `EXTOpacityMicromap` extension required for OMM. A descriptive `LOGGER.info` fallback message has replaced the old `LOGGER.warn("stub")`.

## 5. False Positives / Unresolved Issues
The audit highlighted a few items that were either incorrectly diagnosed or blocked by external constraints:
- **Node Editor Missing Implementations**: The report claimed 90+ node implementations were missing. They are actually fully implemented under `fastdesign/src/main/java/com/blockreality/fastdesign/client/node/impl/render/` and `.../tool/`.
- **CAD Initialization Overhead**: The report pointed out redundant OpenCASCADE initializations in `pipeline.ts`. However, the `initOpenCascade` function natively caches the instance and returns early if it has already been initialized.
- **OMM Extension**: As noted above, `buildBLASWithOMM` is structurally blocked by the Minecraft 1.20.1 LWJGL dependency bounds and cannot be fixed natively without breaking Forge compatibility.