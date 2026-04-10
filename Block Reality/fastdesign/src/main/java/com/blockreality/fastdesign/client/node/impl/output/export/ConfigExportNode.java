package com.blockreality.fastdesign.client.node.impl.output.export;

import com.blockreality.fastdesign.client.node.*;

/** E1-1: TOML 匯出 */
public class ConfigExportNode extends BRNode {
    public ConfigExportNode() {
        super("Config Export", "TOML 匯出", "output", NodeColor.OUTPUT);
        addInput("allConfigs", "所有配置", PortType.STRUCT, null);
        addOutput("tomlContent", PortType.ENUM);
        addOutput("filePath", PortType.ENUM);
    }

    @Override
    public void evaluate() {
        getOutput("tomlContent").setValue("");
        getOutput("filePath").setValue("");
    }

    @Override public String getTooltip() { return "將節點圖配置匯出為 TOML 檔案"; }
    @Override public String typeId() { return "output.export.ConfigExport"; }
}
