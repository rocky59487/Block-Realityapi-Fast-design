/**
 * PFSF-Fluid: 速度梯度 → 像素法線擾動貼圖 Compute Shader
 *
 * 從 sub-cell 速度場計算速度梯度，輸出用於 BRWaterRenderer 法線混合的
 * 擾動貼圖（256×256，R16G16B16A16_SFLOAT）。
 *
 * 輸入：
 *   binding=0: vx[]  (sub-cell 速度 X，m/s)
 *   binding=1: vy[]  (sub-cell 速度 Y，m/s)
 *   binding=2: vz[]  (sub-cell 速度 Z，m/s)
 *   binding=3: vof[] (Volume-of-Fluid 分率 [0,1])
 *
 * 輸出：
 *   binding=4: normalMap（rgba16f image2D，256×256）
 *              XY 通道：切向擾動法線（編碼為 [0,1]，中心 0.5 對應無擾動）
 *              Z 通道：渦度量值（‖∇×v‖，歸一化）
 *              W 通道：預留（1.0）
 *
 * 法線計算：
 *   ∂vx/∂x ≈ (vx[i+1,j,k] - vx[i-1,j,k]) / (2 * cellSize)
 *   ∂vz/∂z ≈ (vz[i,j,k+1] - vz[i,j,k-1]) / (2 * cellSize)
 *   原始法線 = normalize((-∂vx/∂x, 1.0, -∂vz/∂z) * DETAIL_SCALE)
 *   輸出 = normal * 0.5 + 0.5（解碼：normal = texel * 2.0 - 1.0）
 *
 * 渦度計算（Z 分量）：
 *   ω_y = ∂vz/∂x - ∂vx/∂z（Y 軸渦度，對水面最重要）
 *   ‖∇×v‖_norm = clamp(|ω_y| / VORTICITY_SCALE, 0, 1)
 *
 * 解析度映射：
 *   輸出貼圖 256×256 對應 sub-cell 網格的 XZ 截面（中層 Y=subSY/2）。
 *   sub-cell 尺寸 0.1m，每個 texel 對應一個 sub-cell（最多 256 格）。
 *   網格尺寸 < 256 時，貼圖其餘部分填充中性法線（0.5, 0.5, 1.0）。
 *
 * @see BRWaterRenderer（消費此貼圖，60% Gerstner + 40% 物理法線混合）
 * @version 1.0
 */
#version 450

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

// ─── Push Constants ───
layout(push_constant) uniform PushConstants {
    uint  subSX;          // sub-cell 網格 X 尺寸
    uint  subSY;          // sub-cell 網格 Y 尺寸
    uint  subSZ;          // sub-cell 網格 Z 尺寸
    float cellSize;       // sub-cell 邊長（公尺，通常 0.1）
    float detailScale;    // 法線擾動強度（建議 0.15）
    float vorticityScale; // 渦度歸一化係數（建議 5.0，rad/s）
};

// ─── 輸入緩衝（sub-cell SoA，SSBO）───
layout(std430, binding = 0) readonly buffer VxBuf   { float vx[]; };
layout(std430, binding = 1) readonly buffer VyBuf   { float vy[]; };
layout(std430, binding = 2) readonly buffer VzBuf   { float vz[]; };
layout(std430, binding = 3) readonly buffer VofBuf  { float vof[]; };

// ─── 輸出法線貼圖（rgba16f image2D）───
layout(rgba16f, binding = 4) writeonly uniform image2D normalMap;

// ─── 常數 ───
const float MIN_VOF      = 0.1;   // VOF < 此值視為空氣
const float NEUTRAL_NX   = 0.5;   // 無擾動法線 X（[0,1] 編碼）
const float NEUTRAL_NY   = 0.5;   // 無擾動法線 Y（[0,1] 編碼）
const float NEUTRAL_NZ   = 1.0;   // 無擾動法線 Z（直上）

// ─── 工具函數 ───

/** sub-cell 平坦索引（Y 取中層截面 midY） */
uint flatIdx(uint gx, uint midY, uint gz) {
    return gx + midY * subSX + gz * subSX * subSY;
}

/** 安全取 vx（邊界 clamp） */
float sampleVx(int gx, int midY, int gz) {
    int x = clamp(gx, 0, int(subSX) - 1);
    int z = clamp(gz, 0, int(subSZ) - 1);
    return vx[uint(x) + uint(midY) * subSX + uint(z) * subSX * subSY];
}

float sampleVz(int gx, int midY, int gz) {
    int x = clamp(gx, 0, int(subSX) - 1);
    int z = clamp(gz, 0, int(subSZ) - 1);
    return vz[uint(x) + uint(midY) * subSX + uint(z) * subSX * subSY];
}

void main() {
    // 輸出貼圖座標（256×256）
    ivec2 texCoord = ivec2(gl_GlobalInvocationID.xy);
    if (texCoord.x >= 256 || texCoord.y >= 256) return;

    // 貼圖 texel → sub-cell (gx, gz) 對應（texCoord.y → gz）
    int gx = texCoord.x;
    int gz = texCoord.y;

    // 超出 sub-cell 範圍 → 輸出中性法線
    if (uint(gx) >= subSX || uint(gz) >= subSZ) {
        imageStore(normalMap, texCoord, vec4(NEUTRAL_NX, NEUTRAL_NY, NEUTRAL_NZ, 1.0));
        return;
    }

    // 取水面中層 Y（最接近自由表面的 sub-cell 層）
    int midY = int(subSY) / 2;

    // VOF 為空氣 → 輸出中性法線
    uint centerIdx = flatIdx(uint(gx), uint(midY), uint(gz));
    if (vof[centerIdx] < MIN_VOF) {
        imageStore(normalMap, texCoord, vec4(NEUTRAL_NX, NEUTRAL_NY, NEUTRAL_NZ, 1.0));
        return;
    }

    // ─── 速度梯度計算（中心差分） ───
    float inv2h = 1.0 / (2.0 * cellSize);

    float dvxdx = (sampleVx(gx + 1, midY, gz) - sampleVx(gx - 1, midY, gz)) * inv2h;
    float dvzdz = (sampleVz(gx, midY, gz + 1) - sampleVz(gx, midY, gz - 1)) * inv2h;

    // ─── 渦度 Y 分量（∂vz/∂x - ∂vx/∂z） ───
    float dvzdx = (sampleVz(gx + 1, midY, gz) - sampleVz(gx - 1, midY, gz)) * inv2h;
    float dvxdz = (sampleVx(gx, midY, gz + 1) - sampleVx(gx, midY, gz - 1)) * inv2h;
    float vortY  = dvzdx - dvxdz;

    // ─── 法線擾動（速度梯度投影到水面切平面） ───
    // 法線近似：(x, y, z) = normalize(-∂vx/∂x, 1/detailScale, -∂vz/∂z)
    vec3 rawNormal = vec3(-dvxdx * detailScale, 1.0, -dvzdz * detailScale);
    vec3 n = normalize(rawNormal);

    // 渦度歸一化到 [0,1]
    float vortNorm = clamp(abs(vortY) / vorticityScale, 0.0, 1.0);

    // 編碼到 [0,1]（供 BRWaterRenderer 解碼：normal = texel * 2.0 - 1.0）
    vec4 encoded = vec4(
        n.x * 0.5 + 0.5,
        n.z * 0.5 + 0.5,  // Z → texel G（Y 軸朝上，xz 為水平面）
        vortNorm,
        1.0
    );

    imageStore(normalMap, texCoord, encoded);
}
