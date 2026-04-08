# brml — Block Reality Machine Learning Subsystem

**brml** is the external machine learning training pipeline for Block Reality, built using the Python **JAX/Flax** ecosystem. It bridges the gap between deterministic structural GPU calculations and heuristic-based heuristic prediction.

## 1. Overview / 概述

The ML subsystem provides tools to train models that can infer structural physics states (such as potential field diffusion, stress heatmaps, and collapse predictions) based on structural layouts and material bindings without running the full iterative PFSF solver.

本系統提供訓練物理代理模型的工具，能基於結構佈局與材料綁定來推斷結構物理狀態（例如勢場擴散、應力熱力圖與崩塌預測），而無需執行完整的 PFSF 迭代求解器。

## 2. Architecture & Modules / 架構與模組

*   **`brml.train.train_surrogate`**: Trains surrogate models using structural NBT datasets to estimate stress concentrations.
*   **`brml.train.train_recommender`**: Node recommender module for the Fast Design visual programming interface.
*   **`brml.train.train_collapse`**: Specifically predicts collapse points and weak joints via learned topological features.
*   **`brml.export.onnx_export`**: Exports trained Flax models into ONNX format for cross-platform integration back into the Java/Vulkan context (or ONNX Runtime).
*   **`brml.ui.app`**: A Gradio-based user interface for interacting with and visually debugging trained models in the browser.

## 3. Technology Stack / 技術棧

*   **Framework:** JAX / Flax / Optax for rapid neural network prototyping and high-performance gradients.
*   **Math/Scientific:** NumPy, SciPy.
*   **Tooling:** Gradio for UI, `tqdm` for tracking, ONNX for export.

## 4. Workflows / 工作流程

To spin up a surrogate model training session:
```bash
python -m brml.train.train_surrogate --dataset path/to/structures.nbt --epochs 100
```

To launch the web UI:
```bash
python -m brml.ui.app
```
