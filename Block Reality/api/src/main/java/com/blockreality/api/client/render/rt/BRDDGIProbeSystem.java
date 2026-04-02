package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BRDDGIProbeSystem — DDGI（Dynamic Diffuse Global Illumination）Probe 網格管理器。
 *
 * <h3>DDGI 概述</h3>
 * <p>DDGI（Majercik et al. 2019）在世界空間中放置一個規則的 probe 網格；
 * 每個 probe 儲存球面輻射分佈（以 Octahedral Projection 壓縮到紋理中），
 * 渲染時從周圍 8 個 probe 插值取得漫反射 GI。
 *
 * <h3>Probe 網格設計</h3>
 * <ul>
 *   <li>網格跟隨攝影機移動（Scrolling Grid）：當攝影機移動距離超過半個 probe 間距時，
 *       最遠一排 probe 「捲動」到近端，觸發更新</li>
 *   <li>每個 probe 包含兩張貼圖（Octahedral Projection）：
 *       <ul>
 *         <li>Irradiance：8×8 texels，RGB16F，存球面積分輻射量</li>
 *         <li>Visibility：8×8 texels，R16G16（mean + variance of hit distance），
 *             用於 Chebyshev visibility test</li>
 *       </ul>
 *   </li>
 *   <li>每幀只更新 {@code updateRatio} 比例的 probe（輪轉），降低 GPU 開銷</li>
 * </ul>
 *
 * <h3>VRAM 估算</h3>
 * <pre>
 * 預設網格：32×16×32 = 16,384 probes
 * Irradiance：16384 × (8+2)² × 6 bytes（RGB16F + 2 border） ≈ 10 MB
 * Visibility ：16384 × (8+2)² × 4 bytes（RG16F）             ≈ 7 MB
 * Probe UBO  ：16384 × 16 bytes（world pos + flags）         ≈ 0.25 MB
 * Total      ：~17 MB（遠低於 ReSTIR GI 的 126 MB）
 * </pre>
 *
 * @see BRRTSettings#isEnableDDGI()
 */
