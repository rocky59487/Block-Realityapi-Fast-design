/**
 * @file test_normalize_parity.cpp
 * @brief Parity tests: pfsf_normalize_soa6 vs Java PFSFDataBuilder.
 *
 * Java reference (PFSFDataBuilder.java:170-188):
 *   sigmaMax = max over all 6*N conductivity entries
 *   if sigmaMax > NORMALIZE_SIGMA_MIN (1e-6f):
 *       source[i]       /= sigmaMax
 *       rcomp[i]        /= sigmaMax
 *       rtens[i]        /= sigmaMax
 *       conductivity[j] /= sigmaMax   (all 6*N entries)
 */

#include <gtest/gtest.h>
#include <vector>
#include <cmath>
#include "pfsf/pfsf_compute.h"

static constexpr float kSigmaMin = 1e-6f;   // NORMALIZE_SIGMA_MIN
static constexpr float kTol      = 1e-5f;

// ─── Helpers ─────────────────────────────────────────────────────────

struct NormalizeInputs {
    int32_t n;
    std::vector<float> source;
    std::vector<float> rcomp;
    std::vector<float> rtens;
    std::vector<float> conductivity;  // SoA-6: [d*N + i]

    NormalizeInputs(int32_t n_, float cond_val, float src_val)
        : n(n_), source(n_, src_val), rcomp(n_, src_val * 0.5f),
          rtens(n_, src_val * 0.3f), conductivity(6 * n_, cond_val) {}
};

// Java reference implementation
static float javaRef_normalize(NormalizeInputs& inp) {
    float sigma_max = 1.0f;
    for (float c : inp.conductivity) {
        if (c > sigma_max) sigma_max = c;
    }
    if (sigma_max > kSigmaMin) {
        const float inv = 1.0f / sigma_max;
        for (float& s : inp.source)       s *= inv;
        for (float& r : inp.rcomp)        r *= inv;
        for (float& r : inp.rtens)        r *= inv;
        for (float& c : inp.conductivity) c *= inv;
    }
    return sigma_max;
}

// ─── Tests ───────────────────────────────────────────────────────────

TEST(NormalizeParity, BasicNormalization) {
    NormalizeInputs ref(4, /*cond*/ 10.0f, /*src*/ 5.0f);
    NormalizeInputs nat(4, /*cond*/ 10.0f, /*src*/ 5.0f);

    float ref_sigma = javaRef_normalize(ref);

    float nat_sigma = 0.0f;
    pfsf_normalize_soa6(nat.source.data(), nat.rcomp.data(), nat.rtens.data(),
                        nat.conductivity.data(), nullptr, nat.n, &nat_sigma);

    EXPECT_NEAR(ref_sigma, nat_sigma, kTol) << "sigmaMax mismatch";

    for (int i = 0; i < nat.n; ++i) {
        EXPECT_NEAR(ref.source[i], nat.source[i], kTol) << "source[" << i << "]";
        EXPECT_NEAR(ref.rcomp[i],  nat.rcomp[i],  kTol) << "rcomp["  << i << "]";
        EXPECT_NEAR(ref.rtens[i],  nat.rtens[i],  kTol) << "rtens["  << i << "]";
    }
    for (int j = 0; j < 6 * nat.n; ++j) {
        EXPECT_NEAR(ref.conductivity[j], nat.conductivity[j], kTol)
            << "conductivity[" << j << "]";
    }
}

TEST(NormalizeParity, SubMinSigmaIsNoOp) {
    // When all conductivities are below kSigmaMin, nothing should be scaled.
    NormalizeInputs ref(3, /*cond*/ 0.0f, /*src*/ 1.0f);
    NormalizeInputs nat(3, /*cond*/ 0.0f, /*src*/ 1.0f);

    javaRef_normalize(ref);

    float nat_sigma = 0.0f;
    pfsf_normalize_soa6(nat.source.data(), nat.rcomp.data(), nat.rtens.data(),
                        nat.conductivity.data(), nullptr, nat.n, &nat_sigma);

    // Source unchanged
    for (int i = 0; i < nat.n; ++i) {
        EXPECT_NEAR(ref.source[i], nat.source[i], kTol);
    }
}

TEST(NormalizeParity, SigmaMaxIsMaxNotSum) {
    // Place the max in a non-zero direction slot so we verify the scan
    // is truly a max, not a sum.
    NormalizeInputs ref(2, 1.0f, 1.0f);
    NormalizeInputs nat(2, 1.0f, 1.0f);

    // Bump one slot to 20.0f — only this one should be sigmaMax.
    ref.conductivity[3 * 2 + 1] = 20.0f;  // direction-3, voxel-1
    nat.conductivity[3 * 2 + 1] = 20.0f;

    float ref_sigma = javaRef_normalize(ref);

    float nat_sigma = 0.0f;
    pfsf_normalize_soa6(nat.source.data(), nat.rcomp.data(), nat.rtens.data(),
                        nat.conductivity.data(), nullptr, nat.n, &nat_sigma);

    EXPECT_NEAR(20.0f, nat_sigma, kTol);
    EXPECT_NEAR(ref_sigma, nat_sigma, kTol);
}

TEST(NormalizeParity, NullOrEmptyIsNoop) {
    float sigma = 0.0f;
    // n == 0
    pfsf_normalize_soa6(nullptr, nullptr, nullptr, nullptr, nullptr, 0, &sigma);
    EXPECT_NEAR(1.0f, sigma, kTol);

    // n < 0
    pfsf_normalize_soa6(nullptr, nullptr, nullptr, nullptr, nullptr, -1, &sigma);
    EXPECT_NEAR(1.0f, sigma, kTol);
}
