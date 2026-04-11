---
id: "pyml:overview"
type: pyml
tags: ["python", "ml", "jax", "flax", "pipeline"]
---

# 📄 brml 管線總覽

## 📖 內容
brml/ 是 Block Reality 的 Python ML 訓練管線，使用 JAX/Flax/Optax。目標是產生 ONNX surrogate 模型供遊戲內 ONNX Runtime 推理。主要模型包括：FNO3D（流體/物理 surrogate）、CollapsePredictor（崩塌預測）、NodeRecommender（節點推薦）、HydroSubblock / HydroUltimate（子塊/終極流體模型）。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `brml/pyproject.toml`
>   - `brml/pipeline/auto_train.py`
>   - `start-trainer.sh`
>   - `start-trainer.bat`
