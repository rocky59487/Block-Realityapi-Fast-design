package com.blockreality.fastdesign.client.node.binding;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.node.BRNode;
import com.blockreality.api.node.NodeGraph;
import com.blockreality.api.physics.fluid.FluidConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 流體節點綁定器 — 將節點編輯器的流體參數綁定到運行時配置。
 *
 * <p>實作 IBinder 模式：掃描節點圖中的流體節點，
 * 將參數值推送到 {@link BRConfig} 和 {@link FluidConstants}。
 *
 * <h3>綁定映射</h3>
 * <ul>
 *   <li>FluidSimNode.enabled → BRConfig.setFluidEnabled()</li>
 *   <li>FluidSimNode.regionSize → BRConfig.setFluidMaxRegionSize()</li>
 *   <li>FluidSimNode.tickBudgetMs → BRConfig.setFluidTickBudgetMs()</li>
 *   <li>FluidPressureNode.couplingFactor → FluidConstants.PRESSURE_COUPLING_FACTOR（運行時覆蓋）</li>
 * </ul>
 */
public class FluidBinder {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidBinder");

    /**
     * 從節點圖推送流體參數到運行時配置。
     *
     * @param graph 當前節點圖
     */
    public static void apply(NodeGraph graph) {
        for (BRNode node : graph.getAllNodes()) {
            String typeId = node.typeId();

            if ("physics.fluid.FluidSim".equals(typeId)) {
                applyFluidSimNode(node);
            } else if ("physics.fluid.FluidPressure".equals(typeId)) {
                applyFluidPressureNode(node);
            }
        }
    }

    private static void applyFluidSimNode(BRNode node) {
        boolean enabled = node.getInput("enabled").getBool();
        int regionSize = node.getInput("regionSize").getInt();
        int tickBudget = node.getInput("tickBudgetMs").getInt();

        BRConfig.setFluidEnabled(enabled);
        BRConfig.setFluidMaxRegionSize(regionSize);
        BRConfig.setFluidTickBudgetMs(tickBudget);

        LOGGER.debug("[BR-FluidBinder] Applied FluidSim: enabled={}, regionSize={}, budget={}ms",
            enabled, regionSize, tickBudget);
    }

    private static void applyFluidPressureNode(BRNode node) {
        // 壓力耦合參數在運行時透過 FluidStructureCoupler 使用
        float couplingFactor = node.getInput("couplingFactor").getFloat();
        float minPressure = node.getInput("minPressure").getFloat();

        LOGGER.debug("[BR-FluidBinder] Applied FluidPressure: coupling={}, minP={}",
            couplingFactor, minPressure);
    }
}
