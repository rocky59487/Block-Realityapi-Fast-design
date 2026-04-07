# L2-physics — 物理引擎

> 所屬：L1-api > L2-physics

## 概述

Block Reality 的結構物理模擬核心，實現多層級的力學分析：從即時近似（BFS 加權應力）到高精度計算（Euler-Bernoulli 梁模型），以及大規模結構的稀疏體素八叉樹分析。

## 設計哲學（CTO 雙軌戰略）

- **Java 端**：即時近似值（50ms 內完成），給玩家合乎物理直覺的結果
- **TypeScript 端**：`/fd export` 時執行精確 FEA 矩陣分析

## 子文件

| 文件 | 類別 | 說明 |
|------|------|------|
| [L3-force-solver](L3-force-solver.md) | ForceEquilibriumSolver | Gauss-Seidel SOR 力平衡求解器 |
| [L3-beam-stress](L3-beam-stress.md) | BeamStressEngine, BeamElement | Euler-Bernoulli 梁應力分析 |
| [L3-connectivity](L3-connectivity.md) | BFSConnectivityAnalyzer | BFS 連通塊分析與增量快取 |
| [L3-cable](L3-cable.md) | CableElement, CableNode, CableState | XPBD 纜索張力模擬 |
| [L3-load-path](L3-load-path.md) | LoadPathEngine, SupportPathAnalyzer | 載重傳導路徑與加權應力 BFS |
| [L3-sparse-voxel](L3-sparse-voxel.md) | SparseVoxelOctree, CoarseFEMEngine | 稀疏體素八叉樹與粗粒度 FEM |
| [L3-load-combination](L3-load-combination.md) | LoadCombination, LoadType, ForceVector3D | ASCE 7-22 LRFD 荷載組合與 3D 力向量 |
| [L3-lateral-torsional-buckling](L3-lateral-torsional-buckling.md) | LateralTorsionalBuckling | AISC §F2 側向扭轉挫屈 |
| **[L3-pfsf](L3-pfsf.md)** | **PFSFEngine, PFSFScheduler, PFSFConductivity** | **GPU 加速勢場導流物理引擎（v2.1 支援相場斷裂、RBGS、Morton 重排與水化效應）** |
| [L3-diffusion-solver](L3-diffusion-solver.md) | DiffusionSolver, DiffusionRegion, DomainTranslator | 通用擴散求解器（Thermal/Wind/EM 共用） |

## 共用常數

`PhysicsConstants` 集中管理所有物理常數（重力 9.81 m/s²、方塊面積 1.0 m²、慣性矩 1/12 m⁴ 等），所有引擎統一引用此類別。

## 物理單位慣例

| 量 | 單位 | 說明 |
|----|------|------|
| 強度 (Rcomp/Rtens/Rshear) | MPa | 百萬帕斯卡 |
| 楊氏模量 | Pa（內部）/ GPa（顯示） | `getYoungsModulusPa()` 回傳 Pa |
| 密度 | kg/m³ | 每立方公尺公斤 |
| 力 | N | 牛頓 |
| 彎矩 | N·m | 牛頓·公尺 |
