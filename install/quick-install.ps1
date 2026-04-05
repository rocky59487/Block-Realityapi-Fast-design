#Requires -Version 5.1
<#
.SYNOPSIS
    Block Reality v0.1.0-alpha - Smart Installer (Windows PowerShell)
.DESCRIPTION
    Auto-detects and installs: Java 17, Forge 1.20.1, Block Reality mod.
    Supports auto-download, auto-build from source, multi-path JAR scanning.
#>

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ============================================================
#  Configuration
# ============================================================
$JAVA_MSI_URL   = 'https://api.adoptium.net/v3/installer/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
$FORGE_URL      = 'https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-installer.jar'
$FORGE_VER      = '1.20.1-47.4.0'
$ScriptDir      = Split-Path -Parent $MyInvocation.MyCommand.Path

# ============================================================
#  UI Helpers
# ============================================================
function Write-Step  ([string]$Step, [string]$Msg) {
    Write-Host "[$Step] " -ForegroundColor Cyan -NoNewline
    Write-Host $Msg
}
function Write-OK    ([string]$Msg) { Write-Host "  [OK] $Msg" -ForegroundColor Green }
function Write-Warn  ([string]$Msg) { Write-Host "  [!]  $Msg" -ForegroundColor Yellow }
function Write-Fail  ([string]$Msg) { Write-Host "  [X]  $Msg" -ForegroundColor Red }
function Write-Info  ([string]$Msg) { Write-Host "       $Msg" -ForegroundColor Gray }
function Write-Build ([string]$Msg) { Write-Host "  >>   $Msg" -ForegroundColor Cyan }

function Ask-YesNo ([string]$Question) {
    $reply = Read-Host "$Question (y/n)"
    return ($reply -ieq 'y')
}

# ============================================================
#  Banner
# ============================================================
Write-Host ''
Write-Host '  ================================================' -ForegroundColor Cyan
Write-Host '   Block Reality v0.1.0-alpha - Smart Installer'    -ForegroundColor White
Write-Host '   GPU Structural Physics for Minecraft Forge 1.20.1' -ForegroundColor Gray
Write-Host '   PFSF Engine + SDF Ray Marching'                 -ForegroundColor Gray
Write-Host '  ================================================' -ForegroundColor Cyan
Write-Host ''

# ============================================================
#  [1/5] Java 17
# ============================================================
Write-Step '1/5' 'Java 17 ...'

$javaOk = $false
try {
    $javaVer = & java -version 2>&1 | Select-String '17\.'
    if ($javaVer) { $javaOk = $true }
} catch {}

