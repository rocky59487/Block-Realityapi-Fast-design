"""
BIFROST ML Trainer — Gradio Web UI (v2)

Features:
  - Concurrent pipeline (train while generating data)
  - GPU auto-detection
  - Bilingual zh-TW / English
  - Huber loss + LayerNorm hardened models
  - PFSF-native 1ch output
"""
from __future__ import annotations

import time
import threading

import gradio as gr

from .i18n import t, STRINGS
from ..pipeline.concurrent_trainer import ConcurrentPipeline, PipelineConfig, PipelineStatus


def create_app() -> gr.Blocks:
    state = {"pipeline": None, "status": PipelineStatus(), "lang": "zh-TW"}

    def on_status(s: PipelineStatus):
        state["status"] = s

    def L(key):
        return t(key, state["lang"])

    with gr.Blocks(title="BIFROST ML Trainer") as app:

        # ── Header + Language ──
        with gr.Row():
            with gr.Column(scale=4):
                title_md = gr.Markdown(f"# {t('title', 'zh-TW')}")
                subtitle_md = gr.Markdown(t("subtitle", "zh-TW"))
            with gr.Column(scale=1):
                lang_dd = gr.Dropdown(["zh-TW", "en"], value="zh-TW", label="🌐 Language")

        with gr.Tabs():
            # ═══ Tab 1: Parameters ═══
            with gr.Tab("參數 / Parameters"):
                with gr.Row():
                    with gr.Column():
                        model_sel = gr.CheckboxGroup(
                            ["surrogate", "fluid", "collapse"],
                            value=["surrogate"],
                            label=t("models_label", "zh-TW"),
                            info=t("models_info", "zh-TW"),
                        )
                        pipeline_mode = gr.Radio(
                            ["concurrent", "sequential"],
                            value="concurrent",
                            label=t("pipeline_mode", "zh-TW"),
                        )
                    with gr.Column():
                        output_dir = gr.Textbox(value="brml_output", label=t("output_dir", "zh-TW"))
                        seed = gr.Number(value=42, label=t("seed", "zh-TW"), precision=0)

                gr.Markdown("---")
                with gr.Row():
                    grid_size = gr.Slider(4, 24, value=12, step=2, label=t("grid_size", "zh-TW"))
                    n_structs = gr.Slider(10, 5000, value=200, step=10, label=t("n_structures", "zh-TW"))

                with gr.Row():
                    total_steps = gr.Slider(500, 200000, value=10000, step=500, label=t("total_steps", "zh-TW"))
                    lr = gr.Number(value=1e-3, label=t("learning_rate", "zh-TW"))
                with gr.Row():
                    save_every = gr.Slider(200, 10000, value=2000, step=200, label=t("save_every", "zh-TW"))
                    batch_size = gr.Slider(1, 16, value=4, step=1, label=t("batch_size", "zh-TW"))

                gr.Markdown(f"### {t('arch_fno', 'zh-TW')}")
                with gr.Row():
                    fno_h = gr.Slider(16, 128, value=48, step=8, label=t("hidden", "zh-TW"))
                    fno_l = gr.Slider(2, 8, value=4, step=1, label=t("spectral_layers", "zh-TW"))
                    fno_m = gr.Slider(2, 16, value=6, step=1, label=t("fourier_modes", "zh-TW"))

                gr.Markdown(f"### {t('arch_collapse', 'zh-TW')}")
                with gr.Row():
                    mpnn_h = gr.Slider(32, 256, value=128, step=32, label=t("mpnn_hidden", "zh-TW"))
                    mpnn_s = gr.Slider(2, 12, value=6, step=1, label=t("mp_steps", "zh-TW"))

            # ═══ Tab 2: Training ═══
            with gr.Tab("訓練 / Training"):
                with gr.Row():
                    start_btn = gr.Button(t("btn_start", "zh-TW"), variant="primary", size="lg")
                    stop_btn = gr.Button(t("btn_stop", "zh-TW"), variant="stop", size="lg")
                    refresh_btn = gr.Button(t("btn_refresh", "zh-TW"), size="sm")

                with gr.Row():
                    status_box = gr.Textbox(label=t("status", "zh-TW"), interactive=False)
                    msg_box = gr.Textbox(label=t("message", "zh-TW"), interactive=False)

                with gr.Row():
                    step_box = gr.Textbox(label=t("step", "zh-TW"), interactive=False)
                    loss_box = gr.Textbox(label=t("loss", "zh-TW"), interactive=False)
                    time_box = gr.Textbox(label=t("elapsed", "zh-TW"), interactive=False)
                    speed_box = gr.Textbox(label=t("speed", "zh-TW"), interactive=False)

                with gr.Row():
                    gpu_box = gr.Textbox(label=t("gpu", "zh-TW"), interactive=False)
                    samples_box = gr.Textbox(label=t("samples", "zh-TW"), interactive=False)

                loss_plot = gr.LinePlot(x="step", y="loss", title="Training Loss",
                                        x_title="Step", y_title="Loss")

        # ── Auto refresh ──
        try:
            timer = gr.Timer(value=2)
        except (AttributeError, TypeError):
            timer = None

        # ── Callbacks ──
        param_inputs = [model_sel, grid_size, n_structs, total_steps, lr,
                        save_every, batch_size, output_dir, seed,
                        fno_h, fno_l, fno_m, mpnn_h, mpnn_s, pipeline_mode]

        def make_config(*args):
            models, grid, structs, steps, learning_rate, save_ev, bs, out, sd, \
                fh, fl, fm, mh, ms, pmode = args
            if isinstance(models, list):
                models = models
            else:
                models = [models]
            return PipelineConfig(
                grid_size=int(grid), n_structures=int(structs),
                train_steps=int(steps), batch_size=int(bs),
                learning_rate=float(learning_rate), hidden=int(fh),
                layers=int(fl), modes=int(fm),
                save_every=int(save_ev), output_dir=str(out),
                seed=int(sd), models=models,
            )

        def start(*args):
            cfg = make_config(*args)
            pipeline = ConcurrentPipeline(cfg, on_status=on_status)
            state["pipeline"] = pipeline
            threading.Thread(target=pipeline.run, daemon=True).start()
            time.sleep(0.5)
            return refresh()

        def stop():
            p = state.get("pipeline")
            if p:
                p.stop()
            time.sleep(1)
            return refresh()

        def refresh():
            s = state["status"]
            step_s = f"{s.trained_steps} / {s.total_steps}" if s.total_steps else "—"
            loss_s = f"{s.loss:.6f}" if s.loss else "—"
            elapsed = time.time()  # placeholder
            speed_s = "—"
            history = s.loss_history or []
            if history:
                ss = max(1, len(history) // 200)
                df = {"step": list(range(0, len(history[::ss]) * ss, ss)), "loss": history[::ss]}
            else:
                df = {"step": [0], "loss": [0]}

            return (
                s.phase,
                s.message,
                step_s,
                loss_s,
                "—",
                speed_s,
                "Yes" if s.gpu_detected else "CPU",
                str(s.samples_queued),
                df,
            )

        outputs = [status_box, msg_box, step_box, loss_box, time_box,
                   speed_box, gpu_box, samples_box, loss_plot]

        start_btn.click(fn=start, inputs=param_inputs, outputs=outputs)
        stop_btn.click(fn=stop, outputs=outputs)
        refresh_btn.click(fn=refresh, outputs=outputs)

        if timer:
            timer.tick(fn=refresh, outputs=outputs)

        # Language switch
        def switch_lang(lang):
            state["lang"] = lang
            return (
                f"# {t('title', lang)}",
                t("subtitle", lang),
            )

        lang_dd.change(fn=switch_lang, inputs=[lang_dd], outputs=[title_md, subtitle_md])

    return app


def main():
    app = create_app()
    import os
    host = os.environ.get("BRML_UI_HOST", "127.0.0.1")
    app.launch(server_name=host, server_port=7860, share=False,
               theme=gr.themes.Soft())


if __name__ == "__main__":
    main()
