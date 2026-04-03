package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.*;

/**
 * DDGI Probe 系統配置節點（Phase 7）。
 *
 * <p>調整 Dynamic Diffuse GI（Ada 路徑）的 probe 網格密度與更新頻率。
 *
 * <h3>端口說明</h3>
 * <ul>
 *   <li>{@code enableDDGI}    — 啟用 DDGI（僅 Ada+ GPU）</li>
 *   <li>{@code probeSpacing}  — Probe 間距（方塊單位，1-32；越小精度越高）</li>
 *   <li>{@code updateRatio}   — 每幀更新比例（0.0-1.0；0.25 = 每 4 幀輪轉）</li>
 *   <li>{@code irradBias}     — 自遮擋偏移係數（0.1-1.0，建議 0.3）</li>
 * </ul>
 *
 * @see BRRTSettings#isEnableDDGI()
 * @see com.blockreality.api.client.render.rt.BRDDGIProbeSystem
 */
public class DDGIConfigNode extends BRNode {

    public DDGIConfigNode() {
        super("DDGIConfig", "DDGI 設定", "render", NodeColor.RENDER);

        addInput("enableDDGI",   "啟用 DDGI GI（Ada）",  PortType.BOOL,  false);
        addInput("probeSpacing", "Probe 間距（方塊）",   PortType.INT,   8).range(1, 32);
        addInput("updateRatio",  "每幀更新比例",         PortType.FLOAT, 0.25f).range(0.0f, 1.0f).step(0.05f);
        addInput("irradBias",    "自遮擋偏移",           PortType.FLOAT, 0.3f).range(0.1f, 1.0f).step(0.05f);

        addOutput("ddgiConfig",  "DDGI 設定物件", PortType.STRUCT);
    }

    @Override
    public String typeId() {
        return "render.pipeline.DDGIConfig";
    }

    @Override
    public String getTooltip() {
        return "DDGI 設定";
    }

    @Override
    public void evaluate() {
        BRRTSettings s = BRRTSettings.getInstance();

        s.setEnableDDGI(getInput("enableDDGI").getBool());
        s.setDdgiProbeSpacingBlocks(getInput("probeSpacing").getInt());
        s.setDdgiUpdateRatio(getInput("updateRatio").getFloat());

        // irradBias 透過下游 RenderConfigBinder 注入到 DDGI push constant
        getOutput("ddgiConfig").setValue(s);
    }
}
