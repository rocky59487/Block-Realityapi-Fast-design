"""BR-NeXT Gradio Web UI."""
from __future__ import annotations

import time
import threading

import gradio as gr

from .i18n import t
from .session import TrainingSession, TrainParams, TrainState


def create_app() -> gr.Blocks:
    session: TrainingSession | None = None
    state = {"lang": "zh-TW", "last_state": TrainState()}

    def L(key: str) -> str:
        return t(key, state["lang"])

    with gr.Blocks(title="BR-NeXT Trainer") as app:
        with gr.Row():
            with gr.Column(scale=4):
                title_md = gr.Markdown(f"# {L('title')}")
                subtitle_md = gr.Markdown(L("subtitle"))
            with gr.Column(scale=1):
                lang_dd = gr.Dropdown(["zh-TW", "en"], value="zh-TW", label="🌐 Language")

        with gr.Tabs():
            # ═══ Tab 1: Parameters ═══
            with gr.Tab(L("params_tab")):
                with gr.Row():
                    with gr.Column():
                        grid_size = gr.Slider(4, 24, value=12, step=2, label=L("grid_size"))
                        stage1_samples = gr.Slider(100, 50000, value=10000, step=100, label=L("stage1_samples"))
                        stage2_samples = gr.Slider(100, 10000, value=2000, step=100, label=L("stage2_samples"))
                        stage3_samples = gr.Slider(10, 1000, value=200, step=10, label=L("stage3_samples"))
                    with gr.Column():
                        stage1_steps = gr.Slider(100, 20000, value=5000, step=100, label=L("stage1_steps"))
                        stage2_steps = gr.Slider(100, 20000, value=5000, step=100, label=L("stage2_steps"))
                        stage3_steps = gr.Slider(100, 50000, value=10000, step=100, label=L("stage3_steps"))
                with gr.Row():
                    hidden = gr.Slider(16, 128, value=48, step=8, label=L("hidden"))
                    modes = gr.Slider(2, 16, value=6, step=1, label=L("modes"))
                    lr = gr.Number(value=1e-3, label=L("lr"))
                with gr.Row():
                    output_dir = gr.Textbox(value="brnext_output", label=L("output_dir"))
                    seed = gr.Number(value=42, label=L("seed"), precision=0)

            # ═══ Tab 2: Training ═══
            with gr.Tab(L("train_tab")):
                with gr.Row():
                    start_btn = gr.Button(L("btn_start"), variant="primary", size="lg")
                    stop_btn = gr.Button(L("btn_stop"), variant="stop", size="lg")
                    refresh_btn = gr.Button(L("btn_refresh"), size="sm")

                with gr.Row():
                    status_box = gr.Textbox(label=L("status"), interactive=False)
                    stage_box = gr.Textbox(label=L("stage"), interactive=False)
                    msg_box = gr.Textbox(label=L("message"), interactive=False)

                with gr.Row():
                    step_box = gr.Textbox(label=L("step"), interactive=False)
                    loss_box = gr.Textbox(label=L("loss"), interactive=False)
                    time_box = gr.Textbox(label=L("elapsed"), interactive=False)

                with gr.Row():
                    gpu_box = gr.Textbox(label=L("gpu"), interactive=False)

                loss_plot = gr.LinePlot(x="step", y="loss", title="Training Loss",
                                        x_title="Step", y_title="Loss")

            # ═══ Tab 3: Export ═══
            with gr.Tab(L("export_tab")):
                onnx_path_box = gr.Textbox(label=L("onnx_path"), value="brnext_output/brnext_ssgo.onnx")
                export_btn = gr.Button(L("btn_export"), variant="secondary")
                export_result = gr.Textbox(label=L("export_success"), interactive=False)

        # ── Auto refresh ──
        try:
            timer = gr.Timer(value=2)
        except (AttributeError, TypeError):
            timer = None

        param_inputs = [
            grid_size, stage1_samples, stage2_samples, stage3_samples,
            stage1_steps, stage2_steps, stage3_steps,
            hidden, modes, lr, output_dir, seed,
        ]

        def make_params(*args):
            return TrainParams(
                grid_size=int(args[0]),
                stage1_samples=int(args[1]),
                stage2_samples=int(args[2]),
                stage3_samples=int(args[3]),
                stage1_steps=int(args[4]),
                stage2_steps=int(args[5]),
                stage3_steps=int(args[6]),
                hidden=int(args[7]),
                modes=int(args[8]),
                lr=float(args[9]),
                output_dir=str(args[10]),
                seed=int(args[11]),
            )

        def on_state_update(s: TrainState):
            state["last_state"] = s

        def start(*args):
            nonlocal session
            params = make_params(*args)
            session = TrainingSession(params, on_update=on_state_update)
            session.start()
            time.sleep(0.5)
            return refresh()

        def stop():
            nonlocal session
            if session:
                session.stop()
            time.sleep(0.5)
            return refresh()

        def refresh():
            s = session.get_state() if session else state["last_state"]
            step_s = f"{s.current_step} / {s.total_steps}" if s.total_steps else "—"
            loss_s = f"{s.loss:.6f}" if s.loss else "—"
            elapsed_s = f"{s.elapsed_sec:.0f}s" if s.elapsed_sec else "—"

            # Build plot data
            hist = s.loss_history
            if hist:
                # Downsample for plot
                ss = max(1, len(hist) // 300)
                df = {"step": [h["step"] for h in hist[::ss]], "loss": [h["loss"] for h in hist[::ss]]}
            else:
                df = {"step": [0], "loss": [0.0]}

            return (
                s.status,
                s.stage,
                s.message,
                step_s,
                loss_s,
                elapsed_s,
                "Yes" if s.gpu_detected else "CPU / TPU",
                df,
            )

        train_outputs = [status_box, stage_box, msg_box, step_box, loss_box,
                         time_box, gpu_box, loss_plot]

        start_btn.click(fn=start, inputs=param_inputs, outputs=train_outputs)
        stop_btn.click(fn=stop, outputs=train_outputs)
        refresh_btn.click(fn=refresh, outputs=train_outputs)
        if timer:
            timer.tick(fn=refresh, outputs=train_outputs)

        # Export
        def do_export(path):
            try:
                from brnext.export.contract_adapter import validate_surrogate_contract
                validate_surrogate_contract(path)
                return L("export_success")
            except Exception as e:
                return f"{L('export_fail')}: {e}"

        export_btn.click(fn=do_export, inputs=[onnx_path_box], outputs=[export_result])

        # Language switch
        def switch_lang(lang):
            state["lang"] = lang
            return (
                f"# {L('title')}",
                L("subtitle"),
            )

        lang_dd.change(fn=switch_lang, inputs=[lang_dd], outputs=[title_md, subtitle_md])

    return app


def main():
    app = create_app()
    app.launch(server_name="0.0.0.0", server_port=7860, share=False,
               theme=gr.themes.Soft())


if __name__ == "__main__":
    main()
