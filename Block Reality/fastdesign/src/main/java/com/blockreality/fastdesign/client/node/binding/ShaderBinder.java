package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.fastdesign.client.node.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Shader Uniform 綁定器 — 設計報告 §12.1 N3-4
 *
 * 將節點輸出值映射到 BRShaderEngine 的 uniform 變數。
 * 渲染節點修改後，下一幀 shader 即時反映。
 */
@OnlyIn(Dist.CLIENT)
public class ShaderBinder implements IBinder<ShaderBinder.UniformContext> {

    private static final Logger LOGGER = LogManager.getLogger("ShaderBinder");

    private final Map<String, UniformMapping> mappings = new HashMap<>();
    private boolean dirty = false;

    @Override
    public void bind(NodeGraph graph) {
        mappings.clear();
        for (BRNode node : graph.allNodes()) {
            if (!"render".equals(node.category())) continue;
            for (OutputPort port : node.outputs()) {
                if (isUniformCandidate(port)) {
                    String uniformName = "u_" + port.name();
                    mappings.put(uniformName, new UniformMapping(node, port, uniformName));
                }
            }
        }
        LOGGER.info("ShaderBinder: {} uniform mappings", mappings.size());
    }

    @Override
    public void apply(UniformContext context) {
        for (UniformMapping m : mappings.values()) {
            if (!m.node.isEnabled()) continue;
            Object value = m.port.getRawValue();
            if (value == null) continue;
            context.uniforms.put(m.uniformName, value);
        }
        dirty = false;
    }

    @Override
    public void pull(UniformContext context) {
        for (UniformMapping m : mappings.values()) {
            Object value = context.uniforms.get(m.uniformName);
            if (value != null) m.port.setValue(value);
        }
    }

    @Override
    public boolean isDirty() { return dirty; }

    @Override
    public void clearDirty() { dirty = false; }

    @Override
    public int bindingCount() { return mappings.size(); }

    public void markDirty() { dirty = true; }

    private boolean isUniformCandidate(OutputPort port) {
        return port.type() == PortType.FLOAT
                || port.type() == PortType.INT
                || port.type() == PortType.BOOL
                || port.type() == PortType.VEC3
                || port.type() == PortType.VEC4
                || port.type() == PortType.COLOR;
    }

    /**
     * Shader uniform 上下文 — 收集所有待更新的 uniform 值。
     * LivePreviewBridge 將此上下文傳遞給 BRShaderEngine。
     */
    public static class UniformContext {
        public final Map<String, Object> uniforms = new HashMap<>();
    }

    private record UniformMapping(BRNode node, OutputPort port, String uniformName) {}
}
