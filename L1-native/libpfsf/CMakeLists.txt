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
    src/solver/dispatcher.cpp
    src/solver/dispatcher_pcg.cpp
)

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
# libbr_core first), link libpfsf against the shared foundation. Standalone
# builds of libpfsf keep working unchanged — the br_core target simply
# isn't present and we fall back to libpfsf's own Vulkan context.
if(TARGET br_core)
    target_link_libraries(pfsf PUBLIC br_core)
    target_compile_definitions(pfsf PRIVATE PFSF_USE_BR_CORE=1)
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
    target_link_libraries(blockreality_pfsf PRIVATE pfsf JNI::JNI)
    target_include_directories(blockreality_pfsf PRIVATE ${JNI_INCLUDE_DIRS})

    # Strip the default "lib" prefix so the output name matches System.loadLibrary("blockreality_pfsf").
    # On Linux/macOS the shared library stem must be exactly "blockreality_pfsf" for that call to resolve.
    if(NOT WIN32)
        set_target_properties(blockreality_pfsf PROPERTIES PREFIX "lib")
    endif()

    # Hide non-exported C++ symbols so the JNI ABI surface stays minimal.
    set_target_properties(blockreality_pfsf PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

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
if(PFSF_BUILD_EXAMPLE)
    add_executable(pfsf_cli src/example/main.cpp)
    target_link_libraries(pfsf_cli PRIVATE pfsf)
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
