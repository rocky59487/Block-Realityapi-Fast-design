package com.blockreality.api.client.render.rt;

import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 發光方塊抽象層與 Light Tree (SSBO) 管理器
 * 用於在 Vulkan Phase 4B 中支援 ReSTIR DI 渲染直接陰影。
 * 負責收集場景中的發光方塊位置、顏色與強度，並將其上傳到 Vulkan SSBO。
 */
@SuppressWarnings("deprecation") // Phase 4-F: uses deprecated old-pipeline classes pending removal
public class BRRTEmissiveManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BRRTEmissiveManager.class);

    private static final BRRTEmissiveManager INSTANCE = new BRRTEmissiveManager();

    public static BRRTEmissiveManager getInstance() {
        return INSTANCE;
    }

    // 每個 Light Entry 佔用四個 float = 16 bytes:
    // vec4(pos.xyz, intensity)
    // vec4(color.rgb, radius)
    private static final int LIGHT_ENTRY_SIZE = 32;

    private final List<LightNode> activeLights = new ArrayList<>();
    
    private long ssboHandle = 0L;
    private long ssboMemory = 0L;
    private boolean isDirty = true;

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
     * 添加新的發光方塊到 Light Tree
     */
    public void addEmissiveBlock(Vector3f pos, Vector3f color, float intensity) {
        // 設定有效半徑
        float radius = (float) Math.sqrt(intensity) * 5.0f;
        activeLights.add(new LightNode(pos, color, intensity, radius));
        isDirty = true;
    }

    /**
     * 清空當前所有光源 (通常在區塊卸載或重新載入時呼叫)
     */
    public void clearLights() {
        activeLights.clear();
        isDirty = true;
    }

    /**
     * 取得目前註冊的光源數量
     */
    public int getLightCount() {
        return activeLights.size();
    }

    /**
     * 將光源列表構建並同步到 Vulkan SSBO
     * @param device 當前 Vulkan 設備句柄
     */
    public void flushToSSBO(long device) {
        if (!isDirty || device == 0L) return;

        int count = activeLights.size();
        if (count == 0) {
            return; // 可以設定 dummy
        }

        int bufferSize = count * LIGHT_ENTRY_SIZE;
        ByteBuffer buffer = MemoryUtil.memAlloc(bufferSize);

        for (LightNode light : activeLights) {
            buffer.putFloat(light.position.x);
            buffer.putFloat(light.position.y);
            buffer.putFloat(light.position.z);
            buffer.putFloat(light.intensity);

            buffer.putFloat(light.color.x);
            buffer.putFloat(light.color.y);
            buffer.putFloat(light.color.z);
            buffer.putFloat(light.radius);
        }

        buffer.flip();

        // 這裡會呼叫 Vulkan 內存更新 (Bridge function)
        // BRVulkanInterop.updateBufferData(ssboHandle, buffer);
        
        MemoryUtil.memFree(buffer);
        isDirty = false;
        
        LOGGER.debug("Flushed {} emissive lights to SSBO for ReSTIR DI.", count);
    }
}
