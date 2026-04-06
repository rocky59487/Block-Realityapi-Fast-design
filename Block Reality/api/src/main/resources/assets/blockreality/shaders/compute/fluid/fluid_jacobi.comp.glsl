/**
 * PFSF-Fluid: Jacobi 擴散 Compute Shader
 *
 * 8×8×8 workgroup，6 鄰居擴散 + 流出限制 + 雙緩衝。
 * 從 phiPrev 讀取，寫入 phi。每次 dispatch 為一步 Jacobi 迭代。
 *
 * 核心方程：
 *   totalPotential(i) = phi(i) + density(i) * g * height(i)
 *   phi_new(i) = phi_old(i) + diffusionRate * damping * (avgNeighborTotal - totalPotential(i))
 */
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

// ─── Push Constants ───
layout(push_constant) uniform PushConstants {
    uint Lx;              // 區域 X 尺寸
    uint Ly;              // 區域 Y 尺寸
    uint Lz;              // 區域 Z 尺寸
    float diffusionRate;  // 擴散率 [0, 0.45]
    float gravity;        // 9.81 m/s²
    float damping;        // 阻尼因子 (0.998)
    int originY;          // 區域世界 Y 原點
};

// ─── Storage Buffers ───
layout(set = 0, binding = 0) buffer PhiBuf      { float phi[];      };  // 當前勢能（寫入）
layout(set = 0, binding = 1) buffer PhiPrevBuf  { float phiPrev[];  };  // 前一步勢能（讀取）
layout(set = 0, binding = 2) buffer DensityBuf  { float density[];  };  // 密度 kg/m³
layout(set = 0, binding = 3) buffer VolumeBuf   { float volume[];   };  // 體積分率 [0,1]
layout(set = 0, binding = 4) buffer TypeBuf     { uint  fluidType[];};  // 0=air,1=water,...,4=solid
layout(set = 0, binding = 5) buffer PressureBuf { float pressure[]; };  // 靜水壓 (Pa)

// ─── Shared Memory Tile (10×10×10 = 1000 floats for halo) ───
shared float s_phi[10][10][10];
shared uint  s_type[10][10][10];
shared float s_density[10][10][10];

uint flatIdx(uint x, uint y, uint z) {
    return x + y * Lx + z * Lx * Ly;
}

