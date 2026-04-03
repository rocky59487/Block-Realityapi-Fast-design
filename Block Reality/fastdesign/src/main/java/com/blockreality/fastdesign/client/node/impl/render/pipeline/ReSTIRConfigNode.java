package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.*;

/**
 * ReSTIR DI / GI 配置節點（Phase 7）。
 *
 * <p>透過節點編輯器介面調整 ReSTIR DI（直接光照）與 ReSTIR GI（間接光照）的
 * 採樣與重用參數，即時生效。
 *
 * <h3>端口說明</h3>
 * <ul>
 *   <li>{@code enableDI}       — 啟用 ReSTIR DI（Blackwell 直接光照採樣）</li>
 *   <li>{@code enableGI}       — 啟用 ReSTIR GI（次射線間接照明）</li>
 *   <li>{@code initialCands}   — 初始候選採樣數（8-64，越高越準確）</li>
 *   <li>{@code temporalMaxM}   — DI 時域 M-cap（10-30；GI 使用固定 10）</li>
 *   <li>{@code spatialSamples} — DI 空間鄰居數（1-4）</li>
 *   <li>{@code giRaysPerPixel} — GI 每像素次射線數（2-8，Blackwell=4）</li>
 * </ul>
 *
 * @see BRRTSettings#isEnableReSTIRDI()
 * @see BRRTSettings#isEnableReSTIRGI()
 */
public class ReSTIRConfigNode extends BRNode {

    public ReSTIRConfigNode() {
        super("ReSTIRConfig", "ReSTIR 設定", "render", NodeColor.RENDER);

        addInput("enableDI",       "啟用 ReSTIR DI",       PortType.BOOL,  true);
        addInput("enableGI",       "啟用 ReSTIR GI",       PortType.BOOL,  false);
        addInput("initialCands",   "初始候選採樣數",       PortType.INT,   32).range(8, 64);
        addInput("temporalMaxM",   "時域 M-cap（DI）",     PortType.INT,   20).range(5, 50);
        addInput("spatialSamples", "空間鄰居數（DI）",     PortType.INT,    1).range(1, 4);
        addInput("giRaysPerPixel", "GI 次射線數 / 像素",  PortType.INT,    4).range(2, 8);

        addOutput("restirConfig",  "ReSTIR 設定物件", PortType.STRUCT);
    }

    @Override
    public String typeId() {
        return "render.pipeline.ReSTIRConfig";
    }

    @Override
    public String getTooltip() {
        return "ReSTIR 設定";
    }

    @Override
    public void evaluate() {
        BRRTSettings s = BRRTSettings.getInstance();

        s.setEnableReSTIRDI(getInput("enableDI").getBool());
        s.setEnableReSTIRGI(getInput("enableGI").getBool());
        s.setReSTIRDIInitialCandidates(getInput("initialCands").getInt());
        s.setReSTIRDITemporalMaxM(getInput("temporalMaxM").getInt());
        s.setReSTIRDISpatialSamples(getInput("spatialSamples").getInt());
        s.setReSTIRGIRaysPerPixel(getInput("giRaysPerPixel").getInt());

        getOutput("restirConfig").setValue(s);
    }
}
