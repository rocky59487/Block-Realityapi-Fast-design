cmake_minimum_required(VERSION 3.20)
project(pfsf
    VERSION 0.1.0
    DESCRIPTION "Block Reality PFSF physics solver — standalone C++ library"
    LANGUAGES C CXX
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── v0.4 M1c — pinned ABI contract version ──────────────────────────
# PFSF_ABI_VERSION_STRING is set by the Gradle driver (build.gradle
# reads L1-native/libpfsf/abi/pfsf_v1.abi.json's abi_version and passes
# it through at configure time). Standalone CMake invocations can
# override at `-DPFSF_ABI_VERSION_STRING=x.y.z`; otherwise we fall back
# to parsing the json ourselves so `cmake -S L1-native -B build` works
# without Gradle.
if(NOT DEFINED PFSF_ABI_VERSION_STRING OR PFSF_ABI_VERSION_STRING STREQUAL "")
    file(READ "${CMAKE_CURRENT_SOURCE_DIR}/abi/pfsf_v1.abi.json" _abi_json)
    string(REGEX MATCH "\"abi_version\"[ \t]*:[ \t]*\"([0-9]+\\.[0-9]+\\.[0-9]+)\""
           _abi_match "${_abi_json}")
    if(_abi_match)
        set(PFSF_ABI_VERSION_STRING "${CMAKE_MATCH_1}")
    else()
        set(PFSF_ABI_VERSION_STRING "0.0.0")
        message(WARNING "Could not parse abi_version from pfsf_v1.abi.json; "
                        "defaulting PFSF_ABI_VERSION_STRING=0.0.0")
    endif()
endif()
message(STATUS "libpfsf: pinned ABI contract version = ${PFSF_ABI_VERSION_STRING}")

# ── Vulkan SDK ──
find_package(Vulkan REQUIRED)

# ── Vulkan Memory Allocator (VMA) — header-only, single-file ──
# Guard against double-declaration when libbr_core or another umbrella
# subproject has already pulled VMA in.
if(NOT TARGET VulkanMemoryAllocator)
    include(FetchContent)
    FetchContent_Declare(vma
        GIT_REPOSITORY https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator.git
        GIT_TAG        v3.1.0
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(vma)
endif()

# ── JNI (optional, for Phase 2 NativePFSFRuntime) ──
find_package(JNI QUIET)
option(PFSF_BUILD_JNI "Build JNI bridge for Java integration" ${JNI_FOUND})

# ── Build options ──
option(PFSF_BUILD_TESTS   "Build unit tests"           OFF)
option(PFSF_BUILD_EXAMPLE "Build standalone CLI example" ON)
option(BUILD_SHARED_LIBS   "Build shared library"       ON)

# ── Sources ──
set(PFSF_SOURCES
    src/pfsf_api.cpp
    src/pfsf_engine.cpp
    src/core/vulkan_context.cpp
    src/core/island_buffer.cpp
    src/core/buffer_manager.cpp
    src/solver/jacobi_solver.cpp
    src/solver/vcycle_solver.cpp
    src/solver/phase_field.cpp
    src/solver/pcg_solver.cpp
    src/solver/failure_scan.cpp
    src/solver/sparse_scatter.cpp
    src/solver/dispatcher.cpp
    src/solver/dispatcher_pcg.cpp
    src/solver/dispatcher_vcycle.cpp
)

# ── v0.3d Phase 1: libpfsf_compute static target ──
# Host-only numeric primitives (Vulkan-free).
#   Phase 0 shipped stubs only.
#   Phase 1 lights up the first real kernels:
#     * pfsf_abi_version / pfsf_has_feature / pfsf_build_info
#     * pfsf_normalize_soa6
#     * pfsf_apply_wind_bias
#     * pfsf_timoshenko_moment_factor
#     * pfsf_wind_pressure_source
#   Phase 2+ replace additional stubs symbol-by-symbol.
# The target is now linked into blockreality_pfsf (see below) so Java
# delegation can reach these symbols via dlsym.
add_library(pfsf_compute STATIC
    src/compute/version.cpp
    src/compute/normalize.cpp
    src/compute/wind_bias.cpp
    src/compute/timoshenko.cpp
    src/compute/wind_pressure.cpp
    src/compute/arm_map.cpp
    src/compute/arch_factor.cpp
    src/compute/phantom_edges.cpp
    src/compute/morton.cpp
    src/compute/downsample.cpp
    src/compute/chebyshev.cpp
    src/compute/divergence.cpp
    src/compute/features.cpp
    src/compute/conductivity.cpp
    src/compute/aug_kernels.cpp
    src/ext/augmentation.cpp
    src/ext/hooks.cpp
    src/ext/plan_dispatcher.cpp
    src/trace/ring_buffer.cpp
    src/diag/crash.cpp
)
target_include_directories(pfsf_compute
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/compute
)
set_target_properties(pfsf_compute PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_compile_definitions(pfsf_compute PRIVATE
    PFSF_ABI_VERSION_STRING="${PFSF_ABI_VERSION_STRING}"
)

# ═══════════════════════════════════════════════════════════════════
#  pfsf_compute ISA baseline — scoped to this target only.
#
#  Raising the baseline above x86-64 (SSE2) lets -O3 auto-vectorise
#  the element-wise kernels (normalize_soa6 / apply_wind_bias /
#  wind_pressure_source / timoshenko / chebyshev_omega / downsample /
#  features) to 256-bit AVX2 lanes on every GitHub Actions runner.
#
#  We do NOT propagate -march umbrella-wide because AVX2 reduction
#  re-association can perturb FP32 sums beyond the parity-test
#  STRESS_ABS_TOL=1e-5f tolerance in the Vulkan solver TUs.
#  pfsf_compute's parity tests already pass under AVX2 (element-wise
#  kernels are bit-identical; reductions like features/downsample
#  use the same order as the Java ref code path).
# ═══════════════════════════════════════════════════════════════════
if(NOT MSVC)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86_64|amd64|AMD64)")
        target_compile_options(pfsf_compute PRIVATE -march=x86-64-v3)
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "(aarch64|arm64)")
        # ARMv8.2-A covers every Apple silicon Mac and AWS Graviton2+.
        target_compile_options(pfsf_compute PRIVATE -march=armv8.2-a)
    endif()
else()
    # MSVC: /arch:AVX2 enables the same ISA window on Windows x64 hosts.
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86_64|amd64|AMD64)")
        target_compile_options(pfsf_compute PRIVATE /arch:AVX2)
    endif()
