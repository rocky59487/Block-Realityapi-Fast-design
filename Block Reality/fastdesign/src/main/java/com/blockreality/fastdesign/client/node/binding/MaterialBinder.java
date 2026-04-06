package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.api.material.*;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.fastdesign.client.node.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 材料綁定器 — 設計報告 §12.1 N3-2
 *
 * 將 Category B 的材料節點輸出映射到 RMaterial / DynamicMaterial 系統。
 * 支援：
 *   - B1 MaterialConstant → DefaultMaterial 讀取
 *   - B2 RCFusion → DynamicMaterial.ofRCFusion()
 *   - B5 BlockCreator → CustomMaterial.Builder + BlockTypeRegistry
 */
@OnlyIn(Dist.CLIENT)
public class MaterialBinder implements IBinder<MaterialBinder.MaterialContext> {

    private static final Logger LOGGER = LogManager.getLogger("MaterialBinder");

    private final Map<String, BRNode> materialNodes = new HashMap<>();
    private boolean dirty = false;

    @Override
    public void bind(NodeGraph graph) {
        materialNodes.clear();
        for (BRNode node : graph.allNodes()) {
            if ("material".equals(node.category()) || "blending".equals(node.category())) {
                materialNodes.put(node.nodeId(), node);
            }
        }
        LOGGER.info("MaterialBinder: {} material nodes bound", materialNodes.size());
    }

    @Override
    public void apply(MaterialContext context) {
        for (BRNode node : materialNodes.values()) {
            if (!node.isEnabled()) continue;

            // BlockCreator 節點 → 註冊自訂方塊
            if (node.typeId() != null && node.typeId().contains("BlockCreator")) {
                OutputPort blockOut = node.getOutput("customBlock");
                if (blockOut != null && blockOut.getRawValue() instanceof BRBlockDef def) {
                    registerCustomBlock(def, context);
                }
            }
        }
        dirty = false;
    }

    @Override
    public void pull(MaterialContext context) {
        // 材料是唯讀的，不需要 pull
    }

    @Override
    public boolean isDirty() { return dirty; }

    @Override
    public void clearDirty() { dirty = false; }

    @Override
    public int bindingCount() { return materialNodes.size(); }

    public void markDirty() { dirty = true; }

    /**
     * 從節點輸出取得 RMaterial。
     */
    @Nullable
    public RMaterial getMaterialFromNode(String nodeId, String portName) {
        BRNode node = materialNodes.get(nodeId);
        if (node == null) return null;
        OutputPort port = node.getOutput(portName);
        if (port == null) return null;
        Object val = port.getRawValue();
        if (val instanceof RMaterial m) return m;
        if (val instanceof BRBlockDef def) return def.material();
        return null;
    }

    private void registerCustomBlock(BRBlockDef def, MaterialContext context) {
        String name = def.blockId().getPath();
        if (name == null || name.isEmpty()) return;

        // 使用 CustomMaterial.Builder 建立材料
        RMaterial mat = def.material();
        if (mat == null) return;

        // 向 context 報告（實際註冊由 BlockCreatorNode 的 evaluate 處理）
        context.registeredBlocks.put(name, def);
    }

    /**
     * 材料綁定上下文。
     */
    public static class MaterialContext {
        public final Map<String, BRBlockDef> registeredBlocks = new HashMap<>();
    }
}
