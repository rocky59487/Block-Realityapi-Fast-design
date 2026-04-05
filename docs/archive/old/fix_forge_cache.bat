@echo off
cd /d "%~dp0"
echo === ForgeGradle Cache Fix ===
echo Project dir: %CD%

rmdir /s /q "%USERPROFILE%\.gradle\caches\forge_gradle\bundeled_repo" 2>nul
rmdir /s /q "%USERPROFILE%\.gradle\caches\forge_gradle\fg_runs" 2>nul
echo Cache cleared.

echo Rebuilding...
call gradlew.bat --refresh-dependencies compileJava
pause
