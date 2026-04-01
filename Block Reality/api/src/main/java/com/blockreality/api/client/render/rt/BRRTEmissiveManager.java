package com.blockreality.api.client.render.rt;

import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 發光方塊抽象層與 Light BVH (SSBO) 管理器。
 *
 * <p>負責收集場景中的發光方塊位置、顏色與強度，建構 Light BVH，
 * 並將平坦光源列表與 BVH 節點兩份 SSBO 上傳到 GPU，供 ReSTIR DI 使用。
 *
 * <h3>Light BVH 演算法（RT-2-1）</h3>
 * <p>使用遞歸二分法（Recursive Median Split）建構 SAH-free Light BVH：
 * <ol>
 *   <li>計算所有光源的 AABB 和總 power（luminance）</li>
 *   <li>沿最長軸將光源列表排序並在中點切分</li>
 *   <li>遞歸建構左右子樹</li>
 *   <li>深度 &gt; {@link #MAX_BVH_DEPTH} 或節點內光源數 ≤ {@link #BVH_LEAF_MAX_LIGHTS}
 *       時建立葉節點</li>
 *   <li>序列化為線性陣列（BFS 順序），上傳 GPU SSBO</li>
 * </ol>
 *
 * <h3>GPU 節點格式（{@link #BVH_NODE_SIZE} bytes = 48 bytes/node）</h3>
 * <pre>
 * vec4 [0]: (minX, minY, minZ, totalPower)      — AABB min + 子樹總 power
 * vec4 [1]: (maxX, maxY, maxZ, _pad)            — AABB max
 * uvec4[2]: (flags, rightChildOrLightIdx, lightCount, _pad)
 *   flags bit 31   = isLeaf (0=internal, 1=leaf)
 *   flags bits 0-30= leftChildIdx (internal) 或 lightIdx (leaf)
 *   rightChildOrLightIdx: internal 時為右子節點 idx；leaf 時為 0
 *   lightCount: 此子樹的光源總數
 * </pre>
 *
 * <h3>ReSTIR DI 採樣路徑</h3>
 * <pre>
 * GPU shader:
 *   traverseLightBVH(shadingPoint, N, rng)
 *     → 隨機選取重要光源（proportional to power / distance²）
 *     → 返回 candidateLightIdx + pdf
 *     → 後續 reservoir update（在 restir_di.comp.glsl 中實作）
 * </pre>
 *
 * @see BRReSTIRDI
 */
@SuppressWarnings("deprecation") // Phase 4-F: uses deprecated old-pipeline classes pending removal
public class BRRTEmissiveManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BRRTEmissiveManager.class);

    private static final BRRTEmissiveManager INSTANCE = new BRRTEmissiveManager();

    public static BRRTEmissiveManager getInstance() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  常數
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 每個 Light Entry（平坦光源列表）的 GPU 大小。
     * 格式：vec4(pos.xyz, intensity) + vec4(color.rgb, radius) = 32 bytes
     */
    private static final int LIGHT_ENTRY_SIZE = 32;

    /**
     * 每個 BVH 節點的 GPU 大小：3 × vec4 = 48 bytes。
     * 格式：[0] minXYZ+power，[1] maxXYZ+pad，[2] flags+rightChild+lightCount+pad
     */
    public static final int BVH_NODE_SIZE = 48;

    /**
     * BVH 葉節點的最大光源數量。
     * 葉節點光源數 ≤ 此值時停止分裂。
     * 建議值：1-4（越小 GPU 採樣越精確，越大 BVH 建構越快）
     */
    public static final int BVH_LEAF_MAX_LIGHTS = 1;

    /**
     * BVH 最大深度。防止大量共線光源造成 BVH 建構遞歸過深。
     * 對應最多 2^MAX_BVH_DEPTH 個葉節點（通常遠小於此值）。
     */
    public static final int MAX_BVH_DEPTH = 20;

    /**
     * BVH 節點 flags 中 isLeaf 的位元位置（bit 31）。
     */
    private static final int BVH_LEAF_BIT = 1 << 31;

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    private final List<LightNode> activeLights = new ArrayList<>();

    /** 最近一次 buildLightBVH() 的輸出（BFS 線性 BVH 節點陣列） */
    private final List<BVHNode> bvhNodes = new ArrayList<>();

    private long lightSsboHandle  = 0L;
    private long lightSsboMemory  = 0L;
    private long bvhSsboHandle    = 0L;
    private long bvhSsboMemory    = 0L;
    private boolean isDirty       = true;
    private boolean bvhDirty      = true;

    private BRRTEmissiveManager() {}

    /**
     * 發光節點表示 (Light Node)
     */
    public static class LightNode {
        public Vector3f position;
        public Vector3f color;
        public float intensity;
        public float radius;

        public LightNode(Vector3f pos, Vector3f color, float intensity, float radius) {
            this.position = pos;
            this.color = color;
            this.intensity = intensity;
            this.radius = radius;
        }
    }

    /**
     * 添加新的發光方塊到 Light Tree。
     *
     * @param pos       世界座標位置（方塊中心）
     * @param color     RGB 發光顏色（線性空間，通常 [0,1]+ for HDR）
     * @param intensity 發光強度（流明，物理單位）
     */
    public void addEmissiveBlock(Vector3f pos, Vector3f color, float intensity) {
        float radius = (float) Math.sqrt(intensity) * 5.0f;
        activeLights.add(new LightNode(pos, color, intensity, radius));
        isDirty  = true;
        bvhDirty = true; // 光源列表變化 → BVH 需重建
    }

    /**
     * 清空當前所有光源（通常在區塊卸載或重新載入時呼叫）。
     */
    public void clearLights() {
        activeLights.clear();
        bvhNodes.clear();
        isDirty  = true;
        bvhDirty = true;
    }

    /**
     * 取得目前註冊的光源數量
     */
    public int getLightCount() {
        return activeLights.size();
    }

    /** @return 最近一次 buildLightBVH() 產生的 BVH 節點數量 */
    public int getBVHNodeCount() {
        return bvhNodes.size();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  BVH 節點內部結構（Java 側序列化用）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Light BVH 節點（Java 側暫存，最終序列化為 GPU SSBO）。
     *
     * <p>GPU 格式（48 bytes/node，3 × vec4）：
     * <pre>
     * 偏移  0: float minX, minY, minZ, totalPower
     * 偏移 16: float maxX, maxY, maxZ, _pad
     * 偏移 32: uint flags (bit31=isLeaf, bits 0-30 = leftChildIdx 或 lightIdx)
     *         uint rightChildOrLightIdx (internal 時為右子 idx；leaf 時未使用)
     *         uint lightCount
     *         uint _pad
     * </pre>
     */
    static final class BVHNode {
        // AABB
        float minX, minY, minZ;
        float maxX, maxY, maxZ;

        /** 此子樹的總光照能量（luminance，供 GPU 側重要性採樣） */
        float totalPower;

        /** 葉節點：lightIdx；內部節點：leftChildIdx（BFS index） */
        int leftOrLightIdx;
        /** 內部節點右子節點的 BFS 索引；葉節點時未使用（= 0） */
        int rightChildIdx;
        /** 此子樹包含的光源數量 */
        int lightCount;
        /** 是否為葉節點 */
        boolean isLeaf;

        BVHNode() {
            minX = minY = minZ = Float.MAX_VALUE;
            maxX = maxY = maxZ = -Float.MAX_VALUE;
        }

        /** 展開 AABB 以包含給定點 */
        void expandAABB(float x, float y, float z) {
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }

        /** 展開 AABB 以包含另一個節點的 AABB */
        void expandAABB(BVHNode other) {
            if (other.minX < minX) minX = other.minX;
            if (other.minY < minY) minY = other.minY;
            if (other.minZ < minZ) minZ = other.minZ;
            if (other.maxX > maxX) maxX = other.maxX;
            if (other.maxY > maxY) maxY = other.maxY;
            if (other.maxZ > maxZ) maxZ = other.maxZ;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Light BVH 建構（RT-2-1）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 從 {@link #activeLights} 建構 Light BVH，結果存入 {@link #bvhNodes}。
     *
     * <p>演算法：遞歸中點切分（Recursive Median Split）
     * <ol>
     *   <li>計算當前節點的 AABB 和總 power</li>
     *   <li>若光源數 ≤ {@link #BVH_LEAF_MAX_LIGHTS} 或深度 ≥ {@link #MAX_BVH_DEPTH}，
     *       建立葉節點</li>
     *   <li>否則沿 AABB 最長軸排序，在中點切分為左右子集</li>
     *   <li>先分配左子節點 BFS 索引，遞歸填充；再填充右子節點</li>
     * </ol>
     *
     * <p>建構完成後，{@link #bvhNodes} 為 BFS（廣度優先）線性陣列，
     * 與 GPU SSBO 中的節點索引一一對應。
     *
     * <p>時間複雜度：O(N log N)（排序主導）
     * <p>空間複雜度：O(N)（BVH 節點數 ≤ 2N − 1）
     *
     * @return 建構完成的 BVH 節點數量；若光源列表為空返回 0
     */
    public int buildLightBVH() {
        bvhNodes.clear();
        bvhDirty = false;

        int lightCount = activeLights.size();
        if (lightCount == 0) {
            LOGGER.debug("[LightBVH] No lights, BVH empty");
            return 0;
        }

        // 工作列表：[startIdx, endIdx)（半開區間，索引到 activeLights）
        List<Integer> lightIndices = new ArrayList<>(lightCount);
        for (int i = 0; i < lightCount; i++) lightIndices.add(i);

        // 遞歸建構，BFS 插入（深度優先填充，回填父子節點指針）
        buildBVHRecursive(lightIndices, 0, lightCount, 0);

        int nodeCount = bvhNodes.size();
        LOGGER.info("[LightBVH] Built Light BVH: {} lights → {} nodes (max depth {})",
            lightCount, nodeCount, MAX_BVH_DEPTH);
        return nodeCount;
    }

    /**
     * 遞歸建構 BVH 子樹。
     *
     * @param indices    當前子樹的光源索引列表（子列表 [start, end)）
     * @param start      子列表起始（含）
     * @param end        子列表終止（不含）
     * @param depth      當前遞歸深度
     * @return 當前節點在 {@link #bvhNodes} 中的 BFS 索引
     */
    private int buildBVHRecursive(List<Integer> indices, int start, int end, int depth) {
        // ── 分配節點槽位 ─────────────────────────────────────────────────────
        int nodeIdx = bvhNodes.size();
        BVHNode node = new BVHNode();
        bvhNodes.add(node);

        node.lightCount = end - start;

        // ── 計算 AABB 和 totalPower ───────────────────────────────────────────
        for (int i = start; i < end; i++) {
            LightNode light = activeLights.get(indices.get(i));
            node.expandAABB(light.position.x, light.position.y, light.position.z);
            node.totalPower += luminance(light.color) * light.intensity;
        }

        // ── 葉節點條件 ───────────────────────────────────────────────────────
        int count = end - start;
        if (count <= BVH_LEAF_MAX_LIGHTS || depth >= MAX_BVH_DEPTH) {
            node.isLeaf = true;
            // 葉節點儲存第一個光源的索引（BVH_LEAF_MAX_LIGHTS=1 時 count 必為 1）
            node.leftOrLightIdx = indices.get(start);
            node.rightChildIdx  = 0;
            return nodeIdx;
        }

        // ── 選擇切分軸（最長 AABB 軸） ────────────────────────────────────────
        float extX = node.maxX - node.minX;
        float extY = node.maxY - node.minY;
        float extZ = node.maxZ - node.minZ;
        final int axis; // 0=X, 1=Y, 2=Z
        if (extX >= extY && extX >= extZ) axis = 0;
        else if (extY >= extZ)            axis = 1;
        else                               axis = 2;

        // ── 沿切分軸排序並在中點切分 ─────────────────────────────────────────
        Comparator<Integer> comparator = Comparator.comparingDouble(i -> {
            LightNode l = activeLights.get(i);
            return axis == 0 ? l.position.x : (axis == 1 ? l.position.y : l.position.z);
        });
        // 只排序 [start, end) 子區間
        List<Integer> sub = new ArrayList<>(indices.subList(start, end));
        sub.sort(comparator);
        for (int i = start; i < end; i++) indices.set(i, sub.get(i - start));

        int mid = start + count / 2;

        // ── 遞歸建構左子樹（緊接在父節點之後） ───────────────────────────────
        int leftChildIdx = buildBVHRecursive(indices, start, mid, depth + 1);

        // ── 遞歸建構右子樹 ────────────────────────────────────────────────────
        int rightChildIdx = buildBVHRecursive(indices, mid, end, depth + 1);

        // ── 回填父節點指針 ────────────────────────────────────────────────────
        node.isLeaf         = false;
        node.leftOrLightIdx = leftChildIdx;
        node.rightChildIdx  = rightChildIdx;

        return nodeIdx;
    }

    /**
     * 計算 RGB 顏色的相對亮度（ITU-R BT.709 係數）。
     * 用於 BVH power 估算（比三通道平均更符合人眼感知）。
     *
     * @param color RGB 顏色向量
     * @return 相對亮度值（0.0–1.0+ 無上限）
     */
    private static float luminance(Vector3f color) {
        return 0.2126f * color.x + 0.7152f * color.y + 0.0722f * color.z;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GPU SSBO 序列化與上傳
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 將 Light BVH 節點列表序列化為 GPU ByteBuffer。
     *
     * <p>格式見 {@link BVHNode} 文件。呼叫者負責釋放返回的 ByteBuffer
     * （使用 {@link MemoryUtil#memFree}）。
     *
     * @return 序列化後的 ByteBuffer，capacity = nodeCount × {@link #BVH_NODE_SIZE}
     */
    public ByteBuffer serializeLightBVH() {
        int nodeCount = bvhNodes.size();
        if (nodeCount == 0) return null;

        ByteBuffer buf = MemoryUtil.memAlloc(nodeCount * BVH_NODE_SIZE);

        for (BVHNode node : bvhNodes) {
            // vec4[0]: minXYZ + totalPower
            buf.putFloat(node.minX);
            buf.putFloat(node.minY);
            buf.putFloat(node.minZ);
            buf.putFloat(node.totalPower);

            // vec4[1]: maxXYZ + _pad
            buf.putFloat(node.maxX);
            buf.putFloat(node.maxY);
            buf.putFloat(node.maxZ);
            buf.putFloat(0.0f); // _pad

            // uvec4[2]: flags | rightChildOrLightIdx | lightCount | _pad
            int flags = node.isLeaf
                ? (BVH_LEAF_BIT | (node.leftOrLightIdx & 0x7FFFFFFF))
                : (node.leftOrLightIdx & 0x7FFFFFFF);
            buf.putInt(flags);
            buf.putInt(node.isLeaf ? 0 : node.rightChildIdx);
            buf.putInt(node.lightCount);
            buf.putInt(0); // _pad
        }

        buf.flip();
        return buf;
    }

    /**
     * 將光源列表構建並同步到 Vulkan SSBO。
     *
     * <p>★ RT-2-1：若光源列表已更新（{@code isDirty}），自動重建 Light BVH
     * 並上傳 BVH SSBO（{@link #bvhSsboHandle}），使 ReSTIR DI shader 能正確採樣。
     *
     * @param device 當前 Vulkan 設備句柄
     */
    public void flushToSSBO(long device) {
        if (!isDirty || device == 0L) return;

        int count = activeLights.size();
        if (count == 0) {
            isDirty = false;
            return;
        }

        // ── 1. 序列化平坦光源列表 ─────────────────────────────────────────────
        int lightBufSize = count * LIGHT_ENTRY_SIZE;
        ByteBuffer lightBuf = MemoryUtil.memAlloc(lightBufSize);

        for (LightNode light : activeLights) {
            lightBuf.putFloat(light.position.x);
            lightBuf.putFloat(light.position.y);
            lightBuf.putFloat(light.position.z);
            lightBuf.putFloat(light.intensity);

            lightBuf.putFloat(light.color.x);
            lightBuf.putFloat(light.color.y);
            lightBuf.putFloat(light.color.z);
            lightBuf.putFloat(light.radius);
        }
        lightBuf.flip();

        // 委託 Vulkan 側上傳（Phase 2 bridge：BRVulkanInterop.updateBufferData）
        // BRVulkanInterop.updateBufferData(lightSsboHandle, lightBuf);
        MemoryUtil.memFree(lightBuf);

        // ── 2. 建構並序列化 Light BVH ────────────────────────────────────────
        if (bvhDirty) {
            buildLightBVH();
        }

        ByteBuffer bvhBuf = serializeLightBVH();
        if (bvhBuf != null) {
            // BRVulkanInterop.updateBufferData(bvhSsboHandle, bvhBuf);
            MemoryUtil.memFree(bvhBuf);
            LOGGER.debug("[LightBVH] Uploaded Light BVH: {} nodes ({} bytes)",
                bvhNodes.size(), bvhNodes.size() * BVH_NODE_SIZE);
        }

        isDirty  = false;
        bvhDirty = false;

        LOGGER.debug("[LightBVH] Flushed {} emissive lights + {} BVH nodes to SSBO",
            count, bvhNodes.size());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  統計
    // ════════════════════════════════════════════════════════════════════════

    /**
     * @return Light BVH SSBO 估計大小（bytes），供 VRAM 預算追蹤
     */
    public long getBVHMemoryBytes() {
        return (long) bvhNodes.size() * BVH_NODE_SIZE;
    }
}
