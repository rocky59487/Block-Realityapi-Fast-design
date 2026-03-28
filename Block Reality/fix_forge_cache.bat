@echo off
cd /d "%~dp0"
echo Project dir: %CD%
echo.

echo [Step 1] Stopping all Gradle daemons (release file locks)...
call gradlew.bat --stop
echo Done.

echo.
echo [Step 2] Deleting forge_gradle cache...
rmdir /s /q "%USERPROFILE%\.gradle\caches\forge_gradle"
if exist "%USERPROFILE%\.gradle\caches\forge_gradle" (
    echo WARNING: Could not delete cache folder. Try running as Administrator.
) else (
    echo Cache deleted.
)

echo.
echo [Step 3] Compiling...
call gradlew.bat --no-daemon --refresh-dependencies compileJava

pause
