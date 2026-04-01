package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.render.rt.BRNRDDenoiser;
import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.*;

/**
 * NRD 降噪演算法配置節點（Phase 7）。
 *
 * <p>選擇 NRD（NVIDIA Real-time Denoisers）使用的演算法與強度參數：
 * <ul>
 *   <li><b>ReLAX Diffuse/Specular</b>  — 最適合 ReSTIR GI 漫反射 + 鏡面信號</li>
 *   <li><b>ReBLUR</b>                  — 最適合 RTAO / DDGI 低頻輻射信號</li>
 *   <li><b>SIGMA Shadow</b>            — 最適合 ReSTIR DI 陰影信號</li>
 *   <li><b>SVGF（Fallback）</b>        — NRD JNI 不可用時的備援，無需原生庫</li>
 * </ul>
 *
 * <h3>端口說明</h3>
 * <ul>
 *   <li>{@code algorithm}       — 降噪演算法選擇</li>
 *   <li>{@code denoiseStrength} — 降噪強度（0.0 = 關閉，1.0 = 最大；影響 temporal blend）</li>
 *   <li>{@code maxAccumFrames}  — 時域累積幀數上限（4-64；越大越平滑但 ghosting 越重）</li>
 * </ul>
 *
 * @see BRNRDDenoiser
 * @see BRRTSettings#getDenoiserAlgorithm()
 */
public class NRDConfigNode extends BRNode {

    public enum NRDAlgorithm { SVGF, REBLUR, RELAX_DIFFUSE, RELAX_SPECULAR, SIGMA_SHADOW }

    public NRDConfigNode() {
        super("NRDConfig", "NRD 降噪設定", "render", NodeColor.RENDER);

        addInput("algorithm",       "降噪演算法",      PortType.ENUM,  NRDAlgorithm.RELAX_DIFFUSE);
        addInput("denoiseStrength", "降噪強度",        PortType.FLOAT, 0.85f).range(0.0f, 1.0f).step(0.05f);
        addInput("maxAccumFrames",  "最大時域累積幀數", PortType.INT,   32).range(4, 64);

        addOutput("nrdConfig", "NRD 設定物件", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        Object algVal = getInput("algorithm").getRawValue();
        NRDAlgorithm alg = NRDAlgorithm.RELAX_DIFFUSE;
        if (algVal instanceof NRDAlgorithm a) alg = a;
        else if (algVal instanceof String str) {
            try { alg = NRDAlgorithm.valueOf(str); } catch (Exception ignored) {}
        }

        float strength    = getInput("denoiseStrength").getFloat();
        int   accumFrames = getInput("maxAccumFrames").getInt();

        BRRTSettings s = BRRTSettings.getInstance();
        // denoiserAlgorithm: 0=ReBLUR, 1=ReLAX（與 BRRTSettings 定義對齊）
        s.setDenoiserAlgorithm(alg == NRDAlgorithm.REBLUR ? 0 : 1);

        // 通知 BRNRDDenoiser（若已初始化）
        BRNRDDenoiser denoiser = BRNRDDenoiser.getInstance();
        if (denoiser.isInitialized()) {
            denoiser.setAlgorithm(alg.name());
            denoiser.setDenoiseStrength(strength);
            denoiser.setMaxAccumFrames(accumFrames);
        }

        setOutput("nrdConfig", s);
    }
}
