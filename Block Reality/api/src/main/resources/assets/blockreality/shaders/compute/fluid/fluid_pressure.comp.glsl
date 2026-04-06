/**
 * PFSF-Fluid: 靜水壓計算 Compute Shader
 *
 * 從勢場計算靜水壓和體積分率。
 * 在 Jacobi 迭代完成後 dispatch，為渲染和結構耦合準備資料。
 *
 * P = density × g × h_fluid（相對於水面的深度）
 */
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

layout(push_constant) uniform PushConstants {
    uint Lx;
    uint Ly;
    uint Lz;
    float gravity;
    int originY;
};

layout(set = 0, binding = 0) buffer PhiBuf      { float phi[];      };
layout(set = 0, binding = 1) buffer DensityBuf  { float density[];  };
layout(set = 0, binding = 2) buffer VolumeBuf   { float volume[];   };
layout(set = 0, binding = 3) buffer TypeBuf     { uint  fluidType[];};
layout(set = 0, binding = 4) buffer PressureBuf { float pressure[]; };
layout(set = 0, binding = 5) buffer VelocityBuf { float velocity[]; }; // float[3N]: vx, vy, vz

uint flatIdx(uint x, uint y, uint z) {
    return x + y * Lx + z * Lx * Ly;
}

void main() {
    uvec3 gid = gl_GlobalInvocationID;
    if (gid.x >= Lx || gid.y >= Ly || gid.z >= Lz) return;

    uint idx = flatIdx(gid.x, gid.y, gid.z);
    uint myType = fluidType[idx];

    // 非流體跳過
    if (myType == 0u || myType == 4u) {
        pressure[idx] = 0.0;
        velocity[idx * 3]     = 0.0;
        velocity[idx * 3 + 1] = 0.0;
        velocity[idx * 3 + 2] = 0.0;
        return;
    }

    float myPhi = phi[idx];
    float myDensity = density[idx];

    // 壓力直接從勢能取得
    pressure[idx] = myPhi;

    // ─── 導出速度場（梯度的負方向） ───
    // v = -∇φ_total / density（用於渲染可視化）
    float phiXp = (gid.x < Lx - 1) ? phi[flatIdx(gid.x+1, gid.y, gid.z)] : myPhi;
    float phiXm = (gid.x > 0)      ? phi[flatIdx(gid.x-1, gid.y, gid.z)] : myPhi;
    float phiYp = (gid.y < Ly - 1) ? phi[flatIdx(gid.x, gid.y+1, gid.z)] : myPhi;
    float phiYm = (gid.y > 0)      ? phi[flatIdx(gid.x, gid.y-1, gid.z)] : myPhi;
    float phiZp = (gid.z < Lz - 1) ? phi[flatIdx(gid.x, gid.y, gid.z+1)] : myPhi;
    float phiZm = (gid.z > 0)      ? phi[flatIdx(gid.x, gid.y, gid.z-1)] : myPhi;

    float invDensity = (myDensity > 1e-6) ? (1.0 / myDensity) : 0.0;
    velocity[idx * 3]     = -(phiXp - phiXm) * 0.5 * invDensity;
    velocity[idx * 3 + 1] = -(phiYp - phiYm) * 0.5 * invDensity;
    velocity[idx * 3 + 2] = -(phiZp - phiZm) * 0.5 * invDensity;
}
