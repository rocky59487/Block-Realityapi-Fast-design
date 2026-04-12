---
id: "java_api:com.blockreality.api.sidecar.SharedMemoryBridge"
type: class
tags: ["java", "api", "sidecar"]
---

# 🧩 com.blockreality.api.sidecar.SharedMemoryBridge

> [!info] 摘要
> 共享記憶體橋接器 — Java 17 MappedByteBuffer 實作  使用作業系統 mmap 在 Java 與 Sidecar 之間傳輸大型體素數據， 避免 stdio 序列化/反序列化的開銷。  記憶體佈局： [Header: 64 bytes] - magic (4B): 0x42524D4D ("BRMM") - version (4B): 1 - writerPid (4B): 寫入者 PID - sequenceNumber (8B): 單調遞增的序號 - dataOffset (4B): 數據區偏移（固定 64） - dataLength (4B): 數據長度 - flags (4B): 位元旗標（dirty, ready, error） - reserved (32B): 保留 [Data: N bytes] - RLE 壓縮的體素數據 or FEM 結果矩陣  同

> [!tip] 資訊
> 🔌 Implements: Closeable

## 🔗 Related
- [[SharedMemoryBridge]]
- [[read]]
- [[write]]
