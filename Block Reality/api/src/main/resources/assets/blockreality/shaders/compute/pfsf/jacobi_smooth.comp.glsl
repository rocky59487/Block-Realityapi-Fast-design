#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF Jacobi + Chebyshev — Shared Memory Tiled 版本
//
//  B1-fix: 使用 shared memory 快取 (WG+2)³ 的 phi tile
//  每個 thread 先從 global memory 載入自身 + halo，barrier 後
//  所有鄰居讀取都走 shared memory（~1 cycle vs ~200 cycles）
//
//  Workgroup: 8×8×4 = 256 threads
//  Tile: (8+2)×(8+2)×(4+2) = 10×10×6 = 600 floats shared
//  Halo: 邊界 1-voxel 由額外 thread 載入
//
//  參考：PFSF 手冊 §4.1 + B1 優化
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 4) in;

layout(push_constant) uniform PushConstants {
    uint  Lx, Ly, Lz;
    float omega;
    float rho_spec;
    uint  iter;
    float damping;     // M1-fix: 0.0 = 無衰減, 0.995 = 啟用衰減（由 CPU 控制）
} pc;

layout(set = 0, binding = 0) buffer PhiCurrent  { float phi[];     };
layout(set = 0, binding = 1) buffer PhiPrev     { float phiPrev[]; };
layout(set = 0, binding = 2) readonly buffer Source { float rho[];  };
layout(set = 0, binding = 3) readonly buffer Cond   { float sigma[]; };
layout(set = 0, binding = 4) readonly buffer Type   { uint  vtype[]; };

// B1: Shared memory tile (workgroup + 1-voxel halo on each side)
#define TX 8
#define TY 8
#define TZ 4
#define SX (TX + 2)  // 10
#define SY (TY + 2)  // 10
#define SZ (TZ + 2)  //  6

shared float sPhi[SZ][SY][SX];  // 600 floats = 2400 bytes（遠低於 48KB 上限）

uint gIdx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

// 安全載入：超界回傳 0（air equivalent）
float safeLoadPhi(int gx, int gy, int gz) {
    if (gx < 0 || uint(gx) >= pc.Lx ||
        gy < 0 || uint(gy) >= pc.Ly ||
        gz < 0 || uint(gz) >= pc.Lz) return 0.0;
    return phiPrev[gIdx(uint(gx), uint(gy), uint(gz))];
}

void main() {
    // Global coordinates
    uint gx = gl_GlobalInvocationID.x;
    uint gy = gl_GlobalInvocationID.y;
    uint gz = gl_GlobalInvocationID.z;

    // Local coordinates in workgroup
    uint lx = gl_LocalInvocationID.x;
    uint ly = gl_LocalInvocationID.y;
    uint lz = gl_LocalInvocationID.z;

    // Shared memory coordinates (offset by 1 for halo)
    uint sx = lx + 1u;
    uint sy = ly + 1u;
    uint sz = lz + 1u;

    // ─── Phase 1: 協作載入 tile（含 halo）到 shared memory ───

    // 載入自身
    int igx = int(gx), igy = int(gy), igz = int(gz);
    sPhi[sz][sy][sx] = safeLoadPhi(igx, igy, igz);

    // 載入 halo（邊界 thread 負責相鄰 tile 的邊緣）
    // X halo
    if (lx == 0u)        sPhi[sz][sy][0u]      = safeLoadPhi(igx - 1, igy, igz);
    if (lx == TX - 1u)   sPhi[sz][sy][SX - 1u] = safeLoadPhi(igx + 1, igy, igz);
    // Y halo
    if (ly == 0u)        sPhi[sz][0u][sx]       = safeLoadPhi(igx, igy - 1, igz);
    if (ly == TY - 1u)   sPhi[sz][SY - 1u][sx]  = safeLoadPhi(igx, igy + 1, igz);
    // Z halo
    if (lz == 0u)        sPhi[0u][sy][sx]       = safeLoadPhi(igx, igy, igz - 1);
    if (lz == TZ - 1u)   sPhi[SZ - 1u][sy][sx]  = safeLoadPhi(igx, igy, igz + 1);

    barrier();

    // ─── Phase 2: 超界檢查 ───
    if (gx >= pc.Lx || gy >= pc.Ly || gz >= pc.Lz) return;

    uint i = gIdx(gx, gy, gz);

    // Anchor / Air
    if (vtype[i] == 2u) { phi[i] = 0.0; return; }
    if (vtype[i] == 0u) { phi[i] = 0.0; return; }

    // ─── Phase 3: Jacobi 更新（從 shared memory 讀鄰居！）───

    // B5-fix: 邊界有效性
    bool valid[6] = bool[6](
        gx > 0u, gx + 1u < pc.Lx,
        gy > 0u, gy + 1u < pc.Ly,
        gz > 0u, gz + 1u < pc.Lz
    );

    // 從 shared memory 讀取 6 鄰居（~1 cycle 延遲 vs global ~200 cycles）
    float neighborPhi[6] = float[6](
        sPhi[sz][sy][sx - 1u],   // -X
        sPhi[sz][sy][sx + 1u],   // +X
        sPhi[sz][sy - 1u][sx],   // -Y
        sPhi[sz][sy + 1u][sx],   // +Y
        sPhi[sz - 1u][sy][sx],   // -Z
        sPhi[sz + 1u][sy][sx]    // +Z
    );

    float sumSigma = 0.0;
    float sumNeighbor = 0.0;

    for (int d = 0; d < 6; d++) {
        if (!valid[d]) continue;

        // B2-fix: SoA layout — sigma[d * N + i] for coalesced access
        uint N = pc.Lx * pc.Ly * pc.Lz;
        float s = sigma[d * N + i];
        if (s > 0.0) {
            float np = neighborPhi[d];
            // B3-fix: NaN 防護
            if (!isnan(np) && !isinf(np)) {
                sumSigma += s;
                sumNeighbor += s * np;
            }
        }
    }

    float phi_jacobi;
    if (sumSigma > 0.0) {
        phi_jacobi = (rho[i] + sumNeighbor) / sumSigma;
    } else {
        // B4-fix: 孤立體素
        phi_jacobi = 1e7;
    }

    // ─── Chebyshev extrapolation + B3-fix clamp ───
    float prev = sPhi[sz][sy][sx];
    float result = pc.omega * (phi_jacobi - prev) + prev;

    // M1-fix: 條件化能量衰減（只在 CPU 偵測到振盪時才啟用）
    // pc.damping = 0.0 → 無衰減（正常迭代）
    // pc.damping = 0.995 → 0.5% 衰減（振盪壓制模式）
    if (pc.damping > 0.0) result *= pc.damping;

    result = clamp(result, 0.0, 1e7);
    if (isnan(result)) result = prev;

    phi[i] = result;
}
