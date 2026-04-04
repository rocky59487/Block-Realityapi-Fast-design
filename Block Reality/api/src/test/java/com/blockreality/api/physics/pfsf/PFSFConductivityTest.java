package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFConductivity 傳導率計算測試。
 */
class PFSFConductivityTest {

    /** 模擬混凝土材料 (Rcomp=30 MPa, Rtens=3 MPa) */
    private static final RMaterial CONCRETE = createMaterial("concrete", 30.0, 3.0, 2400);

    /** 模擬鋼材 (Rcomp=250 MPa, Rtens=250 MPa) */
    private static final RMaterial STEEL = createMaterial("steel", 250.0, 250.0, 7850);

    /** 模擬玻璃 (Rcomp=100 MPa, Rtens=5 MPa) */
    private static final RMaterial GLASS = createMaterial("glass", 100.0, 5.0, 2500);

    @Test
    @DisplayName("空氣邊傳導率為零")
    void testAirEdge() {
        assertEquals(0.0f, PFSFConductivity.sigma(CONCRETE, null, Direction.UP, 0, 0));
        assertEquals(0.0f, PFSFConductivity.sigma(null, CONCRETE, Direction.DOWN, 0, 0));
        assertEquals(0.0f, PFSFConductivity.sigma(null, null, Direction.NORTH, 0, 0));
    }

    @Test
    @DisplayName("垂直邊傳導率 = min(Rcomp_i, Rcomp_j)")
    void testVerticalEdge() {
        float sigma = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.UP, 0, 0);
        assertEquals(30.0f, sigma, 0.01f); // min(30, 30) = 30

        float sigmaMixed = PFSFConductivity.sigma(CONCRETE, STEEL, Direction.DOWN, 0, 0);
        assertEquals(30.0f, sigmaMixed, 0.01f); // min(30, 250) = 30
    }

    @Test
    @DisplayName("水平邊傳導率受抗拉修正 — 低於垂直邊")
    void testHorizontalLessThanVertical() {
        float sigmaV = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.UP, 0, 0);
        float sigmaH = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.NORTH, 0, 0);

        assertTrue(sigmaH < sigmaV,
                "水平傳導率 (" + sigmaH + ") 應小於垂直 (" + sigmaV + ")");
    }

    @Test
    @DisplayName("鋼材水平傳導率接近垂直（高抗拉）")
    void testSteelHighTension() {
        float sigmaV = PFSFConductivity.sigma(STEEL, STEEL, Direction.UP, 0, 0);
        float sigmaH = PFSFConductivity.sigma(STEEL, STEEL, Direction.NORTH, 0, 0);

        // Steel: Rtens/Rcomp = 1.0 → tensionRatio = 1.0 → 水平 = 垂直（arm=0 無衰減）
        assertEquals(sigmaV, sigmaH, 0.01f);
    }

    @Test
    @DisplayName("力臂越大，水平傳導率越低")
    void testArmDecay() {
        float sigma0 = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.NORTH, 0, 0);
        float sigma5 = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.NORTH, 5, 5);
        float sigma10 = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.NORTH, 10, 10);

        assertTrue(sigma0 > sigma5, "arm=0 (" + sigma0 + ") 應 > arm=5 (" + sigma5 + ")");
        assertTrue(sigma5 > sigma10, "arm=5 (" + sigma5 + ") 應 > arm=10 (" + sigma10 + ")");
    }

    @Test
    @DisplayName("力臂不影響垂直傳導率")
    void testArmNoEffectOnVertical() {
        float sigma0 = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.UP, 0, 0);
        float sigma10 = PFSFConductivity.sigma(CONCRETE, CONCRETE, Direction.UP, 10, 10);
        assertEquals(sigma0, sigma10, 0.001f);
    }

    @Test
    @DisplayName("dirToIndex 對應正確")
    void testDirToIndex() {
        assertEquals(PFSFConstants.DIR_NEG_X, PFSFConductivity.dirToIndex(Direction.WEST));
        assertEquals(PFSFConstants.DIR_POS_X, PFSFConductivity.dirToIndex(Direction.EAST));
        assertEquals(PFSFConstants.DIR_NEG_Y, PFSFConductivity.dirToIndex(Direction.DOWN));
        assertEquals(PFSFConstants.DIR_POS_Y, PFSFConductivity.dirToIndex(Direction.UP));
        assertEquals(PFSFConstants.DIR_NEG_Z, PFSFConductivity.dirToIndex(Direction.NORTH));
        assertEquals(PFSFConstants.DIR_POS_Z, PFSFConductivity.dirToIndex(Direction.SOUTH));
    }

    // ─── Helper ───
    private static RMaterial createMaterial(String id, double rcomp, double rtens, double density) {
        return new RMaterial() {
            @Override public double getRcomp() { return rcomp; }
            @Override public double getRtens() { return rtens; }
            @Override public double getRshear() { return rtens * 0.6; }
            @Override public double getDensity() { return density; }
            @Override public String getMaterialId() { return id; }
        };
    }
}
