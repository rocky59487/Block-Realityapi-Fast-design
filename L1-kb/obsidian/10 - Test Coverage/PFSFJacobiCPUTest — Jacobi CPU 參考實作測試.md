---
id: "test:PFSFJacobiCPUTest"
type: test_coverage
tags: ["test", "pfsf", "jacobi", "solver", "test_coverage"]
command: "./gradlew :api:test --tests 'com.blockreality.api.physics.pfsf.PFSFJacobiCPUTest'"
---

# 🧪 PFSFJacobiCPUTest — Jacobi CPU 參考實作測試

## 📝 適用場景
測試 CPU 端 Jacobi 迭代求解器的數值正確性，作為 GPU shader 的黃金標準（ground truth）。修改 jacobi_smooth.comp.glsl 或 smoother 邏輯時建議執行以驗證一致性。

> [!tip] 相關資訊
> ⌨️ Command: `./gradlew :api:test --tests 'com.blockreality.api.physics.pfsf.PFSFJacobiCPUTest'`
> 📎 Related Source:
>   - [[PFSFEngine]]
>   - [[glsl]]

## 🔗 Related Notes
- [[PFSFJacobiCPUTest]]
- [[glsl]]
- [[jacobi_smooth.comp.glsl]]
- [[smooth]]
