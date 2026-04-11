---
id: "pyml:onnx-export"
type: pyml
tags: ["python", "onnx", "export", "contract"]
---

# 📄 ONNX 匯出與合約驗證

## 📖 內容
brml/export/ 負責將訓練好的 Flax 模型匯出為 ONNX。onnx_contracts.py 定義輸入/輸出 shape、dtype、數值範圍的合約，確保遊戲內 ONNX Runtime 載入時不會發生維度錯誤。hydro_subblock_onnx.py 與 hydro_ultimate_onnx.py 為流體模型專用匯出腳本。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `brml/brml/export/onnx_export.py`
>   - `brml/brml/export/onnx_contracts.py`
>   - `brml/brml/export/hydro_subblock_onnx.py`
>   - `brml/brml/export/hydro_ultimate_onnx.py`

## 🔗 Related Notes
- [[shape]]
- [[type]]
