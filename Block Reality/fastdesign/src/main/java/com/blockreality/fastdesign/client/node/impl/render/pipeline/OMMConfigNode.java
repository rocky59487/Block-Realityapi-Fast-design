package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.api.distmarker.Dist;
import com.blockreality.api.client.render.rt.BROpacityMicromap;
import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.*;

/**
 * Opacity Micromap（OMM）配置節點（Phase 7）。
 *
 * <p>控制 VK_EXT_opacity_micromap 的啟用狀態與 subdivision level。
 * OMM 可讓 GPU 在 BVH 遍歷時跳過半透明材料的 any-hit shader，
 * 通常可提升 RT shadow/AO 效能 20-40%（取決於場景透明物比例）。
 *
 * <h3>端口說明</h3>
 * <ul>
 *   <li>{@code enableOMM}         — 啟用 OMM（需 Ada+ GPU + VK_EXT_opacity_micromap）</li>
 *   <li>{@code subdivisionLevel}  — OMM 細分等級（0-4；越高精度越高、記憶體越大）</li>
 * </ul>
 *
 * @see BROpacityMicromap
 */
@OnlyIn(Dist.CLIENT)
public class OMMConfigNode extends BRNode {

    public OMMConfigNode() {
        super("OMMConfig", "OMM 設定", "render", NodeColor.RENDER);

        addInput("enableOMM",        "啟用 Opacity Micromap", PortType.BOOL, false);
        addInput("subdivisionLevel", "OMM 細分等級（0-4）",  PortType.INT,   2).range(0, 4);

        addOutput("ommConfig", "OMM 設定", PortType.STRUCT);
    }

    @Override
    public String typeId() {
        return "render.pipeline.OMMConfig";
    }

    @Override
    public String getTooltip() {
        return "OMM 設定";
    }

    @Override
    public void evaluate() {
        boolean enable = getInput("enableOMM").getBool();
        int     level  = getInput("subdivisionLevel").getInt();

        // OMM 需要 Ada+ 且 VK_EXT_opacity_micromap 可用
        boolean actualEnable = enable && BRAdaRTConfig.hasOMM();

        BRRTSettings s = BRRTSettings.getInstance();
        s.setEnableOMM(actualEnable);
        s.setOmmSubdivisionLevel(level);

        getOutput("ommConfig").setValue(s);
    }
}
