package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
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
     *
     * @param curingLookup  ICuringManager 水化度查詢（null → 全部視為完全養護 1.0）
     * @param windVec       全域風向向量（null → 不施加風壓偏置）
     */
    static void updateSourceAndConductivity(PFSFIslandBuffer buf,
                                             StructureIsland island,
                                             ServerLevel level,
                                             Function<BlockPos, RMaterial> materialLookup,
                                             Function<BlockPos, Boolean> anchorLookup,
                                             Function<BlockPos, Float> fillRatioLookup,
                                             @Nullable Function<BlockPos, Float> curingLookup,
                                             @Nullable Vec3 windVec) {
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

        // v3: BFS 快取 — 拓撲未變時跳過昂貴的 BFS 計算
        Map<BlockPos, Integer> armMap;
        Map<BlockPos, Double> archFactorMap;
        if (buf.isBfsCacheValid()) {
            // 快取命中：重用上次的 BFS 結果
            armMap = new java.util.HashMap<>();
            for (var e : buf.getCachedArmMap().entrySet()) armMap.put(e.getKey(), e.getValue());
            archFactorMap = new java.util.HashMap<>();
            for (var e : buf.getCachedArchFactorMap().entrySet()) archFactorMap.put(e.getKey(), e.getValue().doubleValue());
        } else {
            // 快取未命中：完整 BFS
            armMap = PFSFSourceBuilder.computeHorizontalArmMap(members, anchors);
            archFactorMap = PFSFSourceBuilder.computeArchFactorMap(members, anchors);
            // 存入快取
            buf.setCachedArmMap(new java.util.HashMap<>(armMap));
            java.util.Map<BlockPos, Float> archFloat = new java.util.HashMap<>();
            for (var e : archFactorMap.entrySet()) archFloat.put(e.getKey(), e.getValue().floatValue());
            buf.setCachedArchFactorMap(archFloat);
        }

        // v2: 風壓配置
        float windSpeed = com.blockreality.api.config.BRConfig.getWindSpeed();

        // v2: Timoshenko — per-column section height map
        java.util.Map<Long, Integer> sectionHeightMap = new java.util.HashMap<>();
        for (BlockPos pos : members) {
            long colKey = ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
            sectionHeightMap.merge(colKey, 1, Integer::sum);
        }

        int N = buf.getN();
        float[] source       = new float[N];
        float[] conductivity = new float[N * 6];
        byte[]  type         = new byte[N];
        float[] maxPhi       = new float[N];
        float[] rcomp        = new float[N];
        float[] rtens        = new float[N];
        float[] hydration    = new float[N];  // v2.1: 水化度 ∈ [0,1]

        for (BlockPos pos : members) {
            if (!buf.contains(pos)) continue;
            int i = buf.flatIndex(pos);

            RMaterial mat = materialLookup != null ? materialLookup.apply(pos) : null;
            if (mat == null) continue;

            float fillRatio = fillRatioLookup != null ? fillRatioLookup.apply(pos) : 1.0f;
            int arm = armMap.getOrDefault(pos, 0);
            double archFactor = archFactorMap.getOrDefault(pos, 0.0);

            // v2.1: 固化時間效應（Bažant 1989 MPS）
            float hDeg = (curingLookup != null) ? Math.max(0.0f, Math.min(1.0f, curingLookup.apply(pos))) : 1.0f;
            hydration[i] = hDeg;
            float sigmaScale = (float) Math.sqrt(hDeg);
            float gcScale    = (float) Math.pow(hDeg, 1.5);

            // v2: Timoshenko 力矩修正（取代舊 α/β 經驗常數）
            long colKey = ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
            float sectionHeight = sectionHeightMap.getOrDefault(colKey, 1).floatValue();
            float momentFactor = PFSFSourceBuilder.computeTimoshenkoMomentFactor(
                    1.0f, sectionHeight, arm,
                    (float) mat.getYoungsModulusGPa(),
                    PFSFConstants.DEFAULT_POISSON_RATIO);
            float baseWeight = (float) (mat.getDensity() * fillRatio
                    * PFSFConstants.GRAVITY * PFSFConstants.BLOCK_VOLUME);
            source[i] = baseWeight * momentFactor;

            type[i]   = anchors.contains(pos) ? VOXEL_ANCHOR : VOXEL_SOLID;
            // maxPhi 反映 G_c（gcScale）影響：養護不足 → maxPhi 降低 → 更容易斷裂
            maxPhi[i] = PFSFSourceBuilder.computeMaxPhiTimoshenko(mat, arm, sectionHeight) * gcScale;
            rcomp[i]  = (float) mat.getRcomp();
            rtens[i]  = (float) mat.getRtens();

            for (Direction dir : Direction.values()) {
                BlockPos nb = pos.relative(dir);
                RMaterial nbMat = members.contains(nb) && materialLookup != null
                        ? materialLookup.apply(nb) : null;
                int armNb = armMap.getOrDefault(nb, 0);
                int dirIdx = PFSFConductivity.dirToIndex(dir);
                // v2.1: upwind conductivity bias（取代 v2 的 WIND_CONDUCTIVITY_DECAY 硬截斷）
                float sigma = PFSFConductivity.sigma(mat, nbMat, dir, arm, armNb, windVec);
                // 養護度縮放（水化不足的體素傳導率較低）
                conductivity[dirIdx * N + i] = sigma * sigmaScale;
            }
        }

        // 上傳水化度陣列到 GPU（v2.1 phase_field_evolve.comp 將讀取此陣列計算 G_c(t)）
        buf.uploadHydration(hydration);

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

        // 粗網格資料（L0 → L1 → L2）
        buf.allocateMultigrid();
        if (buf.getN_L1() > 0) {
            float[] l1Cond = new float[buf.getN_L1() * 6];
            byte[] l1Type = new byte[buf.getN_L1()];
            downsample(conductivity, type,
                    buf.getLx(), buf.getLy(), buf.getLz(),
                    buf.getLxL1(), buf.getLyL1(), buf.getLzL1(),
                    l1Cond, l1Type);
            buf.uploadCoarseData(l1Cond, l1Type);

            // v2: L2 粗網格（W-Cycle 需要）
            if (buf.getN_L2() > 0) {
                float[] l2Cond = new float[buf.getN_L2() * 6];
                byte[] l2Type = new byte[buf.getN_L2()];
                downsample(l1Cond, l1Type,
                        buf.getLxL1(), buf.getLyL1(), buf.getLzL1(),
                        buf.getLxL2(), buf.getLyL2(), buf.getLzL2(),
                        l2Cond, l2Type);
                buf.uploadL2CoarseData(l2Cond, l2Type);
            }
        }
    }

    /**
     * 向下相容：不含水化度/風向的舊 API。
     */
    static void updateSourceAndConductivity(PFSFIslandBuffer buf,
                                             StructureIsland island,
                                             ServerLevel level,
                                             Function<BlockPos, RMaterial> materialLookup,
                                             Function<BlockPos, Boolean> anchorLookup,
                                             Function<BlockPos, Float> fillRatioLookup) {
        updateSourceAndConductivity(buf, island, level, materialLookup,
                anchorLookup, fillRatioLookup, null, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  v2.1: Morton Tiled 記憶體佈局
    // ═══════════════════════════════════════════════════════════════

    /**
     * 計算並上傳 Morton Tiled 佈局的 block offsets。
     *
     * <p>Hybrid Tiled Morton Layout：將 island 分割為 8×8×8 micro-blocks，
     * 每個 micro-block 內部按 9-bit Morton code（512 種排列）排序體素。
     * Micro-block 之間按島嶼幾何邊界做線性排列，節省邊緣 padding 損耗。</p>
     *
     * <p>效益：消除 3D stencil 讀取時的 L2 cache miss，GPU 記憶體頻寬利用率提升至 90%+。</p>
     *
     * @param buf GPU buffer（用於上傳 block_offsets）
     * @return 每個體素的 Morton 重排後索引陣列（長度 = N），用於 CPU 端重排 data arrays
     */
    static int[] buildMortonLayout(PFSFIslandBuffer buf) {
        int Lx = buf.getLx(), Ly = buf.getLy(), Lz = buf.getLz();
        int N = buf.getN();
        int B = MORTON_BLOCK_SIZE; // 8

        // 計算 micro-block 網格維度（ceiling division）
        int bx = (Lx + B - 1) / B;
        int by = (Ly + B - 1) / B;
        int bz = (Lz + B - 1) / B;
        int numBlocks = bx * by * bz;

        // 計算每個 micro-block 的實際體素數（邊緣 block 可能不足 B³）
        int[] blockSizes = new int[numBlocks];
        for (int bzi = 0; bzi < bz; bzi++) {
            for (int byi = 0; byi < by; byi++) {
                for (int bxi = 0; bxi < bx; bxi++) {
                    int bi = bxi + bx * (byi + by * bzi);
                    int sx = Math.min(B, Lx - bxi * B);
                    int sy = Math.min(B, Ly - byi * B);
                    int sz = Math.min(B, Lz - bzi * B);
                    blockSizes[bi] = sx * sy * sz;
                }
            }
        }

        // 計算每個 micro-block 的線性起始偏移（prefix sum）
        int[] blockOffsets = new int[numBlocks];
        int running = 0;
        for (int bi = 0; bi < numBlocks; bi++) {
            blockOffsets[bi] = running;
            running += blockSizes[bi];
        }

        // 為每個線性體素計算 Morton 重排後的索引
        int[] mortonIndex = new int[N];
        // 在每個 micro-block 內依 Morton 順序收集體素
        int[] mortonSlot = new int[numBlocks]; // 每個 block 當前填入的 slot
        System.arraycopy(blockOffsets, 0, mortonSlot, 0, numBlocks);

        // 以 Morton 順序遍歷每個 micro-block 的體素
        for (int bzi = 0; bzi < bz; bzi++) {
            for (int byi = 0; byi < by; byi++) {
                for (int bxi = 0; bxi < bx; bxi++) {
                    int bi = bxi + bx * (byi + by * bzi);
                    int sx = Math.min(B, Lx - bxi * B);
                    int sy = Math.min(B, Ly - byi * B);
                    int sz = Math.min(B, Lz - bzi * B);

                    // 在 micro-block 內依 Morton 碼排序（最多 B³=512 個體素）
                    // 簡化版：直接對 (lx,ly,lz) 計算 Morton3D 並排序
                    int[] localMorton = new int[sx * sy * sz];
                    int[] localLinear = new int[sx * sy * sz];
                    int cnt = 0;
                    for (int lz = 0; lz < sz; lz++) {
                        for (int ly = 0; ly < sy; ly++) {
                            for (int lx = 0; lx < sx; lx++) {
                                localMorton[cnt] = morton3D(lx, ly, lz);
                                localLinear[cnt] = lx + ly * B + lz * B * B; // relative linear
                                cnt++;
                            }
                        }
                    }
                    // 依 Morton 碼升序排列
                    Integer[] order = new Integer[cnt];
                    for (int k = 0; k < cnt; k++) order[k] = k;
                    java.util.Arrays.sort(order, (a, b2) -> Integer.compare(localMorton[a], localMorton[b2]));

                    // 記錄每個體素的重排目標索引
                    for (int rank = 0; rank < cnt; rank++) {
                        int k = order[rank];
                        // 從 localLinear 恢復 (lx, ly, lz)
                        int llin = localLinear[k];
                        int lx = llin % B;
                        int ll  = llin / B;
                        int ly = ll % B;
                        int lz = ll / B;
                        // 全局線性索引
                        int gx = bxi * B + lx, gy = byi * B + ly, gz = bzi * B + lz;
                        if (gx >= Lx || gy >= Ly || gz >= Lz) continue;
                        int linIdx = gx + Lx * (gy + Ly * gz);
                        mortonIndex[linIdx] = mortonSlot[bi]++;
                    }
                }
            }
        }

        buf.uploadBlockOffsets(blockOffsets);
        return mortonIndex;
    }

    /** Morton3D bit-interleave（位元擴展），匹配 morton_utils.glsl 中的實現。 */
    private static int morton3D(int x, int y, int z) {
        return expandBits(x) | (expandBits(y) << 1) | (expandBits(z) << 2);
    }

    private static int expandBits(int v) {
        v = (v | (v << 16)) & 0xFF0000FF;
        v = (v | (v << 8))  & 0x0F00F00F;
        v = (v | (v << 4))  & 0xC30C30C3;
        v = (v | (v << 2))  & 0x49249249;
        return v;
    }

    /**
     * 計算粗網格的 conductivity 和 type（2×2×2 平均降採樣）。
     */
    static void downsample(float[] fineCond, byte[] fineType,
                            int fLx, int fLy, int fLz,
                            int cLx, int cLy, int cLz,
                            float[] outCond, byte[] outType) {
        int cN = cLx * cLy * cLz;
        int fN = fLx * fLy * fLz;

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
                                for (int d = 0; d < 6; d++) {
                                    condSum[d] += fineCond[d * fN + fi];
                                }
                            }
                        }
                    }

                    if (anchorCount > 0) outType[ci] = VOXEL_ANCHOR;
                    else if (solidCount > total / 2) outType[ci] = VOXEL_SOLID;
                    else outType[ci] = VOXEL_AIR;

                    if (total > 0) {
                        for (int d = 0; d < 6; d++) {
                            outCond[d * cN + ci] = condSum[d] / total;
                        }
                    }
                }
            }
        }
    }
}
