package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.PhysicsScheduler;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 勢場導流物理引擎 — 總入口。
 *
 * <p>管理 Vulkan Compute Pipelines，協調 Jacobi 迭代、V-Cycle 多重網格、
 * 斷裂偵測和 SCA 連鎖崩塌。</p>
 *
 * <p>每 Server Tick 呼叫 {@link #onServerTick}，根據 PhysicsScheduler
 * 的排程對 dirty island 執行物理計算。</p>
 *
 * 參考：PFSF 手冊 §5
 */
public final class PFSFEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Engine");

    // ─── State ───
    private static boolean initialized = false;
    private static boolean available = false;

    // ─── Per-island buffers ───
    private static final ConcurrentHashMap<Integer, PFSFIslandBuffer> buffers = new ConcurrentHashMap<>();

    // ─── Vulkan Pipelines ───
    private static long jacobiPipeline;
    private static long jacobiPipelineLayout;
    private static long jacobiDSLayout;

    private static long restrictPipeline;
    private static long restrictPipelineLayout;
    private static long restrictDSLayout;

    private static long prolongPipeline;
    private static long prolongPipelineLayout;
    private static long prolongDSLayout;

    private static long failurePipeline;
    private static long failurePipelineLayout;
    private static long failureDSLayout;

    // ─── Descriptor Pool ───
    private static long descriptorPool;

    // ─── Material lookup (set by Mod initialization) ───
    private static Function<BlockPos, RMaterial> materialLookup;
    private static Function<BlockPos, Boolean> anchorLookup;
    private static Function<BlockPos, Float> fillRatioLookup;

    private PFSFEngine() {}

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化 PFSF Engine。需在 VulkanComputeContext.init() 之後呼叫。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        if (!VulkanComputeContext.isAvailable()) {
            LOGGER.warn("[PFSF] VulkanComputeContext not available, engine disabled");
            available = false;
            return;
        }

        try {
            createPipelines();
            descriptorPool = VulkanComputeContext.createDescriptorPool(256, 2048);
            available = true;
            LOGGER.info("[PFSF] Engine initialized successfully");
        } catch (Throwable e) {
            LOGGER.error("[PFSF] Engine init failed: {}", e.getMessage());
            available = false;
        }
    }

    /**
     * 建立所有 Compute Pipelines。
     */
    private static void createPipelines() {
        try {
            // ─── Jacobi Pipeline (5 bindings) ───
            jacobiDSLayout = VulkanComputeContext.createDescriptorSetLayout(5);
            // Push constants: 3 uint + 2 float + 1 uint = 24 bytes
            jacobiPipelineLayout = VulkanComputeContext.createPipelineLayout(jacobiDSLayout, 24);
            String jacobiSrc = VulkanComputeContext.loadShaderSource(
                    "assets/blockreality/shaders/compute/pfsf/jacobi_smooth.comp.glsl");
            ByteBuffer jacobiSpirv = VulkanComputeContext.compileGLSL(jacobiSrc, "jacobi_smooth.comp");
            jacobiPipeline = VulkanComputeContext.createComputePipeline(jacobiSpirv, jacobiPipelineLayout);
            org.lwjgl.system.MemoryUtil.memFree(jacobiSpirv);

            // ─── Restrict Pipeline (6 bindings) ───
            restrictDSLayout = VulkanComputeContext.createDescriptorSetLayout(6);
            restrictPipelineLayout = VulkanComputeContext.createPipelineLayout(restrictDSLayout, 24);
            String restrictSrc = VulkanComputeContext.loadShaderSource(
                    "assets/blockreality/shaders/compute/pfsf/mg_restrict.comp.glsl");
            ByteBuffer restrictSpirv = VulkanComputeContext.compileGLSL(restrictSrc, "mg_restrict.comp");
            restrictPipeline = VulkanComputeContext.createComputePipeline(restrictSpirv, restrictPipelineLayout);
            org.lwjgl.system.MemoryUtil.memFree(restrictSpirv);

            // ─── Prolong Pipeline (2 bindings) ───
            prolongDSLayout = VulkanComputeContext.createDescriptorSetLayout(2);
            prolongPipelineLayout = VulkanComputeContext.createPipelineLayout(prolongDSLayout, 24);
            String prolongSrc = VulkanComputeContext.loadShaderSource(
                    "assets/blockreality/shaders/compute/pfsf/mg_prolong.comp.glsl");
            ByteBuffer prolongSpirv = VulkanComputeContext.compileGLSL(prolongSrc, "mg_prolong.comp");
            prolongPipeline = VulkanComputeContext.createComputePipeline(prolongSpirv, prolongPipelineLayout);
            org.lwjgl.system.MemoryUtil.memFree(prolongSpirv);

            // ─── Failure Scan Pipeline (6 bindings) ───
            failureDSLayout = VulkanComputeContext.createDescriptorSetLayout(6);
            // Push constants: 1 uint + 1 float = 8 bytes
            failurePipelineLayout = VulkanComputeContext.createPipelineLayout(failureDSLayout, 8);
            String failureSrc = VulkanComputeContext.loadShaderSource(
                    "assets/blockreality/shaders/compute/pfsf/failure_scan.comp.glsl");
            ByteBuffer failureSpirv = VulkanComputeContext.compileGLSL(failureSrc, "failure_scan.comp");
            failurePipeline = VulkanComputeContext.createComputePipeline(failureSpirv, failurePipelineLayout);
            org.lwjgl.system.MemoryUtil.memFree(failureSpirv);

            LOGGER.info("[PFSF] All compute pipelines created");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create PFSF pipelines", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main Tick Loop (§5.2)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 每 Server Tick 的入口 — 處理所有 dirty island 的物理計算。
     */
    public static void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
        if (!available) return;

        long startTime = System.nanoTime();

        List<PhysicsScheduler.ScheduledWork> work =
                PhysicsScheduler.getScheduledWork(players, currentEpoch);

        for (PhysicsScheduler.ScheduledWork sw : work) {
            // Tick budget check
            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed >= TICK_BUDGET_MS) {
                LOGGER.debug("[PFSF] Tick budget exhausted ({}ms), deferring {} remaining islands",
                        elapsed, work.size());
                break;
            }

            StructureIsland island = StructureIslandRegistry.getIsland(sw.islandId());
            if (island == null) continue;

            // Skip oversized islands
            if (island.getBlockCount() > MAX_ISLAND_SIZE) continue;

            PFSFIslandBuffer buf = getOrCreateBuffer(island);

            // Update source and conductivity if dirty
            if (buf.isDirty()) {
                updateSourceAndConductivity(buf, island, level);
                buf.markClean();
            }

            // Determine iteration count
            boolean hasCollapse = false; // TODO: track from CollapseManager
            int steps = PFSFScheduler.recommendSteps(buf, sw.priority() > 0, hasCollapse);

            // Execute iterations
            VkCommandBuffer cmdBuf = VulkanComputeContext.beginSingleTimeCommands();

            for (int k = 0; k < steps; k++) {
                if (k > 0 && k % MG_INTERVAL == 0 && buf.getLmax() > 4) {
                    recordVCycle(cmdBuf, buf);
                } else {
                    float omega = PFSFScheduler.getTickOmega(buf);
                    recordJacobiStep(cmdBuf, buf, omega);
                }
            }

            // Failure scan (every SCAN_INTERVAL steps or at end of iteration)
            if (steps > 0) {
                recordFailureScan(cmdBuf, buf);
            }

            VulkanComputeContext.endSingleTimeCommands(cmdBuf);

            // Async read fail flags and apply
            if (steps > 0) {
                buf.asyncReadFailFlags(failFlags ->
                        PFSFFailureApplicator.apply(failFlags, buf, level));

                // Divergence check
                float maxPhi = buf.readMaxPhi();
                PFSFScheduler.checkDivergence(buf, maxPhi);
            }

            PhysicsScheduler.markProcessed(sw.islandId());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Source & Conductivity Upload (§5.4)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 計算並上傳 island 的 source、conductivity、type 等數據到 GPU。
     */
    static void updateSourceAndConductivity(PFSFIslandBuffer buf,
                                             StructureIsland island,
                                             ServerLevel level) {
        Set<BlockPos> members = island.getMembers();

        // 辨識錨點
        Set<BlockPos> anchors = new HashSet<>();
        for (BlockPos pos : members) {
            if (anchorLookup != null && anchorLookup.apply(pos)) {
                anchors.add(pos);
            }
        }

        // 計算力臂和 ArchFactor
        Map<BlockPos, Integer> armMap = PFSFSourceBuilder.computeHorizontalArmMap(members, anchors);
        Map<BlockPos, Double> archFactorMap = PFSFSourceBuilder.computeArchFactorMap(members, anchors);

        // 填充陣列
        int N = buf.getN();
        float[] source = new float[N];
        float[] conductivity = new float[N * 6];
        byte[] type = new byte[N];
        float[] maxPhi = new float[N];
        float[] rcomp = new float[N];

        for (BlockPos pos : members) {
            if (!buf.contains(pos)) continue;
            int i = buf.flatIndex(pos);

            RMaterial mat = materialLookup != null ? materialLookup.apply(pos) : null;
            if (mat == null) continue;

            float fillRatio = fillRatioLookup != null ? fillRatioLookup.apply(pos) : 1.0f;
            int arm = armMap.getOrDefault(pos, 0);
            double archFactor = archFactorMap.getOrDefault(pos, 0.0);

            // Source term
            source[i] = PFSFSourceBuilder.computeSource(mat, fillRatio, arm, archFactor);

            // Type
            type[i] = anchors.contains(pos) ? VOXEL_ANCHOR : VOXEL_SOLID;

            // Material limits
            maxPhi[i] = PFSFSourceBuilder.computeMaxPhi(mat);
            rcomp[i] = (float) mat.getRcomp();

            // 6-direction conductivity
            for (Direction dir : Direction.values()) {
                BlockPos nb = pos.relative(dir);
                RMaterial nbMat = members.contains(nb) && materialLookup != null
                        ? materialLookup.apply(nb) : null;
                int armNb = armMap.getOrDefault(nb, 0);
                int dirIdx = PFSFConductivity.dirToIndex(dir);
                conductivity[i * 6 + dirIdx] = PFSFConductivity.sigma(mat, nbMat, dir, arm, armNb);
            }
        }

        buf.uploadSourceAndConductivity(source, conductivity, type, maxPhi, rcomp);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU Dispatch: Jacobi Step
    // ═══════════════════════════════════════════════════════════════

    private static void recordJacobiStep(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, float omega) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, jacobiPipeline);

            // Allocate and bind descriptor set
            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, jacobiDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiPrevBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getSourceBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(), 0, buf.getTypeSize());

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    jacobiPipelineLayout, 0, stack.longs(ds), null);

            // Push constants: Lx(4) Ly(4) Lz(4) omega(4) rhoSpec(4) iter(4) = 24 bytes
            ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLx());
            pc.putInt(buf.getLy());
            pc.putInt(buf.getLz());
            pc.putFloat(omega);
            pc.putFloat(buf.rhoSpecOverride);
            pc.putInt(buf.chebyshevIter);
            pc.flip();

            vkCmdPushConstants(cmdBuf, jacobiPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            // Dispatch
            int gx = ceilDiv(buf.getLx(), WG_X);
            int gy = ceilDiv(buf.getLy(), WG_Y);
            int gz = ceilDiv(buf.getLz(), WG_Z);
            vkCmdDispatch(cmdBuf, gx, gy, gz);

            // Barrier before next step
            VulkanComputeContext.computeBarrier(cmdBuf);

            // Swap phi ↔ phiPrev (logical swap by re-binding next time)
            // For simplicity, we let the shader alternate read/write
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU Dispatch: V-Cycle
    // ═══════════════════════════════════════════════════════════════

    private static void recordVCycle(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf) {
        if (!buf.isAllocated()) return;
        buf.allocateMultigrid();

        float omega = PFSFScheduler.getTickOmega(buf);

        // 1. Pre-smooth: 2 Jacobi steps on fine grid
        recordJacobiStep(cmdBuf, buf, omega);
        recordJacobiStep(cmdBuf, buf, omega);

        // 2. Restrict: fine → L1
        recordRestrict(cmdBuf, buf);

        // 3. Coarse solve: 4 Jacobi steps on L1
        // (simplified: use jacobi on coarse buffers)
        for (int i = 0; i < 4; i++) {
            recordCoarseJacobi(cmdBuf, buf, omega);
        }

        // 4. Prolong: L1 → fine
        recordProlong(cmdBuf, buf);

        // 5. Post-smooth: 2 Jacobi steps on fine grid
        recordJacobiStep(cmdBuf, buf, omega);
        recordJacobiStep(cmdBuf, buf, omega);
    }

    private static void recordRestrict(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, restrictPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, restrictDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getSourceBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getTypeBuf(), 0, buf.getTypeSize());

            long nL1 = (long) buf.getLxL1() * buf.getLyL1() * buf.getLzL1() * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getPhiL1Buf(), 0, nL1);
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getSourceL1Buf(), 0, nL1);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    restrictPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putInt(buf.getLxL1()).putInt(buf.getLyL1()).putInt(buf.getLzL1());
            pc.flip();

            vkCmdPushConstants(cmdBuf, restrictPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            int nCoarse = buf.getLxL1() * buf.getLyL1() * buf.getLzL1();
            vkCmdDispatch(cmdBuf, ceilDiv(nCoarse, WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    private static void recordProlong(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, prolongPipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, prolongDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());

            long nL1 = (long) buf.getLxL1() * buf.getLyL1() * buf.getLzL1() * Float.BYTES;
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getPhiL1Buf(), 0, nL1);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    prolongPipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(24);
            pc.putInt(buf.getLx()).putInt(buf.getLy()).putInt(buf.getLz());
            pc.putInt(buf.getLxL1()).putInt(buf.getLyL1()).putInt(buf.getLzL1());
            pc.flip();

            vkCmdPushConstants(cmdBuf, prolongPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf,
                    ceilDiv(buf.getLx(), WG_X),
                    ceilDiv(buf.getLy(), WG_Y),
                    ceilDiv(buf.getLz(), WG_Z));
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    private static void recordCoarseJacobi(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf, float omega) {
        // Simplified: dispatch jacobi on coarse buffers
        // In a full implementation, coarse buffers would have their own conductivity
        // For now, we re-use the fine grid jacobi with coarse dimensions
        // This is a placeholder for the full multigrid implementation
        VulkanComputeContext.computeBarrier(cmdBuf);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU Dispatch: Failure Scan
    // ═══════════════════════════════════════════════════════════════

    private static void recordFailureScan(VkCommandBuffer cmdBuf, PFSFIslandBuffer buf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, failurePipeline);

            long ds = VulkanComputeContext.allocateDescriptorSet(descriptorPool, failureDSLayout);
            VulkanComputeContext.bindBufferToDescriptor(ds, 0, buf.getPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 1, buf.getConductivityBuf(), 0, buf.getConductivitySize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 2, buf.getMaxPhiBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 3, buf.getRcompBuf(), 0, buf.getPhiSize());
            VulkanComputeContext.bindBufferToDescriptor(ds, 4, buf.getTypeBuf(), 0, buf.getTypeSize());
            long failSize = buf.getN(); // uint8 per voxel
            VulkanComputeContext.bindBufferToDescriptor(ds, 5, buf.getFailFlagsBuf(), 0, failSize);

            vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE,
                    failurePipelineLayout, 0, stack.longs(ds), null);

            ByteBuffer pc = stack.malloc(8);
            pc.putInt(buf.getN());
            pc.putFloat(PHI_ORPHAN_THRESHOLD);
            pc.flip();

            vkCmdPushConstants(cmdBuf, failurePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmdBuf, ceilDiv(buf.getN(), WG_SCAN), 1, 1);
            VulkanComputeContext.computeBarrier(cmdBuf);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Buffer Management
    // ═══════════════════════════════════════════════════════════════

    private static PFSFIslandBuffer getOrCreateBuffer(StructureIsland island) {
        return buffers.computeIfAbsent(island.getId(), id -> {
            PFSFIslandBuffer buf = new PFSFIslandBuffer(id);

            BlockPos min = island.getMinCorner();
            BlockPos max = island.getMaxCorner();
            int Lx = max.getX() - min.getX() + 1;
            int Ly = max.getY() - min.getY() + 1;
            int Lz = max.getZ() - min.getZ() + 1;

            buf.allocate(Lx, Ly, Lz, min);
            return buf;
        });
    }

    /**
     * 移除指定 island 的 buffer（island 被銷毀時）。
     */
    public static void removeBuffer(int islandId) {
        PFSFIslandBuffer buf = buffers.remove(islandId);
        if (buf != null) {
            buf.free();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Configuration
    // ═══════════════════════════════════════════════════════════════

    /**
     * 設定材料查詢函式（由 Mod 初始化時注入）。
     */
    public static void setMaterialLookup(Function<BlockPos, RMaterial> lookup) {
        materialLookup = lookup;
    }

    public static void setAnchorLookup(Function<BlockPos, Boolean> lookup) {
        anchorLookup = lookup;
    }

    public static void setFillRatioLookup(Function<BlockPos, Float> lookup) {
        fillRatioLookup = lookup;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    public static boolean isAvailable() {
        return available;
    }

    public static void shutdown() {
        if (!initialized) return;

        // Free all island buffers
        for (PFSFIslandBuffer buf : buffers.values()) {
            buf.free();
        }
        buffers.clear();

        // Destroy pipelines (VkDevice will be destroyed by VulkanComputeContext)
        // Pipelines are owned by the device

        initialized = false;
        available = false;
        LOGGER.info("[PFSF] Engine shut down");
    }

    /**
     * 取得引擎狀態摘要。
     */
    public static String getStats() {
        if (!available) return "PFSF Engine: DISABLED";
        return String.format("PFSF Engine: %d islands buffered, %d total voxels",
                buffers.size(),
                buffers.values().stream().mapToInt(PFSFIslandBuffer::getN).sum());
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
