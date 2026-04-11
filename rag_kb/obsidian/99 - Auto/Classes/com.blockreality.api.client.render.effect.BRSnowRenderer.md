---
id: "java_api:com.blockreality.api.client.render.effect.BRSnowRenderer"
type: class
tags: ["java", "api", "effect", "client-only"]
---

# 🧩 com.blockreality.api.client.render.effect.BRSnowRenderer

> [!info] 摘要
> GPU Instanced 雪花渲染器。  技術架構： - 雪花為 billboard point sprite（圓形 + 6 分支結晶紋理程序化生成） - 飄落路徑：正弦擺動（x,z 方向）+ 重力下落（y） - 積雪效果：snowCoverage 係數傳入 GBuffer shader 修改法線 + albedo - 近距離 bokeh 失焦效果（距相機近的雪花放大 + 模糊）  @author Block Reality Team @version 1.0

## 🔗 Related
- [[BRSnowRenderer]]
- [[author]]
- [[render]]