void main() {
    uvec3 gid = gl_GlobalInvocationID;
    uvec3 lid = gl_LocalInvocationID;

    uint gx = gid.x;
    uint gy = gid.y;
    uint gz = gid.z;

    // 超出區域範圍 → 跳過
    if (gx >= Lx || gy >= Ly || gz >= Lz) return;

    uint idx = flatIdx(gx, gy, gz);

    // ─── 載入共用記憶體 tile（含 halo） ───
    // 每個 thread 載入自己的位置
    uint sx = lid.x + 1;
    uint sy = lid.y + 1;
    uint sz = lid.z + 1;

    s_phi[sz][sy][sx]     = phiPrev[idx];
    s_type[sz][sy][sx]    = fluidType[idx];
    s_density[sz][sy][sx] = density[idx];

    // Halo 載入（邊界 thread 負責）
    if (lid.x == 0 && gx > 0)
        { uint hi = flatIdx(gx-1, gy, gz); s_phi[sz][sy][0] = phiPrev[hi]; s_type[sz][sy][0] = fluidType[hi]; s_density[sz][sy][0] = density[hi]; }
    if (lid.x == 7 && gx < Lx-1)
        { uint hi = flatIdx(gx+1, gy, gz); s_phi[sz][sy][9] = phiPrev[hi]; s_type[sz][sy][9] = fluidType[hi]; s_density[sz][sy][9] = density[hi]; }
    if (lid.y == 0 && gy > 0)
        { uint hi = flatIdx(gx, gy-1, gz); s_phi[sz][0][sx] = phiPrev[hi]; s_type[sz][0][sx] = fluidType[hi]; s_density[sz][0][sx] = density[hi]; }
    if (lid.y == 7 && gy < Ly-1)
        { uint hi = flatIdx(gx, gy+1, gz); s_phi[sz][9][sx] = phiPrev[hi]; s_type[sz][9][sx] = fluidType[hi]; s_density[sz][9][sx] = density[hi]; }
    if (lid.z == 0 && gz > 0)
        { uint hi = flatIdx(gx, gy, gz-1); s_phi[0][sy][sx] = phiPrev[hi]; s_type[0][sy][sx] = fluidType[hi]; s_density[0][sy][sx] = density[hi]; }
    if (lid.z == 7 && gz < Lz-1)
        { uint hi = flatIdx(gx, gy, gz+1); s_phi[9][sy][sx] = phiPrev[hi]; s_type[9][sy][sx] = fluidType[hi]; s_density[9][sy][sx] = density[hi]; }

    // 設置邊界 halo 為自身值（Neumann BC: 零通量）
    if (lid.x == 0 && gx == 0)       { s_phi[sz][sy][0] = s_phi[sz][sy][1]; s_type[sz][sy][0] = 0u; }
    if (lid.x == 7 && gx == Lx-1)    { s_phi[sz][sy][9] = s_phi[sz][sy][8]; s_type[sz][sy][9] = 0u; }
    if (lid.y == 0 && gy == 0)        { s_phi[sz][0][sx] = s_phi[sz][1][sx]; s_type[sz][0][sx] = 0u; }
    if (lid.y == 7 && gy == Ly-1)     { s_phi[sz][9][sx] = s_phi[sz][8][sx]; s_type[sz][9][sx] = 0u; }
    if (lid.z == 0 && gz == 0)        { s_phi[0][sy][sx] = s_phi[1][sy][sx]; s_type[0][sy][sx] = 0u; }
    if (lid.z == 7 && gz == Lz-1)     { s_phi[9][sy][sx] = s_phi[8][sy][sx]; s_type[9][sy][sx] = 0u; }

    barrier();

    // ─── 跳過非流體體素 ───
    uint myType = s_type[sz][sy][sx];
    if (myType == 0u || myType == 4u) return;  // AIR or SOLID_WALL

    float myPhi     = s_phi[sz][sy][sx];
    float myDensity = s_density[sz][sy][sx];
    float myHeight  = float(gy) + float(originY);
    float gravPot   = myDensity * gravity * myHeight;

    // ─── 累加六鄰居 ───
    float totalNeighborPhi = 0.0;
    int validNeighbors = 0;

    // +X
    if (s_type[sz][sy][sx+1] != 4u) { float nh = float(gy) + float(originY); totalNeighborPhi += s_phi[sz][sy][sx+1] + s_density[sz][sy][sx+1] * gravity * nh; validNeighbors++; }
    // -X
    if (s_type[sz][sy][sx-1] != 4u) { float nh = float(gy) + float(originY); totalNeighborPhi += s_phi[sz][sy][sx-1] + s_density[sz][sy][sx-1] * gravity * nh; validNeighbors++; }
    // +Y
    if (s_type[sz][sy+1][sx] != 4u) { float nh = float(gy+1u) + float(originY); totalNeighborPhi += s_phi[sz][sy+1][sx] + s_density[sz][sy+1][sx] * gravity * nh; validNeighbors++; }
    // -Y
    if (s_type[sz][sy-1][sx] != 4u) { float nh = float(gy-1u) + float(originY); totalNeighborPhi += s_phi[sz][sy-1][sx] + s_density[sz][sy-1][sx] * gravity * nh; validNeighbors++; }
    // +Z
    if (s_type[sz+1][sy][sx] != 4u) { float nh = float(gy) + float(originY); totalNeighborPhi += s_phi[sz+1][sy][sx] + s_density[sz+1][sy][sx] * gravity * nh; validNeighbors++; }
    // -Z
    if (s_type[sz-1][sy][sx] != 4u) { float nh = float(gy) + float(originY); totalNeighborPhi += s_phi[sz-1][sy][sx] + s_density[sz-1][sy][sx] * gravity * nh; validNeighbors++; }

    if (validNeighbors == 0) return;

    // ─── Jacobi 更新 ───
    float avgNeighborPhi = totalNeighborPhi / float(validNeighbors);
    float newPhi = myPhi + (avgNeighborPhi - gravPot - myPhi) * diffusionRate * damping;

    // 非負勢能保護
    newPhi = max(newPhi, 0.0);

    // NaN/Inf 保護
    if (isnan(newPhi) || isinf(newPhi)) newPhi = 0.0;
    newPhi = min(newPhi, 1e8);

    phi[idx] = newPhi;

    // ─── 更新體積和壓力 ───
    if (myDensity > 0.0) {
        float maxPhi = myDensity * gravity * (myHeight + 1.0);
        volume[idx] = clamp(newPhi / max(maxPhi, 1e-6), 0.0, 1.0);
    }
    pressure[idx] = newPhi;
}
