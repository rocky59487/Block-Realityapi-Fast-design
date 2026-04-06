@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul 2>&1
:: Block Reality v0.1.0-alpha - Quick Install Launcher
:: This wrapper launches the PowerShell installer for full functionality.
:: If PowerShell is unavailable, falls back to basic batch install.

where powershell >nul 2>&1
if %errorLevel% equ 0 (
    powershell -ExecutionPolicy Bypass -File "%~dp0quick-install.ps1"
    if %errorLevel% neq 0 (
        echo.
        echo [X] Installer exited with error code %errorLevel%
        pause
    )
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

:: Check Java 17 — search PATH, JAVA_HOME, and common vendor directories
echo [1/4] Checking Java 17...
set "JAVA_EXE="

:: 1. PATH
java -version 2>&1 | findstr /i "17\." >nul 2>&1
if %errorLevel% equ 0 (
    set "JAVA_EXE=java"
    goto :java_found
)

:: 2. JAVA_HOME
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /i "17\." >nul 2>&1
        if !errorLevel! equ 0 (
            set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
            set "PATH=%JAVA_HOME%\bin;%PATH%"
            goto :java_found
        )
    )
)

:: 3. Common vendor directories under Program Files
for %%B in ("%ProgramFiles%" "%ProgramFiles(x86)%") do (
    for %%D in (
        "Eclipse Adoptium" "Microsoft" "Java" "Amazon Corretto"
        "BellSoft" "Zulu" "OpenLogic" "SapMachine" "GraalVM"
    ) do (
        if exist "%%~B\%%~D\" (
            for /d %%J in ("%%~B\%%~D\*17*") do (
                if exist "%%J\bin\java.exe" (
                    "%%J\bin\java.exe" -version 2>&1 | findstr /i "17\." >nul 2>&1
                    if !errorLevel! equ 0 (
                        set "JAVA_EXE=%%J\bin\java.exe"
                        set "JAVA_HOME=%%J"
                        set "PATH=%%J\bin;%PATH%"
                        goto :java_found
                    )
                )
            )
        )
    )
)

:: 4. Direct glob under Program Files
for /d %%J in ("%ProgramFiles%\*jdk*17*" "%ProgramFiles%\*jre*17*" "%ProgramFiles%\*java*17*") do (
    if exist "%%J\bin\java.exe" (
        "%%J\bin\java.exe" -version 2>&1 | findstr /i "17\." >nul 2>&1
        if !errorLevel! equ 0 (
            set "JAVA_EXE=%%J\bin\java.exe"
            set "JAVA_HOME=%%J"
            set "PATH=%%J\bin;%PATH%"
            goto :java_found
        )
    )
)

echo [X] Java 17 not found!
echo     Checked: PATH, JAVA_HOME, Program Files (Adoptium/Microsoft/Corretto/Zulu/BellSoft)
echo     Download: https://adoptium.net/
pause
exit /b 1

:java_found
echo [OK] Java 17 found: %JAVA_EXE%
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
