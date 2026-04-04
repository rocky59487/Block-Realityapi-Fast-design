#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Multigrid Prolongation — 修正量插值（粗 → 細）
//  三線性插值粗網格修正量 e 回細網格：phi_fine += e
//  參考：PFSF 手冊 §4.4
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 4) in;

layout(push_constant) uniform PushConstants {
    uint Lx_fine,  Ly_fine,  Lz_fine;
    uint Lx_coarse, Ly_coarse, Lz_coarse;
} pc;

layout(set = 0, binding = 0) buffer PhiFine     { float phi_fine[]; };
layout(set = 0, binding = 1) readonly buffer CorrCoarse { float correction_coarse[]; };

uint idxFine(uint x, uint y, uint z) {
    return x + pc.Lx_fine * (y + pc.Ly_fine * z);
}

uint idxCoarse(uint x, uint y, uint z) {
    return x + pc.Lx_coarse * (y + pc.Ly_coarse * z);
}

// Safe coarse grid access with boundary clamping
float sampleCoarse(int cx, int cy, int cz) {
    cx = clamp(cx, 0, int(pc.Lx_coarse) - 1);
    cy = clamp(cy, 0, int(pc.Ly_coarse) - 1);
    cz = clamp(cz, 0, int(pc.Lz_coarse) - 1);
    return correction_coarse[idxCoarse(uint(cx), uint(cy), uint(cz))];
}

void main() {
    uint fx = gl_GlobalInvocationID.x;
    uint fy = gl_GlobalInvocationID.y;
    uint fz = gl_GlobalInvocationID.z;

    if (fx >= pc.Lx_fine || fy >= pc.Ly_fine || fz >= pc.Lz_fine) return;

    uint fIdx = idxFine(fx, fy, fz);

    // Map fine position to coarse coordinate (continuous)
    // Fine cell center at (fx + 0.5) maps to coarse (fx + 0.5) / 2.0 - 0.5
    float cx_f = float(fx) * 0.5;
    float cy_f = float(fy) * 0.5;
    float cz_f = float(fz) * 0.5;

    // Trilinear interpolation
    int cx0 = int(floor(cx_f));
    int cy0 = int(floor(cy_f));
    int cz0 = int(floor(cz_f));

    float wx = cx_f - float(cx0);
    float wy = cy_f - float(cy0);
    float wz = cz_f - float(cz0);

    // 8 corners of trilinear interpolation
    float c000 = sampleCoarse(cx0,     cy0,     cz0);
    float c100 = sampleCoarse(cx0 + 1, cy0,     cz0);
    float c010 = sampleCoarse(cx0,     cy0 + 1, cz0);
    float c110 = sampleCoarse(cx0 + 1, cy0 + 1, cz0);
    float c001 = sampleCoarse(cx0,     cy0,     cz0 + 1);
    float c101 = sampleCoarse(cx0 + 1, cy0,     cz0 + 1);
    float c011 = sampleCoarse(cx0,     cy0 + 1, cz0 + 1);
    float c111 = sampleCoarse(cx0 + 1, cy0 + 1, cz0 + 1);

    float correction = mix(
        mix(mix(c000, c100, wx), mix(c010, c110, wx), wy),
        mix(mix(c001, c101, wx), mix(c011, c111, wx), wy),
        wz
    );

    // Apply correction to fine grid
    phi_fine[fIdx] += correction;
}
