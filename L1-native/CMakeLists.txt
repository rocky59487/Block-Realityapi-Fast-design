cmake_minimum_required(VERSION 3.20)
project(blockreality_native
    VERSION 0.3.0
    DESCRIPTION "Block Reality v0.3c — external C++ engine (core/pfsf/fluid/render)"
    LANGUAGES C CXX
)

# ═══════════════════════════════════════════════════════════════════
#  Umbrella root — builds the native shared libraries that back
#  Block Reality's Java side (libbr_core, libblockreality_pfsf,
#  libblockreality_fluid, libblockreality_render).
#
#  Build flags (cmake -D):
#    BR_BUILD_PFSF    (default ON)   — PFSF physics solver
#    BR_BUILD_FLUID   (default ON)   — Fluid GPU solver
#    BR_BUILD_RENDER  (default ON)   — Vulkan RT + dispatchers
#    BR_BUILD_SHADERS (default ON)   — glslangValidator SPIR-V precompile
#    BR_BUILD_TESTS   (default OFF)  — GoogleTest unit suites
#    BR_VK_STRICT     (default OFF)  — abort() on any Vulkan validation error
#
#  Gradle invokes one CMake configure on this directory; target
#  selection ("blockreality_pfsf" etc.) is passed via --target.
# ═══════════════════════════════════════════════════════════════════

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(BR_BUILD_PFSF    "Build libblockreality_pfsf"            ON)
# libfluid / librender ship as skeletons in this commit — they configure
# and link but only expose the public C API + Phase-1 stubs. Real GPU
# kernels land as M3 (fluid Jacobi + advection) and M4 (BLAS/TLAS +
# ReSTIR/DDGI/ReLAX) progress.
option(BR_BUILD_FLUID   "Build libblockreality_fluid"           ON)
option(BR_BUILD_RENDER  "Build libblockreality_render"          ON)
option(BR_BUILD_SHADERS "Precompile GLSL→SPIR-V via glslang"    OFF)
option(BR_BUILD_TESTS   "Build GoogleTest unit suites"          OFF)
option(BR_VK_STRICT     "Abort on any Vulkan validation error"  OFF)

if(BR_VK_STRICT)
    add_compile_definitions(BR_VK_STRICT=1)
endif()

# ═══════════════════════════════════════════════════════════════════
#  ISA baseline — scoped per-target (see libpfsf/CMakeLists.txt).
#
#  Only pfsf_compute gets -march=x86-64-v3 (AVX2 / BMI1 / BMI2 / FMA);
#  br_core / libpfsf solver / libfluid / librender stay at the default
#  x86-64 (SSE2) baseline. Rationale: an umbrella-wide -march leaks
#  AVX2 auto-vectorisation into FP32 reduction loops that are parity-
#  tested against the Java reference path with STRESS_ABS_TOL=1e-5f.
#  Reduction-order drift under SIMD can exceed that tolerance.
#  The bench-critical target (pfsf_compute) is element-wise safe,
#  and it is the only code PfsfBenchmark measures.
# ═══════════════════════════════════════════════════════════════════

# libbr_core — shared foundation (single VkInstance, VMA, descriptor/pipeline
# cache, SPIR-V blob registry, JNI helpers). Linked PUBLIC into every domain
# module; never loaded directly by Java.
add_subdirectory(libbr_core)

# Shader precompile (glslangValidator). Must be configured BEFORE the domain
# libraries so they can `target_link_libraries(... br_shaders)` — the
# static-initializer TU inside br_shaders is how SPIR-V blobs get registered
# into br_core::SpirvRegistry. CI-host-side fallback when the Gradle
# precompileShaders task did not run first.
if(BR_BUILD_SHADERS)
    add_subdirectory(shaders)
endif()

# libpfsf — existing, links libbr_core and adds the JNI bridge that Java calls.
if(BR_BUILD_PFSF)
    add_subdirectory(libpfsf)
endif()

# libfluid — new, GPU Jacobi / advection / pressure / boundary.
if(BR_BUILD_FLUID)
    add_subdirectory(libfluid)
endif()

# librender — new, Vulkan RT + BVH + interop + ReSTIR/DDGI/ReLAX dispatchers.
if(BR_BUILD_RENDER)
    add_subdirectory(librender)
endif()

message(STATUS "[blockreality_native] v${PROJECT_VERSION}")
message(STATUS "  BR_BUILD_PFSF    = ${BR_BUILD_PFSF}")
message(STATUS "  BR_BUILD_FLUID   = ${BR_BUILD_FLUID}")
message(STATUS "  BR_BUILD_RENDER  = ${BR_BUILD_RENDER}")
message(STATUS "  BR_BUILD_SHADERS = ${BR_BUILD_SHADERS}")
message(STATUS "  BR_VK_STRICT     = ${BR_VK_STRICT}")
