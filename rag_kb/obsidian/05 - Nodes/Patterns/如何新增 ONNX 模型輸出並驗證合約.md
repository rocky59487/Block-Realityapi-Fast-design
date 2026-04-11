---
id: "pattern:onnx-contract"
type: pattern
tags: ["pattern", "onnx", "ml", "python", "howto"]
---

# 🧩 如何新增 ONNX 模型輸出並驗證合約

## 🪜 步驟
- 1. 在 brml/models/ 定義 Flax 模型。
- 2. 訓練完成後在 brml/export/ 撰寫對應的 onnx_export 腳本。
- 3. 在 onnx_contracts.py 中定義輸入 shape、dtype、輸出 channel 說明。
- 4. 在 Java 端 OnnxPFSFRuntime 載入並驗證輸入輸出維度。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `brml/brml/export/onnx_export.py`
>   - `brml/brml/export/onnx_contracts.py`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/OnnxPFSFRuntime.java`

## 🔗 Related Notes
- [[OnnxPFSFRuntime]]
- [[shape]]
- [[type]]
