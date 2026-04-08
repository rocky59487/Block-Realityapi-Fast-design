"""
Gradio web UI for Block Reality ML training.

Launch: python -m brml.ui.web_ui
Opens browser at http://localhost:7860
"""
from __future__ import annotations

import time
import threading

import gradio as gr

from .session import TrainingSession, TrainParams, TrainState, CheckpointManager


def create_app() -> gr.Blocks:
    """Build the Gradio interface."""

    session_holder: dict = {"session": None, "state": TrainState()}

    def on_state_update(state: TrainState):
        session_holder["state"] = state

    # ── UI ──
    with gr.Blocks(
        title="Block Reality ML Trainer",
        theme=gr.themes.Soft(),
    ) as app:

        gr.Markdown("# Block Reality ML — Unified Trainer")
        gr.Markdown("Generate FEM data → Train models → Export. Select one or more models.")

        with gr.Tabs():
            # ════════════ Tab 1: Parameters ════════════
            with gr.Tab("Parameters"):
                with gr.Row():
                    with gr.Column(scale=1):
                        gr.Markdown("### Models (multi-select)")
                        model_choice = gr.CheckboxGroup(
                            ["surrogate", "fluid", "lod", "collapse"],
                            value=["surrogate"],
                            label="Train which models?",
                            info="surrogate=FEM physics, fluid=water 0.1m, lod=chunk tier, collapse=failure",
                        )

                    with gr.Column(scale=1):
                        gr.Markdown("### Output")
                        output_dir = gr.Textbox(value="brml_output", label="Output Directory")
                        seed = gr.Number(value=42, label="Random Seed", precision=0)

                gr.Markdown("---")
                gr.Markdown("### Data Generation")
                with gr.Row():
                    grid_size = gr.Slider(4, 24, value=12, step=2,
                                          label="Grid Size (L³ voxels)")
                    n_structures = gr.Slider(10, 5000, value=200, step=10,
                                             label="# Structures (FEM samples)")

                gr.Markdown("### Training")
                with gr.Row():
                    total_steps = gr.Slider(1000, 200000, value=10000, step=1000,
                                            label="Training Steps")
                    learning_rate = gr.Number(value=1e-3, label="Learning Rate")
                with gr.Row():
                    weight_decay = gr.Number(value=1e-4, label="Weight Decay")
                    grad_clip = gr.Number(value=1.0, label="Gradient Clip")
                    save_every = gr.Slider(500, 10000, value=2000, step=500,
                                           label="Save Every N Steps")

                gr.Markdown("### Architecture (Surrogate — FNO)")
                with gr.Row():
                    fno_hidden = gr.Slider(16, 128, value=48, step=8,
                                           label="Hidden Channels")
                    fno_layers = gr.Slider(2, 8, value=4, step=1,
                                           label="Spectral Conv Layers")
                    fno_modes = gr.Slider(2, 16, value=6, step=1,
                                          label="Fourier Modes")

                gr.Markdown("### Architecture (Recommender — GAT)")
                with gr.Row():
                    gat_embed = gr.Slider(16, 256, value=64, step=16,
                                          label="Embedding Dim")
                    gat_layers = gr.Slider(1, 6, value=3, step=1,
                                           label="GAT Layers")
                    gat_heads = gr.Slider(1, 8, value=4, step=1,
                                          label="Attention Heads")

                gr.Markdown("### Architecture (Collapse — MPNN)")
                with gr.Row():
                    mpnn_hidden = gr.Slider(32, 256, value=128, step=32,
                                            label="Hidden Dim")
                    mpnn_steps = gr.Slider(2, 12, value=6, step=1,
                                           label="Message Passing Steps")

            # ════════════ Tab 2: Training ════════════
            with gr.Tab("Training"):
                with gr.Row():
                    start_btn = gr.Button("▶ Start Training", variant="primary", size="lg")
                    resume_btn = gr.Button("↻ Resume", variant="secondary", size="lg")
                    stop_btn = gr.Button("■ Stop & Save", variant="stop", size="lg")

                status_text = gr.Textbox(label="Status", interactive=False)
                message_text = gr.Textbox(label="Message", interactive=False)

                with gr.Row():
                    step_text = gr.Textbox(label="Step", interactive=False)
                    loss_text = gr.Textbox(label="Loss", interactive=False)
                    time_text = gr.Textbox(label="Elapsed", interactive=False)
                    speed_text = gr.Textbox(label="Speed", interactive=False)

                loss_plot = gr.LinePlot(
                    x="step", y="loss",
                    title="Training Loss",
                    x_title="Step", y_title="Loss",
                    width=700, height=300,
                )

                refresh_btn = gr.Button("🔄 Refresh", size="sm")

        # ── Callbacks ──

        def collect_params(model, grid, structs, steps, lr, wd, gc, se, out, sd,
                           fh, fl, fm, ge, gl, gh, mh, ms):
            # model is a list from CheckboxGroup → join with comma
            model_str = ",".join(model) if isinstance(model, list) else model
            return TrainParams(
                model=model_str, grid_size=int(grid), n_structures=int(structs),
                total_steps=int(steps), learning_rate=float(lr),
                weight_decay=float(wd), grad_clip=float(gc),
                save_every=int(se), output_dir=out, seed=int(sd),
                fno_hidden=int(fh), fno_layers=int(fl), fno_modes=int(fm),
                gat_embed_dim=int(ge), gat_layers=int(gl), gat_heads=int(gh),
                mpnn_hidden=int(mh), mpnn_steps=int(ms),
            )

        param_inputs = [
            model_choice, grid_size, n_structures, total_steps,
            learning_rate, weight_decay, grad_clip, save_every,
            output_dir, seed,
            fno_hidden, fno_layers, fno_modes,
            gat_embed, gat_layers, gat_heads,
            mpnn_hidden, mpnn_steps,
        ]

        def start_training(*args):
            params = collect_params(*args)
            session = TrainingSession(params, on_update=on_state_update)
            session_holder["session"] = session
            session.start(resume=False)
            time.sleep(0.5)
            return refresh_display()

        def resume_training(*args):
            params = collect_params(*args)
            session = TrainingSession(params, on_update=on_state_update)
            session_holder["session"] = session
            session.start(resume=True)
            time.sleep(0.5)
            return refresh_display()

        def stop_training():
            s = session_holder.get("session")
            if s:
                s.stop()
            time.sleep(1)
            return refresh_display()

        def refresh_display():
            st = session_holder["state"]
            import pandas as pd

            step_str = f"{st.current_step} / {st.total_steps}" if st.total_steps else "—"
            loss_str = f"{st.loss:.6f}" if st.loss else "—"
            elapsed = st.elapsed_sec
            time_str = f"{elapsed:.0f}s" if elapsed < 120 else f"{elapsed/60:.1f}m"
            speed = f"{st.current_step / elapsed:.0f} it/s" if elapsed > 0 and st.current_step > 0 else "—"

            # Build plot data
            history = st.loss_history or []
            if history:
                step_size = max(1, len(history) // 200)  # downsample for display
                sampled = history[::step_size]
                df = {"step": list(range(0, len(sampled) * step_size, step_size)),
                      "loss": sampled}
            else:
                df = {"step": [0], "loss": [0]}

            return (
                st.status,
                st.message or st.fem_progress,
                step_str,
                loss_str,
                time_str,
                speed,
                df,
            )

        display_outputs = [
            status_text, message_text, step_text, loss_text, time_text, speed_text,
            loss_plot,
        ]

        start_btn.click(fn=start_training, inputs=param_inputs, outputs=display_outputs)
        resume_btn.click(fn=resume_training, inputs=param_inputs, outputs=display_outputs)
        stop_btn.click(fn=stop_training, outputs=display_outputs)
        refresh_btn.click(fn=refresh_display, outputs=display_outputs)

        # Auto-refresh
        app.load(fn=refresh_display, outputs=display_outputs, every=3)

    return app


def main():
    app = create_app()
    app.launch(server_name="0.0.0.0", server_port=7860, share=False)


if __name__ == "__main__":
    main()
