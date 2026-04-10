/**
 * PFSF-Fluid: Jacobi 擴散 Compute Shader
 *
 * Ghost Cell Neumann BC（零通量邊界）：
 *   對固體牆面（type==4）或域外邊界，ghost 貢獻 = H_current = phi_i + ρgh_i。
 *   確保靜水壓平衡（H = const）是精確不動點，∂H/∂n = 0 嚴格成立。
 *
 * 體積守恆：
 *   volume[] 是獨立守恆量，本 shader 不更新 volume。
 *   volume 只在 CPU 側顯式源/匯操作時改變。
 *
 * 核心方程：
 *   H(i) = phi(i) + density(i) * g * height(i)
 *   phi_new(i) = phi_old(i) + α * d * (avgH_neighbor - H(i))
 *   其中 avgH 對固體/邊界方向注入 H_ghost = H_current
 */
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

// ─── Push Constants ───
layout(push_constant) uniform PushConstants {
    uint  Lx;             // 區域 X 尺寸
    uint  Ly;             // 區域 Y 尺寸
    uint  Lz;             // 區域 Z 尺寸
    float diffusionRate;  // 擴散率 [0, 0.45]
    float gravity;        // 9.81 m/s²
    float damping;        // 阻尼因子 (0.998)
    int   originY;        // 區域世界 Y 原點
};

// ─── Storage Buffers ───
layout(set = 0, binding = 0) buffer PhiBuf     { float phi[];       }; // 當前勢能（寫入）
layout(set = 0, binding = 1) buffer PhiPrevBuf { float phiPrev[];   }; // 前一步勢能（讀取）
layout(set = 0, binding = 2) buffer DensityBuf { float density[];   }; // 密度 kg/m³
layout(set = 0, binding = 3) buffer VolumeBuf  { float volume[];    }; // 體積分率（只讀，不在此更新）
layout(set = 0, binding = 4) buffer TypeBuf    { uint  fluidType[]; }; // 0=air,1=water,4=solid
layout(set = 0, binding = 5) buffer PressBuf   { float pressure[];  }; // 靜水壓 (Pa)

// ─── Shared Memory Tile (10×10×10 含 halo 邊框) ───
// 用於加速鄰居讀取（避免全域記憶體存取）
shared float s_phi    [10][10][10];
shared uint  s_type   [10][10][10];
shared float s_density[10][10][10];

uint flatIdx(uint x, uint y, uint z) {
    return x + y * Lx + z * Lx * Ly;
}

