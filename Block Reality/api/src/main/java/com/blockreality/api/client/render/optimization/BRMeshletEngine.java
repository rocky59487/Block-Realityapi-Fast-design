package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Nanite-inspired Meshlet clustering system for Block Reality.
 * <p>
 * Academic reference: UE5 Nanite virtualized geometry (Karis 2021).
 * Divides chunk meshes into clusters of max 128 triangles organized in a DAG
 * hierarchy. The CPU performs frustum culling, backface-cone culling, and LOD
 * selection per-meshlet; surviving meshlets are rendered via indirect draw
 * commands.
 * </p>
 */
@OnlyIn(Dist.CLIENT)
public final class BRMeshletEngine {

    private BRMeshletEngine() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-Meshlet");

    // ═══════════════════════════════════════════════════════════════════
    //  Constants
    // ═══════════════════════════════════════════════════════════════════

    /** Maximum triangles packed into a single meshlet. */
    public static final int MAX_TRIANGLES_PER_MESHLET = 128;

    /** Maximum unique vertices referenced by a single meshlet. */
    public static final int MAX_VERTICES_PER_MESHLET = 64;

    /** Maximum index count per meshlet (128 triangles * 3 indices). */
    public static final int MAX_INDICES_PER_MESHLET = 384;

    /** Number of LOD levels (0 = full detail, 1-4 = reduced). */
    public static final int LOD_LEVELS = 5;

    /** Byte size of one meshlet descriptor in SSBO (aligned to 64). */
    public static final int MESHLET_DESCRIPTOR_SIZE = 64;

    // ═══════════════════════════════════════════════════════════════════
    //  Inner classes
    // ═══════════════════════════════════════════════════════════════════

    /**
     * A single meshlet — a small cluster of up to {@value MAX_TRIANGLES_PER_MESHLET}
     * triangles with precomputed bounding sphere and normal cone for fast culling.
     */
    public static final class Meshlet {
        public int vertexOffset;
        public int vertexCount;
        public int indexOffset;
        /** Actual index count for this meshlet (max {@value MAX_INDICES_PER_MESHLET}). */
        public int indexCount;

        // Bounding sphere
        public float boundCenterX, boundCenterY, boundCenterZ;
        public float boundRadius;

        // Normal cone for backface cluster culling
        public float normalConeAxisX, normalConeAxisY, normalConeAxisZ;
        /** Cutoff dot-product — if dot(viewDir, coneAxis) < cutoff the entire meshlet faces away. */
        public float normalConeCutoff;

        /** LOD level: 0 = full detail, 1-4 = progressively reduced. */
        public int lodLevel;

        /** Index of the parent meshlet in the DAG (-1 for root level). */
        public int parentMeshletIndex;
    }

    /**
     * A batch of meshlets sharing a single VAO/VBO/EBO plus an SSBO holding the
     * meshlet descriptor array for GPU-side access.
     */
    public static final class MeshletBatch {
        public int vao;
        public int vbo;
        public int ebo;
        public int meshletCount;
        /** Shader Storage Buffer Object containing packed {@link Meshlet} descriptors. */
        public int meshletSSBO;
        public long estimatedVRAM;

        // Internal bookkeeping
        int[] visibleIndicesBuffer;
        int visibleCount;
        List<Meshlet> meshlets;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════════════════════

    private static boolean initialized = false;
    private static final List<MeshletBatch> activeBatches = new ArrayList<>();
    private static int totalMeshletCount = 0;
    private static int visibleMeshletCount = 0;
    private static long totalVRAM = 0L;

    // Shared indirect-draw buffer
    private static int indirectBuffer = 0;

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Allocate shared resources (indirect draw buffer, etc.).
     * Must be called on the render thread before any meshlet operations.
     */
    public static void init() {
        if (initialized) {
            LOG.warn("BRMeshletEngine.init() called while already initialized — skipping");
            return;
        }
        indirectBuffer = GL15.glGenBuffers();
        // Pre-allocate indirect buffer large enough for a reasonable meshlet count.
        // DrawElementsIndirectCommand: 5 ints = 20 bytes per command.
        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        GL15.glBufferData(GL43.GL_DRAW_INDIRECT_BUFFER, 20L * 4096, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);

        initialized = true;
        LOG.info("BRMeshletEngine initialised — indirect buffer {}", indirectBuffer);
    }

