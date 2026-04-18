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

# Export symbols for SHARED library
if(WIN32)
    target_compile_definitions(br_core PRIVATE BR_CORE_EXPORT=__declspec(dllexport))
    target_compile_definitions(br_core PUBLIC  BR_CORE_IMPORT=__declspec(dllimport))
else()
    target_compile_definitions(br_core PRIVATE BR_CORE_EXPORT=__attribute__((visibility("default"))))
    target_compile_definitions(br_core PUBLIC  BR_CORE_IMPORT=)
endif()

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

# Hide non-exported C++ symbols — the core is a static dep, so nothing
# needs to escape into the dynamic symbol table.
set_target_properties(br_core PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    POSITION_INDEPENDENT_CODE ON
)

message(STATUS "[libbr_core] configured — Vulkan ${Vulkan_VERSION}, JNI=${JNI_FOUND}")
