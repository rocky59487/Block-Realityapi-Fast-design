## pfsf 修復方案 [2026-04-08 18:03:37]

### 優先級1 (立即修復)
1. [jules/pfsf_found/PFSFEngine.java:110] 缺少 `setFluidPressureLookup` 靜態方法導致流體壓力資料流斷點 → 在第110行後加入 `public static void setFluidPressureLookup(Function<BlockPos, Float> lookup) { if (instance != null) instance.setFluidPressureLookup(lookup); }` (解鎖引擎接收流體壓力的介面)
2. [jules/pfsf_found/FluidStructureCoupler.java:69] `updatePressureLookup` 更新後未註冊給 PFSFEngine 導致綁定失效 → 增加第69行的註冊呼叫 `PFSFEngine.setFluidPressureLookup(pressureLookup);` (確保每 tick 動態流體壓力正確傳遞給物理引擎)
3. [jules/pfsf_found/PFSFDataBuilder.java:42] `updateSourceAndConductivity` 未接收 `pressureLookup` 導致計算引用斷點 → 在第42行 `fillRatioLookup` 參數後增加 `Function<BlockPos, Float> pressureLookup`，並修正內部呼叫引用 (結構應力求解能正確包含流體壓力源項)

### 優先級2 (影響較小)
4. [未知檔案:WindManager] `PFSFEngine.setWindVector` 未被呼叫導致風力系統斷點 → 在風力管理器的 tick 函數中增加 `PFSFEngine.setWindVector(currentWind);` 呼叫 (恢復風力向量對結構的影響)

### 執行順序建議
先修 PFSFEngine 增加介面(解鎖綁定) → 修 FluidStructureCoupler 更新綁定邏輯 → 驗證 PFSFDataBuilder 修正引用與參數
