# 第三部分：神經網路架構級優化計畫

本計畫針對 `brml` 子系統進行底層效能與架構強健性之極限重構。

## 1. 痛點分析
在專家的審查中，我們發現 `brml` 管線（特別是 `auto_train.py` 和 `train_fnofluid.py`）中存在兩大核心效能瓶頸：
1. **Host-to-Device 傳輸地獄**:
   在資料前處理與 DataLoader 中，過度使用 `numpy`，隨後又在訓練步驟中轉為 JAX array。這導致每一 Batch 都在 PCIe 通道上來回傳輸，極大拖慢了 GPU 的吞吐量。
2. **JIT 編譯失效**:
   `train_step` 被包裝在原生的 Python `for` 迴圈中，導致 JAX 的 `jax.jit` 無法展開迴圈或將整個訓練過程視為單一的 XLA 計算圖，造成嚴重的 CPU Overhead 且無法發揮 Blackwell 等新世代架構的效能。

## 2. 破壞性重構策略

### A. 全面 JAX 化與非同步 Prefetching
- **修改對象**: `AsyncDataLoader` / `ZarrStore`
- **重構方案**:
  - 廢棄所有 `np.ndarray` 的中繼轉換。
  - 使用 `jax.device_put` 與 `jax.lax.stop_gradient`，並利用 Python `concurrent.futures.ThreadPoolExecutor` 設定嚴格的 `prefetch_count` 來綁定資料到 GPU，防止 CPU OOM。

### B. 訓練迴圈全展開 (Full-Graph JIT)
- **修改對象**: `train_robust_ssgo.py` 及相關 Trainer
- **重構方案**:
  - 將原本的 `for epoch in range(epochs): for batch in dataloader:` 結構，重構為基於 `jax.lax.scan` 的狀態機推演。
  - 將 DataLoader 產生的 Batch 預先堆疊為大的 Device Array，然後透過 `scan` 一次性送入編譯好的 `update_step` 函數。

```python
# 重構前 (極度低效)
@jax.jit
def train_step(state, batch): ...

for batch in dataloader:
    state, loss = train_step(state, batch) # CPU 介入過多

# 重構後 (XLA 極速編譯)
def train_epoch(state, epoch_batches):
    def scan_fn(state, batch):
        new_state, loss = train_step(state, batch)
        return new_state, loss
    # 一次性在 GPU 執行整個 Epoch
    return jax.lax.scan(scan_fn, state, epoch_batches)
```

### C. 精度與硬體特性解放
- 針對高階 RTX 顯示卡，強制作業系統層級環境變數覆寫：
  ```python
  import os
  import jax
  os.environ["NVIDIA_TF32_OVERRIDE"] = "1"
  jax.config.update("jax_default_matmul_precision", "tensorfloat32")
  ```

## 3. 架構預期效益
實施此優化後，預期可達以下 KPI：
- GPU 閒置時間 (Idle Time) 從 40% 降低至 5% 以下。
- 訓練相同 Epoch 數的耗時將縮短 60% 至 75%。
- 徹底根除在長時間掛機訓練時，由 NumPy 與 JAX 垃圾回收器競爭導致的 CPU RAM OOM。
