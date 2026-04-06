// ═══════════════════════════════════════════════════════════════
//  Morton Z-Order 3D encode/decode for GLSL 450
//  v2 Phase B: bit-interleave (x,y,z) → 30-bit Morton code
// ═══════════════════════════════════════════════════════════════

uint mortonExpandBits(uint v) {
    v &= 0x3FFu;
    v = (v | (v << 16u)) & 0x030000FFu;
    v = (v | (v <<  8u)) & 0x0300F00Fu;
    v = (v | (v <<  4u)) & 0x030C30C3u;
    v = (v | (v <<  2u)) & 0x09249249u;
    return v;
}

uint mortonCompactBits(uint v) {
    v &= 0x09249249u;
    v = (v | (v >>  2u)) & 0x030C30C3u;
    v = (v | (v >>  4u)) & 0x0300F00Fu;
    v = (v | (v >>  8u)) & 0x030000FFu;
    v = (v | (v >> 16u)) & 0x3FFu;
    return v;
}

uint mortonEncode(uint x, uint y, uint z) {
    return mortonExpandBits(x) | (mortonExpandBits(y) << 1u) | (mortonExpandBits(z) << 2u);
}

uint mortonDecodeX(uint code) { return mortonCompactBits(code); }
uint mortonDecodeY(uint code) { return mortonCompactBits(code >> 1u); }
uint mortonDecodeZ(uint code) { return mortonCompactBits(code >> 2u); }
