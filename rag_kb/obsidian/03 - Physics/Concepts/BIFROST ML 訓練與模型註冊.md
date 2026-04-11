---
id: "pfsf:BIFROST"
type: pfsf
tags: ["pfsf", "ml", "bifrost", "training", "registry"]
---

# 📄 BIFROST ML 訓練與模型註冊

## 📖 內容
BIFROSTModelRegistry 管理訓練好的 surrogate ONNX 模型。ML 訓練管線位於 brml/，產生 ONNX 後由OnnxPFSFRuntime 載入。訓練入口為 brml/pipeline/auto_train.py。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/BIFROSTModelRegistry.java`
>   - `brml/pipeline/auto_train.py`
>   - `brml/export/onnx_export.py`

## 🔗 Related Notes
- [[BIFROSTModelRegistry]]
- [[OnnxPFSFRuntime]]
- [[line]]
