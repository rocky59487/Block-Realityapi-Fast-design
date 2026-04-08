# libpfsf — Standalone PFSF Physics Solver

**libpfsf** is a standalone C++ library implementing the **Potential Field Structure Failure (PFSF)** physics solver. It abstracts the core Vulkan compute operations outside of the Java environment, allowing for pure native integration or CLI execution.

## 1. Overview / 概述

While Block Reality typically uses Java + LWJGL for Vulkan interactions, `libpfsf` provides a dedicated C++ runtime for the PFSF algorithms. This enables performance testing, alternative rendering engine integrations, and a direct JNI bridge (Phase 2).

雖然 Block Reality 通常使用 Java + LWJGL 來處理 Vulkan 互動，`libpfsf` 為 PFSF 演算法提供了專屬的 C++ 執行期環境。這使得效能測試、整合替代渲染引擎以及直接的 JNI 橋接（Phase 2）成為可能。

## 2. Core Components / 核心組件

*   **Vulkan Context (`src/core/vulkan_context.cpp`)**: Manages the Vulkan device, queues, command pools, and VMA setup independently from Minecraft.
*   **Buffer Management (`src/core/buffer_manager.cpp`, `island_buffer.cpp`)**: Handles the creation and lifetime of per-island GPU buffers (`phi`, `source`, `conductivity`, `fail_flags`).
*   **Solvers (`src/solver/`)**:
    *   `jacobi_solver.cpp`: Standalone iteration methods.
    *   `vcycle_solver.cpp`: Implements the W-Cycle/V-Cycle multigrid approaches.
    *   `phase_field.cpp`: Manages the phase-field fracture mechanics.
*   **API Interface (`src/pfsf_api.cpp`)**: C-style interface for cross-boundary calls (CLI, JNI).

## 3. Build Instructions / 建置指引

The project uses CMake and requires the Vulkan SDK.

```bash
mkdir build
cd build
cmake .. -DPFSF_BUILD_EXAMPLE=ON
cmake --build .
```

This will produce the shared library `libpfsf.so` (or `.dll`) and the standalone CLI executable `pfsf_cli`.

## 4. Integration / 整合

For Phase 2, `libpfsf` is designed to be linked against Java using the `PFSF_BUILD_JNI` flag. This shifts the compute dispatching overhead from Java to native C++, leaving only high-level coordination to the Java server ticks.
