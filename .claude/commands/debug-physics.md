除錯 Block Reality 物理引擎問題。

常見問題類型：
- `solver` — 力平衡求解器不收斂
- `stress` — 梁應力計算異常
- `connectivity` — UnionFind 連通性錯誤
- `collapse` — 崩塌觸發異常
- `cable` — 纜索張力問題
- `load` — 荷載路徑追蹤錯誤

步驟：
1. 確認問題類型
2. 讀取相關原始碼：
   - solver: `physics/ForceEquilibriumSolver.java`（SOR 迭代、omega 自適應）
   - stress: `physics/BeamStressEngine.java`、`physics/BeamElement.java`
   - connectivity: `physics/UnionFindEngine.java`
   - collapse: `collapse/CollapseManager.java`
   - cable: `physics/CableElement.java`、`physics/CableState.java`
   - load: `physics/LoadPathEngine.java`、`physics/SupportPathAnalyzer.java`
3. 檢查相關測試是否通過
4. 分析物理參數是否使用正確單位：
   - 強度: MPa（百萬帕斯卡）
   - 楊氏模量: GPa（十億帕斯卡）
   - 密度: kg/m³
   - 力: N（牛頓）
5. 建議修復方案

除錯技巧：
- SOR 不收斂 → 檢查 omega 值（通常 1.0-1.9）和最大迭代次數
- 應力異常 → 確認材料屬性單位正確，尤其是楊氏模量 GPa vs MPa
- 連通性問題 → 用 `UnionFindEngine.isConnected()` 驗證區域
- RC 融合 → 比例固定為 97/3，確認 `IFusionDetector.checkAndFuse()` 回傳值

測試指令：
```bash
cd "Block Reality"
./gradlew :api:test --tests "com.blockreality.api.physics.*"
```

$ARGUMENTS