if ($javaOk) {
    Write-OK 'Java 17 detected'
} else {
    Write-Fail 'Java 17 not found'
    if (Ask-YesNo '       Auto-download Eclipse Temurin 17?') {
        $msiPath = Join-Path $env:TEMP 'temurin17.msi'
        Write-Build "Downloading Temurin 17 JDK ..."
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $JAVA_MSI_URL -OutFile $msiPath -UseBasicParsing
            Write-Build "Running installer (may require admin) ..."
            Start-Process msiexec.exe -ArgumentList "/i `"$msiPath`" /passive ADDLOCAL=FeatureMain,FeatureJavaHome,FeaturePath" -Wait
            # Re-check
            $env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
                        [System.Environment]::GetEnvironmentVariable('Path', 'User')
            $javaVer2 = & java -version 2>&1 | Select-String '17\.'
            if ($javaVer2) {
                Write-OK 'Java 17 installed successfully'
            } else {
                Write-Warn 'Install finished but java not in PATH yet - restart terminal after install'
            }
        } catch {
            Write-Fail "Download failed: $_"
            Write-Info 'Manual download: https://adoptium.net/'
            Read-Host 'Press Enter to exit'; exit 1
        }
    } else {
        Write-Info 'Manual download: https://adoptium.net/'
        Read-Host 'Press Enter to exit'; exit 1
    }
}
Write-Host ''

# ============================================================
#  [2/5] Vulkan
# ============================================================
Write-Step '2/5' 'Vulkan driver ...'

$vulkanOk = $false
try { $vulkanOk = (Get-Command vulkaninfo -ErrorAction SilentlyContinue) -ne $null } catch {}

if ($vulkanOk) {
    Write-OK 'Vulkan driver detected'
} else {
    Write-Warn 'vulkaninfo not found (non-blocking)'
    Write-Info 'Vulkan is bundled with GPU drivers. Update your GPU driver:'
    Write-Info '  NVIDIA: https://www.nvidia.com/drivers'
    Write-Info '  AMD:    https://www.amd.com/en/support'
    Write-Info '  Intel:  https://www.intel.com/content/www/us/en/download-center'
    Write-Info ''
    Write-Info 'Requirements: Vulkan 1.2+ (GTX 10xx / RX 400 / UHD 600+)'
    Write-Info 'Without Vulkan the mod degrades gracefully (no RT/SDF rendering)'
}
Write-Host ''

# ============================================================
#  [3/5] Locate .minecraft
# ============================================================
Write-Step '3/5' 'Minecraft directory ...'

$mcDir = $null
$mcPath = Join-Path $env:APPDATA '.minecraft'
if (Test-Path $mcPath) { $mcDir = $mcPath }

# PrismLauncher check
$prismPath = Join-Path $env:APPDATA 'PrismLauncher\instances'
if (Test-Path $prismPath) {
    Write-Info 'PrismLauncher detected - copy mpd.jar to your instance mods/ manually'
}

if (-not $mcDir) {
    Write-Fail '.minecraft not found!'
    Write-Info 'Copy mpd.jar to your Minecraft mods folder manually.'
    Read-Host 'Press Enter to exit'; exit 1
}
Write-OK "Found: $mcDir"
Write-Host ''

# ============================================================
#  [4/5] Forge 1.20.1
# ============================================================
Write-Step '4/5' 'Forge 1.20.1 ...'

$versionsDir = Join-Path $mcDir 'versions'
$forgeOk = $false
if (Test-Path $versionsDir) {
    $forgeDirs = Get-ChildItem $versionsDir -Directory | Where-Object { $_.Name -match '1\.20\.1.*forge' }
    if ($forgeDirs) { $forgeOk = $true }
}

if ($forgeOk) {
    Write-OK 'Forge 1.20.1 detected'
} else {
    Write-Warn 'Forge 1.20.1 not found'
    if (Ask-YesNo '       Auto-download Forge installer?') {
        $forgeJar = Join-Path $env:TEMP "forge-$FORGE_VER-installer.jar"
        Write-Build "Downloading Forge $FORGE_VER ..."
        try {
            Invoke-WebRequest -Uri $FORGE_URL -OutFile $forgeJar -UseBasicParsing
            Write-Build 'Launching Forge installer (select Install Client) ...'
            Start-Process java -ArgumentList "-jar `"$forgeJar`"" -Wait
            Write-OK 'Forge installer completed'
        } catch {
            Write-Fail "Download failed: $_"
            Write-Info "Manual download: https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html"
        }
    } else {
        Write-Info 'Download: https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html'
        Write-Info 'Recommended: 47.4.0 or later'
        if (-not (Ask-YesNo '       Continue without Forge?')) { exit 0 }
    }
}
Write-Host ''

# ============================================================
#  [5/5] Install mod JAR
# ============================================================
Write-Step '5/5' 'Block Reality mod ...'

# Multi-path JAR scan
$searchPaths = @(
    (Join-Path $ScriptDir 'mpd.jar'),
    (Join-Path $ScriptDir '..\mpd.jar'),
    (Join-Path $ScriptDir 'build\mpd.jar'),
    (Join-Path $ScriptDir '..\build\mpd.jar'),
    (Join-Path $ScriptDir '..\..\mpd.jar'),
    (Join-Path $ScriptDir '..\Block Reality\build\libs\mpd.jar')
)

# Also scan Block Reality/build/libs/ for any matching JAR
$libsDir = Join-Path $ScriptDir '..\Block Reality\build\libs'
if (Test-Path $libsDir) {
    $latestJar = Get-ChildItem $libsDir -Filter '*.jar' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($latestJar) { $searchPaths += $latestJar.FullName }
}

$jarFile = $null
foreach ($p in $searchPaths) {
    if (Test-Path $p) {
        $jarFile = (Resolve-Path $p).Path
        break
    }
}

