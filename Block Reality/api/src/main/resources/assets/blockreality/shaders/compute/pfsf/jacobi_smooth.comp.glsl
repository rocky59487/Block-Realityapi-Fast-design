#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF Jacobi + Chebyshev Smooth — 核心迭代器
//  每 GPU tick 更新勢場 φ：讀 phiPrev[]，寫 phi[]
//  參考：PFSF 手冊 §4.1
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 4) in;

// Push constants: Chebyshev parameters + grid dimensions
layout(push_constant) uniform PushConstants {
    uint  Lx, Ly, Lz;        // grid dimensions
    float omega;              // Chebyshev weight (updated each iteration)
    float rho_spec;           // spectral radius estimate (~0.9995 for typical grids)
    uint  iter;               // current iteration index (for diagnostics)
} pc;

layout(set = 0, binding = 0) buffer PhiCurrent  { float phi[];     };
layout(set = 0, binding = 1) buffer PhiPrev     { float phiPrev[]; };
layout(set = 0, binding = 2) readonly buffer Source { float rho[];  };
layout(set = 0, binding = 3) readonly buffer Cond   { float sigma[]; }; // packed [i*6+dir]
layout(set = 0, binding = 4) readonly buffer Type   { uint  vtype[]; }; // 0=air,1=solid,2=anchor

uint idx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

void main() {
    uint x = gl_GlobalInvocationID.x;
    uint y = gl_GlobalInvocationID.y;
    uint z = gl_GlobalInvocationID.z;

    if (x >= pc.Lx || y >= pc.Ly || z >= pc.Lz) return;

    uint i = idx(x, y, z);

    // Anchor = Dirichlet BC: phi always 0, skip update
    if (vtype[i] == 2u) {
        phi[i] = 0.0;
        return;
    }

    // Air: no source, no conductivity, skip
    if (vtype[i] == 0u) {
        phi[i] = 0.0;
        return;
    }

    // ─── Jacobi update ───
    // phi_new = (rho_i + sum(sigma_ij * phi_j)) / sum(sigma_ij)

    float sumSigma = 0.0;
    float sumNeighbor = 0.0;

    // 6 directions: 0=-X 1=+X 2=-Y 3=+Y 4=-Z 5=+Z
    // Neighbor coordinates with boundary clamping
    uint nx[6] = uint[6](
        x > 0u      ? x - 1u : x,
        x + 1u < pc.Lx ? x + 1u : x,
        x, x, x, x
    );
    uint ny[6] = uint[6](
        y, y,
        y > 0u      ? y - 1u : y,
        y + 1u < pc.Ly ? y + 1u : y,
        y, y
    );
    uint nz[6] = uint[6](
        z, z, z, z,
        z > 0u      ? z - 1u : z,
        z + 1u < pc.Lz ? z + 1u : z
    );

    for (int d = 0; d < 6; d++) {
        float s = sigma[i * 6 + d];
        if (s > 0.0) {
            uint j = idx(nx[d], ny[d], nz[d]);
            sumSigma    += s;
            sumNeighbor += s * phiPrev[j];
        }
    }

    float phi_jacobi;
    if (sumSigma > 0.0) {
        phi_jacobi = (rho[i] + sumNeighbor) / sumSigma;
    } else {
        // Isolated solid voxel (no conductivity to neighbors): accumulate
        phi_jacobi = phiPrev[i] + rho[i];
    }

    // ─── Chebyshev extrapolation ───
    // phi_new = omega * (phi_jacobi - phiPrev[i]) + phiPrev[i]
    phi[i] = pc.omega * (phi_jacobi - phiPrev[i]) + phiPrev[i];
}
