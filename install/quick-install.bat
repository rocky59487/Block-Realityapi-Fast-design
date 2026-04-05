@echo off
chcp 65001 >nul 2>&1
:: Block Reality v0.1.0-alpha - Quick Install Launcher
:: This wrapper launches the PowerShell installer for full functionality.
:: If PowerShell is unavailable, falls back to basic batch install.

where powershell >nul 2>&1
if %errorLevel% equ 0 (
    powershell -ExecutionPolicy Bypass -File "%~dp0quick-install.ps1"
    exit /b %errorLevel%
)

echo [!] PowerShell not found. Running basic installer...
echo.

:: === Fallback: Basic Batch Installer ===
title Block Reality v0.1.0-alpha - Quick Install

echo ========================================
echo  Block Reality v0.1.0-alpha
echo  GPU Structural Physics for MC 1.20.1
echo ========================================
echo.

:: Check Java
echo [1/4] Checking Java 17...
java -version 2>&1 | findstr /i "17\." >nul 2>&1
if %errorLevel% neq 0 (
    echo [X] Java 17 not found!
    echo     Download: https://adoptium.net/
    pause
    exit /b 1
)
echo [OK] Java 17 found.
echo.

:: Locate .minecraft
echo [2/4] Locating .minecraft...
set "MC_DIR="
if exist "%APPDATA%\.minecraft" set "MC_DIR=%APPDATA%\.minecraft"
if "%MC_DIR%"=="" (
    echo [X] .minecraft not found!
    pause
    exit /b 1
)
echo [OK] Found: %MC_DIR%
echo.

:: Check Forge
echo [3/4] Checking Forge 1.20.1...
dir /b "%MC_DIR%\versions" 2>nul | findstr /i "1.20.1-forge" >nul 2>&1
if %errorLevel% neq 0 (
    echo [!] Forge 1.20.1 not detected.
    echo     Download: https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html
)
echo.

:: Find and copy JAR
echo [4/4] Installing mod...
set "JAR_FILE="
set "SD=%~dp0"
if exist "%SD%mpd.jar" set "JAR_FILE=%SD%mpd.jar"
if "%JAR_FILE%"=="" if exist "%SD%..\mpd.jar" set "JAR_FILE=%SD%..\mpd.jar"

if "%JAR_FILE%"=="" (
    echo [X] mpd.jar not found!
    pause
    exit /b 1
)

if not exist "%MC_DIR%\mods" mkdir "%MC_DIR%\mods"
del /q "%MC_DIR%\mods\mpd.jar" >nul 2>&1
copy /y "%JAR_FILE%" "%MC_DIR%\mods\mpd.jar" >nul
echo [OK] Installed to %MC_DIR%\mods\mpd.jar
echo.
echo Done! Launch Minecraft with Forge 1.20.1.
pause
