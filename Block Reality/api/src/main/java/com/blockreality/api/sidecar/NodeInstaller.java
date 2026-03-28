package com.blockreality.api.sidecar;

import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * NodeInstaller — 自動處理 Node.js 與 sidecar.js 腳本的發行問題。
 * 提供無縫的開箱即用體驗。
 */
public class NodeInstaller {
    private static final Logger LOGGER = LogManager.getLogger("BR-NodeInstaller");

    // 固定的 Node.js LTS 版本
    private static final String NODE_VERSION = "v20.12.2";
    
    /**
     * 第一步：解壓縮內建的 sidecar.js
     * 若磁碟內無對應的腳本，則從 JAR 資源檔複製過去。
     */
    public static Path ensureSidecarScriptExists() throws Exception {
        Path scriptDir = FMLPaths.GAMEDIR.get()
                .resolve("blockreality")
                .resolve("sidecar")
                .resolve("dist");
        
        Files.createDirectories(scriptDir);
        Path scriptPath = scriptDir.resolve("sidecar.js");

        // 若腳本不存在或我們想強制覆蓋最新版
        if (!Files.exists(scriptPath)) {
            LOGGER.info("[NodeInstaller] 正在從 JAR 擷取 sidecar.js 到 {}", scriptPath);
            try (InputStream in = NodeInstaller.class.getResourceAsStream("/assets/blockreality/sidecar/sidecar.js")) {
                if (in == null) {
                    LOGGER.warn("[NodeInstaller] 找不到 JAR 內的 /assets/blockreality/sidecar/sidecar.js，將退回要求使用者手動放置模式。");
                } else {
                    Files.copy(in, scriptPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return scriptPath;
    }

    /**
     * 第二步：取得或下載合適的 Node.js 環境。
     * @return 可執行的 Node.js 路徑，或 null 若無法下載需退回系統 PATH
     */
    public static CompletableFuture<String> ensureNodeJsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 先檢查是否有系統級的 Node.js 存在 (透過 process 確認)
                if (checkSystemNode()) {
                    LOGGER.info("[NodeInstaller] 偵測到系統已有 Node.js，將使用系統版本。");
                    return getSystemNodeCommand();
                }

                // 2. 若系統無 Node.js，準備在模組目錄下安裝 portable 版本
                Path runtimeDir = FMLPaths.GAMEDIR.get()
                        .resolve("blockreality")
                        .resolve("runtime")
                        .resolve("node");
                Files.createDirectories(runtimeDir);

                String osName = System.getProperty("os.name").toLowerCase();
                if (osName.contains("win")) {
                    return installWindowsNode(runtimeDir);
                } else {
                    LOGGER.warn("[NodeInstaller] MacOS/Linux 目前必須依賴系統 PATH 中的 Node.js，請自行安裝。");
                    return "node";
                }
            } catch (Exception e) {
                LOGGER.error("[NodeInstaller] 自動擷取 Node.js 失敗，將降級使用系統版本。", e);
                return getSystemNodeCommand();
            }
        });
    }

    private static boolean checkSystemNode() {
        try {
            Process process = new ProcessBuilder(getSystemNodeCommand(), "-v").start();
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getSystemNodeCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "node.exe" : "node";
    }

    private static String installWindowsNode(Path runtimeDir) throws Exception {
        Path exactExe = runtimeDir.resolve("node.exe");
        if (Files.exists(exactExe) && Files.size(exactExe) > 10_000_000) {
            LOGGER.info("[NodeInstaller] 已找到 Portable Node.js: {}", exactExe);
            return exactExe.toAbsolutePath().toString();
        }

        // 下載完整的 node.exe (Windows 取巧：官方直接提供 raw .exe 不需解壓 ZIP)
        String downloadUrl = "https://nodejs.org/dist/" + NODE_VERSION + "/win-x64/node.exe";
        LOGGER.info("[NodeInstaller] 開始下載 Portable Node.js (約 30MB) 從: {}", downloadUrl);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .GET()
                .build();

        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(exactExe));
        if (response.statusCode() == 200) {
            LOGGER.info("[NodeInstaller] Node.js 下載完成！儲存於: {}", exactExe);
            return exactExe.toAbsolutePath().toString();
        } else {
            Files.deleteIfExists(exactExe);
            throw new RuntimeException("下載失敗，HTTP 狀態碼: " + response.statusCode());
        }
    }
}
