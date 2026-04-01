cmake_minimum_required(VERSION 3.20)
project(blockreality_nrd CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── Java / JNI headers ────────────────────────────────────────────────────
find_package(Java REQUIRED)
find_package(JNI  REQUIRED)

if (NOT JNI_FOUND)
    message(FATAL_ERROR "JNI not found — set JAVA_HOME or JNI_INCLUDE_DIRS manually")
endif()

# ─── Optional: NVIDIA NRD SDK ─────────────────────────────────────────────
# Set NRD_SDK_DIR to the extracted NRD release (contains include/ and lib/).
# If not provided, the library builds in stub mode (loads cleanly, denoise()
# returns false, Java falls back to BRSVGFDenoiser automatically).
option(NRD_SDK_DIR "" "Path to extracted NVIDIA NRD SDK root directory")
set(NRD_AVAILABLE 0)

if (NRD_SDK_DIR AND EXISTS "${NRD_SDK_DIR}/include/NRD.h")
    message(STATUS "[blockreality_nrd] NRD SDK found at ${NRD_SDK_DIR} — building with full NRD support")
    set(NRD_AVAILABLE 1)
    set(NRD_INCLUDE_DIR "${NRD_SDK_DIR}/include")

    if (WIN32)
        set(NRD_LIB "${NRD_SDK_DIR}/lib/Release/NRD.lib")
    else()
        set(NRD_LIB "${NRD_SDK_DIR}/lib/libNRD.a")
    endif()

    if (NOT EXISTS "${NRD_LIB}")
        message(WARNING "[blockreality_nrd] NRD lib not found at ${NRD_LIB}; building stub mode")
        set(NRD_AVAILABLE 0)
    endif()
else()
    message(STATUS "[blockreality_nrd] NRD SDK not found — building stub (graceful fallback) mode")
endif()

# ─── Shared library target ─────────────────────────────────────────────────
add_library(blockreality_nrd SHARED
    BRNRDNative.cpp
)

# JNI include paths
target_include_directories(blockreality_nrd PRIVATE
    ${JNI_INCLUDE_DIRS}
)

# NRD compile definitions and includes
target_compile_definitions(blockreality_nrd PRIVATE
    NRD_AVAILABLE=${NRD_AVAILABLE}
)

if (NRD_AVAILABLE)
    target_include_directories(blockreality_nrd PRIVATE ${NRD_INCLUDE_DIR})
    target_link_libraries(blockreality_nrd PRIVATE ${NRD_LIB})
endif()

# ─── Compiler flags ────────────────────────────────────────────────────────
if (MSVC)
    target_compile_options(blockreality_nrd PRIVATE /W3 /WX- /MP)
    # MSVC: suppress C4100 (unreferenced formal parameter) from NRD headers
    target_compile_options(blockreality_nrd PRIVATE /wd4100)
else()
    target_compile_options(blockreality_nrd PRIVATE
        -Wall -Wextra -Wno-unused-parameter
        -fvisibility=hidden   # only JNI exports are visible
    )
endif()

# ─── Output naming ─────────────────────────────────────────────────────────
# Java System.loadLibrary("blockreality_nrd") expects:
#   Windows : blockreality_nrd.dll
#   Linux   : libblockreality_nrd.so   (loadLibrary strips the "lib" prefix)
#   macOS   : libblockreality_nrd.dylib
set_target_properties(blockreality_nrd PROPERTIES
    OUTPUT_NAME  "blockreality_nrd"
    PREFIX       "${CMAKE_SHARED_LIBRARY_PREFIX}"
    SUFFIX       "${CMAKE_SHARED_LIBRARY_SUFFIX}"
)

# ─── Install rules ─────────────────────────────────────────────────────────
# Place the built .dll/.so into the mod's native-libs directory so Gradle
# can pick it up and bundle it in the final jar / mods folder.
set(BR_NATIVE_OUTPUT_DIR "${CMAKE_SOURCE_DIR}/../resources/native"
    CACHE PATH "Directory where the built library is copied after build")

install(TARGETS blockreality_nrd
    RUNTIME DESTINATION "${BR_NATIVE_OUTPUT_DIR}"   # .dll on Windows
    LIBRARY DESTINATION "${BR_NATIVE_OUTPUT_DIR}"   # .so / .dylib
)

# ─── Build instructions (comment) ─────────────────────────────────────────
# Stub mode (no NRD SDK):
#   mkdir build && cd build
#   cmake .. -DJAVA_HOME=$JAVA_HOME
#   cmake --build . --config Release
#   cmake --install .
#
# With NRD SDK:
#   cmake .. -DJAVA_HOME=$JAVA_HOME -DNRD_SDK_DIR=/path/to/NRD
#   cmake --build . --config Release
#   cmake --install .
