---
id: "tool:docker"
type: tool
tags: ["tool", "docker", "ci", "build"]
command: "docker build -t blockreality:latest ."
---

# 🛠️ Docker 多階段建置

## 📝 說明
專案根目錄的 Dockerfile 定義了多階段建置，包含 Java 模組編譯、C++ native 建置、Python ML 環境安裝。可用於 CI 或統一開發環境。建置指令：docker build -t blockreality:latest .

> [!tip] 使用資訊
> ⌨️ Command: `docker build -t blockreality:latest .`
> 📎 Related Files:
>   - `Dockerfile`

## 🔗 Related Notes
- [[build]]
- [[test]]
