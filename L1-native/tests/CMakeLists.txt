# ═══════════════════════════════════════════════════════════════════
#  L1-native/tests — GoogleTest-based parity + unit suites
#
#  Activated when BR_BUILD_TESTS=ON in the parent configure.
#  Each subdirectory groups tests by domain (physics/, render/, …).
# ═══════════════════════════════════════════════════════════════════

include(FetchContent)
FetchContent_Declare(
    googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG        v1.14.0
    GIT_SHALLOW    TRUE
)
# Prevent GTest from overriding our compiler/linker settings on Windows.
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_subdirectory(physics)
