# Block Reality v0.1.0-alpha - Installation Guide

## Quick Install (Recommended)

### Windows

Double-click `../quick-install.bat`. The smart installer will:
1. Detect Java 17 - auto-download Temurin 17 if missing
2. Check Vulkan driver support
3. Locate `.minecraft` folder
4. Detect Forge 1.20.1 - auto-download installer if missing
5. Find or build `mpd.jar` - auto-run `gradlew mergedJar` if JAR not found
6. Install to `mods/` folder

### Linux / macOS

```bash
chmod +x ../quick-install.sh
../quick-install.sh
```

The script auto-detects your package manager (apt/pacman/brew) for Java 17 installation.

---

## Manual Install

### Prerequisites

| Requirement | Version | Download |
|-------------|---------|----------|
| Java | 17+ | [Eclipse Temurin](https://adoptium.net/) |
| Minecraft | 1.20.1 | [minecraft.net](https://www.minecraft.net/) |
| Forge | 47.2.0+ (recommended 47.4.0+) | [Forge Downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) |

### Steps

1. Install Forge 1.20.1 (Install Client)
2. Copy `mpd.jar` to:
   - **Windows**: `%APPDATA%\.minecraft\mods\`
   - **macOS**: `~/Library/Application Support/minecraft/mods/`
   - **Linux**: `~/.minecraft/mods/`
3. Launch Minecraft with Forge 1.20.1 profile
4. Set JVM argument: `-Xmx4G` (minimum 4GB RAM)

---

## Vulkan Requirements

### Do I need the Vulkan SDK?

**No.** Block Reality uses LWJGL 3.3.5 Vulkan bindings. Only the system-level Vulkan driver is needed (bundled with GPU drivers).

| Item | Required? | Notes |
|------|-----------|-------|
| GPU Driver | Yes | Update to latest version |
| Vulkan SDK | No | Only needed for developers |
| Minimum Vulkan | 1.2 | |
| Minimum GPU | GTX 10xx / RX 400 / UHD 600 | |

### Feature Tiers

| Feature | GPU Requirement |
|---------|----------------|
| PFSF Physics (Vulkan Compute) | Any Vulkan 1.2 GPU |
| SDF Ray Marching (GI/AO) | Any Vulkan 1.2 GPU |
| Hardware Ray Tracing | RTX 20xx+ / RX 6000+ |
| Ada optimized path | RTX 40xx |
| Blackwell optimized path | RTX 50xx |

### Without Vulkan

The mod **auto-degrades** gracefully:
- Physics falls back to CPU mode
- RT rendering disabled, uses vanilla Minecraft renderer
- No crash, game runs normally

### GPU Driver Links

| GPU | Download |
|-----|----------|
| NVIDIA | https://www.nvidia.com/drivers |
| AMD | https://www.amd.com/en/support |
| Intel | https://www.intel.com/content/www/us/en/download-center |

---

## In-Game Commands

| Command | Description |
|---------|-------------|
| `/br status` | Show physics engine and Vulkan status |
| `/br toggle` | Enable/disable physics engine |
| `/br vulkan_test` | Test Vulkan GPU availability |

---

## Memory Settings

| Scenario | JVM Argument |
|----------|-------------|
| Normal play | `-Xmx4G` |
| Large structures (1000+ blocks) | `-Xmx6G` |
| With shaders | `-Xmx8G` |

---

## FAQ

**Q: "Vulkan initialization failed" message on startup**
A: Update your GPU driver. The mod will auto-degrade and still work.

**Q: Game loads slowly on first launch**
A: Vulkan compute shader compilation (8 shaders) takes 5-15 seconds. Subsequent launches use cache.

**Q: PrismLauncher / MultiMC setup**
A: Create Instance (MC 1.20.1) → Install Forge → Copy `mpd.jar` to instance `mods/`.

**Q: Server-side installation**
A: Place `mpd.jar` in server `mods/`. Server does not need a Vulkan GPU (CPU fallback).

---

## Build from Source

```bash
git clone https://github.com/rocky59487/Block-Realityapi-Fast-design.git
cd Block-Realityapi-Fast-design/"Block Reality"
./gradlew mergedJar
# Output: ../mpd.jar
```

Requires: Java 17 JDK + Node.js 20+ (sidecar auto-built)
