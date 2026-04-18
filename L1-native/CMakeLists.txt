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
#  ISA baseline — x86-64-v3 (Haswell-era: AVX2 / BMI1 / BMI2 / FMA).
#
#  Without an explicit -march, GCC/Clang default to x86-64 (SSE2 only).
#  That makes the pfsf_compute scalar loops stay scalar even at -O3,
#  so native_over_java drops below 1.5× on GitHub Actions runners
#  (measured on the v0.3e M6 perf-gate run: apply_wind_bias 1.69×,
#  tick50k_surrogate 0.67×). Raising the baseline to x86-64-v3
#  (supported on literally every Actions runner fleet since ~2017)
#  lets -O3 auto-vectorise to 256-bit AVX2 lanes.
#
#  target_clones-based FMV is the right long-term answer (runtime
#  dispatch + AVX-512 path), but the annotations are not yet on the
#  kernels. This baseline unblocks the perf-gate today.
# ═══════════════════════════════════════════════════════════════════
if(NOT MSVC)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86_64|amd64|AMD64)")
        add_compile_options(-march=x86-64-v3)
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "(aarch64|arm64)")
        # ARMv8.2-A covers every Apple silicon Mac and AWS Graviton2+.
        add_compile_options(-march=armv8.2-a)
    endif()
else()
    # MSVC: /arch:AVX2 enables the same ISA window on Windows x64 hosts.
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86_64|amd64|AMD64)")
        add_compile_options(/arch:AVX2)
    endif()
endif()

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
