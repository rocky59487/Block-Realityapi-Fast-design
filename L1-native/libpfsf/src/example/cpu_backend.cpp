#include "cpu_backend.h"

#include <pfsf/pfsf.h>
#include <pfsf/pfsf_compute.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

namespace pfsf_cli {

namespace {

inline int32_t idx3(int32_t x, int32_t y, int32_t z,
                    int32_t lx, int32_t ly) {
    return (z * ly + y) * lx + x;
}

/** Expand anchors[] list into a dense uint8 array, and the material-id
 *  field into a type array (AIR/SOLID/ANCHOR) using the fixture's
 *  registry.is_anchor flag per-material. */
void build_type_field(const Fixture& fx,
                      std::vector<uint8_t>& members_out,
                      std::vector<uint8_t>& type_out,
                      std::vector<uint8_t>& anchor_flag_out) {
    const size_t N = static_cast<size_t>(fx.lx) *
                     static_cast<size_t>(fx.ly) *
                     static_cast<size_t>(fx.lz);
    members_out.assign(N, 0);
    type_out.assign(N, /*AIR*/ 0);
    anchor_flag_out.assign(N, 0);

    for (size_t i = 0; i < N; ++i) {
        int32_t id = fx.material_voxels[i];
        if (id == 0) continue;  /* air */
        const FixtureMaterialEntry& m = lookup_material(fx, id);
        members_out[i] = 1;
        if (m.is_anchor) {
            type_out[i] = /*ANCHOR*/ 2;
            anchor_flag_out[i] = 1;
        } else {
            type_out[i] = /*SOLID*/ 1;
        }
    }
    /* Explicit anchors from fixture override per-voxel type. */
    for (const auto& a : fx.anchors) {
        if (a[0] < 0 || a[0] >= fx.lx) continue;
        if (a[1] < 0 || a[1] >= fx.ly) continue;
        if (a[2] < 0 || a[2] >= fx.lz) continue;
        int32_t i = idx3(a[0], a[1], a[2], fx.lx, fx.ly);
        members_out[i] = 1;
        type_out[i] = /*ANCHOR*/ 2;
        anchor_flag_out[i] = 1;
    }
}

/** Build initial source and material-derived rcomp/rtens/conductivity
 *  fields from the fixture's registry. Source seed is gravity-dominant:
 *  g · density on each solid voxel (matches PFSFSourceBuilder's
 *  dead-load baseline when arm/arch/timoshenko corrections are absent). */
void seed_fields(const Fixture& fx,
                 std::vector<float>& source,
                 std::vector<float>& rcomp,
                 std::vector<float>& rtens,
                 std::vector<float>& conductivity,
                 std::vector<float>& hydration) {
    const size_t N = static_cast<size_t>(fx.lx) *
                     static_cast<size_t>(fx.ly) *
                     static_cast<size_t>(fx.lz);
    source.assign(N, 0.0f);
    rcomp.assign(N, 0.0f);
    rtens.assign(N, 0.0f);
    /* Conductivity is SoA-6 (6 faces per voxel). */
    conductivity.assign(N * 6, 0.0f);
    hydration.assign(N, 1.0f);  /* cured by default; curing layer
                                   multiplies this when present. */

    constexpr float kGravity_mps2 = 9.80665f;

    for (size_t i = 0; i < N; ++i) {
        int32_t id = fx.material_voxels[i];
        if (id == 0) continue;
        const FixtureMaterialEntry& m = lookup_material(fx, id);
        rcomp[i] = m.rcomp;
        rtens[i] = m.rtens;
        /* Gravity body-load: density (kg/m^3) × g (m/s^2) → Pa → MPa. */
        source[i] = (m.density * kGravity_mps2) * 1e-6f;
        for (int d = 0; d < 6; ++d) conductivity[i * 6 + d] = m.rcomp;
    }
    /* Optional fluid-pressure augmentation: additive source contrib. */
    if (!fx.fluid_pressure.empty()) {
        for (size_t i = 0; i < N; ++i) source[i] += fx.fluid_pressure[i];
    }
    if (!fx.curing.empty()) {
        /* Curing multiplies hydration, clamped to [0,1]. */
        for (size_t i = 0; i < N; ++i) {
            float h = fx.curing[i];
            hydration[i] = std::clamp(h, 0.0f, 1.0f);
        }
    }
}

/** One Jacobi relaxation step on a 6-connectivity diffusion operator.
 *  phi_next[i] = (source[i] + Σ sigma_f · phi_neighbour_f) / (Σ sigma_f)
 *  Anchors keep phi = 0 (Dirichlet). */
void jacobi_step(const std::vector<float>& phi,
                 std::vector<float>& phi_next,
                 const std::vector<float>& source,
                 const std::vector<float>& conductivity,
                 const std::vector<uint8_t>& type,
                 int32_t lx, int32_t ly, int32_t lz) {
    const int32_t dxo[6] = { -1, 1,  0, 0,  0, 0 };
    const int32_t dyo[6] = {  0, 0, -1, 1,  0, 0 };
    const int32_t dzo[6] = {  0, 0,  0, 0, -1, 1 };

    for (int32_t z = 0; z < lz; ++z)
    for (int32_t y = 0; y < ly; ++y)
    for (int32_t x = 0; x < lx; ++x) {
        int32_t i = idx3(x, y, z, lx, ly);
        uint8_t t = type[i];
        if (t == 0 /*AIR*/ || t == 2 /*ANCHOR*/) {
            phi_next[i] = (t == 2) ? 0.0f : phi[i];
            continue;
        }
        float acc = source[i];
        float sum = 0.0f;
        for (int d = 0; d < 6; ++d) {
            int32_t nx = x + dxo[d];
            int32_t ny = y + dyo[d];
            int32_t nz = z + dzo[d];
            if (nx < 0 || ny < 0 || nz < 0) continue;
            if (nx >= lx || ny >= ly || nz >= lz) continue;
            int32_t j = idx3(nx, ny, nz, lx, ly);
            float sigma = conductivity[i * 6 + d];
            acc += sigma * phi[j];
            sum += sigma;
        }
        phi_next[i] = (sum > 1e-12f) ? (acc / sum) : phi[i];
    }
}

bool write_binary_floats(const std::string& path,
                         const std::vector<float>& data) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out.write(reinterpret_cast<const char*>(data.data()),
              static_cast<std::streamsize>(data.size() * sizeof(float)));
    return out.good();
}

} /* namespace */

