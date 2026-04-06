#!/usr/bin/env bash
# Block Reality v0.1.0-alpha - Smart Installer (Linux/macOS)
# Auto-detects Java 17, Forge, and installs the mod.
set -euo pipefail

# ── Colors ──
G='\033[0;32m'  # Green
R='\033[0;31m'  # Red
Y='\033[1;33m'  # Yellow
C='\033[0;36m'  # Cyan
W='\033[1;37m'  # White
D='\033[0;90m'  # Dim
N='\033[0m'     # Reset

step()  { echo -e "${C}[$1]${N} $2"; }
ok()    { echo -e "  ${G}[OK]${N} $1"; }
warn()  { echo -e "  ${Y}[!]${N}  $1"; }
fail()  { echo -e "  ${R}[X]${N}  $1"; }
info()  { echo -e "  ${D}     $1${N}"; }
build() { echo -e "  ${C}>>${N}   $1"; }

ask_yn() {
    read -rp "       $1 (y/n): " reply
    [[ "$reply" == "y" || "$reply" == "Y" ]]
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Banner ──
echo ""
echo -e "  ${C}================================================${N}"
echo -e "  ${W} Block Reality v0.1.0-alpha - Smart Installer${N}"
echo -e "  ${D} GPU Structural Physics for Minecraft Forge 1.20.1${N}"
echo -e "  ${D} PFSF Engine + SDF Ray Marching${N}"
echo -e "  ${C}================================================${N}"
echo ""

# ============================================================
#  Java 17 detection — multi-source search
# ============================================================
# Returns the path to a java binary that reports version 17, or empty string.
find_java17() {
    local j

    # Helper: test if a binary is Java 17
    _is_java17() {
        [[ -x "$1" ]] && "$1" -version 2>&1 | grep -q '17\.'
    }

    # 1. java already in PATH
    if command -v java &>/dev/null && _is_java17 "$(command -v java)"; then
        echo "$(command -v java)"; return 0
    fi

    # 2. JAVA_HOME
    if [[ -n "${JAVA_HOME:-}" ]] && _is_java17 "$JAVA_HOME/bin/java"; then
        echo "$JAVA_HOME/bin/java"; return 0
    fi

    # 3. update-alternatives (Debian/Ubuntu/RHEL)
    if command -v update-alternatives &>/dev/null; then
        local alt
        alt=$(update-alternatives --list java 2>/dev/null | grep '17' | head -1)
        if [[ -n "$alt" ]] && _is_java17 "$alt"; then
            echo "$alt"; return 0
        fi
    fi

    # 4. /usr/lib/jvm  (Linux JVM directory)
    if [[ -d /usr/lib/jvm ]]; then
        for j in /usr/lib/jvm/java-17*/bin/java \
                 /usr/lib/jvm/jdk-17*/bin/java \
                 /usr/lib/jvm/temurin-17*/bin/java \
                 /usr/lib/jvm/zulu-17*/bin/java \
                 /usr/lib/jvm/liberica-17*/bin/java; do
            [[ -x "$j" ]] && _is_java17 "$j" && { echo "$j"; return 0; }
        done
        # Fallback: scan any JVM with "17" in name
        for d in /usr/lib/jvm/*/; do
            j="${d}bin/java"
            _is_java17 "$j" && { echo "$j"; return 0; }
        done
    fi

    # 5. macOS: /Library/Java/JavaVirtualMachines
    if [[ -d /Library/Java/JavaVirtualMachines ]]; then
        for d in /Library/Java/JavaVirtualMachines/*/Contents/Home; do
            j="$d/bin/java"
            _is_java17 "$j" && { echo "$j"; return 0; }
        done
    fi

    # 6. macOS Homebrew (Intel + Apple Silicon)
    for j in /usr/local/opt/openjdk@17/bin/java \
             /opt/homebrew/opt/openjdk@17/bin/java \
             /opt/homebrew/opt/temurin@17/bin/java; do
        _is_java17 "$j" && { echo "$j"; return 0; }
    done

    # 7. SDKMan
    if [[ -d "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java" ]]; then
        local sdk_base="${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java"
        for d in "$sdk_base"/17*/; do
            j="$d/bin/java"
            _is_java17 "$j" && { echo "$j"; return 0; }
        done
    fi

    # 8. ASDF
    if [[ -d "${ASDF_DIR:-$HOME/.asdf}/installs/java" ]]; then
        for d in "${ASDF_DIR:-$HOME/.asdf}"/installs/java/*17*/; do
            j="$d/bin/java"
            _is_java17 "$j" && { echo "$j"; return 0; }
        done
    fi

    # 9. Minecraft Launcher bundled JRE (Linux)
    for d in "$HOME/.minecraft/runtime"/*/ \
             "$HOME/.local/share/multimc/jars" \
             "$HOME/.local/share/PrismLauncher/java"/*; do
        j="$d/bin/java"
        _is_java17 "$j" && { echo "$j"; return 0; }
    done

    echo ""; return 1
}

# ============================================================
#  [1/5] Java 17
# ============================================================
step "1/5" "Java 17 ..."

JAVA_EXE="$(find_java17)"

if [[ -n "$JAVA_EXE" ]]; then
    JAVA_BIN_DIR="$(dirname "$JAVA_EXE")"
    export JAVA_HOME="$(dirname "$JAVA_BIN_DIR")"
    # Prepend to PATH so Gradle and Forge installer pick this java
    export PATH="$JAVA_BIN_DIR:$PATH"
    ok "Java 17 detected: $JAVA_EXE"
else
    fail "Java 17 not found"

    # Detect OS and offer install
    if [[ "$OSTYPE" == "linux"* ]]; then
        if command -v apt &>/dev/null; then
            if ask_yn "Auto-install via apt? (sudo required)"; then
                build "Installing temurin-17-jdk ..."
                sudo apt update -qq
                sudo apt install -y temurin-17-jdk 2>/dev/null || {
                    build "Adding Adoptium repository ..."
                    sudo mkdir -p /etc/apt/keyrings
                    wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /etc/apt/keyrings/adoptium.asc >/dev/null
                    echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
                    sudo apt update -qq
                    sudo apt install -y temurin-17-jdk
                }
                JAVA_EXE="$(find_java17)"
                if [[ -n "$JAVA_EXE" ]]; then
                    export JAVA_HOME="$(dirname "$(dirname "$JAVA_EXE")")"
                    export PATH="$(dirname "$JAVA_EXE"):$PATH"
                    ok "Java 17 installed: $JAVA_EXE"
                else
                    ok "Java 17 installed (restart terminal if PATH not updated)"
                fi
            else
                info "Manual install: https://adoptium.net/"
                exit 1
            fi
        elif command -v pacman &>/dev/null; then
            if ask_yn "Auto-install via pacman? (sudo required)"; then
                sudo pacman -S --noconfirm jdk17-openjdk
                ok "Java 17 installed"
            else
                info "Manual install: sudo pacman -S jdk17-openjdk"
                exit 1
            fi
        else
            info "Manual install: https://adoptium.net/"
            exit 1
        fi
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        if command -v brew &>/dev/null; then
            if ask_yn "Auto-install via Homebrew?"; then
                build "brew install --cask temurin@17 ..."
                brew install --cask temurin@17
                JAVA_EXE="$(find_java17)"
                if [[ -n "$JAVA_EXE" ]]; then
                    export JAVA_HOME="$(dirname "$(dirname "$JAVA_EXE")")"
                    export PATH="$(dirname "$JAVA_EXE"):$PATH"
                    ok "Java 17 installed: $JAVA_EXE"
                else
                    ok "Java 17 installed (restart terminal if PATH not updated)"
                fi
            else
                info "Manual: brew install --cask temurin@17"
                exit 1
            fi
        else
            info "Install Homebrew first: https://brew.sh"
            info "Then: brew install --cask temurin@17"
            exit 1
        fi
    fi
fi
echo ""

# ============================================================
#  [2/5] Vulkan
# ============================================================
step "2/5" "Vulkan driver ..."

if command -v vulkaninfo &>/dev/null; then
    vk_ver=$(vulkaninfo 2>/dev/null | grep -m1 "apiVersion" | awk '{print $NF}' || echo "unknown")
    ok "Vulkan driver detected ($vk_ver)"
else
    warn "vulkaninfo not found (non-blocking)"
    if [[ "$OSTYPE" == "linux"* ]]; then
        info "Install Vulkan drivers:"
        info "  NVIDIA: sudo apt install nvidia-driver-xxx"
        info "  AMD:    sudo apt install mesa-vulkan-drivers"
        info "  Intel:  sudo apt install intel-media-va-driver mesa-vulkan-drivers"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        info "macOS: MoltenVK (brew install molten-vk)"
    fi
    info "Requires Vulkan 1.2+ (GTX 10xx / RX 400 / UHD 600+)"
    info "Without Vulkan the mod degrades gracefully"
fi
echo ""

# ============================================================
#  [3/5] Locate .minecraft
# ============================================================
step "3/5" "Minecraft directory ..."

MC_DIR=""
if [[ "$OSTYPE" == "darwin"* ]]; then
    [[ -d "$HOME/Library/Application Support/minecraft" ]] && MC_DIR="$HOME/Library/Application Support/minecraft"
else
    [[ -d "$HOME/.minecraft" ]] && MC_DIR="$HOME/.minecraft"
fi

# PrismLauncher
for p in "$HOME/.local/share/PrismLauncher/instances" "$HOME/Library/Application Support/PrismLauncher/instances"; do
    [[ -d "$p" ]] && info "PrismLauncher detected - copy mpd.jar to instance mods/ manually"
done

if [[ -z "$MC_DIR" ]]; then
    fail ".minecraft not found!"
    info "Copy mpd.jar to your Minecraft mods folder manually."
    exit 1
fi
ok "Found: $MC_DIR"
echo ""

# ============================================================
#  [4/5] Forge 1.20.1
# ============================================================
step "4/5" "Forge 1.20.1 ..."

forge_ok=false
if [[ -d "$MC_DIR/versions" ]]; then
    if ls "$MC_DIR/versions/" 2>/dev/null | grep -qi "1.20.1.*forge"; then
        forge_ok=true
    fi
fi

if $forge_ok; then
    ok "Forge 1.20.1 detected"
else
    warn "Forge 1.20.1 not found"
    info "Download: https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html"
    info "Recommended: 47.4.0 or later"
    if ! ask_yn "Continue without Forge?"; then
        exit 0
    fi
fi
echo ""

# ============================================================
#  [5/5] Install mod JAR
# ============================================================
step "5/5" "Block Reality mod ..."

# Multi-path JAR scan
JAR_FILE=""
search_paths=(
    "$SCRIPT_DIR/mpd.jar"
    "$SCRIPT_DIR/../mpd.jar"
    "$SCRIPT_DIR/build/mpd.jar"
    "$SCRIPT_DIR/../build/mpd.jar"
    "$SCRIPT_DIR/../../mpd.jar"
)

# Scan Block Reality/build/libs/
libs_dir="$SCRIPT_DIR/../Block Reality/build/libs"
if [[ -d "$libs_dir" ]]; then
    latest=$(ls -t "$libs_dir"/*.jar 2>/dev/null | head -1)
    [[ -n "$latest" ]] && search_paths+=("$latest")
fi

for p in "${search_paths[@]}"; do
    if [[ -f "$p" ]]; then
        JAR_FILE="$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"
        break
    fi
done

# Auto-build if JAR not found
if [[ -z "$JAR_FILE" ]]; then
    warn "mpd.jar not found in search paths"

    gradlew=""
    for gp in "$SCRIPT_DIR/../Block Reality/gradlew" "$SCRIPT_DIR/../../Block Reality/gradlew" "$SCRIPT_DIR/../gradlew"; do
        if [[ -x "$gp" ]]; then
            gradlew="$(cd "$(dirname "$gp")" && pwd)/gradlew"
            break
        fi
    done

    if [[ -n "$gradlew" ]] && ask_yn "Build from source? (./gradlew mergedJar)"; then
        build_dir="$(dirname "$gradlew")"
        build "Building in $build_dir ..."
        build "This may take 2-5 minutes on first run ..."

        pushd "$build_dir" >/dev/null
        ./gradlew mergedJar 2>&1 | while IFS= read -r line; do
            if [[ "$line" == *"BUILD SUCCESSFUL"* ]]; then
                echo -e "  ${G}$line${N}"
            elif [[ "$line" == *"ERROR"* || "$line" == *"FAILED"* ]]; then
                echo -e "  ${R}$line${N}"
            fi
        done
        popd >/dev/null

        # Re-scan
        for p in "${search_paths[@]}"; do
            if [[ -f "$p" ]]; then
                JAR_FILE="$(cd "$(dirname "$p")" && pwd)/$(basename "$p")"
                break
            fi
        done
        # Check parent of build dir
        parent_jar="$build_dir/../mpd.jar"
        if [[ -z "$JAR_FILE" && -f "$parent_jar" ]]; then
            JAR_FILE="$(cd "$(dirname "$parent_jar")" && pwd)/mpd.jar"
        fi
    fi
fi

if [[ -z "$JAR_FILE" ]]; then
    fail "Cannot find mpd.jar!"
    info "Place mpd.jar next to this script, or build from source:"
    info "  cd 'Block Reality' && ./gradlew mergedJar"
    exit 1
fi

info "Source: $JAR_FILE"

# Create mods/ if needed
mkdir -p "$MC_DIR/mods"

# Remove old versions
rm -f "$MC_DIR/mods/mpd.jar" "$MC_DIR/mods/blockreality-"*.jar "$MC_DIR/mods/block-reality-"*.jar 2>/dev/null || true

# Copy
cp "$JAR_FILE" "$MC_DIR/mods/mpd.jar"
ok "Installed: $MC_DIR/mods/mpd.jar"

# ============================================================
#  Done
# ============================================================
echo ""
echo -e "  ${G}================================================${N}"
echo -e "  ${W} Installation Complete!${N}"
echo -e "  ${G}================================================${N}"
echo ""
echo -e "  ${W}How to play:${N}"
echo -e "  ${D}  1. Open Minecraft Launcher${N}"
echo -e "  ${D}  2. Select Forge 1.20.1 profile${N}"
echo -e "  ${D}  3. Launch the game${N}"
echo ""
echo -e "  ${W}First launch notes:${N}"
echo -e "  ${D}  - Vulkan shader compilation takes ~5-15s${N}"
echo -e "  ${D}  - Recommended memory: -Xmx4G or higher${N}"
echo -e "  ${D}  - Physics engine: PFSF (GPU Vulkan Compute)${N}"
echo -e "  ${D}  - Without Vulkan GPU: auto-degrades, no crash${N}"
echo ""
echo -e "  ${W}In-game commands:${N}"
echo -e "  ${D}  /br status       - Show engine status${N}"
echo -e "  ${D}  /br toggle       - Enable/disable physics${N}"
echo -e "  ${D}  /br vulkan_test  - Test Vulkan support${N}"
echo ""
