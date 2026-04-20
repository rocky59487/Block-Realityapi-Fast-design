# ═══════════════════════════════════════════════════════════════════
#  L1-native/tests/physics — pfsf_compute parity tests
#
#  These tests run without Vulkan: they exercise the pure-CPU
#  kernels in pfsf_compute (normalize, wind_bias, timoshenko,
#  chebyshev) and assert that native outputs match the Java
#  reference formulas documented in PFSFDataBuilder.java /
#  PFSFScheduler.java. Any divergence here is a parity regression
#  regardless of convergence-tolerance tuning.
# ═══════════════════════════════════════════════════════════════════

add_executable(pfsf_parity_tests
    test_normalize_parity.cpp
    test_wind_bias_parity.cpp
    test_vcycle_heuristic_parity.cpp
)

target_link_libraries(pfsf_parity_tests
    PRIVATE
        pfsf_compute   # CPU-only kernels, no Vulkan dependency
        GTest::gtest_main
)

target_include_directories(pfsf_parity_tests
    PRIVATE
        ${CMAKE_SOURCE_DIR}/libpfsf/include
        ${CMAKE_SOURCE_DIR}/libpfsf/src
)

include(GoogleTest)
gtest_discover_tests(pfsf_parity_tests)
