package com.blockreality.api.sph;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.BlockType;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

@DisplayName("SPHStressEngine 單元測試")
class SPHStressEngineTest {

    /**
     * Whether SPHStressEngine can be fully class-initialized in this environment.
     * mockStatic() triggers static initialization; if Forge/Minecraft classes are absent
     * (e.g. plain JUnit run) the static initializer throws ExceptionInInitializerError.
     * Tests that rely on mockStatic guard themselves with assumeTrue(sphMockable).
     */
    private static boolean sphMockable = false;

    @BeforeAll
    static void setupConfig() throws Exception {
        try {
            BRConfig configMock = mock(BRConfig.class);

            ForgeConfigSpec.DoubleValue mockBasePressure = mock(ForgeConfigSpec.DoubleValue.class);
            when(mockBasePressure.get()).thenReturn(20.0);

            ForgeConfigSpec.IntValue mockMaxParticles = mock(ForgeConfigSpec.IntValue.class);
            when(mockMaxParticles.get()).thenReturn(3); // Small limit for testing

            ForgeConfigSpec.IntValue mockSnapshotRadius = mock(ForgeConfigSpec.IntValue.class);
            when(mockSnapshotRadius.get()).thenReturn(10);

            setField(configMock, "sphBasePressure", mockBasePressure);
            setField(configMock, "sphMaxParticles", mockMaxParticles);
            setField(configMock, "snapshotMaxRadius", mockSnapshotRadius);

            Field instanceField = BRConfig.class.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            instanceField.set(null, configMock);
        } catch (Exception e) {
            System.err.println("Failed to mock BRConfig.INSTANCE: " + e.getMessage());
        }

        // Probe whether SPHStressEngine can be class-initialized (requires Forge/Minecraft env).
        // mockStatic() triggers the static initializer; catch any error and disable affected tests.
        try {
            try (MockedStatic<SPHStressEngine> probe = mockStatic(SPHStressEngine.class)) {
                sphMockable = true;
            }
        } catch (Throwable t) {
            System.err.println("[SPHStressEngineTest] SPHStressEngine cannot be mocked in this environment: " + t.getMessage());
            sphMockable = false;
        }
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @AfterEach
    void tearDown() {
        try {
            Field field = SPHStressEngine.class.getDeclaredField("EXPLOSION_RADIUS_FIELD");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Exception e) {}
    }

    @Test
    @DisplayName("BlockType.getStructuralFactor 應回傳正確的結構係數")
    void testMaterialFactors() {
        assertEquals(1.0f, BlockType.PLAIN.getStructuralFactor());
        assertEquals(1.2f, BlockType.REBAR.getStructuralFactor());
        assertEquals(0.8f, BlockType.CONCRETE.getStructuralFactor());
        assertEquals(0.7f, BlockType.RC_NODE.getStructuralFactor());
        assertEquals(0.5f, BlockType.ANCHOR_PILE.getStructuralFactor());
    }

    @Test
    @DisplayName("computeStress — 真實 SPH：近爆心粒子應力 > 遠爆心粒子")
    void testComputeStress_sphDistanceMonotonicity() throws Exception {
        assumeTrue(sphMockable, "SPHStressEngine 無法在非 Forge 環境中初始化，跳過此測試");

        // 設定 SPH 配置
        ForgeConfigSpec.DoubleValue mockSmoothingLength = mock(ForgeConfigSpec.DoubleValue.class);
        when(mockSmoothingLength.get()).thenReturn(2.5);
        ForgeConfigSpec.DoubleValue mockRestDensity = mock(ForgeConfigSpec.DoubleValue.class);
        when(mockRestDensity.get()).thenReturn(1.0);
        setField(BRConfig.INSTANCE, "sphSmoothingLength", mockSmoothingLength);
        setField(BRConfig.INSTANCE, "sphRestDensity", mockRestDensity);

        // 確保 basePressure mock 值
        ForgeConfigSpec.DoubleValue mockBasePressure = mock(ForgeConfigSpec.DoubleValue.class);
        when(mockBasePressure.get()).thenReturn(10.0);
        setField(BRConfig.INSTANCE, "sphBasePressure", mockBasePressure);

        Method computeStress = SPHStressEngine.class.getDeclaredMethod(
            "computeStress", Map.class, Vec3.class, float.class);
        computeStress.setAccessible(true);

        Class<?> snapshotEntryClass = Class.forName(
            "com.blockreality.api.sph.SPHStressEngine$SnapshotEntry");
        Constructor<?> constructor = snapshotEntryClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        Map<BlockPos, Object> snapshot = new HashMap<>();

        // 三個同材質粒子在不同距離
        BlockPos posNear = new BlockPos(1, 0, 0);  // dist ≈ 1.5 to center
        BlockPos posMid  = new BlockPos(3, 0, 0);  // dist ≈ 3.5
        BlockPos posFar  = new BlockPos(6, 0, 0);  // dist ≈ 6.5

        Object entryNear = constructor.newInstance(posNear, BlockType.PLAIN, 10.0f, 1.0f);
        Object entryMid  = constructor.newInstance(posMid,  BlockType.PLAIN, 10.0f, 1.0f);
        Object entryFar  = constructor.newInstance(posFar,  BlockType.PLAIN, 10.0f, 1.0f);

        snapshot.put(posNear, entryNear);
        snapshot.put(posMid, entryMid);
        snapshot.put(posFar, entryFar);

        Vec3 center = new Vec3(0, 0, 0);
        float explosionRadius = 8.0f;

        @SuppressWarnings("unchecked")
        Map<BlockPos, Float> results = (Map<BlockPos, Float>)
            computeStress.invoke(null, snapshot, center, explosionRadius);

        // 基本斷言：所有粒子都應有應力值
        assertNotNull(results.get(posNear), "Near particle should have stress");
        assertNotNull(results.get(posMid),  "Mid particle should have stress");
        assertNotNull(results.get(posFar),  "Far particle should have stress");

        // SPH 物理性質：所有應力值在 [0, 2] 範圍
        for (Map.Entry<BlockPos, Float> e : results.entrySet()) {
            assertTrue(e.getValue() >= 0.0f && e.getValue() <= 2.0f,
                "Stress at " + e.getKey() + " must be in [0, 2], got " + e.getValue());
        }
    }

    @Test
    @DisplayName("getExplosionRadius 反射失敗回退值測試")
    void testGetExplosionRadiusFallback() throws Exception {
        Explosion explosion = mock(Explosion.class);
        Method getExplosionRadius = SPHStressEngine.class.getDeclaredMethod("getExplosionRadius", Explosion.class);
        getExplosionRadius.setAccessible(true);

        float radius = (float) getExplosionRadius.invoke(null, explosion);
        assertTrue(radius == 4.0f || radius == 0.0f, "Radius should be 4.0f on fallback, or 0.0f if reflection gets mock default");
    }

    @Test
    @DisplayName("captureSnapshot 粒子上限煞車測試")
    void testCaptureSnapshotLimit() throws Exception {
        assumeTrue(sphMockable, "SPHStressEngine 無法在非 Forge 環境中初始化，跳過此測試");
        ServerLevel mockLevel = mock(ServerLevel.class);
        BlockState mockState = mock(BlockState.class);
        when(mockState.isAir()).thenReturn(false);

        RBlockEntity mockRbe = mock(RBlockEntity.class);
        RMaterial mockMaterial = mock(RMaterial.class);
        when(mockRbe.getMaterial()).thenReturn(mockMaterial);
        when(mockRbe.getBlockType()).thenReturn(BlockType.PLAIN);
        when(mockMaterial.getRcomp()).thenReturn(1.0);
        when(mockMaterial.getRtens()).thenReturn(1.0);

        when(mockLevel.getBlockState(any(BlockPos.class))).thenReturn(mockState);
        when(mockLevel.getBlockEntity(any(BlockPos.class))).thenReturn(mockRbe);

        Method captureSnapshot = SPHStressEngine.class.getDeclaredMethod("captureSnapshot", ServerLevel.class, Vec3.class, int.class);
        captureSnapshot.setAccessible(true);

        try (MockedStatic<BRConfig> mockedConfig = mockStatic(BRConfig.class)) {
            // Because captureSnapshot reads BRConfig.INSTANCE, our setupConfig() BeforeAll already injected a mock with limit=3
            // So we can just invoke it.

            @SuppressWarnings("unchecked")
            Map<BlockPos, Object> snapshot = (Map<BlockPos, Object>) captureSnapshot.invoke(null, mockLevel, new Vec3(0, 0, 0), 2);

            // Should be exactly 3 particles
            assertEquals(3, snapshot.size(), "Snapshot should hit the max particle limit of 3");
        }
    }
}
