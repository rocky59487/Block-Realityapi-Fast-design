# ═══════════════════════════════════════════════════════════════════
#  libbr_core — Block Reality v0.3c shared native foundation
# ═══════════════════════════════════════════════════════════════════
# Linked PUBLIC into libblockreality_pfsf / libblockreality_fluid /
# libblockreality_render. Owns the single VkInstance/VkDevice/VMA,
# descriptor pool LRU cache, pipeline cache persistence, SPIR-V blob
# registry, JNI helpers, callback bus. Never loaded directly by Java —
# dynamic linker resolves it via DT_NEEDED from the domain modules.

find_package(Vulkan REQUIRED)

# VMA — reuse the header-only target from libpfsf if already declared,
# otherwise fetch it here so the core library can stand alone.
if(NOT TARGET VulkanMemoryAllocator)
    include(FetchContent)
    FetchContent_Declare(vma_core
        GIT_REPOSITORY https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator.git
        GIT_TAG        v3.1.0
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(vma_core)
endif()

find_package(JNI QUIET)

set(BR_CORE_SOURCES
    src/br_core.cpp
    src/vulkan_device.cpp
    src/vma_allocator.cpp
    src/descriptor_cache.cpp
    src/pipeline_cache.cpp
    src/spirv_registry.cpp
    src/compute_pipeline.cpp
    src/cmdbuf_pool.cpp
    src/timeline_sync.cpp
    src/callback_bus.cpp
    src/jni_helpers.cpp
)

add_library(br_core SHARED ${BR_CORE_SOURCES})

target_include_directories(br_core
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(br_core
    PUBLIC  Vulkan::Vulkan
    PRIVATE VulkanMemoryAllocator
)

if(JNI_FOUND)
    target_include_directories(br_core PUBLIC ${JNI_INCLUDE_DIRS})
    target_compile_definitions(br_core PUBLIC BR_CORE_HAS_JNI=1)
endif()

# br_core was migrated from STATIC to SHARED. 
# To avoid decorating every class/function with __declspec(dllexport) or
# __attribute__((visibility("default"))), we enable CMake's auto-export
# feature on Windows and disable visibility hiding on Unix platforms.
set_target_properties(br_core PROPERTIES
    WINDOWS_EXPORT_ALL_SYMBOLS ON
    POSITION_INDEPENDENT_CODE ON
)

# PR#187 capy-ai R3106195658 / R3106213993:
# libblockreality_pfsf.so (and the sibling fluid/render shared libraries)
# carry a DT_NEEDED on libbr_core.so plus an `$ORIGIN` rpath so the
# loader expects the core lib to sit next to them at runtime. Gradle's
# packaging step hand-copies matching `lib*.so` / `*.dll` out of the
# build tree into META-INF/native/<triple>/, so it doesn't depend on
# `cmake --install`. External consumers who *do* drive the standalone
# build via `cmake --install` (distro packagers, downstream mods that
# vendor the C++ surface) used to get libblockreality_pfsf without its
# DT_NEEDED libbr_core, producing a load-time "cannot open shared object
# file" at the first System.load / dlopen. Publish br_core alongside
# libpfsf so the install tree mirrors what nativeBuild ships.
install(TARGETS br_core
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
    RUNTIME DESTINATION bin)

message(STATUS "[libbr_core] configured — Vulkan ${Vulkan_VERSION}, JNI=${JNI_FOUND}")