# Auto-build if JAR not found
if (-not $jarFile) {
    Write-Warn 'mpd.jar not found in search paths'

    # Look for gradlew.bat
    $gradlewPaths = @(
        (Join-Path $ScriptDir '..\Block Reality\gradlew.bat'),
        (Join-Path $ScriptDir '..\..\Block Reality\gradlew.bat'),
        (Join-Path $ScriptDir '..\gradlew.bat')
    )
    $gradlew = $null
    foreach ($gp in $gradlewPaths) {
        if (Test-Path $gp) { $gradlew = (Resolve-Path $gp).Path; break }
    }

    if ($gradlew -and (Ask-YesNo '       Build from source? (gradlew mergedJar)')) {
        $buildDir = Split-Path $gradlew -Parent
        Write-Build "Building in $buildDir ..."
        Write-Build 'This may take 2-5 minutes on first run ...'

        Push-Location $buildDir
        try {
            & $gradlew mergedJar 2>&1 | ForEach-Object {
                if ($_ -match 'BUILD SUCCESSFUL') { Write-OK $_ }
                elseif ($_ -match 'ERROR|FAILED') { Write-Fail $_ }
            }
            # Re-scan after build
            foreach ($p in $searchPaths) {
                if (Test-Path $p) { $jarFile = (Resolve-Path $p).Path; break }
            }
            # Also check parent
            $parentJar = Join-Path $buildDir '..\mpd.jar'
            if ((-not $jarFile) -and (Test-Path $parentJar)) {
                $jarFile = (Resolve-Path $parentJar).Path
            }
        } catch {
            Write-Fail "Build failed: $_"
        } finally {
            Pop-Location
        }
    }
}

if (-not $jarFile) {
    Write-Fail 'Cannot find mpd.jar!'
    Write-Info 'Place mpd.jar next to this script, or build from source:'
    Write-Info '  cd "Block Reality" && gradlew.bat mergedJar'
    Read-Host 'Press Enter to exit'; exit 1
}

Write-Info "Source: $jarFile"

# Create mods/ if needed
$modsDir = Join-Path $mcDir 'mods'
if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
    Write-Info "Created: $modsDir"
}

# Remove old versions
Remove-Item (Join-Path $modsDir 'mpd.jar') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $modsDir 'blockreality-*.jar') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $modsDir 'block-reality-*.jar') -Force -ErrorAction SilentlyContinue

# Copy
try {
    Copy-Item $jarFile (Join-Path $modsDir 'mpd.jar') -Force
    Write-OK "Installed: $modsDir\mpd.jar"
} catch {
    Write-Fail "Copy failed: $_ (is Minecraft running?)"
    Read-Host 'Press Enter to exit'; exit 1
}

# ============================================================
#  Done
# ============================================================
Write-Host ''
Write-Host '  ================================================' -ForegroundColor Green
Write-Host '   Installation Complete!'                          -ForegroundColor White
Write-Host '  ================================================' -ForegroundColor Green
Write-Host ''
Write-Host '  How to play:' -ForegroundColor White
Write-Host '    1. Open Minecraft Launcher'               -ForegroundColor Gray
Write-Host '    2. Select Forge 1.20.1 profile'           -ForegroundColor Gray
Write-Host '    3. Launch the game'                        -ForegroundColor Gray
Write-Host ''
Write-Host '  First launch notes:' -ForegroundColor White
Write-Host '    - Vulkan shader compilation takes ~5-15s'  -ForegroundColor Gray
Write-Host '    - Recommended memory: -Xmx4G or higher'   -ForegroundColor Gray
Write-Host '    - Physics engine: PFSF (GPU Vulkan Compute)' -ForegroundColor Gray
Write-Host '    - Without Vulkan GPU: auto-degrades, no crash' -ForegroundColor Gray
Write-Host ''
Write-Host '  In-game commands:' -ForegroundColor White
Write-Host '    /br status       - Show engine status'     -ForegroundColor Gray
Write-Host '    /br toggle       - Enable/disable physics' -ForegroundColor Gray
Write-Host '    /br vulkan_test  - Test Vulkan support'    -ForegroundColor Gray
Write-Host ''
Read-Host 'Press Enter to close'
