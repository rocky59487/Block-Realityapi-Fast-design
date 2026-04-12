# 第二部分：高職專題發表會模擬 (The Defense Simulation)

**場景設定**: 某頂尖科技大學 / 駭客年會 聯合專題發表會現場。台下坐著 8位教授、15位老師、3位業界專家、以及 30位看熱鬧的普通玩家。

---

## 8位教授的致命問題 (理論與學術性)
**Q:「你們的 BIFROST 架構使用 FNO (Fourier Neural Operator) 來跳過 PFSF 迭代。但當遇到結構材料中極端的斷裂邊界條件（如 Ambati 2015 提及的 phase-field 奇異點）時，FNO 的頻譜截斷效應會導致高頻應力震盪。你們如何證明神經網路的結果符合建築力學中的能量守恆定律？」**

**防禦策略 (冷酷回擊):**
> 教授，這正是我們採用 HybridPhysicsRouter (混合路由) 的原因。我們從未盲目信任神經網路。系統內建的 `ShapeClassifier` 會實時計算結構的不規則度。當遇到高頻奇異點或 OOD (Out-of-Distribution) 結構時，信心分數低於 0.45，系統會硬性降級回 GPU 上的 RBGS+MG (多重網格) 物理迭代求解，保證了邊界條件的絕對正確性。我們用 ML 加速了 80% 的平凡解，保留算力給 20% 的奇點，這在工程上叫最優化配置。

---

## 15位老師的質疑 (規範與工作分配)
**Q:「專案用到 Java、Python(JAX)、C++(Vulkan)、TypeScript。這種跨度根本不是普通學生能獨立完成的。你們是不是直接從 GitHub 抄了開源的物理引擎跟 ML 訓練框架來兜底？程式碼裡的 TODO 還有 270 幾個，這算是完成品嗎？」**

**防禦策略 (數據說話):**
> 老師，這並非拼湊，而是高度解耦的微服務設計。Minecraft 只能跑 Java，AI 訓練必須用 Python，GPU 算力必須用 C++ Vulkan 榨取，這是架構的必然。我們自主研發了 JAX 到 ONNX 的匯出管線 (`brml/export`) 並在 Java 端用 `OnnxPFSFRuntime` 承接。至於 271 個 TODO，這叫『技術債追蹤』。在敏捷開發(Agile)中，我們選擇先讓核心閉環（確保實機可玩），而非為了解決原版 Minecraft 水流碰撞的邊緣情況而延誤交付。這是工程權衡，不是抄襲。

---

## 3位專業程序員的拷問 (工程與維運)
**Q:「你們的 ML 訓練管線 `concurrent_trainer.py` 竟然混用了 NumPy 跟 JAX，而且訓練迴圈裡還用 Python 原生 for loop？這種寫法只要資料量一大，Host-Device 的傳輸延遲跟 GIL 鎖就會讓你的高階 GPU 在那邊空轉。你們有做 Profiling 嗎？CI/CD 呢？」**

**防禦策略 (承認並提出破壞性重構計畫):**
> 點出重點了。這確實是目前架構最大的效能毒瘤。我們目前的條件式背書正建立在解決此問題的基礎上。我們已擬定重構計畫：第一，全面剔除 `np.ndarray`，統一使用 `jnp.array` 並實作非同步 DataLoader 綁定 device_put。第二，使用 `jax.lax.scan` 完全重寫 `train_step` 實現全編譯 (Full-JIT)。至於 CI/CD，我們已有基於 GitHub Actions 的自動化 Review 流程（Claude↔Jules），重構的 PR 將在 24 小時內通過自動測試。

---

## 30位普通用戶的抱怨 (體驗與硬體)
**Q:「我就是想玩個蓋房子會倒的模組，為什麼還要裝 Python、還要 CUDA？我電腦只有 16GB 記憶體跟一張 3060，跑這個會不會直接燒機或卡到爆？」**

**防禦策略 (安撫與功能降級展示):**
> 各位玩家，我們設計了三層 LOD (Level of Detail)。第一層：如果你不拆房子，物理引擎的消耗是『0』。第二層：對於 3060 玩家，我們會自動將張量計算鎖定在 VRAM 限制內，並關閉流體耦合。第三層：訓練模型是給伺服器主跟開發者用的，普通玩家下載的 Mod 內建已經訓練好的 ONNX 模型（在 `brml_models` 資料夾下），完全不需要裝 Python 或 CUDA 就能享受 AI 物理加速。

---

## 開發與測試員對質 (內部揭露)
**測試員:**「上次壓力測試，玩家在 10 個 Chunk 同時放 TNT，VRAM 爆了不說，伺服器直接死鎖崩潰，你寫的 `DescriptorPoolManager.destroy()` 根本不防重入！」
**開發者:**「那是因為 Forge 的 Tick Event 是非同步觸發的！我們不能每個 Tick 都去 Lock，會掉 TPS！」
**結論策略:** 雙方妥協。實施非同步的原子計數器 (`AtomicBoolean isDestroyed`) 來確保 `destroy()` 的冪等性，並把 VRAM 的釋放放到每秒一次的 GC Tick 中批量處理，兼顧 TPS 與穩定性。
