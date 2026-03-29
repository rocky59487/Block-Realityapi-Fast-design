package com.blockreality.fastdesign.client.node.impl.render.postfx;

import com.blockreality.fastdesign.client.node.*;

/** A3-12: 電影特效 */
public class CinematicNode extends BRNode {
    public CinematicNode() {
        super("Cinematic", "電影特效", "render", NodeColor.RENDER);
        addInput("enabled", "啟用", PortType.BOOL, true);
        addInput("vignette", "暗角", PortType.FLOAT, 0.3f).range(0f, 1f);
        addInput("chromAb", "色差", PortType.FLOAT, 0.002f).range(0f, 0.01f);
        addInput("filmGrain", "底片顆粒", PortType.FLOAT, 0.03f).range(0f, 0.1f);
        addInput("letterbox", "黑邊", PortType.FLOAT, 0f).range(0f, 0.15f);
        addOutput("cinematicEnabled", PortType.BOOL);
        addOutput("cinematicVignetteIntensity", PortType.FLOAT);
        addOutput("cinematicChromaticAberration", PortType.FLOAT);
        addOutput("cinematicFilmGrain", PortType.FLOAT);
    }

    @Override
    public void evaluate() {
        getOutput("cinematicEnabled").setValue(getInput("enabled").getBool());
        getOutput("cinematicVignetteIntensity").setValue(getInput("vignette").getFloat());
        getOutput("cinematicChromaticAberration").setValue(getInput("chromAb").getFloat());
        getOutput("cinematicFilmGrain").setValue(getInput("filmGrain").getFloat());
    }

    @Override public String getTooltip() { return "電影特效，包含暗角、色差、底片顆粒與黑邊"; }
    @Override public String typeId() { return "render.postfx.Cinematic"; }
}
