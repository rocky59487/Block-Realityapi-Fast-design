# ═══════════════════════════════════════════════════════════════════
#  libblockreality_render — Vulkan RT dispatchers (v0.3c M4 skeleton)
# ═══════════════════════════════════════════════════════════════════
# Owns VkInstance interop with Minecraft's OpenGL swapchain (via
# VK_KHR_external_memory_fd / win32), BLAS / TLAS build, ReSTIR DI + GI
# dispatchers (Blackwell), DDGI probe update (Ada), ReLAX / SVGF / NRD
# denoise. Shares the VkDevice with libpfsf via br_core::get_singleton().

cmake_minimum_required(VERSION 3.20)

find_package(Vulkan REQUIRED)
find_package(JNI QUIET)

set(RENDER_SOURCES
    src/render_api.cpp
    src/core/render_engine.cpp
)

add_library(render STATIC ${RENDER_SOURCES})

target_include_directories(render
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_link_libraries(render
    PUBLIC  Vulkan::Vulkan
    PRIVATE br_core
)

set_target_properties(render PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

if(JNI_FOUND)
    add_library(blockreality_render SHARED src/jni/native_render_bridge.cpp)
    target_link_libraries(blockreality_render PRIVATE render JNI::JNI br_core)
    target_include_directories(blockreality_render PRIVATE ${JNI_INCLUDE_DIRS})
    if(NOT WIN32)
        set_target_properties(blockreality_render PROPERTIES PREFIX "lib")
    endif()
    set_target_properties(blockreality_render PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

    # Ensure runtime loader finds co-located libbr_core.so under
    # META-INF/native/<triple>/ (JVM dlopen has no LD_LIBRARY_PATH).
    if(NOT WIN32 AND NOT APPLE)
        set_target_properties(blockreality_render PROPERTIES
            INSTALL_RPATH "$ORIGIN"
            BUILD_RPATH   "$ORIGIN"
            BUILD_WITH_INSTALL_RPATH TRUE)
    elseif(APPLE)
        set_target_properties(blockreality_render PROPERTIES
            INSTALL_RPATH "@loader_path"
            BUILD_RPATH   "@loader_path"
            BUILD_WITH_INSTALL_RPATH TRUE)
    endif()

    # Force-load br_shaders so its static registration TU isn't stripped
    # (see libpfsf/CMakeLists.txt for rationale).
    if(TARGET br_shaders)
        if(WIN32)
            target_link_libraries(blockreality_render PRIVATE br_shaders)
            set_property(TARGET blockreality_render APPEND_STRING PROPERTY
                LINK_FLAGS " /WHOLEARCHIVE:br_shaders")
        elseif(APPLE)
            target_link_options(blockreality_render PRIVATE
                -Wl,-force_load,$<TARGET_FILE:br_shaders>)
            add_dependencies(blockreality_render br_shaders)
        else()
            target_link_options(blockreality_render PRIVATE
                -Wl,--whole-archive $<TARGET_FILE:br_shaders> -Wl,--no-whole-archive)
            add_dependencies(blockreality_render br_shaders)
        endif()
    endif()

    install(TARGETS blockreality_render LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
endif()

message(STATUS "[librender] configured — JNI=${JNI_FOUND}")
