---
id: "test:FluidCPUSolverTest"
type: test_coverage
tags: ["test", "fluid", "solver", "test_coverage"]
command: "./gradlew :api:test --tests 'com.blockreality.api.physics.fluid.FluidCPUSolverTest'"
---

# 🧪 FluidCPUSolverTest — 流體 CPU 求解器測試

## 📝 適用場景
測試 FluidCPUSolver 的數值正確性。修改流體 shader（fluid_jacobi.comp.glsl、fluid_pressure.comp.glsl）或 FluidStructureCoupler 時建議執行。

> [!tip] 相關資訊
> ⌨️ Command: `./gradlew :api:test --tests 'com.blockreality.api.physics.fluid.FluidCPUSolverTest'`
> 📎 Related Source:
>   - [[FluidCPUSolver]]
>   - [[FluidStructureCoupler]]

## 🔗 Related Notes
- [[FluidCPUSolver]]
- [[FluidCPUSolverTest]]
- [[FluidStructureCoupler]]
- [[fluid_jacobi.comp.glsl]]
- [[fluid_pressure.comp.glsl]]
- [[glsl]]
