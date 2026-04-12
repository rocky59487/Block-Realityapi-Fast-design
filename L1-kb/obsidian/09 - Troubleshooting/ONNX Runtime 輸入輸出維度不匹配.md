---
id: "trouble:onnx-shape-mismatch"
type: troubleshooting
tags: ["troubleshooting", "onnx", "ml", "shape-mismatch"]
severity: "medium"
---

# 🚑 ONNX Runtime 輸入輸出維度不匹配

> [!failure] 症狀
> OnnxPFSFRuntime.infer() 拋出 OrtException，指出 input shape 或 output shape 與模型預期不符。原因：1. Java 端輸入陣列維度與訓練時不一致。2. ONNX 匯出時的 dynamic axes 設定錯誤。3. 模型版本更新但 Java 端沒同步。解決：1. 檢查 onnx_contracts.py 中的合約定義。2. 使用 Netron 檢視 .onnx 檔案的輸入輸出維度。3. 對齊 Java 端輸入陣列形狀。

> [!danger] Severity: `medium`

> [!tip] 相關資訊
> 🚨 Severity: `medium`
> 📎 Related Files:
>   - `brml/brml/export/onnx_contracts.py`
>   - `Block Reality/api/src/main/java/com/blockreality/api/physics/pfsf/OnnxPFSFRuntime.java`

## 🔗 Related Notes
- [[OnnxPFSFRuntime]]
- [[infer]]
- [[shape]]
