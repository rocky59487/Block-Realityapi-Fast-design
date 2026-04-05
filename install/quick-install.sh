#!/usr/bin/env bash
# Block Reality v0.1.0-alpha — Quick Install (Linux/macOS)
set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "╔══════════════════════════════════════════════════════════╗"
echo "║     Block Reality v0.1.0-alpha — Quick Install          ║"
echo "║     GPU 結構物理模擬引擎 for Minecraft Forge 1.20.1      ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Step 1: Check Java 17 ──
echo "[1/5] 檢查 Java 17..."
if java -version 2>&1 | grep -q '17\.'; then
    echo -e "${GREEN}[OK]${NC} Java 17 已安裝"
else
    echo -e "${RED}[X]${NC} 未偵測到 Java 17！"
    echo "    請安裝: https://adoptium.net/"
    exit 1
fi
echo

# ── Step 2: Check Vulkan ──
echo "[2/5] 檢查 Vulkan 支援..."
if command -v vulkaninfo &>/dev/null; then
    VK_VER=$(vulkaninfo 2>/dev/null | grep -m1 "apiVersion" | awk '{print $NF}' || echo "unknown")
    echo -e "${GREEN}[OK]${NC} Vulkan 驅動已安裝 (${VK_VER})"
else
    echo -e "${YELLOW}[!]${NC} 未找到 vulkaninfo"
    echo "    Vulkan 驅動通常隨 GPU 驅動一起安裝。"
    echo
    echo "    Linux 安裝方式："
    echo "      NVIDIA: sudo apt install nvidia-driver-XXX (或你的發行版對應套件)"
    echo "      AMD:    sudo apt install mesa-vulkan-drivers"
    echo "      Intel:  sudo apt install intel-media-va-driver mesa-vulkan-drivers"
    echo
    echo "    macOS: MoltenVK (透過 Homebrew: brew install molten-vk)"
    echo
    echo "    需要 Vulkan 1.2+。無 Vulkan 時模組會降級為 CPU 模式。"
fi
echo

# ── Step 3: Locate .minecraft ──
echo "[3/5] 搜尋 Minecraft 安裝目錄..."
MC_DIR=""

if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    [[ -d "$HOME/Library/Application Support/minecraft" ]] && MC_DIR="$HOME/Library/Application Support/minecraft"
else
    # Linux
    [[ -d "$HOME/.minecraft" ]] && MC_DIR="$HOME/.minecraft"
fi

# PrismLauncher check
if [[ -d "$HOME/.local/share/PrismLauncher/instances" ]] || [[ -d "$HOME/Library/Application Support/PrismLauncher/instances" ]]; then
    echo -e "${YELLOW}[i]${NC} 偵測到 PrismLauncher，請手動複製 JAR 到對應 instance"
fi

if [[ -z "$MC_DIR" ]]; then
    echo -e "${RED}[X]${NC} 未找到 .minecraft 目錄！"
    echo "    請手動將 mpd.jar 複製到你的 Minecraft mods 資料夾"
    exit 1
fi

echo -e "${GREEN}[OK]${NC} 找到: $MC_DIR"
echo

# ── Step 4: Check Forge ──
echo "[4/5] 檢查 Forge 1.20.1..."
if ls "$MC_DIR/versions/" 2>/dev/null | grep -qi "1.20.1-forge"; then
    echo -e "${GREEN}[OK]${NC} Forge 1.20.1 已安裝"
else
    echo -e "${YELLOW}[!]${NC} 未偵測到 Forge 1.20.1"
    echo "    請先安裝: https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html"
    echo "    建議版本: 47.4.0 或更新"
    echo
    read -rp "    是否繼續安裝模組？(y/n): " CONTINUE
    [[ "$CONTINUE" != "y" ]] && exit 0
fi
echo

# ── Step 5: Copy mod ──
echo "[5/5] 安裝 Block Reality 模組..."
mkdir -p "$MC_DIR/mods"

JAR_FILE=""
[[ -f "$SCRIPT_DIR/mpd.jar" ]] && JAR_FILE="$SCRIPT_DIR/mpd.jar"
[[ -f "$SCRIPT_DIR/../mpd.jar" ]] && JAR_FILE="$SCRIPT_DIR/../mpd.jar"
[[ -f "$SCRIPT_DIR/build/mpd.jar" ]] && JAR_FILE="$SCRIPT_DIR/build/mpd.jar"

if [[ -z "$JAR_FILE" ]]; then
    echo -e "${RED}[X]${NC} 找不到 mpd.jar！"
    echo "    請確認 mpd.jar 與此腳本放在同一目錄"
    echo "    從原始碼建置: cd 'Block Reality' && ./gradlew mergedJar"
    exit 1
fi

# Remove old versions
rm -f "$MC_DIR/mods/mpd.jar" "$MC_DIR/mods/blockreality-"*.jar "$MC_DIR/mods/block-reality-"*.jar 2>/dev/null || true

cp "$JAR_FILE" "$MC_DIR/mods/mpd.jar"
echo -e "${GREEN}[OK]${NC} 已安裝 mpd.jar 到 $MC_DIR/mods/"
echo

echo "╔══════════════════════════════════════════════════════════╗"
echo "║                   安裝完成！                             ║"
echo "║                                                          ║"
echo "║  啟動: Minecraft Launcher → Forge 1.20.1 → Play         ║"
echo "║  首次啟動 Vulkan shader 編譯可能需要數秒               ║"
echo "║  建議記憶體: -Xmx4G                                    ║"
echo "╚══════════════════════════════════════════════════════════╝"
