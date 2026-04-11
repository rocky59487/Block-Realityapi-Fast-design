---
id: "native:nrd-jni"
type: native
tags: ["native", "jni", "nrd", "denoiser", "cpp"]
---

# 📄 NRD JNI 原生橋接

## 📖 內容
位於 Block Reality/api/src/main/native/，包含 BRNRDNative.cpp 與 CMakeLists.txt。建置方式：mkdir build && cd build && cmake .. -DJAVA_HOME=$JAVA_HOME && cmake --build . --config Release && cmake --install .。輸出的 native library 會被 BRVulkanRT / BRNRDNative 載入。

> [!tip] 相關資訊
> 📎 Related Files:
>   - `Block Reality/api/src/main/native/BRNRDNative.cpp`
>   - `Block Reality/api/src/main/native/CMakeLists.txt`

## 🔗 Related Notes
- [[BRNRDNative]]
- [[BRVulkanRT]]
- [[build]]
- [[main]]
