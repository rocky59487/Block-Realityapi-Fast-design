package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MortonCodeTest {

    @Test
    @DisplayName("encode/decode roundtrip: (0,0,0)")
    void testOrigin() {
        int code = MortonCode.encode(0, 0, 0);
        assertEquals(0, code);
        assertEquals(0, MortonCode.decodeX(code));
        assertEquals(0, MortonCode.decodeY(code));
        assertEquals(0, MortonCode.decodeZ(code));
    }

    @Test
    @DisplayName("encode/decode roundtrip: 16³ 全座標")
    void testFullRoundtrip16() {
        for (int z = 0; z < 16; z++)
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    int code = MortonCode.encode(x, y, z);
                    assertEquals(x, MortonCode.decodeX(code), "X mismatch at " + x + "," + y + "," + z);
                    assertEquals(y, MortonCode.decodeY(code), "Y mismatch at " + x + "," + y + "," + z);
                    assertEquals(z, MortonCode.decodeZ(code), "Z mismatch at " + x + "," + y + "," + z);
                }
    }

    @Test
    @DisplayName("encode/decode roundtrip: 邊界值 (1023,1023,1023)")
    void testMaxCoords() {
        int code = MortonCode.encode(1023, 1023, 1023);
        assertEquals(1023, MortonCode.decodeX(code));
        assertEquals(1023, MortonCode.decodeY(code));
        assertEquals(1023, MortonCode.decodeZ(code));
    }

    @Test
    @DisplayName("encode 唯一性：不同座標不同 code")
    void testUniqueness() {
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        for (int z = 0; z < 8; z++)
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    assertTrue(codes.add(MortonCode.encode(x, y, z)));
        assertEquals(512, codes.size());
    }

    @Test
    @DisplayName("nextPow2 正確性")
    void testNextPow2() {
        assertEquals(1, MortonCode.nextPow2(1));
        assertEquals(2, MortonCode.nextPow2(2));
        assertEquals(4, MortonCode.nextPow2(3));
        assertEquals(64, MortonCode.nextPow2(33));
        assertEquals(128, MortonCode.nextPow2(65));
        assertEquals(1024, MortonCode.nextPow2(1024));
    }
}
