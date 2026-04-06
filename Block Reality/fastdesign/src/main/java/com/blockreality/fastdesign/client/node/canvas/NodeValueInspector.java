package com.blockreality.fastdesign.client.node.canvas;

import com.blockreality.fastdesign.client.node.BRNode;
import com.blockreality.fastdesign.client.node.OutputPort;
import com.blockreality.fastdesign.client.node.PortType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

/**
 * 節點資料預覽面板 — Grasshopper 風格 "Preview value"
 */
public class NodeValueInspector {

    private BRNode targetNode;

    public void setTargetNode(BRNode node) {
        if (this.targetNode == node) {
            this.targetNode = null; // toggle off
        } else {
            this.targetNode = node;
        }
    }

    public BRNode getTargetNode() {
        return targetNode;
    }

    public void render(GuiGraphics gui, CanvasTransform transform) {
        if (targetNode == null) return;

        // Follow the node: positioned 8px to the right of the node
        float scale = targetNode.animScale();
        float width = targetNode.width() * scale;
        float height = targetNode.height() * scale;
        float cx = targetNode.posX() + targetNode.width() / 2.0f;
        float cy = targetNode.posY() + targetNode.height() / 2.0f;

        float nodeSx = transform.toScreenX(cx - width / 2.0f);
        float nodeSy = transform.toScreenY(cy - height / 2.0f);
        float nodeSw = transform.toScreenSize(width);

        int startX = (int) (nodeSx + nodeSw) + 8;
        int startY = (int) nodeSy;

        Font font = Minecraft.getInstance().font;

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("§l" + targetNode.displayName());

        if (targetNode.outputs().isEmpty()) {
            lines.add("No outputs");
        } else {
            for (OutputPort port : targetNode.outputs()) {
                String valStr = formatValue(port);
                lines.add(port.displayName() + ": " + valStr);
            }
        }

        int maxW = 100;
        for (String line : lines) {
            maxW = Math.max(maxW, font.width(line));
        }

        int pW = maxW + 16;
        int pH = lines.size() * 12 + 12;

        gui.fill(startX, startY, startX + pW, startY + pH, 0xEE1A1A1A);
        gui.fill(startX, startY, startX + pW, startY + 1, 0xFF444444);
        gui.fill(startX, startY + pH - 1, startX + pW, startY + pH, 0xFF444444);
        gui.fill(startX, startY, startX + 1, startY + pH, 0xFF444444);
        gui.fill(startX + pW - 1, startY, startX + pW, startY + pH, 0xFF444444);

        int lineY = startY + 8;
        for (String line : lines) {
            gui.drawString(font, line, startX + 8, lineY, 0xFFCCCCCC);
            lineY += 12;
        }
    }

    private String formatValue(OutputPort port) {
        Object val = port.getRawValue();
        if (val == null) return "null";
        if (val instanceof Float f) {
            if (f == 0.0f) return "0";
            float abs = Math.abs(f);
            if (abs < 0.01f || abs > 1000f) return String.format("%.2e", f);
            return String.format("%.3g", f);
        }
        if (val instanceof Integer i) return i.toString();
        if (val instanceof Boolean b) return b ? "§a✓" : "§c✕";
        if (val instanceof com.blockreality.api.material.RMaterial rm) {
            String name = rm.name();
            return name.length() > 6 ? name.substring(0, 6) : name;
        }
        if (port.type() == PortType.VEC3) {
            float[] v = (float[]) val;
            return String.format("[%.1f, %.1f, %.1f]", v[0], v[1], v[2]);
        }
        return val.toString();
    }
}