endif()

# ── Library target ──
add_library(pfsf ${PFSF_SOURCES})

target_include_directories(pfsf
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(pfsf
    PUBLIC  Vulkan::Vulkan
    PRIVATE VulkanMemoryAllocator
)

# When the umbrella build is in effect (L1-native/CMakeLists.txt added
# libbr_core first), br_core is already a target. Standalone builds of
# libpfsf must build the shared foundation themselves to resolve the
# br_core:: types now pervasive in the solvers.
if(NOT TARGET br_core)
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../libbr_core ${CMAKE_CURRENT_BINARY_DIR}/libbr_core)
endif()

target_link_libraries(pfsf PUBLIC br_core)
target_compile_definitions(pfsf PRIVATE PFSF_USE_BR_CORE=1)

# When pfsf is built shared (BUILD_SHARED_LIBS=ON, which is our default for
# the umbrella build), it becomes a DT_NEEDED transitive dependency of
# libblockreality_pfsf.so. Its own DT_NEEDED libbr_core.so must resolve at
# runtime from the co-located directory, so $ORIGIN rpath is required.
# Without this, CMake hard-codes the build tree path, which is wrong once
# the .so ships inside the mod jar.
get_target_property(_pfsf_type pfsf TYPE)
if(_pfsf_type STREQUAL "SHARED_LIBRARY")
    if(NOT WIN32 AND NOT APPLE)
        set_target_properties(pfsf PROPERTIES
            INSTALL_RPATH "$ORIGIN"
            BUILD_RPATH   "$ORIGIN"
            BUILD_WITH_INSTALL_RPATH TRUE)
    elseif(APPLE)
        set_target_properties(pfsf PROPERTIES
            INSTALL_RPATH "@loader_path"
            BUILD_RPATH   "@loader_path"
            BUILD_WITH_INSTALL_RPATH TRUE)
    endif()
endif()

# Platform-specific
if(WIN32)
    target_compile_definitions(pfsf PRIVATE PFSF_EXPORT)
endif()

