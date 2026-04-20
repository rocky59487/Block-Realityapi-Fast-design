@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" x64
set VULKAN_SDK=C:\VulkanSDK\1.4.341.1
cd "Block Reality"
echo Starting Gradle Native Build and Parity Test...
call gradlew.bat :api:nativeBuild :api:test --tests com.blockreality.api.physics.pfsf.GoldenParityTest "-Pblockreality.native.build=true" "-Pblockreality.native.shaders=true" "-Dpfsf.native.required=true"
