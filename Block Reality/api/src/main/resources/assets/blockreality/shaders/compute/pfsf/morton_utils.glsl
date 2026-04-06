// ═══════════════════════════════════════════════════════════════
//  PFSF Morton Z-Order Utilities
//
//  v2.1 新增：Hybrid Tiled Morton 記憶體佈局工具函式。
//
//  理論基礎：
//    Mellor-Crummey et al. (2001)：Morton 空間填充曲線在 3D stencil
//    運算中的 L2 Cache 命中率優勢（比線性佈局提升 ~1.8×）。
//    Raman & Wise (2008)：Magic bits 位元擴展的快速實作。
//    Pascucci & Frank (2001)：實時探索超大型網格的全局靜態索引。
//
//  佈局設計（Hybrid Tiled Morton Layout）：
//    - 將 island 分割為 8×8×8 micro-blocks（B=8，B³=512 體素）
//    - micro-block 內部：9-bit Morton code（expandBits 位元交錯）
//    - micro-block 之間：線性排列（block_offsets[] 記錄起始偏移）
//    - 非完整邊緣 block 只產生少量 padding，全局填充率 >90%
//
//  在 Shader 中使用：
//    #include "morton_utils.glsl"  （VulkanComputeContext.compileGLSL 預處理）
//    uint idx = mortonGlobalIndex(gx, gy, gz, Lx, Ly, Lz, B, block_offsets);
//
//  CPU 端對應：PFSFDataBuilder.buildMortonLayout()
//               PFSFDataBuilder.morton3D() / expandBits()
// ═══════════════════════════════════════════════════════════════

#ifndef MORTON_UTILS_GLSL
#define MORTON_UTILS_GLSL

// ─── Morton 位元擴展（Magic bits 演算法）───
// 將 10-bit 整數 v 擴展為 30-bit，每個原始 bit 間隔 2 個零位。
uint mortonExpandBits(uint v) {
    v = (v * 0x00010001u) & 0xFF0000FFu;
    v = (v * 0x00000101u) & 0x0F00F00Fu;
    v = (v * 0x00000011u) & 0xC30C30C3u;
    v = (v * 0x00000005u) & 0x49249249u;
    return v;
}

// ─── Morton 位元壓縮（decode 逆操作）───
uint mortonCompactBits(uint v) {
    v &= 0x09249249u;
    v = (v | (v >>  2u)) & 0x030C30C3u;
    v = (v | (v >>  4u)) & 0x0300F00Fu;
    v = (v | (v >>  8u)) & 0x030000FFu;
    v = (v | (v >> 16u)) & 0x3FFu;
    return v;
}

// ─── 3D Morton code（Z-order curve）───
uint morton3D(uvec3 localPos) {
    return mortonExpandBits(localPos.x)
         | (mortonExpandBits(localPos.y) << 1u)
         | (mortonExpandBits(localPos.z) << 2u);
}

// ─── Morton encode/decode（完整介面）───
uint mortonEncode(uint x, uint y, uint z) {
    return mortonExpandBits(x) | (mortonExpandBits(y) << 1u) | (mortonExpandBits(z) << 2u);
}
uint mortonDecodeX(uint code) { return mortonCompactBits(code); }
uint mortonDecodeY(uint code) { return mortonCompactBits(code >> 1u); }
uint mortonDecodeZ(uint code) { return mortonCompactBits(code >> 2u); }

// ─── 全局 Morton 索引計算（Hybrid Tiled Layout）───
uint mortonGlobalIndex(uint gx, uint gy, uint gz,
                        uint Lx, uint Ly, uint Lz,
                        uint B,
                        readonly uint[] blockOffsets) {
    uint bx = gx / B, by = gy / B, bz = gz / B;
    uint bLx = (Lx + B - 1u) / B;
    uint bLy = (Ly + B - 1u) / B;
    uint blockIdx = bx + bLx * (by + bLy * bz);
    uvec3 local = uvec3(gx % B, gy % B, gz % B);
    return blockOffsets[blockIdx] + morton3D(local);
}

// ─── 線性回退索引（Morton 未啟用時的降級模式）───
uint linearIndex(uint gx, uint gy, uint gz, uint Lx, uint Ly) {
    return gx + Lx * (gy + Ly * gz);
}

#endif // MORTON_UTILS_GLSL
