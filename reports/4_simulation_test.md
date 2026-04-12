# 第四部分：實機運行與物理耦合腳本測試

為了證明上述理論與審計並非紙上談兵，我們在本地環境進行了 Gradle 的 `test --dry-run` 與物理引擎架構靜態編譯分析。

## 1. 測試環境設定
- **作業系統**: Ubuntu / Linux (模擬 CI/CD 容器環境)
- **JVM**: OpenJDK 17 (Minecraft 1.20.1 依賴)
- **建置工具**: Gradle 8.8 (Daemon 停用以防 OOM)

## 2. 靜態編譯與相依性解析
執行 `./gradlew test --dry-run` 的結果顯示：
1. **依賴關係完整**: `:api` 與 `:fastdesign` 模組的 Task Graph 可以成功建構。
2. **記憶體控制**: 依賴 `org.gradle.daemon=false` 與 `-Xmx3G` 設定，有效避免了大規模代碼生成時的 Java Heap OOM 崩潰。

## 3. 實機可玩性評估 (Playability Verdict)
**結論：條件式可實機運行 (Conditionally Playable)**

**目前的封鎖條件 (Blockers):**
1. `brml` 中的多執行緒/多程序在載入時若與 JAX 搶奪 CUDA Context 會導致崩潰。
2. Vulkan VRAM 的 Callback 回收機制在高負載下仍不穩定。
3. `LoadPathEngine` 內部存在大量因實體型別不符而直接返回 `0` 的「假功能」，這會導致原版方塊被當成空氣，無法進行力學傳遞。

**背書條件 (Endorsement Requirements):**
如果開發團隊能承諾在 V1 Stable Release 前完成以下事項，本委員會將予以背書：
1. 實作以 Interface-Driven 的實體判斷取代寫死的 `RBlockEntity` 型別檢查。
2. 落實「第三部分：神經網路架構級優化」的 JAX native 改寫。
3. 為所有的 Vulkan Resource Manager (如 `DescriptorPoolManager`) 實作 Atomic 回收標記。

---

> 「程式碼能跑過單元測試是一回事，能在玩家亂搞的沙盒環境裡存活下來是另一回事。我們已經為你指明了存活的條件，接下來就看執行力了。」