@OnlyIn(Dist.CLIENT)
public final class BRDDGIProbeSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-DDGI");

    // ════════════════════════════════════════════════════════════════════════
    //  常數
    // ════════════════════════════════════════════════════════════════════════

    /** 每個 probe 的 Octahedral Irradiance 貼圖邊長（不含 border）。 */
    public static final int PROBE_IRRADIANCE_TEXELS = 8;

    /** 每個 probe 的 Octahedral Visibility 貼圖邊長（不含 border）。 */
    public static final int PROBE_VISIBILITY_TEXELS = 8;

    /** Probe 貼圖的 border（1 texel 四周），用於雙線性濾波越界修正。 */
    public static final int PROBE_BORDER = 1;

    /**
     * 每個 probe 在 Irradiance Atlas 的實際 texel 大小
     * = (PROBE_IRRADIANCE_TEXELS + 2 × PROBE_BORDER)²
     */
    public static final int PROBE_IRRAD_FULL = PROBE_IRRADIANCE_TEXELS + 2 * PROBE_BORDER;

    /**
     * 每個 probe 在 Visibility Atlas 的實際 texel 大小
     * = (PROBE_VISIBILITY_TEXELS + 2 × PROBE_BORDER)²
     */
    public static final int PROBE_VIS_FULL = PROBE_VISIBILITY_TEXELS + 2 * PROBE_BORDER;

    /**
     * 預設 probe 網格大小（X × Y × Z，方塊為單位的間距獨立）。
     * 32 × 16 × 32 = 16,384 probes，覆蓋範圍由 {@link BRRTSettings#getDdgiProbeSpacingBlocks()} 決定。
     */
    public static final int DEFAULT_GRID_X = 32;
    public static final int DEFAULT_GRID_Y = 16;
    public static final int DEFAULT_GRID_Z = 32;

    /** Probe UBO 每條目大小（bytes）：world_pos(vec3) + flags(uint) = 4×float = 16 bytes。 */
    public static final int PROBE_UBO_ENTRY_SIZE = 16;

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRDDGIProbeSystem INSTANCE = new BRDDGIProbeSystem();

    public static BRDDGIProbeSystem getInstance() { return INSTANCE; }

    private BRDDGIProbeSystem() {}

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    private boolean initialized    = false;

    // 網格尺寸（可由 BRRTSettings 調整）
    private int gridX = DEFAULT_GRID_X;
    private int gridY = DEFAULT_GRID_Y;
    private int gridZ = DEFAULT_GRID_Z;

    /** 每個方向的 probe 間距（block 單位） */
    private int spacingBlocks = 8;

    /** 網格「原點」世界座標（grid[0,0,0] 的 world pos，隨攝影機捲動） */
    private final Vector3i gridOrigin = new Vector3i(0, 0, 0);

    // GPU handles — Vulkan image/buffer handles（RT-6-1: 替換原有 stub 20L-23L）
    private long irradianceAtlasImage  = 0L;  // VkImage — RGB16F Octahedral irradiance atlas
    private long irradianceAtlasMemory = 0L;  // VkDeviceMemory
    private long irradianceAtlasView   = 0L;  // VkImageView（STORAGE + SAMPLED）
    private long visibilityAtlasImage  = 0L;  // VkImage — RG16F mean/variance visibility atlas
    private long visibilityAtlasMemory = 0L;  // VkDeviceMemory
    private long visibilityAtlasView   = 0L;  // VkImageView（STORAGE + SAMPLED）
    private long probeUboBuffer        = 0L;  // VkBuffer — probe world positions SSBO（HOST_COHERENT）
    private long probeUboMemory        = 0L;  // VkDeviceMemory（HOST_VISIBLE + HOST_COHERENT）

    // 更新計數
    private long  frameCount            = 0L;
    private int   probesUpdatedThisFrame = 0;

    /** 下一個要更新的 probe 起始索引（輪轉） */
    private int   updateCursor          = 0;

    // ════════════════════════════════════════════════════════════════════════
    //  生命週期
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 DDGI probe 系統。
     *
     * @param spacing probe 間距（block 單位，從 {@link BRRTSettings#getDdgiProbeSpacingBlocks()}）
     * @return true = 成功
     */
    public boolean init(int spacing) {
        if (initialized) {
            LOGGER.warn("[DDGI] Already initialized; call resize(spacing) to reconfigure");
            return true;
        }
        spacingBlocks = Math.max(1, spacing);
        int totalProbes = gridX * gridY * gridZ;

        try {
            long device = BRVulkanDevice.getVkDevice();
            if (device == 0L) {
                LOGGER.error("[DDGI] Vulkan device not available — DDGI disabled");
                return false;
            }

            // ── Irradiance Atlas — RGBA16F（RGB=irradiance, A=unused/weight） ─────────
            // Usage: STORAGE（compute write） + SAMPLED（fragment read）
            final int irrW = getIrradianceAtlasWidth();
            final int irrH = getIrradianceAtlasHeight();
            final int irrUsage = org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT
                               | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT
                               | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            long[] irrImg = BRVulkanDevice.createImage2D(device, irrW, irrH,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    irrUsage,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT);
            if (irrImg == null) {
                LOGGER.error("[DDGI] Failed to create irradiance atlas ({}×{})", irrW, irrH);
                return false;
            }
            irradianceAtlasImage  = irrImg[0];
            irradianceAtlasMemory = irrImg[1];
            irradianceAtlasView   = irrImg[2];

            // ── Visibility Atlas — RG16F（R=mean hit dist, G=mean hit dist²） ─────────
            final int visW = getVisibilityAtlasWidth();
            final int visH = getVisibilityAtlasHeight();
            long[] visImg = BRVulkanDevice.createImage2D(device, visW, visH,
                    org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT,
                    irrUsage,   // same usage flags
                    org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT);
            if (visImg == null) {
                LOGGER.error("[DDGI] Failed to create visibility atlas ({}×{})", visW, visH);
                cleanup();
                return false;
            }
            visibilityAtlasImage  = visImg[0];
            visibilityAtlasMemory = visImg[1];
            visibilityAtlasView   = visImg[2];

            // ── Probe UBO buffer — HOST_COHERENT SSBO（mapped each frame） ─────────────
            // 每 probe 16 bytes（vec3 world_pos + uint flags）
            final long uboSize = (long) totalProbes * PROBE_UBO_ENTRY_SIZE;
            probeUboBuffer = BRVulkanDevice.createBuffer(device, uboSize,
                    org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            if (probeUboBuffer == 0L) {
                LOGGER.error("[DDGI] Failed to create probe UBO buffer ({} bytes)", uboSize);
                cleanup();
                return false;
            }
            probeUboMemory = BRVulkanDevice.allocateAndBindBuffer(device, probeUboBuffer,
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                  | org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (probeUboMemory == 0L) {
                LOGGER.error("[DDGI] Failed to allocate probe UBO memory");
                cleanup();
                return false;
            }

            // Upload initial probe world positions (all probes relative to origin 0,0,0)
            uploadProbePositions(device);

            LOGGER.info("[DDGI] Initialized: grid={}×{}×{} ({} probes), spacing={}b, " +
                "irrAtlas={}×{} (~{}MB), visAtlas={}×{} (~{}MB)",
                gridX, gridY, gridZ, totalProbes, spacingBlocks,
                irrW, irrH, (long) irrW * irrH * 8 / (1024 * 1024),
                visW, visH, (long) visW * visH * 4 / (1024 * 1024));

            initialized = true;
            return true;
        } catch (Exception e) {
            LOGGER.error("[DDGI] Initialization failed", e);
            cleanup();
            return false;
        }
    }

    /**
     * 清理 GPU 資源。
     */
    public void cleanup() {
        if (!initialized && irradianceAtlasImage == 0L) return;
        long device = BRVulkanDevice.getVkDevice();
        if (device != 0L) {
            BRVulkanDevice.destroyImage2D(device,
                    irradianceAtlasImage, irradianceAtlasMemory, irradianceAtlasView);
            BRVulkanDevice.destroyImage2D(device,
                    visibilityAtlasImage, visibilityAtlasMemory, visibilityAtlasView);
            if (probeUboBuffer != 0L) {
                org.lwjgl.vulkan.VK10.vkDestroyBuffer(
                        BRVulkanDevice.getVkDeviceObj(), probeUboBuffer, null);
            }
            if (probeUboMemory != 0L) {
                org.lwjgl.vulkan.VK10.vkFreeMemory(
                        BRVulkanDevice.getVkDeviceObj(), probeUboMemory, null);
            }
        }
        irradianceAtlasImage  = 0L;
        irradianceAtlasMemory = 0L;
        irradianceAtlasView   = 0L;
        visibilityAtlasImage  = 0L;
        visibilityAtlasMemory = 0L;
        visibilityAtlasView   = 0L;
        probeUboBuffer        = 0L;
        probeUboMemory        = 0L;
        initialized           = false;
        frameCount            = 0L;
        updateCursor          = 0;
        LOGGER.info("[DDGI] Cleanup complete");
    }

    /**
     * 把所有 probe 的世界座標上傳到 HOST_COHERENT probeUboBuffer。
     * 在 init() 結尾和 Scrolling Grid 捲動後呼叫。
     */
    private void uploadProbePositions(long device) {
        if (probeUboMemory == 0L) return;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            int total = getTotalProbeCount();
            // Map 整段 buffer
            java.nio.LongBuffer pData = stack.mallocLong(1);
            int res = org.lwjgl.vulkan.VK10.vkMapMemory(
                    BRVulkanDevice.getVkDeviceObj(), probeUboMemory,
                    0L, (long) total * PROBE_UBO_ENTRY_SIZE, 0, pData);
            if (res != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                LOGGER.warn("[DDGI] vkMapMemory failed for probe UBO ({})", res);
                return;
            }
            java.nio.ByteBuffer mapped = org.lwjgl.system.MemoryUtil.memByteBuffer(
                    pData.get(0), total * PROBE_UBO_ENTRY_SIZE);
            mapped.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            // 寫入每個 probe 的世界座標（vec3 + float flags = 16 bytes）
            int linearIdx = 0;
            for (int iy = 0; iy < gridY; iy++) {
                for (int iz = 0; iz < gridZ; iz++) {
                    for (int ix = 0; ix < gridX; ix++) {
                        org.joml.Vector3f pos = probeWorldPos(ix, iy, iz);
                        int off = linearIdx * PROBE_UBO_ENTRY_SIZE;
                        mapped.putFloat(off,      pos.x);
                        mapped.putFloat(off + 4,  pos.y);
                        mapped.putFloat(off + 8,  pos.z);
                        mapped.putFloat(off + 12, 0.0f); // flags = 0 (active)
                        linearIdx++;
                    }
                }
            }
            org.lwjgl.vulkan.VK10.vkUnmapMemory(
                    BRVulkanDevice.getVkDeviceObj(), probeUboMemory);
        } catch (Exception e) {
            LOGGER.warn("[DDGI] uploadProbePositions failed: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  每幀操作
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 幀開始時：更新網格原點（Scrolling Grid），計算本幀需要更新的 probe 列表。
     *
     * @param camPos       攝影機世界座標
     * @param updateRatio  每幀更新的 probe 比例（0.0–1.0）
     * @return 本幀需要更新的 probe 索引陣列（長度 = ceil(totalProbes × ratio)）
     */
    public int[] onFrameStart(Vector3f camPos, float updateRatio) {
        if (!initialized) return new int[0];

        // ── Scrolling Grid：跟隨攝影機捲動 ────────────────────────────────
        scrollGrid(camPos);

        // ── 計算本幀更新的 probe 索引（輪轉） ────────────────────────────
        int totalProbes    = getTotalProbeCount();
        int probesThisFrame = Math.max(1,
            (int) Math.ceil(totalProbes * Math.max(0.0f, Math.min(updateRatio, 1.0f))));

        int[] updateList = new int[probesThisFrame];
        for (int i = 0; i < probesThisFrame; i++) {
            updateList[i] = updateCursor % totalProbes;
            updateCursor++;
        }

        probesUpdatedThisFrame = probesThisFrame;
        frameCount++;
        return updateList;
    }

    /**
     * 捲動網格：當攝影機超過半個 probe 間距時，捲動最遠一排 probe 到近端。
     * 修改 {@link #gridOrigin} 以反映捲動。
     */
    private void scrollGrid(Vector3f camPos) {
        int halfGridX = gridX / 2 * spacingBlocks;
        int halfGridY = gridY / 2 * spacingBlocks;
        int halfGridZ = gridZ / 2 * spacingBlocks;

        // 理想網格原點（攝影機為中心，對齊 spacing）
        int idealOriginX = Math.floorDiv((int) camPos.x - halfGridX, spacingBlocks) * spacingBlocks;
        int idealOriginY = Math.floorDiv((int) camPos.y - halfGridY, spacingBlocks) * spacingBlocks;
        int idealOriginZ = Math.floorDiv((int) camPos.z - halfGridZ, spacingBlocks) * spacingBlocks;

        if (idealOriginX != gridOrigin.x || idealOriginY != gridOrigin.y
                || idealOriginZ != gridOrigin.z) {
            LOGGER.debug("[DDGI] Grid scroll: ({},{},{}) → ({},{},{})",
                gridOrigin.x, gridOrigin.y, gridOrigin.z,
                idealOriginX, idealOriginY, idealOriginZ);
            gridOrigin.set(idealOriginX, idealOriginY, idealOriginZ);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  座標計算（核心數學，純 Java 可測）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 計算 probe 的世界座標（block 中心）。
     *
     * @param ix probe X 索引（0 ≤ ix &lt; gridX）
     * @param iy probe Y 索引（0 ≤ iy &lt; gridY）
     * @param iz probe Z 索引（0 ≤ iz &lt; gridZ）
     * @return 世界座標（vec3 in block units）
     */
    public Vector3f probeWorldPos(int ix, int iy, int iz) {
        return new Vector3f(
            gridOrigin.x + ix * spacingBlocks + spacingBlocks * 0.5f,
            gridOrigin.y + iy * spacingBlocks + spacingBlocks * 0.5f,
            gridOrigin.z + iz * spacingBlocks + spacingBlocks * 0.5f
        );
    }

    /**
     * 將線性 probe 索引轉換為 (ix, iy, iz) 三維索引。
     * 順序：X-major → Z → Y（Y 為高度軸，更新最少）。
     *
     * @param linearIdx 線性索引（0 ≤ idx &lt; gridX × gridY × gridZ）
     * @return int[3]：{ix, iy, iz}
     */
    public int[] linearToGrid(int linearIdx) {
        int ix = linearIdx % gridX;
        int tmp = linearIdx / gridX;
        int iz = tmp % gridZ;
        int iy = tmp / gridZ;
        return new int[]{ix, iy, iz};
    }

    /**
     * 將 (ix, iy, iz) 轉換為線性 probe 索引。
     */
    public int gridToLinear(int ix, int iy, int iz) {
        return iy * gridX * gridZ + iz * gridX + ix;
    }

    /**
     * 計算 probe 在 Irradiance Atlas 中的 texel 起始座標（含 border）。
     * Atlas 排列：所有 probe 水平展開，probe[i] 起始在 (i × PROBE_IRRAD_FULL, 0)。
     *
     * @param probeIdx 線性 probe 索引
     * @return int[2]：{atlasX, atlasY}（texel 座標）
     */
    public int[] probeIrradianceAtlasOffset(int probeIdx) {
        // 每行放 gridX × gridZ 個 probe（XZ 平面），行數 = gridY
        int probesPerRow = gridX * gridZ;
        int row = probeIdx / probesPerRow;
        int col = probeIdx % probesPerRow;
        return new int[]{col * PROBE_IRRAD_FULL, row * PROBE_IRRAD_FULL};
    }

    /**
     * 八面體映射（Octahedral Projection）：將方向向量映射到 [0,1]² UV 座標。
     * 供 shader 使用的 CPU 端參考實作（用於測試）。
     *
     * @param dir 單位方向向量（world space）
     * @return float[2]：UV 座標，範圍 [0, 1]
     */
    public static float[] dirToOctUV(float[] dir) {
        // L1 正規化投影到菱形
        float absSum = Math.abs(dir[0]) + Math.abs(dir[1]) + Math.abs(dir[2]);
        float ox = dir[0] / absSum;
        float oy = dir[1] / absSum;
        // 下半球摺疊
        if (dir[2] < 0.0f) {
            float ox2 = (1.0f - Math.abs(oy)) * Math.signum(ox);
            float oy2 = (1.0f - Math.abs(ox)) * Math.signum(oy);
            ox = ox2;
            oy = oy2;
        }
        // 映射到 [0, 1]
        return new float[]{ox * 0.5f + 0.5f, oy * 0.5f + 0.5f};
    }

    /**
     * 八面體逆映射：UV [0,1]² → 方向向量。
     *
     * @param u u 座標 [0, 1]
     * @param v v 座標 [0, 1]
     * @return float[3]：單位方向向量
     */
    public static float[] octUVToDir(float u, float v) {
        float ox = u * 2.0f - 1.0f;
        float oy = v * 2.0f - 1.0f;
        float oz = 1.0f - Math.abs(ox) - Math.abs(oy);
        // 下半球逆摺疊
        if (oz < 0.0f) {
            float ox2 = (1.0f - Math.abs(oy)) * Math.signum(ox);
            float oy2 = (1.0f - Math.abs(ox)) * Math.signum(oy);
            ox = ox2;
            oy = oy2;
        }
        // 正規化
        float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
        return new float[]{ox / len, oy / len, oz / len};
    }

    /**
     * 計算世界點 P 插值所需的 8 個 probe（Trilinear 插值格）。
     *
     * <p>返回 8 個 probe 線性索引，對應插值權重由呼叫端使用三線性公式計算。
     * 任何超出網格範圍的 probe 索引以 -1 表示（邊界 clamp）。
     *
     * @param worldPos 世界座標（block 單位）
     * @return int[8]：8 個 probe 線性索引（超出範圍者為 -1）
     */
    public int[] getInterpolationProbes(Vector3f worldPos) {
        // 找到包圍 worldPos 的網格格元
        float relX = (worldPos.x - gridOrigin.x) / spacingBlocks;
        float relY = (worldPos.y - gridOrigin.y) / spacingBlocks;
        float relZ = (worldPos.z - gridOrigin.z) / spacingBlocks;

        int baseX = (int) Math.floor(relX);
        int baseY = (int) Math.floor(relY);
        int baseZ = (int) Math.floor(relZ);

        int[] result = new int[8];
        for (int i = 0; i < 8; i++) {
            int dx = (i & 1);
            int dy = (i >> 1 & 1);
            int dz = (i >> 2 & 1);
            int ix = baseX + dx;
            int iy = baseY + dy;
            int iz = baseZ + dz;

            if (ix < 0 || iy < 0 || iz < 0
                    || ix >= gridX || iy >= gridY || iz >= gridZ) {
                result[i] = -1;
            } else {
                result[i] = gridToLinear(ix, iy, iz);
            }
        }
        return result;
    }

    /**
     * 序列化 Probe UBO 資料（供 GPU 讀取 probe 世界座標）。
     *
     * <p>格式：每 probe 16 bytes（vec4）：xyz = world_pos，w = flags（0 = valid，1 = dirty）。
     *
     * @return ByteBuffer（little-endian，已 flip）
     */
    public ByteBuffer serializeProbeUBO() {
        int total = getTotalProbeCount();
        ByteBuffer buf = ByteBuffer.allocate(total * PROBE_UBO_ENTRY_SIZE)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        for (int linearIdx = 0; linearIdx < total; linearIdx++) {
            int[] grid = linearToGrid(linearIdx);
            Vector3f pos = probeWorldPos(grid[0], grid[1], grid[2]);
            buf.putFloat(pos.x);
            buf.putFloat(pos.y);
            buf.putFloat(pos.z);
            buf.putInt(0); // flags = 0（valid）
        }
        buf.flip();
        return buf;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  查詢 / 統計
    // ════════════════════════════════════════════════════════════════════════

    public boolean isInitialized()         { return initialized; }
    public int     getTotalProbeCount()    { return gridX * gridY * gridZ; }
    public int     getGridX()              { return gridX; }
    public int     getGridY()              { return gridY; }
    public int     getGridZ()              { return gridZ; }
    public int     getSpacingBlocks()      { return spacingBlocks; }
    public Vector3i getGridOrigin()        { return new Vector3i(gridOrigin); }
    public long    getFrameCount()         { return frameCount; }
    public int     getProbesUpdatedLastFrame() { return probesUpdatedThisFrame; }
    public long    getIrradianceAtlas()    { return irradianceAtlasImage; }
    public long    getIrradianceAtlasView(){ return irradianceAtlasView; }
    public long    getVisibilityAtlas()    { return visibilityAtlasImage; }
    public long    getVisibilityAtlasView(){ return visibilityAtlasView; }
    public long    getProbeUboBuffer()     { return probeUboBuffer; }

    /** Irradiance Atlas 寬度（texels）：所有 probe 水平排列，每行 gridX × gridZ 個。 */
    public int getIrradianceAtlasWidth()   { return gridX * gridZ * PROBE_IRRAD_FULL; }
    /** Irradiance Atlas 高度（texels）：行數 = gridY。 */
    public int getIrradianceAtlasHeight()  { return gridY * PROBE_IRRAD_FULL; }
    /** Visibility Atlas 寬度。 */
    public int getVisibilityAtlasWidth()   { return gridX * gridZ * PROBE_VIS_FULL; }
    /** Visibility Atlas 高度。 */
    public int getVisibilityAtlasHeight()  { return gridY * PROBE_VIS_FULL; }

    /**
     * 估算 DDGI 系統 VRAM 使用量（bytes）。
     * @return Irradiance(RGB16F) + Visibility(RG16F) + ProbeUBO
     */
    public long estimateVRAMBytes() {
        int totalProbes = getTotalProbeCount();
        long irrad   = (long) getIrradianceAtlasWidth() * getIrradianceAtlasHeight() * 6L; // RGB16F
        long vis     = (long) getVisibilityAtlasWidth()  * getVisibilityAtlasHeight()  * 4L; // RG16F
        long ubo     = (long) totalProbes * PROBE_UBO_ENTRY_SIZE;
        return irrad + vis + ubo;
    }
}
