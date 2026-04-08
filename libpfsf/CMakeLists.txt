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
)

# Platform-specific
if(WIN32)
    target_compile_definitions(pfsf PRIVATE PFSF_EXPORT)
endif()

# ── JNI bridge (Phase 2) ──
if(PFSF_BUILD_JNI AND JNI_FOUND)
    message(STATUS "[libpfsf] JNI found — building JNI bridge")
    # Phase 2: add_library(pfsf_jni SHARED src/jni/native_pfsf_runtime.cpp)
    # target_link_libraries(pfsf_jni PRIVATE pfsf JNI::JNI)
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
