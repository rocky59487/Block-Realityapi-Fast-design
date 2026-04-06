package com.blockreality.api.physics;

import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// TODO: DynamicMaterial 中未實作取得 Blended Young's Modulus 的方法，待實作後補測試
public class DynamicMaterialTest {

    @Test
    public void testOfRCFusionCorrectness() {
        RMaterial concrete = new RMaterial() {
            @Override public double getRcomp() { return 30.0; }
            @Override public double getRtens() { return 3.0; }
            @Override public double getRshear() { return 5.0; }
            @Override public double getDensity() { return 2400.0; }
            @Override public String getMaterialId() { return "concrete"; }
        };

        RMaterial rebar = new RMaterial() {
            @Override public double getRcomp() { return 400.0; }
            @Override public double getRtens() { return 400.0; }
            @Override public double getRshear() { return 250.0; }
            @Override public double getDensity() { return 7850.0; }
            @Override public String getMaterialId() { return "rebar"; }
        };

        double phiTens = 0.8;
        double phiShear = 0.6;
        double compBoost = 1.1;

        DynamicMaterial fusion = DynamicMaterial.ofRCFusion(concrete, rebar, phiTens, phiShear, compBoost, false);

        assertEquals(30.0 * 1.1, fusion.getRcomp(), 0.001);
        assertEquals(3.0 + 400.0 * 0.8, fusion.getRtens(), 0.001);
        assertEquals(5.0 + 250.0 * 0.6, fusion.getRshear(), 0.001);
        assertEquals("rc_fusion", fusion.getMaterialId());
    }

    @Test
    public void testHoneycombPenalty() {
        RMaterial concrete = new RMaterial() {
            @Override public double getRcomp() { return 30.0; }
            @Override public double getRtens() { return 3.0; }
            @Override public double getRshear() { return 5.0; }
            @Override public double getDensity() { return 2400.0; }
            @Override public String getMaterialId() { return "concrete"; }
        };

        RMaterial rebar = new RMaterial() {
            @Override public double getRcomp() { return 400.0; }
            @Override public double getRtens() { return 400.0; }
            @Override public double getRshear() { return 250.0; }
            @Override public double getDensity() { return 7850.0; }
            @Override public String getMaterialId() { return "rebar"; }
        };

        DynamicMaterial fusion = DynamicMaterial.ofRCFusion(concrete, rebar, 0.8, 0.6, 1.1, true);

        assertEquals((30.0 * 1.1) * 0.7, fusion.getRcomp(), 0.001);
        assertEquals((3.0 + 400.0 * 0.8) * 0.7, fusion.getRtens(), 0.001);
        assertEquals((5.0 + 250.0 * 0.6) * 0.7, fusion.getRshear(), 0.001);
        assertEquals("rc_fusion_honeycomb", fusion.getMaterialId());
    }

    @Test
    public void testOfCustom() {
        DynamicMaterial custom = DynamicMaterial.ofCustom("custom_mat", 50.0, 10.0, 15.0, 2000.0);

        assertEquals("custom_mat", custom.getMaterialId());
        assertEquals(50.0, custom.getRcomp(), 0.001);
        assertEquals(10.0, custom.getRtens(), 0.001);
        assertEquals(15.0, custom.getRshear(), 0.001);
        assertEquals(2000.0, custom.getDensity(), 0.001);
    }

    @Test
    public void testAccessorConsistency() {
        DynamicMaterial custom = DynamicMaterial.ofCustom("test", 10.0, 5.0, 2.0, 1000.0);

        assertEquals(custom.rcomp(), custom.getRcomp(), 0.0001);
        assertEquals(custom.rtens(), custom.getRtens(), 0.0001);
        assertEquals(custom.rshear(), custom.getRshear(), 0.0001);
        assertEquals(custom.density(), custom.getDensity(), 0.0001);
        assertEquals(custom.materialId(), custom.getMaterialId());
    }
}
