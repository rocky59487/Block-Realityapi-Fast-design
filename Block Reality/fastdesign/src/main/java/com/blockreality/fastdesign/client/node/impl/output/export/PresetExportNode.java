package com.blockreality.fastdesign.client.node.impl.output.export;

import com.blockreality.fastdesign.client.node.*;

/** E1-3: 預設匯出 */
public class PresetExportNode extends BRNode {
    public PresetExportNode() {
        super("Preset Export", "預設匯出", "output", NodeColor.OUTPUT);
        addInput("presetName", "名稱", PortType.ENUM, "");
        addInput("description", "描述", PortType.ENUM, "");
        addInput("author", "作者", PortType.ENUM, "");
        addOutput("presetFile", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        getOutput("presetFile").setValue("");
    }

    @Override public String getTooltip() { return "將當前配置匯出為預設檔案"; }
    @Override public String typeId() { return "output.export.PresetExport"; }
}