void main() {
    uvec3 gid = gl_GlobalInvocationID;
    uint gx = gid.x, gy = gid.y, gz = gid.z;
    if (gx >= Lx || gy >= Ly || gz >= Lz) return;

    uint idx = flatIdx(gx, gy, gz);

    // ─── 載入共用記憶體 tile（含 halo） ───
    uint sx = gl_LocalInvocationID.x + 1u;
    uint sy = gl_LocalInvocationID.y + 1u;
    uint sz = gl_LocalInvocationID.z + 1u;

    s_phi    [sz][sy][sx] = phiPrev   [idx];
    s_type   [sz][sy][sx] = fluidType [idx];
    s_density[sz][sy][sx] = density   [idx];

    // 內部 halo（鄰居存在）
    if (gl_LocalInvocationID.x == 0u  && gx > 0u)
    { uint hi = flatIdx(gx-1u,gy,gz); s_phi[sz][sy][0] = phiPrev[hi]; s_type[sz][sy][0] = fluidType[hi]; s_density[sz][sy][0] = density[hi]; }
    if (gl_LocalInvocationID.x == 7u  && gx < Lx-1u)
    { uint hi = flatIdx(gx+1u,gy,gz); s_phi[sz][sy][9] = phiPrev[hi]; s_type[sz][sy][9] = fluidType[hi]; s_density[sz][sy][9] = density[hi]; }
    if (gl_LocalInvocationID.y == 0u  && gy > 0u)
    { uint hi = flatIdx(gx,gy-1u,gz); s_phi[sz][0][sx] = phiPrev[hi]; s_type[sz][0][sx] = fluidType[hi]; s_density[sz][0][sx] = density[hi]; }
    if (gl_LocalInvocationID.y == 7u  && gy < Ly-1u)
    { uint hi = flatIdx(gx,gy+1u,gz); s_phi[sz][9][sx] = phiPrev[hi]; s_type[sz][9][sx] = fluidType[hi]; s_density[sz][9][sx] = density[hi]; }
    if (gl_LocalInvocationID.z == 0u  && gz > 0u)
    { uint hi = flatIdx(gx,gy,gz-1u); s_phi[0][sy][sx] = phiPrev[hi]; s_type[0][sy][sx] = fluidType[hi]; s_density[0][sy][sx] = density[hi]; }
    if (gl_LocalInvocationID.z == 7u  && gz < Lz-1u)
    { uint hi = flatIdx(gx,gy,gz+1u); s_phi[9][sy][sx] = phiPrev[hi]; s_type[9][sy][sx] = fluidType[hi]; s_density[9][sy][sx] = density[hi]; }

    // 域外邊界 halo → 標記為 SOLID_WALL(4)，後續以 ghost cell 處理
    if (gl_LocalInvocationID.x == 0u  && gx == 0u)
        { s_type[sz][sy][0] = 4u; }
    if (gl_LocalInvocationID.x == 7u  && gx == Lx-1u)
        { s_type[sz][sy][9] = 4u; }
    if (gl_LocalInvocationID.y == 0u  && gy == 0u)
        { s_type[sz][0][sx] = 4u; }
    if (gl_LocalInvocationID.y == 7u  && gy == Ly-1u)
        { s_type[sz][9][sx] = 4u; }
    if (gl_LocalInvocationID.z == 0u  && gz == 0u)
        { s_type[0][sy][sx] = 4u; }
    if (gl_LocalInvocationID.z == 7u  && gz == Lz-1u)
        { s_type[9][sy][sx] = 4u; }

    barrier();

    // ─── 跳過非流體體素 ───
    uint myType = s_type[sz][sy][sx];
    if (myType == 0u || myType == 4u) return;  // AIR or SOLID_WALL

    float myPhi       = s_phi[sz][sy][sx];
    float myDensity   = s_density[sz][sy][sx];
    float myHeight    = float(gy) + float(originY);
    float myGravPot   = myDensity * gravity * myHeight;
    float myTotalHead = myPhi + myGravPot;   // H_current（ghost cell 貢獻值）

    // ─── 累加六鄰居（ghost cell Neumann BC：固體/域外 → 貢獻 H_current） ───
    // 使用動態 valid_count 代替硬編碼 6.0，防止壁面 ghost cell 導致壓力偏低。
    float totalH = 0.0;
    int   valid_count = 0;

    // +X
    if (s_type[sz][sy][sx+1u] == 4u) { totalH += myTotalHead; valid_count++; }
    else { float nh = myHeight; totalH += s_phi[sz][sy][sx+1u] + s_density[sz][sy][sx+1u] * gravity * nh; valid_count++; }
    // -X
    if (s_type[sz][sy][sx-1u] == 4u) { totalH += myTotalHead; valid_count++; }
    else { float nh = myHeight; totalH += s_phi[sz][sy][sx-1u] + s_density[sz][sy][sx-1u] * gravity * nh; valid_count++; }
    // +Y
    if (s_type[sz][sy+1u][sx] == 4u) { totalH += myTotalHead; valid_count++; }
    else { float nh = float(gy+1u) + float(originY); totalH += s_phi[sz][sy+1u][sx] + s_density[sz][sy+1u][sx] * gravity * nh; valid_count++; }
    // -Y
    if (s_type[sz][sy-1u][sx] == 4u) { totalH += myTotalHead; valid_count++; }
    else { float nh = float(gy-1u) + float(originY); totalH += s_phi[sz][sy-1u][sx] + s_density[sz][sy-1u][sx] * gravity * nh; valid_count++; }
    // +Z
    if (s_type[sz+1u][sy][sx] == 4u) { totalH += myTotalHead; valid_count++; }
    else { float nh = myHeight; totalH += s_phi[sz+1u][sy][sx] + s_density[sz+1u][sy][sx] * gravity * nh; valid_count++; }
    // -Z
    if (s_type[sz-1u][sy][sx] == 4u) { totalH += myTotalHead; valid_count++; }
    else { float nh = myHeight; totalH += s_phi[sz-1u][sy][sx] + s_density[sz-1u][sy][sx] * gravity * nh; valid_count++; }

    // ─── Jacobi 更新（dynamic valid_count 平均，不硬編碼 6） ───
    float avgH   = totalH / float(max(valid_count, 1));
    float newPhi = myPhi + (avgH - myGravPot - myPhi) * diffusionRate * damping;

    // 負值 + NaN/Inf 保護
    if (isnan(newPhi) || isinf(newPhi) || newPhi < 0.0) newPhi = 0.0;
    newPhi = min(newPhi, 1e8);

    phi[idx] = newPhi;
    // volume[] 不在此更新（獨立守恆量）
    pressure[idx] = newPhi;
}
