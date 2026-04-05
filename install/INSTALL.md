# Block Reality v0.1.0-alpha — 安裝指南

## 快速安裝

### Windows
雙擊執行 `quick-install.bat`，腳本會自動：
1. 檢查 Java 17
2. 檢查 Vulkan 驅動
3. 找到 `.minecraft` 資料夾
4. 檢查 Forge 1.20.1
5. 複製 `mpd.jar` 到 `mods/`

### Linux / macOS
```bash
chmod +x quick-install.sh
./quick-install.sh
```

---

## 手動安裝

### 1. 安裝前置需求

| 需求 | 版本 | 下載連結 |
|------|------|---------|
| Java | 17+ | [Eclipse Temurin](https://adoptium.net/) |
| Minecraft | 1.20.1 | [minecraft.net](https://www.minecraft.net/) |
| Forge | 47.2.0+ (建議 47.4.0+) | [Forge 下載](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) |

### 2. 安裝 Forge

1. 下載 Forge 1.20.1 Installer
2. 執行 `forge-1.20.1-47.x.x-installer.jar`
3. 選擇 **Install Client**
4. 點擊 OK，等待安裝完成
5. 開啟 Minecraft Launcher，確認出現 Forge 1.20.1 設定檔

### 3. 安裝 Block Reality

1. 將 `mpd.jar` 複製到：
   - **Windows**: `%APPDATA%\.minecraft\mods\`
   - **macOS**: `~/Library/Application Support/minecraft/mods/`
   - **Linux**: `~/.minecraft/mods/`
2. 如果 `mods/` 資料夾不存在，手動建立

### 4. 啟動遊戲

1. 開啟 Minecraft Launcher
2. 選擇 **Forge 1.20.1** 設定檔
3. 建議在 JVM 參數中設定 `-Xmx4G`（至少 4GB 記憶體）
4. 啟動遊戲

---

## Vulkan 需求說明

### 需要安裝 Vulkan SDK 嗎？

**不需要。** Block Reality 使用 LWJGL 3.3.5 的 Vulkan bindings，只需要系統級 Vulkan 驅動（隨 GPU 驅動一起安裝）。

### 需要什麼？

| 項目 | 說明 |
|------|------|
| **Vulkan 驅動** | 隨 GPU 驅動安裝，不需額外操作 |
| **Vulkan SDK** | **不需要**（僅開發者需要） |
| **最低 Vulkan 版本** | 1.2 |
| **最低 GPU** | NVIDIA GTX 10xx (Pascal) / AMD RX 400 (Polaris) / Intel UHD 600 (Gen 9) |

### 完整功能需求（RT + SDF Ray Marching）

| 功能 | GPU 需求 | 說明 |
|------|---------|------|
| PFSF 物理 (Vulkan Compute) | 任何 Vulkan 1.2 GPU | 全 GPU 加速結構物理計算 |
| SDF Ray Marching (GI/AO) | 任何 Vulkan 1.2 GPU | JFA + Sphere Tracing |
| Hardware Ray Tracing | RTX 20xx+ / RX 6000+ | 硬體光線追蹤管線 |
| Ada 優化路徑 | RTX 40xx (Ada) | SER + OMM + Mesh Shader |
| Blackwell 優化路徑 | RTX 50xx (Blackwell) | 最佳效能路徑 |

### 沒有 Vulkan 會怎樣？

模組會**自動降級**：
- 物理引擎退回 CPU 模式（效能較低但功能完整）
- RT 渲染管線停用，使用 Minecraft 原生渲染
- 不會 crash，遊戲正常運行

### 如何確認 Vulkan 已安裝？

**Windows:**
```cmd
vulkaninfo --summary
```
或在裝置管理員中確認 GPU 驅動是最新版本。

**Linux:**
```bash
vulkaninfo --summary
# 如果未安裝:
# NVIDIA: sudo apt install nvidia-driver-xxx
# AMD/Intel: sudo apt install mesa-vulkan-drivers
```

### 更新 GPU 驅動

| GPU | 驅動下載 |
|-----|---------|
| NVIDIA | https://www.nvidia.com/drivers |
| AMD | https://www.amd.com/en/support |
| Intel | https://www.intel.com/content/www/us/en/download-center |

---

## 記憶體設定建議

| 場景 | 建議 JVM 參數 |
|------|-------------|
| 一般遊玩 | `-Xmx4G` |
| 大型結構 (1000+ 方塊) | `-Xmx6G` |
| 搭配光影包 | `-Xmx8G` |

在 Minecraft Launcher 中：
1. 選擇 Forge 1.20.1 設定檔
2. 點擊「更多選項」
3. 在 JVM 參數中修改 `-Xmx` 值

---

## 常見問題

### Q: 啟動後看到 "Vulkan initialization failed" 訊息
**A:** GPU 驅動版本太舊。更新 GPU 驅動即可。模組會自動降級，不影響遊玩。

### Q: 遊戲啟動很慢
**A:** 首次啟動需要編譯 Vulkan compute shaders（8 個著色器），通常需要 5-15 秒。後續啟動會使用快取。

### Q: PrismLauncher / MultiMC 怎麼裝？
**A:** 
1. 建立新 Instance → Minecraft 1.20.1
2. 編輯 Instance → Version → Install Forge
3. 將 `mpd.jar` 放入該 Instance 的 `.minecraft/mods/`

### Q: 伺服器端需要安裝嗎？
**A:** 是的，`mpd.jar` 需要放在伺服器端的 `mods/` 資料夾。伺服器端不需要 Vulkan GPU（物理計算在伺服器上使用 CPU fallback）。

---

## 從原始碼建置

```bash
git clone https://github.com/rocky59487/Block-Realityapi-Fast-design.git
cd Block-Realityapi-Fast-design/"Block Reality"

# 建置合併 JAR
./gradlew mergedJar

# 輸出: ../mpd.jar
```

需要：Java 17 JDK + Node.js 20+（sidecar 自動建置）
