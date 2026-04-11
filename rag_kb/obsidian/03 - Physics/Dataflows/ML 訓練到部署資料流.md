---
id: "dataflow:ml-training"
type: dataflow
tags: ["dataflow", "ml", "python", "jax", "onnx"]
---

# 🌊 ML 訓練到部署資料流

## 📝 概述
藍圖/世界資料 → blueprint_loader / physics_dataset 載入 → FEM ground-truth（hex8_element + fem_solver）產生標籤 → brml/pipeline/auto_train 或 concurrent_trainer 啟動訓練 → Flax 模型（fno_fluid、pfsf_surrogate 等）前向與反向傳播 → 訓練完成後 brml/export/onnx_export 匯出 ONNX → onnx_contracts.py 驗證輸入輸出 shape/dtype → BIFROSTModelRegistry 註冊模型路徑 → OnnxPFSFRuntime 在遊戲內載入並執行推理。

## 🔄 資料流階段
1. physics_dataset
2. fem_solver
3. auto_train
4. onnx_export
5. onnx_contracts
6. [[BIFROSTModelRegistry]]
7. [[OnnxPFSFRuntime]]

> [!tip] 相關資訊
> 🔄 Pipeline Stages:
>   - physics_dataset
>   - fem_solver
>   - auto_train
>   - onnx_export
>   - onnx_contracts
>   - [[BIFROSTModelRegistry]]
>   - [[OnnxPFSFRuntime]]
> 📎 Related Files:
>   - `brml/brml/data/physics_dataset.py`
>   - `brml/brml/fem/fem_solver.py`
>   - `brml/brml/pipeline/auto_train.py`
>   - `brml/brml/export/onnx_export.py`
>   - `brml/brml/export/onnx_contracts.py`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/BIFROSTModelRegistry.java`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/OnnxPFSFRuntime.java`

## 🔗 Related Notes
- [[BIFROSTModelRegistry]]
- [[OnnxPFSFRuntime]]
- [[blue]]
- [[line]]
- [[load]]
- [[shape]]
- [[solve]]
- [[type]]
