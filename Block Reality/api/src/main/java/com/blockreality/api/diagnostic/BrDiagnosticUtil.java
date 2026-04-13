package com.blockreality.api.diagnostic;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 收集崩潰報告所需的所有靜態診斷資訊。
 *
 * <p>本類別為純靜態工具類，所有方法皆進行防禦性包裝，
 * 確保在不穩定環境（崩潰中）下也能安全執行、不再拋出例外。
 */
public final class BrDiagnosticUtil {

    private BrDiagnosticUtil() {}

    // ─────────────────────────────────────────────────────────────────
    //  1. JVM / 作業系統資訊
    // ─────────────────────────────────────────────────────────────────

    public static Map<String, String> collectSystemInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        try {
            RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

            info.put("OS", System.getProperty("os.name", "?")
                    + " " + System.getProperty("os.version", "")
                    + " (" + System.getProperty("os.arch", "?") + ")");
            info.put("Java", System.getProperty("java.version", "?")
                    + " — " + System.getProperty("java.vendor", "?"));
            info.put("JVM Uptime", formatMs(rt.getUptime()));
            info.put("CPU Cores", String.valueOf(os.getAvailableProcessors()));
            info.put("Heap Used", formatBytes(mem.getHeapMemoryUsage().getUsed()));
            info.put("Heap Max",  formatBytes(mem.getHeapMemoryUsage().getMax()));
            info.put("Non-Heap Used", formatBytes(mem.getNonHeapMemoryUsage().getUsed()));

            // 嘗試取得系統實體記憶體（若有 com.sun 擴充）
            try {
                java.lang.reflect.Method totalMem = os.getClass().getMethod("getTotalMemorySize");
                totalMem.setAccessible(true);
                long total = (long) totalMem.invoke(os);
                java.lang.reflect.Method freeMem = os.getClass().getMethod("getFreeMemorySize");
                freeMem.setAccessible(true);
                long free = (long) freeMem.invoke(os);
                info.put("System RAM", formatBytes(total - free) + " used / " + formatBytes(total) + " total");
            } catch (Throwable ignored) {}

        } catch (Throwable e) {
            info.put("System Info Error", e.getMessage());
        }
        return info;
    }

    // ─────────────────────────────────────────────────────────────────
    //  2. Vulkan / PFSF 引擎狀態
    // ─────────────────────────────────────────────────────────────────

    public static Map<String, String> collectVulkanInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        try {
            // ★ EIIE-fix: PFSFEngine / VulkanComputeContext 可能因 ExceptionInInitializerError
            //   而處於毀損狀態（NoClassDefFoundError）。每個欄位獨立 catch，確保部分失敗不影響整體。

            boolean pfsfAvail = false;
            try { pfsfAvail = PFSFEngine.isAvailable(); }
            catch (NoClassDefFoundError e) { info.put("PFSF Status Error", "NoClassDefFoundError: " + e.getMessage()); }
            catch (Throwable e)            { info.put("PFSF Status Error", e.getClass().getSimpleName()); }

            info.put("PFSF Status", pfsfAvail ? "✅ READY" : "❌ UNAVAILABLE (CPU fallback)");

            try { info.put("PFSF Config", BRConfig.isPFSFEnabled() ? "Enabled" : "Disabled (manual)"); }
            catch (Throwable e) { info.put("PFSF Config", "Error: " + e.getClass().getSimpleName()); }

            if (pfsfAvail) {
                try { info.put("GPU Device",    VulkanComputeContext.getDeviceName()); }
                catch (Throwable e) { info.put("GPU Device", "Error"); }
                try { info.put("VRAM Pressure", String.format("%.1f%%", VulkanComputeContext.getVramPressure() * 100)); }
                catch (Throwable e) { info.put("VRAM Pressure", "Error"); }
                try { info.put("VRAM Used",     formatBytes(VulkanComputeContext.getTotalVramUsage())); }
                catch (Throwable e) { info.put("VRAM Used", "Error"); }
                try { info.put("VRAM Free",     formatBytes(VulkanComputeContext.getVramFreeMemory())); }
                catch (Throwable e) { info.put("VRAM Free", "Error"); }

                try {
                    info.put("VRAM PFSF Partition",  formatBytes(VulkanComputeContext.getPartitionUsage(VulkanComputeContext.PARTITION_PFSF)));
                    info.put("VRAM Fluid Partition", formatBytes(VulkanComputeContext.getPartitionUsage(VulkanComputeContext.PARTITION_FLUID)));
                    info.put("VRAM Other Partition", formatBytes(VulkanComputeContext.getPartitionUsage(VulkanComputeContext.PARTITION_OTHER)));
                } catch (Throwable e) { info.put("VRAM Partitions", "Error: " + e.getClass().getSimpleName()); }
            }

            // 模組功能開關狀態（逐一 catch，避免任一設定讀取失敗拖垮整個區塊）
            safeGet(info, "Physics Enabled",    () -> BRConfig.isPhysicsEnabled()  ? "Yes" : "No");
            safeGet(info, "PCG Solver",         () -> BRConfig.isPFSFPCGEnabled()  ? "Yes" : "No");
            safeGet(info, "Fluid Sim",          () -> BRConfig.isFluidEnabled()    ? "Yes" : "No");
            safeGet(info, "Thermal Sim",        () -> BRConfig.isThermalEnabled()  ? "Yes" : "No");
            safeGet(info, "Wind Sim",           () -> BRConfig.isWindEnabled()     ? "Yes" : "No");
            safeGet(info, "EM Sim",             () -> BRConfig.isEmEnabled()       ? "Yes" : "No");
            safeGet(info, "Overturning Physics",() -> BRConfig.isOverturningEnabled() ? "Yes" : "No");
            safeGet(info, "PFSF Tick Budget",   () -> BRConfig.getPFSFTickBudgetMs() + " ms");
            safeGet(info, "Max Island Size",    () -> String.format("%,d blocks", BRConfig.getPFSFMaxIslandSize()));

        } catch (Throwable e) {
            info.put("Vulkan Info Error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return info;
    }

    /** 安全取值輔助：任何例外（含 NoClassDefFoundError）都記錄為 Error 而非拋出 */
    private static void safeGet(Map<String, String> map, String key, java.util.concurrent.Callable<String> getter) {
        try {
            map.put(key, getter.call());
        } catch (Throwable e) {
            map.put(key, "Error: " + e.getClass().getSimpleName());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  3. 所有已載入的 Forge 模組
    // ─────────────────────────────────────────────────────────────────

    public record ModEntry(String modId, String version, boolean isBlockReality) {}

    public static List<ModEntry> collectModList() {
        try {
            return ModList.get().getMods().stream()
                    .map(info -> new ModEntry(
                            info.getModId(),
                            info.getVersion().toString(),
                            info.getModId().startsWith("blockreality") || info.getModId().startsWith("fastdesign")
                    ))
                    .sorted((a, b) -> {
                        // Block Reality 模組排最前面
                        if (a.isBlockReality() != b.isBlockReality())
                            return a.isBlockReality() ? -1 : 1;
                        return a.modId().compareToIgnoreCase(b.modId());
                    })
                    .collect(Collectors.toList());
        } catch (Throwable e) {
            return List.of(new ModEntry("(error collecting mods)", e.getMessage(), false));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  4. 格式化工具
    // ─────────────────────────────────────────────────────────────────

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String formatMs(long ms) {
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format("%.1f s", ms / 1000.0);
        return String.format("%d m %d s", ms / 60_000, (ms % 60_000) / 1000);
    }
}