    /**
     * Release every active batch and shared GL resources.
     */
    public static void cleanup() {
        if (!initialized) {
            return;
        }
        for (MeshletBatch batch : new ArrayList<>(activeBatches)) {
            destroyBatch(batch);
        }
        activeBatches.clear();

        if (indirectBuffer != 0) {
            GL15.glDeleteBuffers(indirectBuffer);
            indirectBuffer = 0;
        }

        totalMeshletCount = 0;
        visibleMeshletCount = 0;
        totalVRAM = 0L;
        initialized = false;
        LOG.info("BRMeshletEngine cleaned up");
    }

    /**
     * @return {@code true} if {@link #init()} has been called and {@link #cleanup()} has not.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Build meshlets
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Partition an indexed triangle mesh into meshlets of up to
     * {@value MAX_TRIANGLES_PER_MESHLET} triangles each.
     *
     * @param vertices    interleaved vertex data (position + any attributes)
     * @param indices     triangle index buffer (length must be divisible by 3)
     * @param vertexStride number of floats per vertex (minimum 3 for xyz)
     * @return a {@link MeshletBatch} ready for culling and rendering
     */
    public static MeshletBatch buildMeshlets(float[] vertices, int[] indices, int vertexStride) {
        if (!initialized) {
            throw new IllegalStateException("BRMeshletEngine not initialised — call init() first");
        }
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException("Index count must be divisible by 3, got " + indices.length);
        }
        if (vertexStride < 3) {
            throw new IllegalArgumentException("vertexStride must be >= 3, got " + vertexStride);
        }

        int totalTriangles = indices.length / 3;
        List<Meshlet> meshlets = new ArrayList<>();

        // Greedy sequential partitioning
        int triOffset = 0;
        while (triOffset < totalTriangles) {
            int triCount = Math.min(MAX_TRIANGLES_PER_MESHLET, totalTriangles - triOffset);
            int idxOffset = triOffset * 3;
            int idxCount = triCount * 3;

            Meshlet m = new Meshlet();
            m.indexOffset = idxOffset;
            m.indexCount = idxCount;
            m.lodLevel = 0;
            m.parentMeshletIndex = -1;

            // Determine vertex range referenced by this meshlet
            computeVertexRange(m, indices, idxOffset, idxCount);

            // Bounding sphere — min/max approach
            computeBoundingSphere(m, vertices, indices, idxOffset, idxCount, vertexStride);

            // Normal cone
            computeNormalCone(m, vertices, indices, idxOffset, idxCount, vertexStride);

            meshlets.add(m);
            triOffset += triCount;
        }

        // Upload geometry to GPU
        MeshletBatch batch = uploadBatch(vertices, indices, vertexStride, meshlets);
        batch.meshlets = meshlets;

        activeBatches.add(batch);
        totalMeshletCount += batch.meshletCount;

        LOG.debug("Built {} meshlets from {} triangles ({} vertices)",
                batch.meshletCount, totalTriangles, vertices.length / vertexStride);

