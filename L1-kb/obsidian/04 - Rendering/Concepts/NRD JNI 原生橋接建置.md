---
id: "render:native-bridge"
type: render
tags: ["render", "native", "jni", "nrd", "cmake"]
---

# 📄 NRD JNI 原生橋接建置

## 📖 內容
NRD 降噪器的 JNI 橋接位於 Block Reality/api/src/main/native/，需獨立 cmake 建置：mkdir build && cd build && cmake .. -DJAVA_HOME=$JAVA_HOME && cmake --build . --config Release && cmake --install .

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/native/CMakeLists.txt`

## 🔗 Related Notes
- [[NRD JNI 原生橋接]]
- [[build]]
- [[main]]
