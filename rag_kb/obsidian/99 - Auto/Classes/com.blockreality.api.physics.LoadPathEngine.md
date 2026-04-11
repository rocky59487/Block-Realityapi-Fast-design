---
id: "java_api:com.blockreality.api.physics.LoadPathEngine"
type: class
tags: ["java", "api", "physics"]
---

# 🧩 com.blockreality.api.physics.LoadPathEngine

> [!info] 摘要
> 載重傳導路徑引擎 (Load Path Engine)  核心概念：把建築變成一棵「往下長」的支撐樹。 每個方塊記住「我的重量傳給誰 (Parent)」， 當方塊被放置或破壞時，只沿著傳導路徑做局部更新。  三大操作： 1. onBlockPlaced — 方塊被放置時：找到最佳支撐者，把自重傳下去 2. onBlockBroken — 方塊被破壞時：通知依賴者尋找新的支撐，找不到就崩塌 3. findBestSupport — 在鄰居中找到最強的支撐者  效能： - 放置：O(H)，H = 樓層高度（載重沿樹往下傳遞） - 破壞：O(K)，K = 受影響的依賴者數量（局部重新連接） - 完全不需要全局 BFS 掃描  力學規則： - 重力方向：-Y（Minecraft 世界座標） - 純壓力傳遞路徑：垂直向下（直覺路徑） - 側向支撐：需要 Rtens > 0 的材料（鋼筋、鐵、木材 =

## 🔗 Related
- [[LoadPathEngine]]
- [[find]]
- [[findBestSupport]]
- [[onBlockBroken]]
- [[onBlockPlace]]
- [[onBlockPlaced]]