        return batch;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOD hierarchy
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build a DAG LOD hierarchy on top of existing LOD-0 meshlets.
     * Every 4 children are merged into 1 parent with ~50% vertex reduction.
     * New parent meshlets are appended to the batch's meshlet list and the SSBO
     * is re-uploaded.
     *
     * @param batch the batch previously returned by {@link #buildMeshlets}
     */
    public static void buildLODHierarchy(MeshletBatch batch) {
        if (!initialized || batch == null || batch.meshlets == null) {
            return;
        }

        List<Meshlet> allMeshlets = batch.meshlets;
        List<Meshlet> currentLevel = new ArrayList<>();
        // Gather LOD-0 meshlets
        for (Meshlet m : allMeshlets) {
            if (m.lodLevel == 0) {
                currentLevel.add(m);
            }
        }

        for (int lod = 1; lod < LOD_LEVELS; lod++) {
            List<Meshlet> nextLevel = new ArrayList<>();
            for (int i = 0; i < currentLevel.size(); i += 4) {
                int groupEnd = Math.min(i + 4, currentLevel.size());
                Meshlet parent = mergeChildMeshlets(currentLevel, i, groupEnd, lod, allMeshlets.size() + nextLevel.size());

                // Link children to parent
                int parentIndex = allMeshlets.size() + nextLevel.size();
                for (int c = i; c < groupEnd; c++) {
                    currentLevel.get(c).parentMeshletIndex = parentIndex;
                }

                nextLevel.add(parent);
            }
            allMeshlets.addAll(nextLevel);
            currentLevel = nextLevel;

            if (currentLevel.size() <= 1) {
                break; // single root reached
            }
        }

        batch.meshletCount = allMeshlets.size();
        totalMeshletCount += allMeshlets.size() - batch.meshletCount;

        // Re-upload SSBO
        reuploadMeshletSSBO(batch);

        LOG.debug("LOD hierarchy built — total meshlets now {}", batch.meshletCount);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Culling & LOD selection
    // ═══════════════════════════════════════════════════════════════════

    /**
     * CPU-side meshlet visibility determination: frustum test, backface-cone
     * culling, and LOD selection.
     *
     * @param batch        the meshlet batch to cull
     * @param camX         camera world position X
     * @param camY         camera world position Y
     * @param camZ         camera world position Z
     * @param frustumPlanes 24 floats — 6 planes * (nx, ny, nz, d)
     * @return the number of visible meshlets
     */
    public static int cullAndSelectMeshlets(MeshletBatch batch,
                                            float camX, float camY, float camZ,
                                            float[] frustumPlanes) {
        if (batch == null || batch.meshlets == null) {
            return 0;
        }
        if (frustumPlanes.length < 24) {
            throw new IllegalArgumentException("frustumPlanes must have at least 24 floats (6 planes)");
        }

        List<Meshlet> meshlets = batch.meshlets;
        if (batch.visibleIndicesBuffer == null || batch.visibleIndicesBuffer.length < meshlets.size()) {
            batch.visibleIndicesBuffer = new int[meshlets.size()];
        }

        int visible = 0;

        for (int i = 0; i < meshlets.size(); i++) {
            Meshlet m = meshlets.get(i);

            // Skip non-leaf meshlets that have children — prefer the child level
            // unless child was culled (LOD selection below handles promotion).
            if (m.lodLevel > 0) {
                continue; // parent meshlets evaluated only via LOD promotion
            }

            // ── Frustum culling (sphere test against 6 planes) ──
            if (!sphereInFrustum(m.boundCenterX, m.boundCenterY, m.boundCenterZ,
                    m.boundRadius, frustumPlanes)) {
                continue;
            }

            // ── Backface cone culling ──
            float viewDirX = m.boundCenterX - camX;
            float viewDirY = m.boundCenterY - camY;
            float viewDirZ = m.boundCenterZ - camZ;
            float dist = (float) Math.sqrt(viewDirX * viewDirX + viewDirY * viewDirY + viewDirZ * viewDirZ);
            if (dist > 1e-6f) {
                viewDirX /= dist;
                viewDirY /= dist;
                viewDirZ /= dist;
            }
            float coneDot = viewDirX * m.normalConeAxisX
                    + viewDirY * m.normalConeAxisY
                    + viewDirZ * m.normalConeAxisZ;
            if (coneDot < m.normalConeCutoff) {
                // Entire cluster faces away from camera
                continue;
            }

            // ── LOD selection: project bounding sphere to screen space ──
            int selectedIndex = selectLOD(meshlets, i, dist, m.boundRadius);

            batch.visibleIndicesBuffer[visible++] = selectedIndex;
        }

        batch.visibleCount = visible;
        visibleMeshletCount = visible;
        return visible;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Upload & render
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Upload indirect draw commands for the visible meshlet set.
     *
     * @param batch          the batch being rendered
     * @param visibleIndices indices into the meshlet array that passed culling
     * @param count          number of visible meshlets
     */
    public static void uploadVisibleMeshlets(MeshletBatch batch, int[] visibleIndices, int count) {
        if (!initialized || batch == null || count <= 0) {
            return;
        }

        // Each DrawElementsIndirectCommand: { indexCount, instanceCount, firstIndex, baseVertex, baseInstance }
        int commandSize = 5 * 4; // 20 bytes
        ByteBuffer buf = ByteBuffer.allocateDirect(commandSize * count)
                .order(ByteOrder.nativeOrder());

        for (int i = 0; i < count; i++) {
            Meshlet m = batch.meshlets.get(visibleIndices[i]);
            buf.putInt(m.indexCount);   // indexCount
            buf.putInt(1);              // instanceCount
            buf.putInt(m.indexOffset);  // firstIndex
            buf.putInt(m.vertexOffset); // baseVertex
            buf.putInt(0);              // baseInstance
        }
        buf.flip();

        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        // Reallocate if needed
        if ((long) commandSize * count > 20L * 4096) {
            GL15.glBufferData(GL43.GL_DRAW_INDIRECT_BUFFER, buf, GL15.GL_DYNAMIC_DRAW);
        } else {
            GL15.glBufferSubData(GL43.GL_DRAW_INDIRECT_BUFFER, 0, buf);
        }
        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);
    }

    /**
     * Issue the draw call for a previously culled and uploaded batch.
     *
     * @param batch the batch to render
     */
    public static void renderBatch(MeshletBatch batch) {
        if (!initialized || batch == null || batch.visibleCount <= 0) {
            return;
        }

        GL30.glBindVertexArray(batch.vao);
        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);

        // Bind meshlet descriptor SSBO at binding point 0
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, batch.meshletSSBO);

        // Multi-draw indirect
        GL43.glMultiDrawElementsIndirect(
                GL11.GL_TRIANGLES,
                GL11.GL_UNSIGNED_INT,
                0L,
                batch.visibleCount,
                0  // tightly packed
        );

        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
        GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Destroy a single batch and free its GL resources.
     *
     * @param batch the batch to destroy
     */
    public static void destroyBatch(MeshletBatch batch) {
        if (batch == null) {
            return;
        }

        if (batch.vao != 0) {
            GL30.glDeleteVertexArrays(batch.vao);
        }
        if (batch.vbo != 0) {
            GL15.glDeleteBuffers(batch.vbo);
        }
        if (batch.ebo != 0) {
            GL15.glDeleteBuffers(batch.ebo);
        }
        if (batch.meshletSSBO != 0) {
            GL15.glDeleteBuffers(batch.meshletSSBO);
        }

        totalVRAM -= batch.estimatedVRAM;
        totalMeshletCount -= batch.meshletCount;
        activeBatches.remove(batch);

        batch.vao = 0;
        batch.vbo = 0;
        batch.ebo = 0;
        batch.meshletSSBO = 0;
        batch.meshlets = null;

        LOG.debug("Destroyed meshlet batch — remaining batches: {}", activeBatches.size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Statistics
    // ═══════════════════════════════════════════════════════════════════

    /** @return total number of meshlets across all active batches (all LOD levels). */
    public static int getTotalMeshletCount() {
        return totalMeshletCount;
    }

    /** @return number of meshlets that survived the last cull pass. */
    public static int getVisibleMeshletCount() {
        return visibleMeshletCount;
    }

    /** @return estimated total VRAM in bytes consumed by all active batches. */
    public static long getTotalVRAM() {
        return totalVRAM;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Determine the vertex offset and count for a meshlet from its index range.
     */
    private static void computeVertexRange(Meshlet m, int[] indices, int idxOffset, int idxCount) {
        int minVert = Integer.MAX_VALUE;
        int maxVert = Integer.MIN_VALUE;
        for (int i = idxOffset; i < idxOffset + idxCount; i++) {
            int v = indices[i];
            if (v < minVert) minVert = v;
            if (v > maxVert) maxVert = v;
        }
        m.vertexOffset = minVert;
        m.vertexCount = maxVert - minVert + 1;
    }

    /**
     * Compute a bounding sphere via the min/max extents method.
     * center = (min + max) / 2, radius = max distance from center.
     */
    private static void computeBoundingSphere(Meshlet m, float[] vertices, int[] indices,
                                              int idxOffset, int idxCount, int stride) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (int i = idxOffset; i < idxOffset + idxCount; i++) {
            int base = indices[i] * stride;
            float x = vertices[base];
            float y = vertices[base + 1];
            float z = vertices[base + 2];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        m.boundCenterX = (minX + maxX) * 0.5f;
        m.boundCenterY = (minY + maxY) * 0.5f;
        m.boundCenterZ = (minZ + maxZ) * 0.5f;

        // Radius = max distance from center to any referenced vertex
        float maxDistSq = 0f;
        for (int i = idxOffset; i < idxOffset + idxCount; i++) {
            int base = indices[i] * stride;
            float dx = vertices[base] - m.boundCenterX;
            float dy = vertices[base + 1] - m.boundCenterY;
            float dz = vertices[base + 2] - m.boundCenterZ;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxDistSq) maxDistSq = distSq;
        }
        m.boundRadius = (float) Math.sqrt(maxDistSq);
    }

    /**
     * Compute the normal cone: average face normal and tightest cutoff angle.
     */
    private static void computeNormalCone(Meshlet m, float[] vertices, int[] indices,
                                          int idxOffset, int idxCount, int stride) {
        float avgNx = 0f, avgNy = 0f, avgNz = 0f;
        int triCount = idxCount / 3;

        // Temporary array for per-face normals
        float[] faceNormals = new float[triCount * 3];

        for (int t = 0; t < triCount; t++) {
            int i0 = indices[idxOffset + t * 3] * stride;
            int i1 = indices[idxOffset + t * 3 + 1] * stride;
            int i2 = indices[idxOffset + t * 3 + 2] * stride;

            float e1x = vertices[i1] - vertices[i0];
            float e1y = vertices[i1 + 1] - vertices[i0 + 1];
            float e1z = vertices[i1 + 2] - vertices[i0 + 2];
            float e2x = vertices[i2] - vertices[i0];
            float e2y = vertices[i2 + 1] - vertices[i0 + 1];
            float e2z = vertices[i2 + 2] - vertices[i0 + 2];

            // Cross product
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;

            // Normalize
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-8f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }

            faceNormals[t * 3] = nx;
            faceNormals[t * 3 + 1] = ny;
            faceNormals[t * 3 + 2] = nz;

            avgNx += nx;
            avgNy += ny;
            avgNz += nz;
        }

        // Normalize average normal
        float avgLen = (float) Math.sqrt(avgNx * avgNx + avgNy * avgNy + avgNz * avgNz);
        if (avgLen > 1e-8f) {
            avgNx /= avgLen;
            avgNy /= avgLen;
            avgNz /= avgLen;
        } else {
            avgNx = 0f;
            avgNy = 1f;
            avgNz = 0f;
        }

        m.normalConeAxisX = avgNx;
        m.normalConeAxisY = avgNy;
        m.normalConeAxisZ = avgNz;

        // Cutoff = minimum dot product of any face normal with the average
        float minDot = 1f;
        for (int t = 0; t < triCount; t++) {
            float dot = faceNormals[t * 3] * avgNx
                    + faceNormals[t * 3 + 1] * avgNy
                    + faceNormals[t * 3 + 2] * avgNz;
            if (dot < minDot) minDot = dot;
        }
        m.normalConeCutoff = minDot;
    }

    /**
     * Upload vertex/index data and meshlet descriptors to the GPU.
     */
    private static MeshletBatch uploadBatch(float[] vertices, int[] indices,
                                            int vertexStride, List<Meshlet> meshlets) {
        MeshletBatch batch = new MeshletBatch();
        batch.meshletCount = meshlets.size();

        // VAO
        batch.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(batch.vao);

        // VBO
        FloatBuffer vertBuf = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertBuf.put(vertices).flip();

        batch.vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batch.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertBuf, GL15.GL_STATIC_DRAW);

        // Position attribute (location 0): 3 floats at offset 0
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, vertexStride * 4, 0L);

        // If stride > 3, bind additional attributes (normals at location 1, etc.)
        if (vertexStride >= 6) {
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, vertexStride * 4, 3L * 4);
        }
        if (vertexStride >= 8) {
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, vertexStride * 4, 6L * 4);
        }

