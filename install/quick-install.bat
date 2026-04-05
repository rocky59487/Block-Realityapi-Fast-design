@echo off
chcp 65001 >nul 2>&1
title Block Reality v0.1.0-alpha — Quick Install
color 0F

echo ╔══════════════════════════════════════════════════════════╗
echo ║     Block Reality v0.1.0-alpha — Quick Install          ║
echo ║     GPU 結構物理模擬引擎 for Minecraft Forge 1.20.1      ║
echo ╚══════════════════════════════════════════════════════════╝
echo.

:: ── Step 0: Check admin ──
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [!] 建議以系統管理員身分執行以取得完整權限
    echo.
)

:: ── Step 1: Check Java 17 ──
echo [1/5] 檢查 Java 17...
java -version 2>nul | findstr /i "17\." >nul 2>&1
if %errorLevel% neq 0 (
    echo [X] 未偵測到 Java 17！
    echo     請安裝 Eclipse Temurin 17: https://adoptium.net/
    echo     或 Oracle JDK 17: https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html
    echo.
    pause
    exit /b 1
)
echo [OK] Java 17 已安裝
echo.

:: ── Step 2: Check Vulkan support ──
echo [2/5] 檢查 Vulkan 支援...
where vulkaninfo >nul 2>&1
if %errorLevel% neq 0 (
    echo [!] 未找到 vulkaninfo 工具（不影響安裝，但建議確認 GPU 驅動已更新）
    echo.
    echo     Vulkan 驅動通常隨 GPU 驅動一起安裝：
    echo       NVIDIA: https://www.nvidia.com/drivers
    echo       AMD:    https://www.amd.com/en/support
    echo       Intel:  https://www.intel.com/content/www/us/en/download-center
    echo.
    echo     注意：Block Reality 需要 Vulkan 1.2+ 支援
    echo           - NVIDIA GTX 10xx 以上 ^(Pascal+^)
    echo           - AMD RX 400 以上 ^(Polaris+^)
    echo           - Intel UHD 600 以上 ^(Gen 9+^)
    echo.
    echo     如果沒有 Vulkan 支援，模組會自動降級為 CPU 模式（無 RT/SDF 渲染）
    echo.
) else (
    echo [OK] Vulkan 驅動已安裝
    echo.
)

:: ── Step 3: Locate .minecraft ──
echo [3/5] 搜尋 Minecraft 安裝目錄...

set "MC_DIR="

:: Check common locations
if exist "%APPDATA%\.minecraft" (
    set "MC_DIR=%APPDATA%\.minecraft"
)

:: PrismLauncher
if exist "%APPDATA%\PrismLauncher\instances" (
    echo     [i] 偵測到 PrismLauncher，請手動複製 JAR 到對應 instance 的 .minecraft\mods\
)

if "%MC_DIR%"=="" (
    echo [X] 未找到 .minecraft 目錄！
    echo     請手動將 mpd.jar 複製到你的 Minecraft mods 資料夾
    pause
    exit /b 1
)

echo [OK] 找到: %MC_DIR%
echo.

:: ── Step 4: Check Forge ──
echo [4/5] 檢查 Forge 1.20.1...

if not exist "%MC_DIR%\versions" (
    echo [X] 未找到 versions 資料夾
    goto :install_forge
)

dir /b "%MC_DIR%\versions" 2>nul | findstr /i "1.20.1-forge" >nul 2>&1
if %errorLevel% neq 0 (
    goto :install_forge
) else (
    echo [OK] Forge 1.20.1 已安裝
    echo.
    goto :copy_mod
)

:install_forge
echo [!] 未偵測到 Forge 1.20.1！
echo.
echo     請先安裝 Minecraft Forge 1.20.1:
echo     https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html
echo.
echo     建議版本: 47.4.0 或更新
echo.
set /p "CONTINUE=是否繼續安裝模組？(y/n): "
if /i not "%CONTINUE%"=="y" (
    exit /b 0
)
echo.

:: ── Step 5: Copy mod ──
:copy_mod
echo [5/5] 安裝 Block Reality 模組...

:: Create mods folder if needed
if not exist "%MC_DIR%\mods" (
    mkdir "%MC_DIR%\mods"
    echo     建立 mods 資料夾: %MC_DIR%\mods
)

:: Find mpd.jar
set "JAR_FILE="
set "SCRIPT_DIR=%~dp0"

:: Check relative paths
if exist "%SCRIPT_DIR%mpd.jar" set "JAR_FILE=%SCRIPT_DIR%mpd.jar"
if exist "%SCRIPT_DIR%..\mpd.jar" set "JAR_FILE=%SCRIPT_DIR%..\mpd.jar"
if exist "%SCRIPT_DIR%build\mpd.jar" set "JAR_FILE=%SCRIPT_DIR%build\mpd.jar"

if "%JAR_FILE%"=="" (
    echo [X] 找不到 mpd.jar！
    echo     請確認 mpd.jar 與此腳本放在同一目錄，或放在上層目錄
    echo.
    echo     如果你需要從原始碼建置：
    echo       cd "Block Reality"
    echo       gradlew.bat mergedJar
    echo.
    pause
    exit /b 1
)

:: Remove old versions
del /q "%MC_DIR%\mods\mpd.jar" >nul 2>&1
del /q "%MC_DIR%\mods\blockreality-*.jar" >nul 2>&1
del /q "%MC_DIR%\mods\block-reality-*.jar" >nul 2>&1

:: Copy new jar
copy /y "%JAR_FILE%" "%MC_DIR%\mods\mpd.jar" >nul
if %errorLevel% neq 0 (
    echo [X] 複製失敗！請確認 Minecraft 未在執行中
    pause
    exit /b 1
)

echo [OK] 已安裝 mpd.jar 到 %MC_DIR%\mods\
echo.

:: ── Done ──
echo ╔══════════════════════════════════════════════════════════╗
echo ║                   安裝完成！                             ║
echo ╠══════════════════════════════════════════════════════════╣
echo ║                                                          ║
echo ║  啟動方式:                                               ║
echo ║  1. 開啟 Minecraft Launcher                              ║
echo ║  2. 選擇 Forge 1.20.1 設定檔                            ║
echo ║  3. 啟動遊戲                                             ║
echo ║                                                          ║
echo ║  首次啟動注意:                                           ║
echo ║  - Vulkan 初始化可能需要數秒（編譯 compute shaders）    ║
echo ║  - 如果沒有 Vulkan GPU，模組會自動降級為 CPU 模式       ║
echo ║  - 記憶體建議: 至少分配 4GB（-Xmx4G）                  ║
echo ║                                                          ║
echo ╚══════════════════════════════════════════════════════════╝
echo.
pause