int run_cpu(const Fixture& fx, const Args& args) {
    std::printf("libpfsf v%s — fixture=%s backend=cpu\n",
                pfsf_version(),
                args.fixture_path.c_str());
    std::fprintf(stderr,
                 "[pfsf_cli] --backend=cpu is a numerical-verification path.\n"
                 "          It drives pfsf_compute host primitives through a\n"
                 "          simplified 6-connectivity Jacobi loop. This is\n"
                 "          NOT the production 26-connectivity RBGS+PCG\n"
                 "          solver — convergence characteristics will differ.\n");

    const size_t N = static_cast<size_t>(fx.lx) *
                     static_cast<size_t>(fx.ly) *
                     static_cast<size_t>(fx.lz);

    std::vector<uint8_t> members, type, anchor_flag;
    build_type_field(fx, members, type, anchor_flag);

    std::vector<float> source, rcomp, rtens, conductivity, hydration;
    seed_fields(fx, source, rcomp, rtens, conductivity, hydration);

    /* Phase-1 normalisation: divide source/rcomp/rtens/conductivity by
     * sigmaMax so downstream fields land in [0, ~1]. */
    float sigma_max = 0.0f;
    pfsf_normalize_soa6(source.data(), rcomp.data(), rtens.data(),
                        conductivity.data(), hydration.data(),
                        static_cast<int32_t>(N), &sigma_max);

    /* Optional wind bias on conductivity. */
    const pfsf_vec3 wind{ fx.wind[0], fx.wind[1], fx.wind[2] };
    const float wind_mag = std::sqrt(wind.x * wind.x +
                                     wind.y * wind.y +
                                     wind.z * wind.z);
    if (wind_mag > 1e-6f) {
        pfsf_apply_wind_bias(conductivity.data(),
                             static_cast<int32_t>(N),
                             wind,
                             /*upwind_factor=*/0.2f);
    }

    /* Jacobi iteration — phi initialised to zero, one step per "tick"
     * so --ticks scales the relaxation count. */
    std::vector<float> phi(N, 0.0f);
    std::vector<float> phi_next(N, 0.0f);
    for (int32_t t = 0; t < args.ticks; ++t) {
        jacobi_step(phi, phi_next, source, conductivity, type,
                    fx.lx, fx.ly, fx.lz);
        phi.swap(phi_next);
    }

    /* Basic summary — peak phi & quick RMS for the dev eye-test. */
    float peak = 0.0f;
    double sq  = 0.0;
    size_t active = 0;
    for (size_t i = 0; i < N; ++i) {
        if (type[i] == 1) {
            float v = std::fabs(phi[i]);
            peak = std::max(peak, v);
            sq  += static_cast<double>(v) * v;
            ++active;
        }
    }
    const double rms = active ? std::sqrt(sq / static_cast<double>(active)) : 0.0;

    std::printf("  dims         = %dx%dx%d (N=%zu)\n", fx.lx, fx.ly, fx.lz, N);
    std::printf("  solid voxels = %zu\n", active);
    std::printf("  sigma_max    = %.6g\n", sigma_max);
    std::printf("  wind         = (%.2f, %.2f, %.2f)\n",
                fx.wind[0], fx.wind[1], fx.wind[2]);
    std::printf("  ticks        = %d\n", args.ticks);
    std::printf("  peak |phi|   = %.6g\n", peak);
    std::printf("  rms  |phi|   = %.6g\n", rms);

    if (!args.dump_stress.empty()) {
        if (!write_binary_floats(args.dump_stress, phi)) {
            std::fprintf(stderr, "pfsf_cli: cannot write stress dump: %s\n",
                         args.dump_stress.c_str());
            return 1;
        }
        std::printf("  dump-stress  = %s (%zu floats)\n",
                    args.dump_stress.c_str(), phi.size());
    }
    if (!args.dump_trace.empty()) {
        /* CPU backend produces no native trace-ring events. Surface the
         * condition explicitly instead of writing an empty file. */
        std::fprintf(stderr,
                     "[pfsf_cli] --dump-trace ignored on backend=cpu "
                     "(trace ring is VK-only). Use --backend=vk for trace.\n");
    }
    return 0;
}

} /* namespace pfsf_cli */
