package com.blockreality.api.physics.pfsf;

/**
 * Morton Z-Order 3D 編碼/解碼工具。
 *
 * <p>v2 Phase B：將 3D 座標 (x,y,z) 交錯編碼為 1D Morton code，
 * 使 GPU 3D stencil 操作的 L1/L2 cache hit 率提升 15-30%。</p>
 *
 * <p>支援每軸最大 1024（10 bits），Morton code 最大 30 bits（int 安全）。</p>
 */
public final class MortonCode {

    private MortonCode() {}

    /**
     * 將 x 的低 10 bits 擴展為每隔 2 bits 插入 0 的格式。
     * 例如：0b1010 → 0b001_000_001_000
     */
    private static int expandBits(int v) {
        v &= 0x3FF; // 10 bits
        v = (v | (v << 16)) & 0x030000FF;
        v = (v | (v <<  8)) & 0x0300F00F;
        v = (v | (v <<  4)) & 0x030C30C3;
        v = (v | (v <<  2)) & 0x09249249;
        return v;
    }

    /**
     * expandBits 的逆運算：壓縮每隔 2 bits 取出的值。
     */
    private static int compactBits(int v) {
        v &= 0x09249249;
        v = (v | (v >>  2)) & 0x030C30C3;
        v = (v | (v >>  4)) & 0x0300F00F;
        v = (v | (v >>  8)) & 0x030000FF;
        v = (v | (v >> 16)) & 0x3FF;
        return v;
    }

    /**
     * 3D → Morton code。
     * @param x X 座標 (0-1023)
     * @param y Y 座標 (0-1023)
     * @param z Z 座標 (0-1023)
     * @return Morton code (30 bits)
     */
    public static int encode(int x, int y, int z) {
        return expandBits(x) | (expandBits(y) << 1) | (expandBits(z) << 2);
    }

    /** Morton code → X 座標 */
    public static int decodeX(int code) { return compactBits(code); }
    /** Morton code → Y 座標 */
    public static int decodeY(int code) { return compactBits(code >> 1); }
    /** Morton code → Z 座標 */
    public static int decodeZ(int code) { return compactBits(code >> 2); }

    /** 取下一個 ≥ v 的 2 的冪次 */
    public static int nextPow2(int v) {
        if (v <= 1) return 1;
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        return v + 1;
    }
}
