#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF PCG Matrix-Vector Product (Laplacian Stencil)
//
//  計算 Ap[i] = sum_j sigma_ij * (input[i] - input[j])
//  用於 6-connectivity Laplacian 矩陣-向量乘積。
//
//  與 jacobi_smooth / rbgs_smooth 使用相同的 stencil 結構，
//  但輸入/輸出分離（input → output，非 in-place）。
//
//  Workgroup: 256 threads (1D flat)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint Lx, Ly, Lz;
} pc;

layout(set = 0, binding = 0) readonly  buffer InputVec  { float inputVec[];  };
layout(set = 0, binding = 1) writeonly buffer OutputVec { float outputVec[]; };
layout(set = 0, binding = 2) readonly  buffer Cond      { float sigma[];     };
layout(set = 0, binding = 3) readonly  buffer Type      { uint  vtype[];     };

uint gIdx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

float safeInput(int gx, int gy, int gz) {
    if (gx < 0 || uint(gx) >= pc.Lx ||
        gy < 0 || uint(gy) >= pc.Ly ||
        gz < 0 || uint(gz) >= pc.Lz) return 0.0;
    return inputVec[gIdx(uint(gx), uint(gy), uint(gz))];
}

void main() {
    uint flatIdx = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;
    if (flatIdx >= N) return;

    // 還原 3D 座標
    uint gx = flatIdx % pc.Lx;
    uint rem = flatIdx / pc.Lx;
    uint gy = rem % pc.Ly;
    uint gz = rem / pc.Ly;

    uint i = flatIdx;

    // Air / Anchor: Ap = 0
    if (vtype[i] == 0u || vtype[i] == 2u) {
        outputVec[i] = 0.0;
        return;
    }

    // 6 鄰居有效性
    bool valid[6] = bool[6](
        gx > 0u, gx + 1u < pc.Lx,
        gy > 0u, gy + 1u < pc.Ly,
        gz > 0u, gz + 1u < pc.Lz
    );

    int igx = int(gx), igy = int(gy), igz = int(gz);
    float centerVal = inputVec[i];

    float neighborVal[6] = float[6](
        safeInput(igx - 1, igy, igz),
        safeInput(igx + 1, igy, igz),
        safeInput(igx, igy - 1, igz),
        safeInput(igx, igy + 1, igz),
        safeInput(igx, igy, igz - 1),
        safeInput(igx, igy, igz + 1)
    );

    // Ap[i] = sum_j sigma_ij * (input[i] - input[j])
    // This is the Laplacian stencil: A*x where A is the discrete Laplacian
    float result = 0.0;

    for (int d = 0; d < 6; d++) {
        if (!valid[d]) continue;
        float s = sigma[d * N + i]; // SoA layout
        if (s > 0.0) {
            float nv = neighborVal[d];
            if (!isnan(nv) && !isinf(nv)) {
                result += s * (centerVal - nv);
            }
        }
    }

    // NaN 防護
    if (isnan(result) || isinf(result)) result = 0.0;

    outputVec[i] = result;
}
