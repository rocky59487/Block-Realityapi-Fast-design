"""
Terminal UI fallback — when Gradio is not installed.

Uses simple input() prompts + live progress display.
"""
from __future__ import annotations

import os
import sys
import time

from .session import TrainingSession, TrainParams, CheckpointManager, TrainState


def clear():
    os.system("cls" if os.name == "nt" else "clear")


def print_header():
    print("╔══════════════════════════════════════════════╗")
    print("║  Block Reality ML — Unified Trainer (TUI)   ║")
    print("╚══════════════════════════════════════════════╝")
    print()


def ask_int(prompt: str, default: int, lo: int = 0, hi: int = 999999) -> int:
    s = input(f"  {prompt} [{default}]: ").strip()
    if not s:
        return default
    try:
        v = int(s)
        return max(lo, min(hi, v))
    except ValueError:
        return default


def ask_float(prompt: str, default: float) -> float:
    s = input(f"  {prompt} [{default}]: ").strip()
    if not s:
        return default
    try:
        return float(s)
    except ValueError:
        return default


def ask_choice(prompt: str, options: list[str], default: int = 0) -> str:
    print(f"  {prompt}")
    for i, opt in enumerate(options):
        marker = ">" if i == default else " "
        print(f"    {marker} [{i+1}] {opt}")
    s = input(f"  Choice [{ default+1}]: ").strip()
    try:
        idx = int(s) - 1
        if 0 <= idx < len(options):
            return options[idx]
    except ValueError:
        pass
    return options[default]


def configure_params() -> TrainParams:
    """Interactive parameter configuration."""
    p = TrainParams()

    print("─── Model Selection ───")
    p.model = ask_choice("Model type:", ["surrogate", "recommender", "collapse"], 0)
    print()

    print("─── Data Generation ───")
    p.grid_size = ask_int("Grid size (L³)", p.grid_size, 4, 32)
    p.n_structures = ask_int("# Structures", p.n_structures, 10, 10000)
    print()

    print("─── Training ───")
    p.total_steps = ask_int("Total steps", p.total_steps, 100, 500000)
    p.learning_rate = ask_float("Learning rate", p.learning_rate)
    p.save_every = ask_int("Auto-save every N steps", p.save_every, 100, 50000)
    print()

    if p.model == "surrogate":
        print("─── FNO Architecture ───")
        p.fno_hidden = ask_int("Hidden channels", p.fno_hidden, 8, 256)
        p.fno_layers = ask_int("Spectral layers", p.fno_layers, 1, 12)
        p.fno_modes = ask_int("Fourier modes", p.fno_modes, 2, 16)
    elif p.model == "recommender":
        print("─── GAT Architecture ───")
        p.gat_embed_dim = ask_int("Embedding dim", p.gat_embed_dim, 16, 512)
        p.gat_layers = ask_int("GAT layers", p.gat_layers, 1, 8)
    elif p.model == "collapse":
        print("─── MPNN Architecture ───")
        p.mpnn_hidden = ask_int("Hidden dim", p.mpnn_hidden, 32, 512)
        p.mpnn_steps = ask_int("MP steps", p.mpnn_steps, 2, 16)

    print()
    p.output_dir = input(f"  Output dir [{p.output_dir}]: ").strip() or p.output_dir
    p.seed = ask_int("Random seed", p.seed, 0, 999999)

    return p


def print_live_state(state: TrainState, last_print: float) -> float:
    """Print live training progress. Returns current time."""
    now = time.time()
    if now - last_print < 0.5:  # rate limit
        return last_print

    step = state.current_step
    total = state.total_steps or 1
    pct = 100.0 * step / total
    loss = state.loss

    elapsed = state.elapsed_sec
    if elapsed > 0 and step > 0:
        speed = step / elapsed
        eta = (total - step) / speed
        eta_str = f"{eta:.0f}s" if eta < 120 else f"{eta/60:.1f}m"
    else:
        speed = 0
        eta_str = "—"

    bar_len = 30
    filled = int(bar_len * step / total)
    bar = "█" * filled + "░" * (bar_len - filled)

    # Print on same line
    sys.stdout.write(
        f"\r  [{bar}] {pct:5.1f}%  step={step}/{total}  "
        f"loss={loss:.6f}  {speed:.0f} it/s  ETA={eta_str}  "
    )
    sys.stdout.flush()
    return now


def main():
    clear()
    print_header()

    # Check for resumable checkpoint
    default_dir = "brml_output"
    ckpt = CheckpointManager(default_dir)
    resume = False

    if ckpt.can_resume():
        loaded_params, step = ckpt.load_meta()
        print(f"  Found checkpoint: {loaded_params.model} at step {step}")
        choice = input("  Resume? [Y/n]: ").strip().lower()
        if choice != "n":
            resume = True
            params = loaded_params
            params.total_steps = ask_int(
                "Continue to total steps", params.total_steps, step, 500000)
            print()

    if not resume:
        print("─── Configure New Training ───")
        print()
        params = configure_params()

    # Summary
    print()
    print("═══ Training Configuration ═══")
    print(f"  Model:      {params.model}")
    print(f"  Grid:       {params.grid_size}³")
    print(f"  Structures: {params.n_structures}")
    print(f"  Steps:      {params.total_steps}")
    print(f"  LR:         {params.learning_rate}")
    print(f"  Output:     {params.output_dir}/")
    if resume:
        print(f"  Resuming from step {step}")
    print()

    confirm = input("  Start? [Y/n]: ").strip().lower()
    if confirm == "n":
        print("  Aborted.")
        return

    # ── Training ──
    print()
    print("═══ Training ═══")

    live_state = TrainState()
    last_print = [0.0]

    def on_update(state: TrainState):
        nonlocal live_state
        live_state = state

        if state.status in ("generating", "solving"):
            sys.stdout.write(f"\r  {state.status}: {state.message}                    ")
            sys.stdout.flush()
        elif state.status == "training":
            last_print[0] = print_live_state(state, last_print[0])
        elif state.status == "done":
            print(f"\n\n  {state.message}")
        elif state.status == "error":
            print(f"\n\n  ERROR: {state.message}")

    session = TrainingSession(params, on_update=on_update)
    session.start(resume=resume)

    # Wait with Ctrl+C handling
    try:
        while session._thread and session._thread.is_alive():
            time.sleep(0.3)
    except KeyboardInterrupt:
        print("\n\n  Ctrl+C — stopping & saving...")
        session.stop()

    print()
    final = session.get_state()
    if final.status == "done":
        print(f"  Output: {params.output_dir}/")
        print(f"  Total steps: {final.current_step}")
        print(f"  Final loss: {final.loss:.6f}")
    elif final.can_resume:
        print(f"  Saved at step {final.current_step}. Run again to resume.")

    print()
    print("  Done.")


if __name__ == "__main__":
    main()
