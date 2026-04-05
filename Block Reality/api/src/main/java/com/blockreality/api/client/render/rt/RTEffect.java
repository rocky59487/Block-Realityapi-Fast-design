package com.blockreality.api.client.render.rt;

/**
 * RT 效果項目 — 代表 RT pipeline 中可獨立啟停的渲染 pass。
 *
 * <p>供 {@link com.blockreality.api.client.rendering.vulkan.VkRTPipeline#enableEffect(RTEffect)}
 * / {@link com.blockreality.api.client.rendering.vulkan.VkRTPipeline#disableEffect(RTEffect)} 使用。
 *
 * <p>{@link com.blockreality.api.client.rendering.BRRTCompositor} 透過這些開關按幀預算
 * 動態啟停各 pass，例如在 GPU 幀時間超標時先關閉 {@link #RTAO}，再關閉 {@link #SVGF_DENOISE}。
 *
 * <h4>預算優先級（建議關閉順序，由耗時高至低）</h4>
 * <ol>
 *   <li>{@link #RTAO} — Ray Query AO 8/16 samples，最耗時</li>
 *   <li>{@link #SVGF_DENOISE} — 時域降噪 memory bandwidth</li>
 *   <li>{@link #DAG_GI} — DAG SSBO PCIe 上傳帶寬</li>
 *   <li>{@link #REFLECTIONS} — RT 反射 GBA channel</li>
 *   <li>{@link #SHADOWS} — RT 陰影，核心功能，最後關閉</li>
 * </ol>
 *
 * @see com.blockreality.api.client.rendering.vulkan.VkRTPipeline
 * @see com.blockreality.api.client.rendering.BRRTCompositor
 */
public enum RTEffect {

    /**
     * Ray Query AO pass（{@code rtao.comp.glsl} compute shader）。
     * Ada = 8 samples，Blackwell = 16 samples（specialization constant SC_1）。
     * 關閉後跳過 {@code VkRTAO.dispatchAO()}，AO texture = 空白（全亮）。
     *
     * <p>與 {@link com.blockreality.api.client.render.rt.BRRTSettings#isEnableRTAO()} 聯動：
     * {@code enableEffect(RTAO)} 同步設為 {@code true}，{@code disableEffect(RTAO)} 同步設為 {@code false}。
     */
    RTAO,

    /**
     * RT 陰影（{@code primary.rgen.glsl} 主要輸出，payload R channel = shadow factor）。
     * 關閉後整個 {@code traceRays()} 呼叫被跳過（須同時與 {@link #REFLECTIONS} 協調）。
     *
     * <p>注意：若 {@link #SHADOWS} 與 {@link #REFLECTIONS} 均為關閉，{@code traceRays()}
     * 不會被呼叫（節省 RT core 時間）。
     */
    SHADOWS,

    /**
     * RT 反射（{@code primary.rgen.glsl} payload GBA channels = 反射輝度）。
     * 關閉後合成 shader {@code u_RTBlendFactor} 設為 0（反射貢獻歸零）。
     *
     * <p>注意：RT pass 本身仍執行（若 {@link #SHADOWS} 開啟），僅合成時不寫入反射。
     */
    REFLECTIONS,

    /**
     * SVGF / NRD 時域降噪 pass（{@link com.blockreality.api.client.rendering.vulkan.BRNRDDenoiser}）。
     * 關閉後跳過降噪器，直接使用原始 RT 輸出（較噪但省 memory bandwidth）。
     *
     * <p>對靜態場景或快速移動鏡頭（ghosting 明顯時）可考慮暫關。
     */
    SVGF_DENOISE,

    /**
     * BRSparseVoxelDAG 遠距 GI SSBO 上傳（128+ chunk 軟追蹤，Ada+ 專用）。
     * 關閉後跳過 {@link com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig#uploadDAGToGPU()}，
     * SSBO 保留上次上傳的資料（GI 品質退化但無 PCIe 帶寬壓力）。
     */
    DAG_GI,

    /**
     * SDF Ray Marching GI + AO pass（{@code sdf_gi_ao.comp.glsl} compute shader）。
     * 使用 Sphere Tracing 在 SDF Volume 中計算遠距 GI 採樣與環境遮蔽，
     * 作為硬體 RT 的輔助/替代方案（混合渲染：近處 HW RT，遠處 SDF）。
     *
     * <p>關閉後跳過 SDF ray marching dispatch，遠距 GI 僅依賴 DAG SSBO。
     *
     * @see com.blockreality.api.client.render.rt.BRSDFVolumeManager
     * @see com.blockreality.api.client.render.rt.BRSDFRayMarcher
     */
    SDF_GI
}
