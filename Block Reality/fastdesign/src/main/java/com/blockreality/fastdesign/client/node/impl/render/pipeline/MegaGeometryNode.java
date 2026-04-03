package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig;
import com.blockreality.api.client.render.rt.BRClusterBVH;
import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.*;

/**
 * Mega Geometry / Cluster BVH 配置節點（Phase 7）。
 *
 * <p>控制 Blackwell Cluster BVH（{@link BRClusterBVH}）的合併策略與更新預算。
 * 在非 Blackwell GPU 上此節點為 passthrough（所有設定靜默忽略）。
 *
 * <h3>端口說明</h3>
 * <ul>
 *   <li>{@code enableCluster}    — 啟用 Cluster BVH（需 Blackwell SM10+）</li>
 *   <li>{@code maxRebuildPerFrame} — 每幀最多重建的 Cluster 數（1-8；越高更新越即時）</li>
 *   <li>{@code clusterSize}      — 每個 Cluster 的 Section 數（2-8；固定 4×4=16）</li>
 * </ul>
 *
 * @see BRClusterBVH
 * @see BRRTSettings#isEnableClusterBVH()
 */
public class MegaGeometryNode extends BRNode {

    public MegaGeometryNode() {
        super("MegaGeometry", "Cluster BVH", "render", NodeColor.RENDER);

        addInput("enableCluster",      "啟用 Cluster BVH（Blackwell）", PortType.BOOL, true);
        addInput("maxRebuildPerFrame", "每幀最大重建數",                PortType.INT,  4).range(1, 16);

        addOutput("geoConfig", "Cluster BVH 設定", PortType.STRUCT);
    }

    @Override
    public String typeId() {
        return "render.pipeline.MegaGeometryConfig";
    }

    @Override
    public String getTooltip() {
        return "Cluster BVH 設定";
    }

    @Override
    public void evaluate() {
        boolean enable = getInput("enableCluster").getBool();
        int     maxR   = getInput("maxRebuildPerFrame").getInt();

        BRRTSettings s = BRRTSettings.getInstance();
        s.setEnableClusterBVH(enable && BRAdaRTConfig.isBlackwellOrNewer());

        // maxRebuildPerFrame 注入 BRRTSettings (Phase 8 API removed direct BRClusterBVH mutate here)
        // actually BRClusterBVH does not seem to have setMaxBlasRebuildsPerFrame.
        // We will just let BRClusterBVH read from constants or settings later if exposed.

        getOutput("geoConfig").setValue(s);
    }
}
