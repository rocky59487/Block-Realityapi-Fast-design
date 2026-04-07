package com.blockreality.api.physics.pfsf;

import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.physics.FailureType;
import com.blockreality.api.physics.StressField;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 結果處理器 — 從 PFSFEngine 提取的 GPU 結果讀回 + 失效處理 + 封包同步。
 *
 * <p>P2 重構：PFSFEngine 拆分為三層之一。</p>
 *
 * <p>職責：
 * <ul>
 *   <li>processCompletedFrame() — 讀取壓縮 failure 結果 → CollapseManager</li>
 *   <li>checkDivergence() — 委託 PFSFScheduler 發散/振盪偵測</li>
 *   <li>syncStressToClients() — 每 N tick 同步應力場到附近客戶端</li>
 * </ul>
 */
public final class PFSFResultProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Result");

    /** M10: 同步 tick 計數器（每 island） */
    private final ConcurrentHashMap<Integer, Integer> syncCounters = new ConcurrentHashMap<>();

    /**
     * 處理 GPU 完成的計算結果（在主線程上的回調）。
     */
    public void processCompletedFrame(PFSFAsyncCompute.ComputeFrame frame,
                                       PFSFIslandBuffer buf, ServerLevel level) {
        if (frame.readbackStagingBuf == null) return;

        // 讀取壓縮後的 failure 結果
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(
                frame.readbackStagingBuf[1], frame.readbackStagingSize);
        int failCount = mapped.getInt(0);

        if (failCount > 0) {
            failCount = Math.min(failCount, MAX_FAILURE_PER_TICK);
            for (int i = 0; i < failCount; i++) {
                int packed = mapped.getInt((i + 1) * 4);
                int flatIndex = packed >>> 4;
                byte failType = (byte) (packed & 0xF);

                if (flatIndex < 0 || flatIndex >= buf.getN()) continue;

                BlockPos pos = buf.fromFlatIndex(flatIndex);
                FailureType type = switch (failType) {
                    case FAIL_CANTILEVER -> FailureType.CANTILEVER_BREAK;
                    case FAIL_CRUSHING -> FailureType.CRUSHING;
                    case FAIL_NO_SUPPORT -> FailureType.NO_SUPPORT;
                    case FAIL_TENSION -> FailureType.TENSION_BREAK;
                    default -> null;
                };
                if (type != null) {
                    CollapseManager.triggerPFSFCollapse(level, pos, type);
                }
            }
            buf.markDirty();
            PFSFScheduler.onCollapseTriggered(buf);
        }

        VulkanComputeContext.unmapBuffer(frame.readbackStagingBuf[1]);

        // GPU-side phi max 歸約結果 → 精確發散偵測
        if (frame.phiMaxStagingBuf != null) {
            ByteBuffer phiMaxMapped = VulkanComputeContext.mapBuffer(
                    frame.phiMaxStagingBuf[1], Float.BYTES);
            float maxPhiNow = phiMaxMapped.getFloat(0);
            VulkanComputeContext.unmapBuffer(frame.phiMaxStagingBuf[1]);

            PFSFScheduler.checkDivergence(buf, maxPhiNow);

            if (frame.phiMaxPartialBuf != null) {
                for (int i = 0; i < frame.phiMaxPartialBuf.length; i += 2) {
                    VulkanComputeContext.freeBuffer(frame.phiMaxPartialBuf[i], frame.phiMaxPartialBuf[i + 1]);
                }
                frame.phiMaxPartialBuf = null;
            }
        }

        // M10: 週期性應力同步到客戶端
        syncStressToClients(buf, level);
    }

    /**
     * 週期性應力同步到附近客戶端。
     */
    private void syncStressToClients(PFSFIslandBuffer buf, ServerLevel level) {
        int counter = syncCounters.merge(buf.getIslandId(), 1, Integer::sum);
        if (counter % STRESS_SYNC_INTERVAL != 0) return;

        StressField stressField = PFSFStressExtractor.extractStressField(buf);
        Map<BlockPos, Float> stressMap = stressField != null ? stressField.stressValues() : null;
        if (stressMap == null || stressMap.isEmpty()) return;

        Object2FloatOpenHashMap<BlockPos> filtered = new Object2FloatOpenHashMap<>(stressMap.size());
        for (Map.Entry<BlockPos, Float> entry : stressMap.entrySet()) {
            if (entry.getValue() >= 0.3f) {
                filtered.put(entry.getKey(), entry.getValue().floatValue());
            }
        }
        if (filtered.isEmpty()) return;

        BlockPos center = buf.getOrigin().offset(buf.getLx() / 2, buf.getLy() / 2, buf.getLz() / 2);
        com.blockreality.api.network.PFSFStressSyncPacket packet =
                new com.blockreality.api.network.PFSFStressSyncPacket(buf.getIslandId(), filtered);
        com.blockreality.api.network.BRNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.NEAR.with(
                        () -> new net.minecraftforge.network.PacketDistributor.TargetPoint(
                                center.getX(), center.getY(), center.getZ(),
                                64.0, level.dimension())),
                packet);
    }
}
