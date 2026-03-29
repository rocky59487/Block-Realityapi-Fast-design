package com.blockreality.fastdesign.client.node.impl.render.lod;

import com.blockreality.fastdesign.client.node.*;

/** A5-3: 視錐裁剪 */
public class FrustumCullerNode extends BRNode {
    public FrustumCullerNode() {
        super("FrustumCuller", "視錐裁剪", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("padding", "邊距", PortType.FLOAT, 2f).range(0f, 8f);
        addOutput("cullerSpec", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        // cullerSpec populated by downstream binder
    }

    @Override public String getTooltip() { return "視錐裁剪啟用與邊距設定"; }
    @Override public String typeId() { return "render.lod.FrustumCuller"; }
}
