/**
 * @file test_wind_bias_parity.cpp
 * @brief Parity tests: pfsf_apply_wind_bias vs Java PFSFConductivity.
 *
 * Java reference (PFSFConductivity.java:74-85, per-edge inline):
 *   dot = step_d · wind_xz
 *   if (dot > 0): sigma *= (1 + k)
 *   if (dot < 0): sigma /= (1 + k)
 *   UP/DOWN (d=2,3) never biased.
 *
 * SoA layout: conductivity[d * N + i]
 *   d=0 (-X), d=1 (+X), d=2 (-Y), d=3 (+Y), d=4 (-Z), d=5 (+Z)
 */

#include <gtest/gtest.h>
#include <vector>
#include <cmath>
#include "pfsf/pfsf_compute.h"

static constexpr float kTol = 1e-5f;

// Java reference for a single direction slot.
// step_x, step_z: the unit step for direction d in grid coords.
static float javaRef_biasSlot(float sigma, int step_x, int step_z,
                               float wx, float wz, float k) {
    const float dot = static_cast<float>(step_x) * wx
                    + static_cast<float>(step_z) * wz;
    if (dot > 0.0f) return sigma * (1.0f + k);
    if (dot < 0.0f) return sigma / (1.0f + k);
    return sigma;
}

static void refApplyWindBias(float* conductivity, int32_t n,
                              float wx, float wz, float k) {
    constexpr int32_t step_x[6] = { -1, +1,  0,  0,  0,  0 };
    constexpr int32_t step_z[6] = {  0,  0,  0,  0, -1, +1 };
    for (int d = 0; d < 6; ++d) {
        if (d == 2 || d == 3) continue;  // Y dirs skip
        const float dot = static_cast<float>(step_x[d]) * wx
                        + static_cast<float>(step_z[d]) * wz;
        if (dot == 0.0f) continue;
        float* slot = conductivity + d * n;
        if (dot > 0.0f) {
            for (int i = 0; i < n; ++i) slot[i] *= (1.0f + k);
        } else {
            const float inv = 1.0f / (1.0f + k);
            for (int i = 0; i < n; ++i) slot[i] *= inv;
        }
    }
}

TEST(WindBiasParity, PositiveXWind) {
    constexpr int n = 4;
    constexpr float k = 0.3f;

    std::vector<float> ref_cond(6 * n, 1.0f);
    std::vector<float> nat_cond(6 * n, 1.0f);

    refApplyWindBias(ref_cond.data(), n, /*wx*/ 1.0f, /*wz*/ 0.0f, k);

    pfsf_vec3 wind{ 1.0f, 0.0f, 0.0f };
    pfsf_apply_wind_bias(nat_cond.data(), n, wind, k);

    for (int j = 0; j < 6 * n; ++j) {
        EXPECT_NEAR(ref_cond[j], nat_cond[j], kTol) << "j=" << j;
    }
}

TEST(WindBiasParity, DiagonalWind_XZ) {
    constexpr int n = 3;
    constexpr float k = 0.25f;

    std::vector<float> ref_cond(6 * n, 2.0f);
    std::vector<float> nat_cond(6 * n, 2.0f);

    refApplyWindBias(ref_cond.data(), n, 0.707f, 0.707f, k);

    pfsf_vec3 wind{ 0.707f, 0.0f, 0.707f };
    pfsf_apply_wind_bias(nat_cond.data(), n, wind, k);

    for (int j = 0; j < 6 * n; ++j) {
        EXPECT_NEAR(ref_cond[j], nat_cond[j], kTol) << "j=" << j;
    }
}

TEST(WindBiasParity, ZeroWindIsNoOp) {
    constexpr int n = 5;
    constexpr float k = 0.5f;

    std::vector<float> cond(6 * n, 3.0f);
    std::vector<float> orig(cond);

    pfsf_vec3 wind{ 0.0f, 0.0f, 0.0f };
    pfsf_apply_wind_bias(cond.data(), n, wind, k);

    for (int j = 0; j < 6 * n; ++j) {
        EXPECT_NEAR(orig[j], cond[j], kTol) << "j=" << j;
    }
}

TEST(WindBiasParity, YDirectionsUnaffected) {
    constexpr int n = 4;
    constexpr float k = 0.4f;

    std::vector<float> cond(6 * n, 1.0f);

    pfsf_vec3 wind{ 1.0f, 0.0f, 0.0f };
    pfsf_apply_wind_bias(cond.data(), n, wind, k);

    // d=2 (-Y) and d=3 (+Y) must be unchanged = 1.0f.
    for (int i = 0; i < n; ++i) {
        EXPECT_NEAR(1.0f, cond[2 * n + i], kTol) << "-Y slot i=" << i;
        EXPECT_NEAR(1.0f, cond[3 * n + i], kTol) << "+Y slot i=" << i;
    }
}
