#version 460
#extension GL_EXT_shader_explicit_arithmetic_types_int32 : require

// ══════════════════════════════════════════════════════════════════════════════
// sdf_update.comp.glsl — SDF Volume 增量更新（Jump Flooding Algorithm）
//
// 從 occupancy bitmask 重建 Signed Distance Field。
// 使用 JFA（Jump Flooding Algorithm）在 O(log N) passes 內生成近似 SDF。
//
// 概念：
//   - 每個 texel 儲存最近表面點的座標（種子）
//   - 每個 pass 以遞減步長（N/2, N/4, ..., 1）檢查 26-鄰居
//   - 選擇產生最短距離的種子
//   - 最終 pass 後從種子計算有符號距離並寫入 3D texture
//
// Bindings:
//   set 0, b0: occupancy SSBO (uint[], 1 bit per block, readonly)
//   set 0, b1: SDF 3D image  (r16f, imageStore)
//
// Push constants:
//   stepSize (int)   — 當前 JFA pass 的步長
//   dimX/Y/Z (int)   — Volume 每軸解析度
//
// 參考：Rong & Tan, "Jump Flooding in GPU with Applications to
//        Voronoi Diagram and Distance Transform", I3D 2006
// ══════════════════════════════════════════════════════════════════════════════

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

// ── Bindings ────────────────────────────────────────────────────────────────

// Occupancy bitmask: 1 bit per voxel, packed in uint32 (32 voxels per uint)
layout(set = 0, binding = 0) readonly buffer OccupancyBuffer {
    uint occupancy[];
} occBuf;

// SDF 3D output image (R16F: positive = outside, negative = inside, 0 = surface)
layout(set = 0, binding = 1, r16f) uniform image3D sdfVolume;

// ── Push Constants ──────────────────────────────────────────────────────────

layout(push_constant) uniform PushConstants {
    int stepSize;   // JFA step size for this pass (N/2, N/4, ..., 1)
    int dimX;       // Volume dimension X
    int dimY;       // Volume dimension Y
    int dimZ;       // Volume dimension Z
} pc;

// ── Shared memory for seed propagation ──────────────────────────────────────

// Each texel's seed coordinate (packed as ivec3; -1 = no seed)
// During JFA, we propagate seeds; after final pass, compute distance from seed

// ── Helper Functions ────────────────────────────────────────────────────────

// Check if voxel at (x,y,z) is occupied (solid)
bool isOccupied(int x, int y, int z) {
    if (x < 0 || y < 0 || z < 0 || x >= pc.dimX || y >= pc.dimY || z >= pc.dimZ)
        return false;
    int flatIdx = z * pc.dimY * pc.dimX + y * pc.dimX + x;
    int wordIdx = flatIdx >> 5;   // / 32
    int bitIdx  = flatIdx & 31;   // % 32
    return (occBuf.occupancy[wordIdx] & (1u << bitIdx)) != 0u;
}

// Check if voxel is on a surface (has at least one air neighbor)
bool isSurface(int x, int y, int z) {
    if (!isOccupied(x, y, z)) return false;
    // Check 6-connected neighbors
    return !isOccupied(x-1, y, z) || !isOccupied(x+1, y, z) ||
           !isOccupied(x, y-1, z) || !isOccupied(x, y+1, z) ||
           !isOccupied(x, y, z-1) || !isOccupied(x, y, z+1);
}

// ── Main ────────────────────────────────────────────────────────────────────

void main() {
    ivec3 pos = ivec3(gl_GlobalInvocationID.xyz);

    // Bounds check
    if (pos.x >= pc.dimX || pos.y >= pc.dimY || pos.z >= pc.dimZ)
        return;

    int step = pc.stepSize;

    if (step == pc.dimX) {
        // ═══ SEED PASS (first pass): Initialize seeds ═══
        // Surface voxels seed themselves; others get infinity
        if (isSurface(pos.x, pos.y, pos.z)) {
            // Store 0 distance at surface
            imageStore(sdfVolume, pos, vec4(0.0));
        } else {
            // Store large positive value (will be refined by JFA)
            float sign = isOccupied(pos.x, pos.y, pos.z) ? -1.0 : 1.0;
            imageStore(sdfVolume, pos, vec4(sign * 9999.0));
        }
        return;
    }

    // ═══ JFA PASS: Propagate nearest surface distance ═══

    float bestDist = abs(imageLoad(sdfVolume, pos).r);
    float currentVal = imageLoad(sdfVolume, pos).r;
    float sign = currentVal < 0.0 ? -1.0 : 1.0;

    // Check 26 neighbors at stride 'step'
    for (int dz = -1; dz <= 1; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;

                ivec3 neighbor = pos + ivec3(dx, dy, dz) * step;

                // Bounds check
                if (neighbor.x < 0 || neighbor.y < 0 || neighbor.z < 0 ||
                    neighbor.x >= pc.dimX || neighbor.y >= pc.dimY || neighbor.z >= pc.dimZ)
                    continue;

                float neighborDist = abs(imageLoad(sdfVolume, neighbor).r);

                // Distance from current position through neighbor's seed
                float candidateDist = neighborDist + length(vec3(dx, dy, dz) * float(step));

                if (candidateDist < bestDist) {
                    bestDist = candidateDist;
                }
            }
        }
    }

    // Write back with correct sign (negative inside, positive outside)
    imageStore(sdfVolume, pos, vec4(sign * bestDist));
}
