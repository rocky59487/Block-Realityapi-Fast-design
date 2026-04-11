---
id: "java_api:com.blockreality.api.physics.fluid.FluidRegion"
type: class
tags: ["java", "api", "fluid"]
---

# 🧩 com.blockreality.api.physics.fluid.FluidRegion

> [!info] 摘要
> 流體模擬區域 — 一個矩形體積內的流體狀態容器。  <p>儲存 SoA (Structure of Arrays) 布局的流體資料， 與 GPU buffer 一對一對應。CPU 端使用此類進行 參考求解和查詢；GPU 端則直接操作 buffer。  <p>陣列索引使用 row-major: index = x + y  sizeX + z  sizeX  sizeY

## 🔗 Related
- [[FluidRegion]]
- [[index]]
- [[size]]
- [[sizeX]]
- [[sizeY]]