# ── JNI bridge (v0.3c Phase 1) ──
# Builds libblockreality_pfsf — the shared library loaded by
# com.blockreality.api.physics.pfsf.NativePFSFBridge via System.loadLibrary.
# Produces:
#   Linux   → libblockreality_pfsf.so
#   macOS   → libblockreality_pfsf.dylib
#   Windows → blockreality_pfsf.dll
if(PFSF_BUILD_JNI AND JNI_FOUND)
    message(STATUS "[libpfsf] JNI found — building JNI bridge (blockreality_pfsf)")
    add_library(blockreality_pfsf SHARED src/jni/native_pfsf_bridge.cpp)
    # v0.3d Phase 1: compute primitives (pfsf_compute) are now linked into the
    # JNI shared library so Java delegation can resolve their symbols.
    # Whole-archive is required on Linux because the nm-visible entry points
    # are pure C (no C++ static constructors referencing them).
    target_link_libraries(blockreality_pfsf PRIVATE pfsf JNI::JNI)
    target_include_directories(blockreality_pfsf PRIVATE ${JNI_INCLUDE_DIRS})
    if(WIN32)
        target_link_libraries(blockreality_pfsf PRIVATE pfsf_compute)
        set_property(TARGET blockreality_pfsf APPEND_STRING PROPERTY
            LINK_FLAGS " /WHOLEARCHIVE:pfsf_compute")
    elseif(APPLE)
        target_link_options(blockreality_pfsf PRIVATE
            -Wl,-force_load,$<TARGET_FILE:pfsf_compute>)
        add_dependencies(blockreality_pfsf pfsf_compute)
    else()
        target_link_options(blockreality_pfsf PRIVATE
            -Wl,--whole-archive $<TARGET_FILE:pfsf_compute> -Wl,--no-whole-archive)
        add_dependencies(blockreality_pfsf pfsf_compute)
    endif()

    # Strip the default "lib" prefix so the output name matches System.loadLibrary("blockreality_pfsf").
    # On Linux/macOS the shared library stem must be exactly "blockreality_pfsf" for that call to resolve.
    if(NOT WIN32)
        set_target_properties(blockreality_pfsf PROPERTIES PREFIX "lib")
    endif()

    # Hide non-exported C++ symbols so the JNI ABI surface stays minimal.
    set_target_properties(blockreality_pfsf PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

    # Ensure the runtime loader finds co-located libbr_core.so when the .so
    # is dlopen'd by the JVM from META-INF/native/<triple>/. Without an
    # rpath entry, ld.so cannot locate the DT_NEEDED libbr_core sitting
    # next to us (it is not on LD_LIBRARY_PATH in production).
    if(NOT WIN32 AND NOT APPLE)
        set_target_properties(blockreality_pfsf PROPERTIES
            INSTALL_RPATH "$ORIGIN"
            BUILD_RPATH   "$ORIGIN"
            BUILD_WITH_INSTALL_RPATH TRUE)
    elseif(APPLE)
        set_target_properties(blockreality_pfsf PROPERTIES
            INSTALL_RPATH "@loader_path"
            BUILD_RPATH   "@loader_path"
            BUILD_WITH_INSTALL_RPATH TRUE)
    endif()

    # Pull in SPIR-V auto-registration TU — without --whole-archive the
    # static initialiser inside br_shaders' generated _spirv_registry_autogen.cpp
    # would be stripped because no explicit symbol references it from
    # libpfsf. Guarded so standalone libpfsf builds without shader precompile
    # keep working (GLSL JIT via Java side).
    if(TARGET br_shaders)
        if(WIN32)
            target_link_libraries(blockreality_pfsf PRIVATE br_shaders)
            set_property(TARGET blockreality_pfsf APPEND_STRING PROPERTY
                LINK_FLAGS " /WHOLEARCHIVE:br_shaders")
        elseif(APPLE)
            target_link_options(blockreality_pfsf PRIVATE
                -Wl,-force_load,$<TARGET_FILE:br_shaders>)
            add_dependencies(blockreality_pfsf br_shaders)
        else()
            target_link_options(blockreality_pfsf PRIVATE
                -Wl,--whole-archive $<TARGET_FILE:br_shaders> -Wl,--no-whole-archive)
            add_dependencies(blockreality_pfsf br_shaders)
        endif()
    endif()

    install(TARGETS blockreality_pfsf
        LIBRARY DESTINATION lib
        RUNTIME DESTINATION bin)
endif()

# ── Standalone CLI example ──
# v0.4 M3a: split into driver + flag parser + fixture loader. Sources
# are compiled into the executable directly (no helper library) because
# they are only consumed by pfsf_cli and the unit tests invoke them via
# #include of the .cpp files.
if(PFSF_BUILD_EXAMPLE)
    add_executable(pfsf_cli
        src/example/main.cpp
        src/example/pfsf_cli_args.cpp
        src/example/fixture_loader.cpp
        src/example/cpu_backend.cpp
        src/example/vk_backend.cpp
    )
    # pfsf for the VK-path (pfsf_create/init/tick), pfsf_compute for the
    # host primitives the CPU backend drives (pfsf_normalize_soa6 et al).
    target_link_libraries(pfsf_cli PRIVATE pfsf pfsf_compute)
endif()

# ── Install ──
install(TARGETS pfsf
    LIBRARY  DESTINATION lib
    ARCHIVE  DESTINATION lib
    RUNTIME  DESTINATION bin
)
install(DIRECTORY include/pfsf DESTINATION include)

message(STATUS "[libpfsf] v${PROJECT_VERSION} — Vulkan ${Vulkan_VERSION}")
message(STATUS "  BUILD_SHARED_LIBS = ${BUILD_SHARED_LIBS}")
message(STATUS "  PFSF_BUILD_JNI    = ${PFSF_BUILD_JNI}")
message(STATUS "  PFSF_BUILD_TESTS  = ${PFSF_BUILD_TESTS}")
message(STATUS "  pfsf_compute      = Phase 1-7 live (Phase 1 + arm/arch/phantom + morton/downsample/tiled_layout + diagnostics + augmentation registry + plan dispatcher + trace ring + v0.3e M5 crash dump)")
