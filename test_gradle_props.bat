@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" x64
set VULKAN_SDK=C:\VulkanSDK\1.4.341.1
cd "Block Reality"
echo Running tests with native defaults from gradle.properties...
call gradlew.bat :api:test --tests com.blockreality.api.physics.pfsf.GoldenParityTest
