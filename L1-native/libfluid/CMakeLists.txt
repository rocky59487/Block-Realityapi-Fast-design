# ═══════════════════════════════════════════════════════════════════
#  libblockreality_fluid — GPU fluid solver (v0.3c M3)
# ═══════════════════════════════════════════════════════════════════
# Runs PFSF-Fluid's Jacobi pressure + semi-Lagrangian advection +
# structure-coupling fields on the same Vulkan device as libpfsf via
# br_core::get_singleton(). Loaded by NativeFluidBridge on the Java side;
# IFluidManager SPI semantics (1-tick lag, default-OFF flag) are preserved.

cmake_minimum_required(VERSION 3.20)

find_package(Vulkan REQUIRED)
find_package(JNI QUIET)

set(FLUID_SOURCES
    src/fluid_api.cpp
    src/core/fluid_engine.cpp
)

add_library(fluid STATIC ${FLUID_SOURCES})

target_include_directories(fluid
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(fluid
    PUBLIC  Vulkan::Vulkan
    PRIVATE br_core
)

set_target_properties(fluid PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# JNI bridge — compiled into a separate shared library so Java's
# System.loadLibrary("blockreality_fluid") resolves it. Mirrors the
# libpfsf layout: the core .a ships independent of Java, the _jni .so
# is the JVM-visible face.
if(JNI_FOUND)
    add_library(blockreality_fluid SHARED src/jni/native_fluid_bridge.cpp)
    target_link_libraries(blockreality_fluid PRIVATE fluid JNI::JNI br_core)
    target_include_directories(blockreality_fluid PRIVATE ${JNI_INCLUDE_DIRS})
    if(NOT WIN32)
        set_target_properties(blockreality_fluid PROPERTIES PREFIX "lib")
    endif()
    set_target_properties(blockreality_fluid PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
    install(TARGETS blockreality_fluid LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
endif()

message(STATUS "[libfluid] configured — JNI=${JNI_FOUND}")
