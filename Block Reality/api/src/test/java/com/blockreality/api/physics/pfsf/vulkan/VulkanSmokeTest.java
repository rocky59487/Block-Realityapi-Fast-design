package com.blockreality.api.physics.pfsf.vulkan;

import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Vulkan 煙霧測試 — 使用 lavapipe（Mesa llvmpipe）CPU 軟體渲染器
 *
 * 此測試在 CI/CD 環境中無 GPU 的情況下驗證：
 *   1. Vulkan instance 建立與 physical device 選取
 *   2. VMA 初始化（驗證 pVulkanFunctions 修復）
 *   3. Shaderc GLSL→SPIR-V 編譯
 *   4. Compute pipeline + GPU dispatch + 結果正確性
 *
 * 運行條件：
 *   - VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json（lavapipe）
 *   - 或 LWJGL Vulkan 在系統 classpath 中可用（開發機有 GPU）
 *
 * 用法：
 *   cd "Block Reality"
 *   VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json \
 *   ./gradlew :api:test --tests "*.vulkan.VulkanSmokeTest"
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Vulkan Smoke Tests (lavapipe CPU renderer)")
class VulkanSmokeTest {

    private static final String LAVAPIPE_ICD = "/usr/share/vulkan/icd.d/lvp_icd.json";
    private static final String NATIVES_DIR  = System.getProperty("java.io.tmpdir") + "/br_vk_test_natives";

    /** 煙霧測試的外部程序輸出 */
    private static final List<String> lastOutput = new ArrayList<>();

    @BeforeAll
    static void setup() {
        // 強制使用 lavapipe
        if (new File(LAVAPIPE_ICD).exists()) {
            System.setProperty("VK_ICD_FILENAMES", LAVAPIPE_ICD);
        }

        // 確認 LWJGL Vulkan 可在 classpath 中找到
        try {
            Class.forName("org.lwjgl.vulkan.VK10");
        } catch (ClassNotFoundException e) {
            assumeTrue(false, "org.lwjgl.vulkan not on classpath — skipping Vulkan tests");
        }
    }

    // ─── Stage 1: VkInstance ─────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("S1: Vulkan instance 建立 + lavapipe device 偵測")
    void testVkInstanceCreation() throws Exception {
        assumeTrue(isLavapipeAvailable(), "lavapipe ICD not found at " + LAVAPIPE_ICD);

        int exitCode = runStage("Stage1_VkInstance");
        assertEquals(0, exitCode,
            "Stage 1 (VkInstance + device selection) failed\n" + String.join("\n", lastOutput));
    }

    // ─── Stage 2: VMA pVulkanFunctions ───────────────────────────────

    @Test
    @Order(2)
    @DisplayName("S2: VMA 初始化 — 驗證 pVulkanFunctions 修復（LWJGL 3.3.1 必要）")
    void testVMAWithPVulkanFunctions() throws Exception {
        assumeTrue(isLavapipeAvailable(), "lavapipe ICD not found");

        int exitCode = runStage("Stage2_VMAInit");
        assertEquals(0, exitCode,
            "Stage 2 (VMA pVulkanFunctions) failed\n" + String.join("\n", lastOutput));

        // Verify key lines in output
        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("NPE") || l.contains("pVulkanFunctions")),
            "Expected confirmation of pVulkanFunctions validation");
        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("vmaCreateAllocator succeeded")),
            "vmaCreateAllocator must succeed with pVulkanFunctions");
        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("vmaCreateBuffer")),
            "VMA buffer allocation must succeed");
    }

    // ─── Stage 3: Shaderc ─────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("S3: Shaderc GLSL→SPIR-V — PFSF + Fluid compute shader 編譯")
    void testShadercCompilation() throws Exception {
        assumeTrue(isLavapipeAvailable(), "lavapipe ICD not found");

        int exitCode = runStage("Stage3_Shaderc");
        assertEquals(0, exitCode,
            "Stage 3 (Shaderc compilation) failed\n" + String.join("\n", lastOutput));

        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("pfsf_rbgs.comp") && l.contains("compiled OK")),
            "PFSF compute shader must compile successfully");
        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("fluid_advect.comp") && l.contains("compiled OK")),
            "Fluid compute shader must compile successfully");
    }

    // ─── Stage 4: Full compute pipeline ──────────────────────────────

    @Test
    @Order(4)
    @DisplayName("S4: Compute pipeline + GPU dispatch + 計算結果正確性")
    void testComputePipelineAndDispatch() throws Exception {
        assumeTrue(isLavapipeAvailable(), "lavapipe ICD not found");

        int exitCode = runStage("Stage4_ComputePipeline");
        assertEquals(0, exitCode,
            "Stage 4 (Compute pipeline dispatch) failed\n" + String.join("\n", lastOutput));

        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("Compute pipeline created")),
            "Compute pipeline must be created");
        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("GPU dispatch complete")),
            "GPU dispatch must complete via vkQueueWaitIdle");
        assertTrue(lastOutput.stream().anyMatch(l -> l.contains("1024") && l.contains("正しい") || l.contains("PASS")),
            "Compute results must be correct");
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    /**
     * 運行獨立的 smoke test stage（子進程）。
     * 使用與模組相同的 LWJGL 版本（3.3.1）和 lavapipe ICD。
     */
    private int runStage(String mainClass) throws IOException, InterruptedException {
        // Build classpath from known LWJGL cache locations
        String gradleCache = System.getProperty("user.home") + "/.gradle/caches/modules-2/files-2.1/org.lwjgl";
        List<String> cpEntries = findLwjglJars(gradleCache);
        String smokeTestOut = System.getProperty("java.io.tmpdir") + "/br_vk_smoke_out";
        cpEntries.add(smokeTestOut);

        String cp = String.join(File.pathSeparator, cpEntries);

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-cp"); cmd.add(cp);
        cmd.add("-Djava.library.path=" + NATIVES_DIR);
        cmd.add("-Dorg.lwjgl.librarypath=" + NATIVES_DIR);
        cmd.add("-Dorg.lwjgl.util.Debug=false");
        cmd.add(mainClass);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("VK_ICD_FILENAMES", LAVAPIPE_ICD);
        pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        lastOutput.clear();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastOutput.add(line);
                System.out.println("  [" + mainClass + "] " + line);
            }
        }

        return proc.waitFor();
    }

    private static boolean isLavapipeAvailable() {
        return new File(LAVAPIPE_ICD).exists();
    }

    private static List<String> findLwjglJars(String base) {
        List<String> jars = new ArrayList<>();
        String[][] modules = {
            {"lwjgl", "3.3.1"},
            {"lwjgl-vulkan", "3.3.1"},
            {"lwjgl-vma", "3.3.1"},
            {"lwjgl-shaderc", "3.3.1"},
        };
        for (String[] mod : modules) {
            File dir = new File(base + "/" + mod[0] + "/" + mod[1]);
            if (dir.exists()) {
                for (File hash : dir.listFiles()) {
                    for (File jar : hash.listFiles()) {
                        String name = jar.getName();
                        // Include API jars and linux natives
                        if (name.endsWith(".jar") && !name.endsWith(".pom")
                                && !name.contains("natives-windows")
                                && !name.contains("natives-macos")) {
                            jars.add(jar.getAbsolutePath());
                        }
                    }
                }
            }
        }
        return jars;
    }
}
