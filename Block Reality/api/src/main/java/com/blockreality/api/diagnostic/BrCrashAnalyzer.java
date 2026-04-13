package com.blockreality.api.diagnostic;

import java.util.ArrayList;
import java.util.List;

/**
 * Block Reality 崩潰根因分析引擎。
 *
 * <p>透過分析 Throwable 的 class、message、stack trace，以及
 * 最近的日誌條目，嘗試識別已知崩潰模式並輸出：
 * <ul>
 *   <li>人類可讀的「可能原因」</li>
 *   <li>具體的「修復建議」</li>
 *   <li>崩潰的「嚴重度」分類</li>
 * </ul>
 *
 * <p>所有規則以靜態陣列定義，易於擴展，不依賴外部資源也不拋出例外。
 */
public final class BrCrashAnalyzer {

    private BrCrashAnalyzer() {}

    // ─────────────────────────────────────────────────────────────────
    //  輸出結構
    // ─────────────────────────────────────────────────────────────────

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN }

    public record AnalysisResult(
            Severity severity,
            String shortSummary,
            List<String> possibleCauses,
            List<String> fixSuggestions,
            boolean isBrRelated
    ) {}

    // ─────────────────────────────────────────────────────────────────
    //  規則定義
    // ─────────────────────────────────────────────────────────────────

    /** 一條分析規則：符合條件 → 輸出結果。 */
    private record AnalysisRule(
            String id,
            Severity severity,
            String summary,
            List<String> triggers,     // 只要 throwable 字串包含其中任一關鍵字即觸發
            List<String> causes,
            List<String> fixes,
            boolean brRelated
    ) {
        boolean matches(String throwableStr, String logStr) {
            String lower = throwableStr.toLowerCase() + " " + logStr.toLowerCase();
            return triggers.stream().anyMatch(lower::contains);
        }
    }

    /** 規則庫（按嚴重度降序） */
    private static final List<AnalysisRule> RULES = buildRules();

    // ─────────────────────────────────────────────────────────────────
    //  主入口
    // ─────────────────────────────────────────────────────────────────

    /**
     * 分析崩潰並回傳結果。
     *
     * <p>分析策略：
     * <ol>
     *   <li>先對完整 cause-chain 字串掃描規則庫（包含所有層級的例外類別與訊息）</li>
     *   <li>若 throwable 本身為 ExceptionInInitializerError，額外提取 root cause
     *       進行第二輪規則比對，給出更精確的診斷</li>
     *   <li>均未命中則進入通用分析（亦特化處理 EIIE）</li>
     * </ol>
     *
     * @param t         導致崩潰的 Throwable
     * @param recentLog 最近的日誌條目（來自 BrLogCapture）
     */
    public static AnalysisResult analyze(Throwable t, List<BrLogCapture.LogEntry> recentLog) {
        if (t == null) return unknownResult();

        // 1. 建立可搜尋的完整字串（遍歷 cause chain）
        String throwableStr = buildThrowableString(t);
        String logStr = recentLog.stream()
                .map(e -> e.level() + " " + e.message())
                .reduce("", (a, b) -> a + " " + b);

        // 2. 掃描規則庫（第一條命中即返回，規則已按優先度排列）
        for (AnalysisRule rule : RULES) {
            if (rule.matches(throwableStr, logStr)) {
                return new AnalysisResult(
                        rule.severity(),
                        rule.summary(),
                        rule.causes(),
                        rule.fixes(),
                        rule.brRelated()
                );
            }
        }

        // 3. 未命中任何規則 → 通用分析（特化處理 EIIE）
        return genericAnalysis(t, throwableStr);
    }

    /** 提取 cause chain 的最底層（最原始）例外。 */
    private static Throwable getRootCause(Throwable t) {
        Throwable cur = t;
        int guard = 16;
        while (cur.getCause() != null && guard-- > 0) cur = cur.getCause();
        return cur;
    }

    // ─────────────────────────────────────────────────────────────────
    //  規則庫建構
    // ─────────────────────────────────────────────────────────────────

    private static List<AnalysisRule> buildRules() {
        List<AnalysisRule> r = new ArrayList<>();

        // ══════════ CRITICAL ════════════════════════════════════════

        // ── ExceptionInInitializerError（EIIE）──
        // EIIE 是「包裝層」，真正根因在 cause 中，因此觸發關鍵字刻意廣泛；
        // 後續 genericAnalysis 會進一步細化 cause 層的資訊。
        r.add(new AnalysisRule("EIIE", Severity.CRITICAL,
                "靜態初始化失敗（ExceptionInInitializerError）— 類別無法載入",
                List.of("exceptionininitializererror", "exception in initializer"),
                List.of(
                        "某個 static { } 區塊或 static 欄位初始化時拋出了例外",
                        "常見根因：NullPointerException（依賴尚未初始化）、UnsatisfiedLinkError（Native 函式庫缺失）",
                        "常見根因：ClassNotFoundException / NoClassDefFoundError（依賴模組版本不對）",
                        "常見根因：IllegalArgumentException（重複向 Forge 或 BlockRegistry 註冊同一個 ID）",
                        "Block Reality 中最常發生於：BRConfig 靜態初始化、VulkanComputeContext static 欄位、NodeRegistry 大量節點批次註冊"
                ),
                List.of(
                        "查看崩潰報告中的『完整版 Stack Trace』並找到 Caused by: 最後一層——那才是真正的錯誤",
                        "若 Caused by 為 UnsatisfiedLinkError：LWJGL native jar 未在 classpath，見 VK-INIT-FAIL / UNSATISFIED-LINK 規則修復步驟",
                        "若 Caused by 為 ClassNotFoundException/NoClassDefFoundError：JAR 損壞或 Forge 版本不符，重新建置 ./gradlew mergedJar",
                        "若 Caused by 為 IllegalArgumentException 且含 duplicate key/registry：執行 python L1-tooling/fix/fix_registry.py 修復重複節點註冊",
                        "若 Caused by 為 NullPointerException 在 BRConfig: 確認 Forge 版本 ≥ 47.4.13（舊版 ForgeConfigSpec API 不相容）",
                        "若問題在 NodeRegistry：可能是 BrConverter 或 MaterialNode 子類的靜態 PORT 欄位初始化順序問題，確認父類初始化在子類之前完成"
                ),
                true
        ));


        r.add(new AnalysisRule("VK-INIT-FAIL", Severity.CRITICAL,
                "Vulkan 初始化失敗 — PFSF GPU 引擎無法啟動",
                List.of("vcreateinstance failed", "createinstancefailed",
                        "vkcreateinstance", "vk_error_incompatible_driver",
                        "system vulkan driver not found", "failed to load vulkan"),
                List.of(
                        "GPU 驅動程式過舊，不支援 Vulkan 1.2",
                        "系統缺少 vulkan-1.dll (Windows) 或 libvulkan.so (Linux)",
                        "虛擬機（VM）或不支援 GPU 直通的環境"
                ),
                List.of(
                        "更新 GPU 驅動至最新版（NVIDIA: GeForce Experience / AMD: Adrenalin / Intel: Arc Control）",
                        "Windows：確認 C:\\Windows\\System32\\vulkan-1.dll 存在",
                        "Linux：安裝 vulkan-tools 軟體包（sudo apt install vulkan-tools）並執行 vulkaninfo 驗證",
                        "Block Reality 會自動降級至 CPU 物理引擎，功能不受影響但效能降低",
                        "在 config/blockreality-common.toml 中設定 pfsf_enabled = false 可完全停用 GPU 路徑"
                ),
                true
        ));

        r.add(new AnalysisRule("VMA-OOM", Severity.CRITICAL,
                "VRAM 記憶體耗盡 — GPU 記憶體不足",
                List.of("vmacreatealloc", "vmacreate failed", "vk_error_out_of_device_memory",
                        "out_of_device_memory", "vram budget exceeded", "tryrecord"),
                List.of(
                        "模組 VRAM 用量超出預算（預設佔可用 VRAM 的 60%）",
                        "島嶼（Island）過大（超過 100 萬方塊）導致 SSBO 分配過多",
                        "其他模組（如光追材質包）佔用大量 VRAM，壓縮 Block Reality 配額"
                ),
                List.of(
                        "config/blockreality-common.toml: 降低 vram_usage_percent（例如從 60 改為 40）",
                        "執行 /br status 查看目前 VRAM 使用量",
                        "減少同時活躍的大型建築結構數量",
                        "移除其他高 VRAM 消耗的視覺模組（光追材質包、Distant Horizons 等）",
                        "升級顯示卡（建議 VRAM ≥ 8 GB 以使用完整功能）"
                ),
                true
        ));

        r.add(new AnalysisRule("STACK-OVERFLOW", Severity.CRITICAL,
                "Stack Overflow — BFS/崩塌遞迴無限迴圈",
                List.of("stackoverflow", "stackoverflowerror"),
                List.of(
                        "結構 BFS 分析因迴圈依賴陷入無限遞迴",
                        "崩塌傳播鏈觸發相互引用（例如：結構 A 崩塌觸發 B，B 再觸發 A）",
                        "節點圖（Node Graph）存在拓撲迴路"
                ),
                List.of(
                        "config/blockreality-common.toml: 降低 bfs_max_blocks（例如 500000）限制分析深度",
                        "config/blockreality-common.toml: 降低 cycle_detect_max_depth（預設 8）",
                        "避免在大型迴圈形結構（如圓形牆）中移除關鍵支撐點",
                        "回報 Bug：請附上此崩潰報告到 GitHub Issues"
                ),
                true
        ));

        r.add(new AnalysisRule("SHADER-COMPILE-FAIL", Severity.CRITICAL,
                "GLSL Shader 編譯失敗 — PFSF 計算著色器錯誤",
                List.of("glsl compilation failed", "shaderc_result_get_error",
                        "shader compilation", "phase_field", "jacobi", "rbgs",
                        "pfsf_pipeline"),
                List.of(
                        "Shader 原始碼與 Vulkan 版本不相容",
                        "JAR 損壞導致 .glsl 資源檔缺失",
                        "GPU 不支援 Shader 中使用的 GLSL 擴充功能"
                ),
                List.of(
                        "重新下載模組 JAR（確認 SHA256 雜湊一致）",
                        "確認 GPU 支援 Vulkan 1.2 Compute",
                        "臨時解決方案：config/blockreality-common.toml 設定 pfsf_enabled = false"
                ),
                true
        ));

        // ══════════ HIGH ════════════════════════════════════════════

        r.add(new AnalysisRule("UNSATISFIED-LINK", Severity.HIGH,
                "Native 函式庫連結失敗 — VMA/Shaderc JNI 缺失",
                List.of("unsatisfiedlinkerror", "unsatisfied link",
                        "no lwjgl_vulkan", "no shaderc", "no vma"),
                List.of(
                        "LWJGL native 函式庫（VMA、Shaderc）未加入 classpath",
                        "build.gradle 中的 -Xbootclasspath/a 設定遺失",
                        "在 Prism Launcher / MultiMC 中未設定 Java 附加引數"
                ),
                List.of(
                        "確認 build.gradle 的 afterEvaluate 區塊包含 LWJGL native jar 的 -Xbootclasspath/a 設定",
                        "若使用啟動器：在 Java 引數中加入 -Xbootclasspath/a:<path/to/lwjgl-natives.jar>",
                        "執行 ./gradlew :api:runClient 而非直接啟動 Minecraft（自動設定 JVM 引數）"
                ),
                true
        ));

        r.add(new AnalysisRule("CLASS-NOT-FOUND-BR", Severity.HIGH,
                "Block Reality 類別載入失敗 — 模組 JAR 損壞或依賴缺失",
                List.of("classnotfoundexception: com.blockreality",
                        "noclassdeffounderror: com/blockreality",
                        "classnotfoundexception: com/blockreality"),
                List.of(
                        "模組 JAR（mpd.jar）損壞或不完整",
                        "api 與 fastdesign 模組版本不匹配（使用了分開的 JAR）",
                        "Forge 版本不相容（需要 47.4.13+）"
                ),
                List.of(
                        "檢查 mods/ 資料夾中是否只有一個完整的 mpd.jar，刪除舊版本",
                        "確認 Forge 版本為 1.20.1-47.4.13 或更高",
                        "重新執行 ./gradlew mergedJar 並將輸出的 mpd.jar 替換至 mods/ 資料夾"
                ),
                true
        ));

        r.add(new AnalysisRule("NPE-PFSF", Severity.HIGH,
                "PFSF 引擎空指針異常 — 物理求解器狀態異常",
                List.of("nullpointerexception", "npe",
                        "pfsf", "pfsfengine", "pfsfpipeline", "pfsfgridstore"),
                List.of(
                        "PFSF 引擎在未完全初始化時即開始處理物理事件",
                        "VulkanComputeContext 初始化失敗但上層邏輯未正確判斷 isAvailable()",
                        "Island 資料在崩塌動畫期間被提前釋放"
                ),
                List.of(
                        "確認 /br status 顯示 PFSF GPU: Available 後再測試",
                        "更新至最新版本（此問題已在後續修補）",
                        "若持續發生，請在 GitHub Issues 附上完整崩潰報告回報"
                ),
                true
        ));

        r.add(new AnalysisRule("SERVER-ONLY-CLIENT", Severity.HIGH,
                "伺服器載入 Client-Only 類別 — @OnlyIn 違規",
                List.of("onlyin", "dist.client", "clientsetup",
                        "cannot find class", "brvulkandevice",
                        "holographicdisplayrenderer", "brhudrenderer"),
                List.of(
                        "某個類別缺少 @OnlyIn(Dist.CLIENT) 標註，在專用伺服器上被錯誤載入",
                        "網路封包類別直接 import 了 client 套件下的類別",
                        "模組 JAR 中 fastdesign client 類別的 @OnlyIn 標註不完整"
                ),
                List.of(
                        "執行修復腳本：python L1-tooling/fix/fix_only_in_client.py",
                        "執行修復腳本：python L1-tooling/fix/fix_imports.py",
                        "確認所有 fastdesign/client/ 套件下的類別都有 @OnlyIn(Dist.CLIENT)",
                        "臨時解決：改用客戶端模式（./gradlew :fastdesign:runClient）而非專用伺服器"
                ),
                true
        ));

        // ══════════ MEDIUM ════════════════════════════════════════

        r.add(new AnalysisRule("MOD-CONFLICT", Severity.MEDIUM,
                "模組衝突 — 可能與第三方模組不相容",
                List.of("coremods", "mixinfailed", "mixin failed", "asmvisitor",
                        "conflicting", "duplicate mod", "already registered"),
                List.of(
                        "另一個模組與 Block Reality 的物理方塊或材料系統衝突",
                        "Mixin 應用失敗（常見於修改方塊物理的模組）",
                        "兩個版本的相同模組同時存在"
                ),
                List.of(
                        "以二分法移除模組找出衝突來源：先移除一半模組，確認無崩潰後再逐步加回",
                        "檢查 mods/ 資料夾是否有重複的模組（不同版本）",
                        "已知不相容模組：任何直接修改方塊 getDestroySpeed() 的模組",
                        "回報相容性問題至 GitHub Issues（附上完整模組清單）"
                ),
                false
        ));

        r.add(new AnalysisRule("VS2-BRIDGE-FAIL", Severity.MEDIUM,
                "Valkyrien Skies 2 橋接失敗",
                List.of("vs2", "valkyrienskies", "vs2shipbridge", "ivs2bridge"),
                List.of(
                        "VS2 版本與 Block Reality 橋接不相容",
                        "VS2 Ship 初始化在 Block Reality 讀取飛船資料之前完成"
                ),
                List.of(
                        "更新 Valkyrien Skies 2 至最新版本",
                        "確認 VS2 與 Block Reality 的相容版本表（見 GitHub README）",
                        "Block Reality 會自動使用 NoOpVS2Bridge 降級，飛船力學功能停用但不崩潰"
                ),
                true
        ));

        r.add(new AnalysisRule("CONCURRENT-MOD", Severity.MEDIUM,
                "並發修改異常 — 多執行緒物理更新衝突",
                List.of("concurrentmodificationexception", "concurrent modification"),
                List.of(
                        "物理執行緒與主線程同時修改結構資料",
                        "CollapseManager 或 StructureIslandRegistry 在無鎖狀態下被多執行緒存取"
                ),
                List.of(
                        "config/blockreality-common.toml: 設定 physics_thread_count = 1（停用多執行緒）以確認是否為執行緒問題",
                        "此為已知問題，正在修復中—請更新至最新版",
                        "暫時解決：降低 bfs_max_ms 減少每 tick 物理計算量"
                ),
                true
        ));

        r.add(new AnalysisRule("RESOURCE-LOAD-FAIL", Severity.MEDIUM,
                "資源載入失敗 — 材料定義或藍圖讀取錯誤",
                List.of("ioexception", "filenotfound", "resource not found", "shader not found",
                        "vanillamaterialmap", "blueprint"),
                List.of(
                        "模組 JAR 中的資源檔（材料定義、著色器）損壞或缺失",
                        "磁碟空間不足（無法解壓縮 JAR 資源）",
                        "防毒軟體隔離了必要資源檔案"
                ),
                List.of(
                        "重新下載完整的 mpd.jar 模組檔案",
                        "確認磁碟剩餘空間充足（建議 ≥ 2 GB）",
                        "暫時停用防毒軟體的即時掃描並重試",
                        "確認 .minecraft（或實例）資料夾路徑不含空格或非 ASCII 字元"
                ),
                true
        ));

        // ══════════ LOW ════════════════════════════════════════════

        r.add(new AnalysisRule("ASSERTION-FAIL", Severity.LOW,
                "斷言失敗 — 內部一致性檢查不通過",
                List.of("assertionerror", "assertion failed"),
                List.of(
                        "sigmaMax 正規化流程中出現零值或負值材料屬性",
                        "26 連通 Stencil 係數設定不一致（RBGS/Jacobi 不同步）",
                        "開發版本的調試斷言（生產版本通常不觸發）"
                ),
                List.of(
                        "如果使用開發版（-dev.jar），這是預期行為—改用正式版",
                        "確認所有自訂材料的抗壓強度 > 0 MPa"
                ),
                true
        ));

        return r;
    }

    // ─────────────────────────────────────────────────────────────────
    //  通用分析（規則庫未命中時）
    // ─────────────────────────────────────────────────────────────────

    private static AnalysisResult genericAnalysis(Throwable t, String throwableStr) {
        boolean hasBrFrames = throwableStr.contains("com.blockreality");
        Severity sev = hasBrFrames ? Severity.HIGH : Severity.UNKNOWN;

        List<String> causes = new ArrayList<>();
        List<String> fixes = new ArrayList<>();

        String className = t.getClass().getName();
        String simpleName = t.getClass().getSimpleName();

        // ── ExceptionInInitializerError 特化（規則庫未精確命中時的備援）──
        if (className.contains("ExceptionInInitializerError")) {
            sev = Severity.CRITICAL;
            Throwable rootCause = getRootCause(t);
            String rootName = rootCause.getClass().getSimpleName();
            String rootMsg  = rootCause.getMessage() != null ? rootCause.getMessage() : "(no message)";

            // 找出導致 EIIE 的靜態類別（stack trace 第一幀）
            String initClass = "(unknown class)";
            if (t.getStackTrace().length > 0) {
                initClass = t.getStackTrace()[0].getClassName();
                // 只保留類別簡短名稱
                int dot = initClass.lastIndexOf('.');
                if (dot >= 0) initClass = initClass.substring(dot + 1);
            }

            causes.add("靜態初始化失敗：" + initClass + " 的 static 區塊在載入時拋出了 " + rootName);
            causes.add("Root Cause: " + rootName + " — " + rootMsg);

            // 根據 root cause 類型給出更精準的修復
            String rcClass = rootCause.getClass().getName();
            if (rcClass.contains("UnsatisfiedLinkError")) {
                causes.add("Native 函式庫（VMA、Shaderc 或 Vulkan）不在 classpath 中");
                fixes.add("確認 build.gradle 的 afterEvaluate 區塊包含 -Xbootclasspath/a 設定");
                fixes.add("若使用啟動器（Prism/MultiMC）：Java 附加引數需加入 LWJGL native jar 路徑");
            } else if (rcClass.contains("ClassNotFoundException") || rcClass.contains("NoClassDefFoundError")) {
                causes.add("依賴類別在 classpath 不存在（模組 JAR 損壞或 Forge 版本不符）");
                fixes.add("重新執行 ./gradlew mergedJar 重建合併 JAR");
                fixes.add("確認 Forge 版本為 1.20.1-47.4.13+");
            } else if (rcClass.contains("NullPointerException")) {
                causes.add("Static 欄位相互依賴導致初始化順序錯誤（A 依賴 B，但 B 尚未初始化）");
                fixes.add("確認 Forge 版本 ≥ 47.4.13（舊版 ForgeConfigSpec API 有相容問題）");
                fixes.add("若出現在 NodeRegistry：確認父類 BRNode static PORT 欄位在子類之前初始化");
            } else if (rcClass.contains("IllegalArgumentException") || rcClass.contains("IllegalStateException")) {
                causes.add("重複向 Registry 註冊相同 ID，或提供了不合法的配置值");
                fixes.add("執行 python L1-tooling/fix/fix_registry.py 移除重複的節點註冊");
                fixes.add("檢查 config/blockreality-common.toml 中是否有超出範圍的配置值");
            } else {
                causes.add("Root Cause 類別為 " + rootName + "，請展開完整 Stack Trace 查看 Caused by 鏈");
                fixes.add("在完整版 Stack Trace 中找到最後一個 Caused by，那才是真正的錯誤點");
            }

            if (fixes.isEmpty()) {
                fixes.add("查看報告中的 Caused by 最末層，複製給 GitHub Issues");
            }
            fixes.add("回報至 Block Reality GitHub Issues，並附上此崩潰報告中的完整 Stack Trace");

            return new AnalysisResult(sev,
                    "靜態初始化失敗 (EIIE) — Root Cause: " + rootName + " in " + initClass,
                    causes, fixes, hasBrFrames);
        }

        // ── 其他通用分析 ──
        if (className.contains("OutOfMemoryError")) {
            sev = Severity.CRITICAL;
            causes.add("Java Heap 空間耗盡（非 VRAM）");
            fixes.add("在啟動器中增加 -Xmx 記憶體（建議至少 4GB：-Xmx4G）");
            fixes.add("關閉瀏覽器等其他高記憶體程式");
        } else if (className.contains("IncompatibleClassChangeError") || className.contains("NoSuchMethodError")) {
            sev = Severity.HIGH;
            causes.add("Forge 版本或依賴模組版本不匹配");
            fixes.add("確認使用 Minecraft Forge 1.20.1-47.4.13+");
        } else if (className.contains("NoSuchFieldError")) {
            sev = Severity.HIGH;
            causes.add("欄位在執行時不存在，通常為模組間二進制不相容（ABI mismatch）");
            causes.add("ForgeRegistries 欄位名稱在生產環境用 SRG 名稱，與 Mojmap 不同");
            fixes.add("確認使用 ResourceLocation 方式存取 ForgeRegistries（而非直接存取靜態欄位）");
            fixes.add("確認 Forge 版本與 Block Reality 建置時相同");
        }

        if (hasBrFrames && causes.isEmpty()) {
            causes.add("Block Reality 模組內部的未預期錯誤");
            causes.add("可能是邊緣情況觸發了未完全覆蓋的程式碼路徑");
        } else if (!hasBrFrames && causes.isEmpty()) {
            causes.add("此崩潰可能與 Block Reality 無關");
            causes.add("另一個模組或 Minecraft 本體引起的崩潰");
        }

        if (fixes.isEmpty()) {
            fixes.add("嘗試停用 Block Reality 確認崩潰是否消失");
            fixes.add("確認所有模組都是最新版本");
            fixes.add("回報至 Block Reality GitHub Issues 附上此崩潰報告");
        }

        return new AnalysisResult(
                sev,
                hasBrFrames
                        ? "未知的 Block Reality 內部錯誤 (" + simpleName + ")"
                        : "可能為第三方衝突 (" + simpleName + ")",
                causes,
                fixes,
                hasBrFrames
        );
    }

    // ─────────────────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────────────────

    /** 將整個 Throwable（含 cause chain）序列化為可搜尋的小寫字串。 */
    static String buildThrowableString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            sb.append(cur.getClass().getName()).append(" ");
            if (cur.getMessage() != null) sb.append(cur.getMessage()).append(" ");
            StackTraceElement[] frames = cur.getStackTrace();
            int limit = Math.min(frames.length, 30);
            for (int i = 0; i < limit; i++) {
                sb.append(frames[i].toString()).append(" ");
            }
            cur = cur.getCause();
            if (cur != null) sb.append("caused by ");
        }
        return sb.toString().toLowerCase();
    }

    private static AnalysisResult unknownResult() {
        return new AnalysisResult(
                Severity.UNKNOWN,
                "無 Throwable 資訊",
                List.of("無法分析原因"),
                List.of("請查看 crashreporter/ 資料夾中的完整日誌"),
                false
        );
    }

    /** 嚴重度的 emoji 指示器 */
    public static String severityIcon(Severity s) {
        return switch (s) {
            case CRITICAL -> "🔴 CRITICAL";
            case HIGH     -> "🟠 HIGH";
            case MEDIUM   -> "🟡 MEDIUM";
            case LOW      -> "🟢 LOW";
            case UNKNOWN  -> "⚪ UNKNOWN";
        };
    }
}
