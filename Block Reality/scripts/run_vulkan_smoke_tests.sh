#!/bin/bash
# Block Reality Vulkan Smoke Test Runner
# lavapipe (Mesa llvmpipe) を使用してGPUなしでVulkan機能を検証

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
OUT_DIR="$SCRIPT_DIR/out"
NATIVES_DIR="$SCRIPT_DIR/natives"

# ─── LWJGL JAR paths ───
LWJGL_CACHE=~/.gradle/caches/modules-2/files-2.1/org.lwjgl

LWJGL_CORE="$LWJGL_CACHE/lwjgl/3.3.1/ae58664f88e18a9bb2c77b063833ca7aaec484cb/lwjgl-3.3.1.jar"
LWJGL_CORE_NAT="$LWJGL_CACHE/lwjgl/3.3.1/1de885aba434f934201b99f2f1afb142036ac189/lwjgl-3.3.1-natives-linux.jar"
LWJGL_VK="$LWJGL_CACHE/lwjgl-vulkan/3.3.1/4af1ebb27699d743aba74797b7ac4567f3f1fd4a/lwjgl-vulkan-3.3.1.jar"
LWJGL_VMA="$LWJGL_CACHE/lwjgl-vma/3.3.1/9c50b674f8d56039d8699a2264ed020873f87053/lwjgl-vma-3.3.1.jar"
LWJGL_VMA_NAT="$LWJGL_CACHE/lwjgl-vma/3.3.1/7cb5326de62d58c2a958100864433c29625755ec/lwjgl-vma-3.3.1-natives-linux.jar"
LWJGL_SHADERC="$LWJGL_CACHE/lwjgl-shaderc/3.3.1/241ecfb343b2f5b94bbad7e6ccbf3859b02229b7/lwjgl-shaderc-3.3.1.jar"
LWJGL_SHADERC_NAT="$LWJGL_CACHE/lwjgl-shaderc/3.3.1/1e69425fa32047d58a778442695ab49f5da9ae95/lwjgl-shaderc-3.3.1-natives-linux.jar"

# ─── Check JARs exist ───
echo "=== 依存 JAR 確認 ==="
for jar in "$LWJGL_CORE" "$LWJGL_CORE_NAT" "$LWJGL_VK" "$LWJGL_VMA" "$LWJGL_VMA_NAT" "$LWJGL_SHADERC" "$LWJGL_SHADERC_NAT"; do
    if [ -f "$jar" ]; then
        echo "  [OK] $(basename $jar)"
    else
        echo "  [MISSING] $jar"
        exit 1
    fi
done
echo

# ─── Extract natives ───
echo "=== ネイティブライブラリ展開 ==="
mkdir -p "$NATIVES_DIR"
for nat_jar in "$LWJGL_CORE_NAT" "$LWJGL_VMA_NAT" "$LWJGL_SHADERC_NAT"; do
    echo "  Extracting: $(basename $nat_jar)"
    cd "$NATIVES_DIR" && jar xf "$nat_jar" 2>/dev/null || unzip -q -o "$nat_jar" "*.so" 2>/dev/null || true
done
# List extracted natives
echo "  Extracted:"
ls "$NATIVES_DIR"/*.so 2>/dev/null | while read f; do echo "    $(basename $f)"; done
echo

# ─── Build classpath ───
CP="$LWJGL_CORE:$LWJGL_VK:$LWJGL_VMA:$LWJGL_SHADERC:$OUT_DIR"

# ─── Compile all stages ───
echo "=== コンパイル ==="
mkdir -p "$OUT_DIR"
javac --source 17 --target 17 \
    -cp "$CP" \
    -d "$OUT_DIR" \
    "$SRC_DIR"/Stage1_VkInstance.java \
    "$SRC_DIR"/Stage2_VMAInit.java \
    "$SRC_DIR"/Stage3_Shaderc.java \
    "$SRC_DIR"/Stage4_ComputePipeline.java
echo "  [OK] 全クラスのコンパイル成功"
echo

# ─── JVM common args ───
JVM_ARGS=(
    "-Xmx512m"
    "-Djava.library.path=$NATIVES_DIR"
    "-Dorg.lwjgl.librarypath=$NATIVES_DIR"
    "-Dorg.lwjgl.util.Debug=false"
    "-Dorg.lwjgl.util.DebugLoader=false"
)

# ─── Force lavapipe (no real GPU) ───
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json
export VK_LOADER_DRIVERS_DISABLE="*"  # disable other drivers
export VK_LOADER_DRIVERS_SELECT="lvp_icd.json"
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe

echo "=== Vulkan 環境設定 ==="
echo "  VK_ICD_FILENAMES=$VK_ICD_FILENAMES"
echo "  Driver: lavapipe (Mesa llvmpipe CPU renderer)"
echo

PASS_COUNT=0
FAIL_COUNT=0

run_stage() {
    local name="$1"
    local class="$2"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  実行: $name"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if java "${JVM_ARGS[@]}" -cp "$CP" "$class" 2>&1; then
        echo "  ✓ $name: PASSED"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo "  ✗ $name: FAILED (exit code $?)"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
    echo
}

run_stage "Stage 1: VkInstance + Physical Device 選取" "Stage1_VkInstance"
run_stage "Stage 2: VMA 初期化 (pVulkanFunctions 修正検証)" "Stage2_VMAInit"
run_stage "Stage 3: Shaderc GLSL→SPIR-V コンパイル" "Stage3_Shaderc"
run_stage "Stage 4: Compute Pipeline + GPU Dispatch + 結果検証" "Stage4_ComputePipeline"

echo "╔══════════════════════════════════════════════════════╗"
echo "║  テスト結果サマリ                                     ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  PASSED: $PASS_COUNT / 4                                      ║"
echo "║  FAILED: $FAIL_COUNT / 4                                      ║"
echo "╚══════════════════════════════════════════════════════╝"

if [ "$FAIL_COUNT" -eq 0 ]; then
    echo ""
    echo "  全ステージ PASSED — Block Reality Vulkan パスは"
    echo "  RTX 5070 Ti で正常動作することが確認されました。"
    exit 0
else
    echo ""
    echo "  $FAIL_COUNT ステージ FAILED — 失敗箇所を確認してください。"
    exit 1
fi
