/**
 * PFSF-Fluid: 邊界壓力提取 Compute Shader
 *
 * 偵測固體牆面體素，累加相鄰流體的壓力。
 * 輸出到 boundaryPressure[] 供 CPU 讀回，作為 PFSF 結構引擎的 source term。
 *
 * 只有牆面旁邊有流體（type 1-3）時才寫入非零壓力。
 */
#version 450

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

layout(push_constant) uniform PushConstants {
    uint Lx;
    uint Ly;
    uint Lz;
    float couplingFactor;  // 壓力耦合係數 (1.0)
    float minPressure;     // 最小耦合壓力閾值 (100.0 Pa)
};

layout(set = 0, binding = 0) buffer PressureBuf     { float pressure[];         };
layout(set = 0, binding = 1) buffer TypeBuf          { uint  fluidType[];        };
layout(set = 0, binding = 2) buffer VolumeBuf        { float volume[];           };
layout(set = 0, binding = 3) buffer BoundaryBuf      { float boundaryPressure[]; }; // 輸出：每體素的邊界壓力

uint flatIdx(uint x, uint y, uint z) {
    return x + y * Lx + z * Lx * Ly;
}

bool isFluid(uint typeId) {
    return typeId >= 1u && typeId <= 3u;
}

void main() {
    uvec3 gid = gl_GlobalInvocationID;
    if (gid.x >= Lx || gid.y >= Ly || gid.z >= Lz) return;

    uint idx = flatIdx(gid.x, gid.y, gid.z);
    uint myType = fluidType[idx];

    // 只處理固體牆面
    if (myType != 4u) {
        boundaryPressure[idx] = 0.0;
        return;
    }

    // 累加六鄰居中流體的壓力
    float totalPressure = 0.0;

    // +X
    if (gid.x < Lx - 1) { uint ni = flatIdx(gid.x+1, gid.y, gid.z); if (isFluid(fluidType[ni]) && volume[ni] > 1e-6) totalPressure += pressure[ni]; }
    // -X
    if (gid.x > 0)      { uint ni = flatIdx(gid.x-1, gid.y, gid.z); if (isFluid(fluidType[ni]) && volume[ni] > 1e-6) totalPressure += pressure[ni]; }
    // +Y
    if (gid.y < Ly - 1) { uint ni = flatIdx(gid.x, gid.y+1, gid.z); if (isFluid(fluidType[ni]) && volume[ni] > 1e-6) totalPressure += pressure[ni]; }
    // -Y
    if (gid.y > 0)      { uint ni = flatIdx(gid.x, gid.y-1, gid.z); if (isFluid(fluidType[ni]) && volume[ni] > 1e-6) totalPressure += pressure[ni]; }
    // +Z
    if (gid.z < Lz - 1) { uint ni = flatIdx(gid.x, gid.y, gid.z+1); if (isFluid(fluidType[ni]) && volume[ni] > 1e-6) totalPressure += pressure[ni]; }
    // -Z
    if (gid.z > 0)      { uint ni = flatIdx(gid.x, gid.y, gid.z-1); if (isFluid(fluidType[ni]) && volume[ni] > 1e-6) totalPressure += pressure[ni]; }

    // 套用耦合係數和閾值
    float coupledPressure = totalPressure * couplingFactor;
    boundaryPressure[idx] = (coupledPressure >= minPressure) ? coupledPressure : 0.0;
}