        // EBO
        IntBuffer idxBuf = ByteBuffer.allocateDirect(indices.length * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        idxBuf.put(indices).flip();

        batch.ebo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, batch.ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idxBuf, GL15.GL_STATIC_DRAW);

        GL30.glBindVertexArray(0);

        // Meshlet descriptor SSBO
        batch.meshletSSBO = GL15.glGenBuffers();
        uploadMeshletSSBO(batch, meshlets);

        // VRAM estimate: vertices + indices + SSBO
        long vramVerts = (long) vertices.length * 4;
        long vramIdx = (long) indices.length * 4;
        long vramSSBO = (long) meshlets.size() * MESHLET_DESCRIPTOR_SIZE;
        batch.estimatedVRAM = vramVerts + vramIdx + vramSSBO;
        totalVRAM += batch.estimatedVRAM;

        return batch;
    }

    /**
     * Pack meshlet descriptors into the SSBO.
     */
    private static void uploadMeshletSSBO(MeshletBatch batch, List<Meshlet> meshlets) {
        ByteBuffer buf = ByteBuffer.allocateDirect(meshlets.size() * MESHLET_DESCRIPTOR_SIZE)
                .order(ByteOrder.nativeOrder());

        for (Meshlet m : meshlets) {
            // 64 bytes per descriptor, tightly packed
            buf.putInt(m.vertexOffset);          // 0
            buf.putInt(m.vertexCount);           // 4
            buf.putInt(m.indexOffset);           // 8
            buf.putInt(m.indexCount);            // 12
            buf.putFloat(m.boundCenterX);        // 16
            buf.putFloat(m.boundCenterY);        // 20
            buf.putFloat(m.boundCenterZ);        // 24
            buf.putFloat(m.boundRadius);         // 28
            buf.putFloat(m.normalConeAxisX);     // 32
            buf.putFloat(m.normalConeAxisY);     // 36
            buf.putFloat(m.normalConeAxisZ);     // 40
            buf.putFloat(m.normalConeCutoff);    // 44
            buf.putInt(m.lodLevel);              // 48
            buf.putInt(m.parentMeshletIndex);    // 52
            buf.putInt(0); // padding            // 56
            buf.putInt(0); // padding            // 60
        }
        buf.flip();

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, batch.meshletSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, buf, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Re-upload the SSBO after LOD hierarchy changes.
     */
    private static void reuploadMeshletSSBO(MeshletBatch batch) {
        if (batch.meshlets == null) return;
        uploadMeshletSSBO(batch, batch.meshlets);
        // Update VRAM estimate for new SSBO size
        long oldSSBO = (long) (batch.meshletCount) * MESHLET_DESCRIPTOR_SIZE;
        long newSSBO = (long) batch.meshlets.size() * MESHLET_DESCRIPTOR_SIZE;
        totalVRAM += (newSSBO - oldSSBO);
    }

    /**
     * Merge a group of child meshlets into a single parent meshlet at the given LOD level.
     * Geometry is simplified by 50% (skip every other index).
     */
    private static Meshlet mergeChildMeshlets(List<Meshlet> children, int from, int to,
                                              int lodLevel, int selfIndex) {
        Meshlet parent = new Meshlet();
        parent.lodLevel = lodLevel;
        parent.parentMeshletIndex = -1;

        // Merged bounding sphere: encompass all children
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        float avgNx = 0f, avgNy = 0f, avgNz = 0f;
        float minConeDot = 1f;
        int totalIndices = 0;
        int minVertexOffset = Integer.MAX_VALUE;
        int maxVertexEnd = 0;

        for (int i = from; i < to; i++) {
            Meshlet c = children.get(i);

            float cMinX = c.boundCenterX - c.boundRadius;
            float cMinY = c.boundCenterY - c.boundRadius;
            float cMinZ = c.boundCenterZ - c.boundRadius;
            float cMaxX = c.boundCenterX + c.boundRadius;
            float cMaxY = c.boundCenterY + c.boundRadius;
            float cMaxZ = c.boundCenterZ + c.boundRadius;

            if (cMinX < minX) minX = cMinX;
            if (cMinY < minY) minY = cMinY;
            if (cMinZ < minZ) minZ = cMinZ;
            if (cMaxX > maxX) maxX = cMaxX;
            if (cMaxY > maxY) maxY = cMaxY;
            if (cMaxZ > maxZ) maxZ = cMaxZ;

            avgNx += c.normalConeAxisX;
            avgNy += c.normalConeAxisY;
            avgNz += c.normalConeAxisZ;

            if (c.normalConeCutoff < minConeDot) {
                minConeDot = c.normalConeCutoff;
            }

            // Accumulate simplified index count (50% of children)
            totalIndices += c.indexCount / 2;

            if (c.vertexOffset < minVertexOffset) minVertexOffset = c.vertexOffset;
            int vertEnd = c.vertexOffset + c.vertexCount;
            if (vertEnd > maxVertexEnd) maxVertexEnd = vertEnd;
        }

        parent.boundCenterX = (minX + maxX) * 0.5f;
        parent.boundCenterY = (minY + maxY) * 0.5f;
        parent.boundCenterZ = (minZ + maxZ) * 0.5f;

        float halfExtX = (maxX - minX) * 0.5f;
        float halfExtY = (maxY - minY) * 0.5f;
        float halfExtZ = (maxZ - minZ) * 0.5f;
        parent.boundRadius = (float) Math.sqrt(halfExtX * halfExtX + halfExtY * halfExtY + halfExtZ * halfExtZ);

        // Normal cone — average of children, use worst-case cutoff
        float len = (float) Math.sqrt(avgNx * avgNx + avgNy * avgNy + avgNz * avgNz);
        if (len > 1e-8f) {
            parent.normalConeAxisX = avgNx / len;
            parent.normalConeAxisY = avgNy / len;
            parent.normalConeAxisZ = avgNz / len;
        } else {
            parent.normalConeAxisX = 0f;
            parent.normalConeAxisY = 1f;
            parent.normalConeAxisZ = 0f;
        }
        parent.normalConeCutoff = minConeDot;

        // Use first child's index offset; count is the decimated total
        parent.indexOffset = children.get(from).indexOffset;
        parent.indexCount = Math.min(totalIndices, MAX_INDICES_PER_MESHLET);
        parent.vertexOffset = minVertexOffset;
        parent.vertexCount = maxVertexEnd - minVertexOffset;

        return parent;
    }

    /**
     * Sphere-frustum intersection test.
     *
     * @return true if the sphere is at least partially inside the frustum
     */
    private static boolean sphereInFrustum(float cx, float cy, float cz, float radius,
                                           float[] planes) {
        for (int p = 0; p < 6; p++) {
            int off = p * 4;
            float dist = planes[off] * cx + planes[off + 1] * cy + planes[off + 2] * cz + planes[off + 3];
            if (dist < -radius) {
                return false;
            }
        }
        return true;
    }

    /**
     * Select the best LOD level for a meshlet based on its screen-space projected size.
     * Walks up the DAG (child → parent) until the projected size is small enough for
     * the parent's reduced geometry, or no parent exists.
     *
     * @return the index of the meshlet to actually render
     */
    private static int selectLOD(List<Meshlet> meshlets, int meshletIndex,
                                 float distToCamera, float boundRadius) {
        // Screen-space metric: approximate pixel radius
        // Assume ~90 degree FOV, 1080p → roughly 1000 / dist * radius
        float screenSize = (distToCamera > 1e-4f) ? (1000f * boundRadius / distToCamera) : Float.MAX_VALUE;

        // Thresholds per LOD level (pixels)
        // LOD 0: > 32px, LOD 1: 16-32, LOD 2: 8-16, LOD 3: 4-8, LOD 4: < 4
        int current = meshletIndex;
        Meshlet m = meshlets.get(current);

        while (m.parentMeshletIndex >= 0 && m.parentMeshletIndex < meshlets.size()) {
            float threshold = 32f / (1 << m.lodLevel);
            if (screenSize < threshold) {
                current = m.parentMeshletIndex;
                m = meshlets.get(current);
            } else {
                break;
            }
        }

        return current;
    }
}
