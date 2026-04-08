# L2-coupling — 跨域連動

> 所屬：L1-api > L2-coupling

## 概述

協調多物理域之間的單向/雙向耦合。每 tick 在所有域獨立求解完後執行，
讀取域 A 輸出、注入域 B 輸入（1-tick 延遲設計）。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `MultiDomainCoupler` | `api.physics.coupling` | 每 tick 統一調度所有耦合 |

## 耦合矩陣

| 來源 → 目標 | 耦合方式 | 狀態 |
|---|---|---|
| EM → Thermal | Joule 加熱 P = σ\|E\|² | ✅ |
| Wind → Thermal | 強制對流增強 σ_eff = σ × (1 + 0.1v^0.8) | ✅ |
| Thermal → Structure | 熱應力 σ_th → PFSF source | 預留 |
| Wind → Structure | 風壓 q = ½ρv²Cd → PFSF source | 預留 |
| Thermal → Fluid | 相變（T>100→蒸發, T<0→結冰） | 預留 |
