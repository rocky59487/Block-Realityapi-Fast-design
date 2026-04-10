#version 450

// ══════════════════════════════════════════════════════════════
//  PFSF Sparse Scatter — GPU 端稀疏更新散布
//  從小型 update buffer 將變更散布到大型 source/conductivity/type 陣列
//  避免 CPU 上傳整個陣列（185,000× 頻寬節省）
//
//  每筆 update = 48 bytes（12 × 4）：
//    [0..3]   int   flatIndex
//    [4..7]   float source
//    [8..11]  int   type (byte→int aligned)
//    [12..15] float maxPhi
//    [16..19] float rcomp
//    [20..23] float rtens   ← 新增抗拉強度
//    [24..47] float conductivity[6]
// ══════════════════════════════════════════════════════════════

layout(local_size_x = 64) in;

layout(push_constant) uniform PC {
    uint updateCount;    // 本次更新的體素數量
    uint totalN;         // #7-fix: 總體素數（SoA 佈局需要）
} pc;

// 打包的更新資料（由 CPU 上傳的小型 buffer）
layout(set = 0, binding = 0) readonly buffer Updates {
    // 每筆 12 個 uint/float（48 bytes = 12 × 4）
    uint updateData[];
};

// 目標大型陣列（device-local，常駐 VRAM）
layout(set = 0, binding = 1) buffer Source        { float source[];        };
layout(set = 0, binding = 2) buffer Conductivity  { float conductivity[];  };
layout(set = 0, binding = 3) buffer Type          { uint  vtype[];         };
layout(set = 0, binding = 4) buffer MaxPhiBuf     { float maxPhiArr[];     };
layout(set = 0, binding = 5) buffer RcompBuf      { float rcompArr[];      };
layout(set = 0, binding = 6) buffer RtensBuf      { float rtensArr[];      };

void main() {
    uint uid = gl_GlobalInvocationID.x;
    if (uid >= pc.updateCount) return;

    // 每筆 12 個 uint（48 bytes / 4 = 12）
    uint base = uid * 12u;

    uint  flatIndex = updateData[base + 0u];
    float src       = uintBitsToFloat(updateData[base + 1u]);
    uint  typ       = updateData[base + 2u];
    float mPhi      = uintBitsToFloat(updateData[base + 3u]);
    float rcomp     = uintBitsToFloat(updateData[base + 4u]);
    float rtens     = uintBitsToFloat(updateData[base + 5u]);

    // 散布到大型陣列
    source[flatIndex]    = src;
    vtype[flatIndex]     = typ;
    maxPhiArr[flatIndex] = mPhi;
    rcompArr[flatIndex]  = rcomp;
    rtensArr[flatIndex]  = rtens;

    // #7-fix: SoA layout — conductivity[d * N + flatIndex]（與 CPU 一致）
    uint N = pc.totalN;
    for (uint d = 0u; d < 6u; d++) {
        conductivity[d * N + flatIndex] = uintBitsToFloat(updateData[base + 6u + d]);
    }
}
