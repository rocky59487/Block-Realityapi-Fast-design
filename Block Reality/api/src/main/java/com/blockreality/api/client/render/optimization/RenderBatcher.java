package com.blockreality.api.client.render.optimization;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Render Batcher — Sodium/Embeddium 風格 Draw Call 合併器。
 *
 * 核心概念：
 *   - 將多個小 mesh 的頂點數據合併到單一 VBO
 *   - 減少 glDrawArrays/glDrawElements 呼叫次數
 *   - 支援 multi-draw（同材質一次提交多個 mesh）
 *
 * Embeddium 啟發：
 *   - 頂點格式壓縮（position: 3×float, normal: 3×byte packed, color: 4×byte, matId: 1×byte）
 *   - 但為了可讀性和相容性，此版使用 10×float per vertex
 *
 * 使用模式：
 *   begin() → submit() × N → flush() → 回傳 draw call 數
 */
@OnlyIn(Dist.CLIENT)
public final class RenderBatcher {

    private final int maxVertices;
    private final int maxMerge;

    // GL 資源
    private int vao;
    private int vbo;
    private FloatBuffer vertexBuffer;

    private int vertexCount;
    private int submitCount;
    private boolean inBatch;

    /** 每頂點 float 數（position3 + normal3 + color4） */
    public static final int FLOATS_PER_VERTEX = 10;

    public RenderBatcher(int maxVertices, int maxMerge) {
        this.maxVertices = maxVertices;
        this.maxMerge = maxMerge;

        // 建立 VAO + VBO
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
            (long) maxVertices * FLOATS_PER_VERTEX * Float.BYTES,
            GL15.GL_DYNAMIC_DRAW);

        // 頂點屬性佈局
        int stride = FLOATS_PER_VERTEX * Float.BYTES;

        // location 0: position (vec3)
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);

        // location 1: normal (vec3)
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);

        // location 2: color (vec4)
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 6L * Float.BYTES);

        GL30.glBindVertexArray(0);

        // CPU 端暫存
        vertexBuffer = MemoryUtil.memAllocFloat(maxVertices * FLOATS_PER_VERTEX);
    }

    /**
     * 開始新的批次。
     */
    public void begin() {
        vertexCount = 0;
        submitCount = 0;
        inBatch = true;
        vertexBuffer.clear();
    }

    /**
     * 提交一組頂點到批次。
     *
     * @param vertices 頂點數據（FLOATS_PER_VERTEX floats per vertex）
     * @param count    頂點數量
     * @return true 如果成功加入，false 如果已滿
     */
    public boolean submit(float[] vertices, int vertexCountToAdd) {
        if (!inBatch) return false;
        if (vertexCount + vertexCountToAdd > maxVertices) return false;
        if (submitCount >= maxMerge) return false;

        int floatCount = vertexCountToAdd * FLOATS_PER_VERTEX;
        vertexBuffer.put(vertices, 0, floatCount);
        vertexCount += vertexCountToAdd;
        submitCount++;
        return true;
    }

    /**
     * 刷新批次 — 上傳 VBO 並發出 draw call。
     *
     * @return 實際執行的 draw call 數量（理想情況下為 1）
     */
    public int flush() {
        if (!inBatch || vertexCount == 0) {
            inBatch = false;
            return 0;
        }

        vertexBuffer.flip();

        // 上傳到 GPU
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertexBuffer);

        // 繪製
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        GL30.glBindVertexArray(0);

        inBatch = false;
        return 1; // 合併為單一 draw call
    }

    /**
     * 取得當前批次中的頂點數。
     */
    public int getVertexCount() { return vertexCount; }

    /**
     * 取得已提交的 mesh 數。
     */
    public int getSubmitCount() { return submitCount; }

    /**
     * 清除 GL 資源。
     */
    public void cleanup() {
        if (vao != 0) { GL30.glDeleteVertexArrays(vao); vao = 0; }
        if (vbo != 0) { GL15.glDeleteBuffers(vbo); vbo = 0; }
        if (vertexBuffer != null) { MemoryUtil.memFree(vertexBuffer); vertexBuffer = null; }
    }
}
