package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 資料建構器 — 計算 source / conductivity / type 陣列並上傳到 GPU。
 *
 * <p>從 PFSFEngine 提取的 §5.4 Source &amp; Conductivity Upload 邏輯。</p>
 */
public final class PFSFDataBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Data");

    private PFSFDataBuilder() {}

    /**
     * 計算並上傳 island 的 source、conductivity、type 等數據到 GPU。
     */
    static void updateSourceAndConductivity(PFSFIslandBuffer buf,
                                             StructureIsland island,
                                             ServerLevel level,
                                             Function<BlockPos, RMaterial> materialLookup,
                                             Function<BlockPos, Boolean> anchorLookup,
                                             Function<BlockPos, Float> fillRatioLookup) {
        Set<BlockPos> members = island.getMembers();

        Set<BlockPos> anchors = new HashSet<>();
        for (BlockPos pos : members) {
            if (anchorLookup != null && anchorLookup.apply(pos)) {
                anchors.add(pos);
            }
        }

        // M3-fix: 全 anchor island 跳過
        if (anchors.size() == members.size()) {
            buf.markClean();
            return;
        }

        Map<BlockPos, Integer> armMap = PFSFSourceBuilder.computeHorizontalArmMap(members, anchors);
        Map<BlockPos, Double> archFactorMap = PFSFSourceBuilder.computeArchFactorMap(members, anchors);

        int N = buf.getN();
        float[] source = new float[N];
        float[] conductivity = new float[N * 6];
        byte[] type = new byte[N];
        float[] maxPhi = new float[N];
        float[] rcomp = new float[N];
        float[] rtens = new float[N];

        for (BlockPos pos : members) {
            if (!buf.contains(pos)) continue;
            int i = buf.flatIndex(pos);

            RMaterial mat = materialLookup != null ? materialLookup.apply(pos) : null;
            if (mat == null) continue;

            float fillRatio = fillRatioLookup != null ? fillRatioLookup.apply(pos) : 1.0f;
            int arm = armMap.getOrDefault(pos, 0);
            double archFactor = archFactorMap.getOrDefault(pos, 0.0);

            source[i] = PFSFSourceBuilder.computeSource(mat, fillRatio, arm, archFactor);
            type[i] = anchors.contains(pos) ? VOXEL_ANCHOR : VOXEL_SOLID;
            maxPhi[i] = PFSFSourceBuilder.computeMaxPhi(mat, arm, archFactor);
            rcomp[i] = (float) mat.getRcomp();
            rtens[i] = (float) mat.getRtens();

            for (Direction dir : Direction.values()) {
                BlockPos nb = pos.relative(dir);
                RMaterial nbMat = members.contains(nb) && materialLookup != null
                        ? materialLookup.apply(nb) : null;
                int armNb = armMap.getOrDefault(nb, 0);
                int dirIdx = PFSFConductivity.dirToIndex(dir);
                conductivity[dirIdx * N + i] = PFSFConductivity.sigma(mat, nbMat, dir, arm, armNb);
            }
        }

        // Diagonal phantom edges
        int phantomCount = PFSFSourceBuilder.injectDiagonalPhantomEdges(
                members, conductivity, N,
                buf.getLx(), buf.getLy(), buf.getLz(), buf.getOrigin(),
                materialLookup);
        if (phantomCount > 0) {
            LOGGER.debug("[PFSF] Island {} — injected {} diagonal phantom edges",
                    island.getId(), phantomCount);
        }

        // B8+M2-fix: 單次遍歷正規化
        float sigmaMax = 1.0f;
        for (float c : conductivity) {
            if (c > sigmaMax) sigmaMax = c;
        }
        if (sigmaMax > 1.0f) {
            float normFactor = 1.0f / sigmaMax;
            for (int j = 0; j < N; j++) {
                source[j] *= normFactor;
                maxPhi[j] *= normFactor;
            }
            for (int j = 0; j < conductivity.length; j++) {
                conductivity[j] *= normFactor;
            }
        }

        buf.uploadSourceAndConductivity(source, conductivity, type, maxPhi, rcomp, rtens);

        // 粗網格資料
        buf.allocateMultigrid();
        if (buf.getN_L1() > 0) {
            uploadCoarseGridData(buf, conductivity, type,
                    buf.getLx(), buf.getLy(), buf.getLz(),
                    buf.getLxL1(), buf.getLyL1(), buf.getLzL1());
        }
    }

    /**
     * 計算粗網格的 conductivity 和 type（2×2×2 平均降採樣）。
     */
    static void uploadCoarseGridData(PFSFIslandBuffer buf,
                                      float[] fineCond, byte[] fineType,
                                      int fLx, int fLy, int fLz,
                                      int cLx, int cLy, int cLz) {
        int cN = cLx * cLy * cLz;
        float[] coarseCond = new float[cN * 6];
        byte[] coarseType = new byte[cN];

        for (int cz = 0; cz < cLz; cz++) {
            for (int cy = 0; cy < cLy; cy++) {
                for (int cx = 0; cx < cLx; cx++) {
                    int ci = cx + cLx * (cy + cLy * cz);
                    int fx0 = cx * 2, fy0 = cy * 2, fz0 = cz * 2;
                    float[] condSum = new float[6];
                    int solidCount = 0, anchorCount = 0, total = 0;

                    for (int dz = 0; dz < 2 && fz0 + dz < fLz; dz++) {
                        for (int dy = 0; dy < 2 && fy0 + dy < fLy; dy++) {
                            for (int dx = 0; dx < 2 && fx0 + dx < fLx; dx++) {
                                int fi = (fx0 + dx) + fLx * ((fy0 + dy) + fLy * (fz0 + dz));
                                total++;
                                if (fineType[fi] == VOXEL_ANCHOR) anchorCount++;
                                else if (fineType[fi] == VOXEL_SOLID) solidCount++;
                                int fN = fLx * fLy * fLz;
                                for (int d = 0; d < 6; d++) {
                                    condSum[d] += fineCond[d * fN + fi];
                                }
                            }
                        }
                    }

                    if (anchorCount > 0) coarseType[ci] = VOXEL_ANCHOR;
                    else if (solidCount > total / 2) coarseType[ci] = VOXEL_SOLID;
                    else coarseType[ci] = VOXEL_AIR;

                    if (total > 0) {
                        for (int d = 0; d < 6; d++) {
                            coarseCond[d * cN + ci] = condSum[d] / total;
                        }
                    }
                }
            }
        }
        buf.uploadCoarseData(coarseCond, coarseType);
    }
}
