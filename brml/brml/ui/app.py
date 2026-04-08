"""
Unified launcher — tries Gradio web UI, falls back to terminal UI.

Usage:
  brml-ui          # auto-detect
  brml-ui --web    # force web UI
  brml-ui --tui    # force terminal UI
"""
from __future__ import annotations

import argparse
import sys


def main():
    parser = argparse.ArgumentParser(description="Block Reality ML — Unified Trainer")
    parser.add_argument("--web", action="store_true", help="Force web UI (Gradio)")
    parser.add_argument("--tui", action="store_true", help="Force terminal UI")
    parser.add_argument("--port", type=int, default=7860, help="Web UI port")
    args = parser.parse_args()

    if args.tui:
        from .tui import main as tui_main
        tui_main()
        return

    if args.web:
        _launch_web(args.port)
        return

    # Auto-detect
    try:
        import gradio
        print("Gradio found — launching web UI at http://localhost:7860")
        _launch_web(args.port)
    except ImportError:
        print("Gradio not installed — using terminal UI")
        print("  (Install with: pip install gradio)")
        print()
        from .tui import main as tui_main
        tui_main()


def _launch_web(port: int):
    from .web_ui import create_app
    app = create_app()
    app.launch(server_name="0.0.0.0", server_port=port, share=False)


if __name__ == "__main__":
    main()
