package com.blockreality.fastdesign.client.node.impl.render.pipeline;

import com.blockreality.api.client.render.rt.BRDLSS4Manager;
import com.blockreality.api.client.render.rt.BRRTSettings;
import com.blockreality.fastdesign.client.node.*;

/**
 * DLSS 4 超解析度 + Frame Generation 配置節點（Phase 7）。
 *
 * <p>控制 DLSS 4 SR 品質模式與 MFG 幀生成選項。
 * 若 DLSS SDK 未載入，節點保持 passthrough 狀態並記錄 warning。
 *
 * <h3>端口說明</h3>
 * <ul>
 *   <li>{@code enableDLSS}   — 啟用 DLSS SR（超解析度）</li>
 *   <li>{@code dlssMode}     — DLSS 品質模式（0-4）</li>
 *   <li>{@code enableFG}     — 啟用 Frame Generation（Ada=DLSS3 FG，Blackwell=DLSS4 MFG）</li>
 * </ul>
 *
 * @see BRDLSS4Manager
 * @see BRRTSettings#isEnableDLSS()
 */
public class DLSSConfigNode extends BRNode {

    public enum DLSSMode { NATIVE_AA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERF }

    public DLSSConfigNode() {
        super("DLSSConfig", "DLSS 設定", "render", NodeColor.RENDER);

        addInput("enableDLSS", "啟用 DLSS SR",          PortType.BOOL, false);
        addInput("dlssMode",   "DLSS 品質模式",         PortType.ENUM, DLSSMode.BALANCED);
        addInput("enableFG",   "啟用 Frame Generation", PortType.BOOL, false);

        addOutput("dlssConfig", "DLSS 設定物件", PortType.STRUCT);
    }

    @Override
    public void evaluate() {
        BRRTSettings s = BRRTSettings.getInstance();

        boolean enableDLSS = getInput("enableDLSS").getBool();
        boolean enableFG   = getInput("enableFG").getBool();

        Object modeVal = getInput("dlssMode").getRawValue();
        DLSSMode mode  = DLSSMode.BALANCED;
        if (modeVal instanceof DLSSMode dm) mode = dm;
        else if (modeVal instanceof String str) {
            try { mode = DLSSMode.valueOf(str); } catch (Exception ignored) {}
        }

        s.setEnableDLSS(enableDLSS);
        s.setDlssMode(mode.ordinal());
        s.setEnableFrameGeneration(enableFG);

        // 通知 DLSS4Manager 配置變更（若已初始化）
        BRDLSS4Manager dlss = BRDLSS4Manager.getInstance();
        if (dlss.isInitialized()) {
            dlss.reconfigure(dlss.getInputWidth(), dlss.getInputHeight(),
                             dlss.getOutputWidth(), dlss.getOutputHeight(),
                             mode.ordinal(), enableFG && enableDLSS);
        }

        setOutput("dlssConfig", s);
    }
}
