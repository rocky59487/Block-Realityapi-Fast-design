@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" x64
set VULKAN_SDK=C:\VulkanSDK\1.4.341.1
cd "Block Reality"
echo stop > stop.txt
echo Running Forge Server for headless integration testing...
call gradlew.bat :fastdesign:runServer < stop.txt
