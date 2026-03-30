# 動畫引擎

> 所屬：L1-api > L2-render

## 概述

Block Reality 的 GeckoLib 風格骨骼動畫系統，支援每實體多控制器、骨骼矩陣計算、關鍵幀事件與 GPU Compute Skinning 加速。

## 關鍵類別

| 類別 | 套件路徑 | 說明 |
|------|---------|------|
| `BRAnimationEngine` | `client.render.animation` | 動畫引擎主類別（靜態全域狀態） |
| `AnimatableInstance` | 內部類別 | 每實體動畫狀態容器 |
| `BoneHierarchy` | `client.render.animation` | 骨骼層級系統 — 矩陣計算與蒙皮 |
| `Bone` | 內部類別 | 單一骨骼（Rest Pose + 動畫覆蓋） |
| `AnimationClip` | `client.render.animation` | 具名動畫片段（多 Channel、關鍵幀序列） |
| `BoneChannel` | 內部類別 | 單骨骼動畫通道（position/rotation/scale） |
| `Keyframe` | 內部類別 | 單一關鍵幀（時間、值、緩動函數） |
| `AnimationController` | `client.render.animation` | 動畫控制器 — 播放、暫停、過渡狀態機 |
| `EasingFunctions` | `client.render.animation` | 緩動函數庫 |

## 架構對應

| GeckoLib 概念 | Block Reality 對應 |
|---|---|
| `AnimatableInstanceCache` | `AnimatableInstance` |
| `AnimationController` | `AnimationController` |
| `GeoBone` | `BoneHierarchy.Bone` |
| `KeyframeEvent` | `AnimationController.KeyframeEvent` |

## BRAnimationEngine

### 預編譯動畫片段

- `blockPlacementClip` — 方塊放置動畫
- `blockDestroyClip` — 方塊破壞動畫
- `selectionPulseClip` — 選擇脈衝動畫
- `structureCollapseClip` — 結構崩塌動畫

### 骨骼階層類型

- `BLOCK` — 簡單方塊骨骼（最小化）
- `CHARACTER` — 完整角色骨骼（複雜裝備、肢體動畫）

### Compute Skinning

當活躍動畫實體超過 50 個時自動啟用 GPU Compute Skinning，將蒙皮矩陣計算從 CPU 轉移到 GPU。

## AnimationController 狀態

| 狀態 | 說明 |
|------|------|
| `STOPPED` | 停止 |
| `PLAYING` | 播放中 |
| `PAUSED` | 暫停 |
| `TRANSITIONING` | 交叉淡入過渡中 |

### 關鍵幀事件類型

- `SOUND` — 播放聲音
- `PARTICLE` — 生成粒子效果
- `CUSTOM` — 遊戲邏輯回調

## BoneHierarchy

### 骨骼資料

每個 `Bone` 維護：
- **Rest Pose**：localPosition、localRotation（四元數 XYZW）、localScale
- **動畫覆蓋**：animPosition、animRotation、animScale
- **矩陣快取**：localMatrix、worldMatrix、inverseBindMatrix

### 蒙皮矩陣計算

`worldMatrix * inverseBindMatrix` → 上傳到 shader uniform（最多 128 個骨骼）

## 關聯接口

- 被依賴 ← [RenderPipeline](L3-pipeline.md) — GBuffer 實體渲染
- 依賴 → `BRComputeSkinning` — GPU 加速蒙皮
