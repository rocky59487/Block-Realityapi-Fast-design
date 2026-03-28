package com.blockreality.api.client.render.optimization;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Sparse Voxel DAG compression system for LOD data.
 *
 * <p>Compresses voxel data via a directed acyclic graph, sharing identical subtrees
 * across the octree hierarchy. Achieves 5-10x compression vs raw voxel grids by
 * deduplicating structurally identical sub-volumes.</p>
 *
 * <p>Reference: K. Kampe, E. Sintorn, U. Assarsson,
 * "High Resolution Sparse Voxel DAGs", ACM Trans. Graph. 32(4), 2013.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BRSparseVoxelDAG {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRSparseVoxelDAG.class);

    private static final byte[] MAGIC = {'B', 'R', 'D', 'A', 'G', 0};
    private static final int FORMAT_VERSION = 1;

    private BRSparseVoxelDAG() {}

    // ---- Inner class ----

    /**
     * DAG node: either internal (has children) or leaf (has data).
     */
    public static final class DAGNode {
        final int id;
        final int childMask;       // 8-bit: which octants have children
        final int[] childIndices;  // indices into node pool (max 8)
        final int materialId;     // leaf material (-1 for internal)
        final int lodLevel;

        public DAGNode(int id, int childMask, int[] childIndices, int materialId, int lodLevel) {
            this.id = id;
            this.childMask = childMask;
            this.childIndices = childIndices;
            this.materialId = materialId;
            this.lodLevel = lodLevel;
        }
    }

    // ---- Fields ----

    private static final ArrayList<DAGNode> nodes = new ArrayList<>();
    private static final HashMap<Long, Integer> nodeHashMap = new HashMap<>();
    private static int rootIndex = -1;
    private static int maxDepth = 10;

    private static int totalNodes = 0;
    private static int deduplicatedNodes = 0;
    private static float compressionRatio = 1.0f;

    private static boolean initialized = false;

    // ---- Lifecycle ----

    /**
     * Initialize the DAG with the given maximum tree depth.
     * A depth of 10 supports up to 1024^3 voxel grids.
     *
     * @param depth maximum octree depth
     */
    public static void init(int depth) {
        if (initialized) {
            LOGGER.warn("BRSparseVoxelDAG already initialized, cleaning up first");
            cleanup();
        }
        maxDepth = depth;
        nodes.clear();
        nodeHashMap.clear();
        rootIndex = -1;
        totalNodes = 0;
        deduplicatedNodes = 0;
        compressionRatio = 1.0f;
        initialized = true;
        LOGGER.info("BRSparseVoxelDAG initialized with maxDepth={}", maxDepth);
    }

    /**
     * Clear all DAG data and release memory.
     */
    public static void cleanup() {
        nodes.clear();
        nodeHashMap.clear();
        rootIndex = -1;
        totalNodes = 0;
        deduplicatedNodes = 0;
        compressionRatio = 1.0f;
        initialized = false;
        LOGGER.info("BRSparseVoxelDAG cleaned up");
    }

    /**
     * @return true if the DAG has been initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    // ---- Build ----

    /**
     * Build a DAG from a raw 3D voxel grid. The grid dimensions are rounded up to
     * the next power of two internally to form a proper octree.
     *
     * @param voxelData flat array of material IDs (x + y*sizeX + z*sizeX*sizeY)
     * @param sizeX     grid width
     * @param sizeY     grid height
     * @param sizeZ     grid depth
     * @return root node index
     */
    public static int buildFromVoxelGrid(int[] voxelData, int sizeX, int sizeY, int sizeZ) {
        if (!initialized) {
            LOGGER.error("BRSparseVoxelDAG not initialized, call init() first");
            return -1;
        }

        nodes.clear();
        nodeHashMap.clear();
        totalNodes = 0;
        deduplicatedNodes = 0;

        int maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
        int gridSize = 1;
        int depth = 0;
        while (gridSize < maxDim) {
            gridSize <<= 1;
            depth++;
        }
        if (depth > maxDepth) {
            depth = maxDepth;
            gridSize = 1 << maxDepth;
        }

        LOGGER.info("Building DAG from voxel grid {}x{}x{}, padded to {}, depth={}",
                sizeX, sizeY, sizeZ, gridSize, depth);

        rootIndex = buildRecursive(voxelData, sizeX, sizeY, sizeZ, 0, 0, 0, gridSize, depth);

        int rawNodeCount = countRawOctreeNodes(depth);
        compressionRatio = rawNodeCount > 0 ? (float) rawNodeCount / nodes.size() : 1.0f;

        LOGGER.info("DAG built: {} nodes ({} deduplicated), compression ratio {:.2f}x",
                nodes.size(), deduplicatedNodes, compressionRatio);

        return rootIndex;
    }

    private static int buildRecursive(int[] voxelData, int sizeX, int sizeY, int sizeZ,
                                      int ox, int oy, int oz, int size, int depth) {
        totalNodes++;

        // Leaf level: return material at this position
        if (size == 1 || depth == 0) {
            int mat = sampleVoxel(voxelData, sizeX, sizeY, sizeZ, ox, oy, oz);
            return getOrCreateLeaf(mat, depth);
        }

        int half = size >> 1;
        int[] childIndices = new int[8];
        int childMask = 0;
        boolean allSame = true;
        int firstChild = -1;

        for (int i = 0; i < 8; i++) {
            int cx = ox + ((i & 1) != 0 ? half : 0);
            int cy = oy + ((i & 2) != 0 ? half : 0);
            int cz = oz + ((i & 4) != 0 ? half : 0);

            childIndices[i] = buildRecursive(voxelData, sizeX, sizeY, sizeZ,
                    cx, cy, cz, half, depth - 1);

            if (childIndices[i] >= 0) {
                childMask |= (1 << i);
            }

            if (i == 0) {
                firstChild = childIndices[i];
            } else if (childIndices[i] != firstChild) {
                allSame = false;
            }
        }

        // If all children are identical, collapse to the single child node
        if (allSame && firstChild >= 0) {
            deduplicatedNodes++;
            return firstChild;
        }

        // Deduplicate via hash
        long hash = computeNodeHash(childMask, childIndices, -1);
        Integer existing = nodeHashMap.get(hash);
        if (existing != null) {
            DAGNode existingNode = nodes.get(existing);
            if (existingNode.childMask == childMask && matchChildren(existingNode.childIndices, childIndices)) {
                deduplicatedNodes++;
                return existing;
            }
        }

        int nodeId = nodes.size();
        int[] compactChildren = compactChildArray(childMask, childIndices);
        DAGNode node = new DAGNode(nodeId, childMask, compactChildren, -1, depth);
        nodes.add(node);
        nodeHashMap.put(hash, nodeId);
        return nodeId;
    }

    private static int getOrCreateLeaf(int materialId, int lodLevel) {
        long hash = computeNodeHash(0, new int[0], materialId);
        Integer existing = nodeHashMap.get(hash);
        if (existing != null) {
            DAGNode existingNode = nodes.get(existing);
            if (existingNode.materialId == materialId && existingNode.childMask == 0) {
                deduplicatedNodes++;
                return existing;
            }
        }

        int nodeId = nodes.size();
        DAGNode leaf = new DAGNode(nodeId, 0, new int[0], materialId, lodLevel);
        nodes.add(leaf);
        nodeHashMap.put(hash, nodeId);
        return nodeId;
    }

    private static int sampleVoxel(int[] voxelData, int sizeX, int sizeY, int sizeZ,
                                   int x, int y, int z) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            return 0; // air / empty
        }
        int index = x + y * sizeX + z * sizeX * sizeY;
        if (index < 0 || index >= voxelData.length) {
            return 0;
        }
        return voxelData[index];
    }

    private static int[] compactChildArray(int childMask, int[] fullChildren) {
        int count = Integer.bitCount(childMask);
        int[] compact = new int[count];
        int ci = 0;
        for (int i = 0; i < 8; i++) {
            if ((childMask & (1 << i)) != 0) {
                compact[ci++] = fullChildren[i];
            }
        }
        return compact;
    }

    private static boolean matchChildren(int[] a, int[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static int countRawOctreeNodes(int depth) {
        // Geometric sum: 1 + 8 + 64 + ... + 8^depth = (8^(depth+1) - 1) / 7
        // Cap to avoid overflow
        if (depth > 10) depth = 10;
        int count = 0;
        int level = 1;
        for (int i = 0; i <= depth; i++) {
            count += level;
            if (level > Integer.MAX_VALUE / 8) break;
            level *= 8;
        }
        return count;
    }

    // ---- Query ----

    /**
     * Traverse the DAG to find the material at the given voxel position.
     *
     * @param x voxel X coordinate
     * @param y voxel Y coordinate
     * @param z voxel Z coordinate
     * @return material ID at the position, or 0 if empty/out of bounds
     */
    public static int query(int x, int y, int z) {
        if (!initialized || rootIndex < 0 || nodes.isEmpty()) {
            return 0;
        }
        return traverseNode(rootIndex, x, y, z, 1 << maxDepth, maxDepth);
    }

    /**
     * Query at a specific LOD level. Traversal stops early at the target LOD,
     * returning the dominant material of that sub-volume.
     *
     * @param x         voxel X coordinate
     * @param y         voxel Y coordinate
     * @param z         voxel Z coordinate
     * @param targetLOD LOD level (0 = full resolution, higher = coarser)
     * @return material ID at the given LOD
     */
    public static int queryLOD(int x, int y, int z, int targetLOD) {
        if (!initialized || rootIndex < 0 || nodes.isEmpty()) {
            return 0;
        }
        return traverseNodeLOD(rootIndex, x, y, z, 1 << maxDepth, maxDepth, targetLOD);
    }

    private static int traverseNode(int nodeIndex, int x, int y, int z, int size, int depth) {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            return 0;
        }
        DAGNode node = nodes.get(nodeIndex);

        // Leaf node
        if (node.childMask == 0) {
            return node.materialId;
        }

        if (depth <= 0) {
            return node.materialId;
        }

        int half = size >> 1;
        if (half == 0) return node.materialId;

        int octant = 0;
        if (x >= half) { octant |= 1; x -= half; }
        if (y >= half) { octant |= 2; y -= half; }
        if (z >= half) { octant |= 4; z -= half; }

        if ((node.childMask & (1 << octant)) == 0) {
            return 0; // empty octant
        }

        int compactIndex = Integer.bitCount(node.childMask & ((1 << octant) - 1));
        if (compactIndex >= node.childIndices.length) {
            return 0;
        }

        return traverseNode(node.childIndices[compactIndex], x, y, z, half, depth - 1);
    }

    private static int traverseNodeLOD(int nodeIndex, int x, int y, int z,
                                       int size, int depth, int targetLOD) {
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            return 0;
        }
        DAGNode node = nodes.get(nodeIndex);

        // Reached target LOD or leaf
        if (depth <= targetLOD || node.childMask == 0) {
            return node.materialId;
        }

        if (depth <= 0) {
            return node.materialId;
        }

        int half = size >> 1;
        if (half == 0) return node.materialId;

        int octant = 0;
        int lx = x, ly = y, lz = z;
        if (lx >= half) { octant |= 1; lx -= half; }
        if (ly >= half) { octant |= 2; ly -= half; }
        if (lz >= half) { octant |= 4; lz -= half; }

        if ((node.childMask & (1 << octant)) == 0) {
            return 0;
        }

        int compactIndex = Integer.bitCount(node.childMask & ((1 << octant) - 1));
        if (compactIndex >= node.childIndices.length) {
            return 0;
        }

        return traverseNodeLOD(node.childIndices[compactIndex], lx, ly, lz, half, depth - 1, targetLOD);
    }

    // ---- Statistics ----

    /**
     * @return compression ratio (raw nodes / actual nodes)
     */
    public static float getCompressionRatio() {
        return compressionRatio;
    }

    /**
     * @return total number of nodes in the DAG pool
     */
    public static int getTotalNodes() {
        return nodes.size();
    }

    /**
     * @return number of nodes eliminated by deduplication
     */
    public static int getDeduplicatedNodes() {
        return deduplicatedNodes;
    }

    /**
     * @return maximum octree depth
     */
    public static int getMaxDepth() {
        return maxDepth;
    }

    // ---- Hash ----

    /**
     * Compute a hash for a node configuration using FNV-1a style hashing.
     * Used for deduplication lookup.
     *
     * @param childMask    8-bit child occupancy mask
     * @param childIndices child node indices
     * @param materialId   material ID (-1 for internal nodes)
     * @return 64-bit hash
     */
    public static long computeNodeHash(int childMask, int[] childIndices, int materialId) {
        // FNV-1a 64-bit
        long hash = 0xcbf29ce484222325L;
        final long FNV_PRIME = 0x100000001b3L;

        hash ^= childMask;
        hash *= FNV_PRIME;

        hash ^= materialId;
        hash *= FNV_PRIME;

        for (int idx : childIndices) {
            hash ^= idx;
            hash *= FNV_PRIME;
        }

        return hash;
    }

    // ---- Serialization ----

    /**
     * Serialize the entire DAG to a compact binary format for disk storage.
     *
     * <p>Format: magic "BRDAG\0", version (int), maxDepth (int), rootIndex (int),
     * nodeCount (int), then for each node: id, childMask, childCount, childIndices[],
     * materialId, lodLevel.</p>
     *
     * @return byte array containing the serialized DAG, or null on failure
     */
    public static byte[] serialize() {
        if (!initialized || nodes.isEmpty()) {
            LOGGER.warn("Cannot serialize: DAG is empty or not initialized");
            return null;
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // Header
            dos.write(MAGIC);
            dos.writeInt(FORMAT_VERSION);
            dos.writeInt(maxDepth);
            dos.writeInt(rootIndex);
            dos.writeInt(nodes.size());

            // Nodes
            for (DAGNode node : nodes) {
                dos.writeInt(node.id);
                dos.writeInt(node.childMask);
                dos.writeInt(node.childIndices.length);
                for (int ci : node.childIndices) {
                    dos.writeInt(ci);
                }
                dos.writeInt(node.materialId);
                dos.writeInt(node.lodLevel);
            }

            dos.flush();
            byte[] result = baos.toByteArray();
            LOGGER.info("Serialized DAG: {} bytes, {} nodes", result.length, nodes.size());
            return result;
        } catch (IOException e) {
            LOGGER.error("Failed to serialize DAG", e);
            return null;
        }
    }

    /**
     * Rebuild the DAG from a previously serialized binary representation.
     *
     * @param data byte array from {@link #serialize()}
     */
    public static void deserialize(byte[] data) {
        if (data == null || data.length < MAGIC.length + 16) {
            LOGGER.error("Cannot deserialize: invalid data");
            return;
        }

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);

            // Verify magic
            byte[] magic = new byte[MAGIC.length];
            dis.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    LOGGER.error("Cannot deserialize: invalid magic bytes");
                    return;
                }
            }

            int version = dis.readInt();
            if (version != FORMAT_VERSION) {
                LOGGER.error("Cannot deserialize: unsupported version {} (expected {})",
                        version, FORMAT_VERSION);
                return;
            }

            int depth = dis.readInt();
            int root = dis.readInt();
            int nodeCount = dis.readInt();

            // Rebuild
            nodes.clear();
            nodeHashMap.clear();

            for (int n = 0; n < nodeCount; n++) {
                int id = dis.readInt();
                int childMask = dis.readInt();
                int childCount = dis.readInt();
                int[] childIndices = new int[childCount];
                for (int c = 0; c < childCount; c++) {
                    childIndices[c] = dis.readInt();
                }
                int materialId = dis.readInt();
                int lodLevel = dis.readInt();

                DAGNode node = new DAGNode(id, childMask, childIndices, materialId, lodLevel);
                nodes.add(node);

                long hash = computeNodeHash(childMask, childIndices, materialId);
                nodeHashMap.put(hash, id);
            }

            maxDepth = depth;
            rootIndex = root;
            totalNodes = nodeCount;
            deduplicatedNodes = 0;
            compressionRatio = 1.0f;
            initialized = true;

            LOGGER.info("Deserialized DAG: {} nodes, maxDepth={}, rootIndex={}",
                    nodeCount, maxDepth, rootIndex);
        } catch (IOException e) {
            LOGGER.error("Failed to deserialize DAG", e);
        }
    }
}
