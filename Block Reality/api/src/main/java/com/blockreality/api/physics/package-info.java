/**
 * Block Reality 物理引擎核心模組。
 *
 * <h2>三層式架構</h2>
 * <ul>
 *   <li><b>Layer 3 (CoarseFEM)</b> — Section 級粗粒度有限元素，O(S) 複雜度</li>
 *   <li><b>Layer 2 (UnionFind)</b> — 連通性分析 + 懸浮偵測，O(N) BFS</li>
 *   <li><b>Layer 1 (ForceEquilibrium)</b> — 逐塊精確力平衡，SOR 迭代求解</li>
 * </ul>
 *
 * <h2>演算法研究路線圖（E-1 ~ E-5）</h2>
 *
 * <h3>E-1: Heavy-Light Decomposition（中期）</h3>
 * <p>來源：cp-algorithms。O(log²N) 路徑查詢，適用於大型樹狀支撐結構的
 * 快速荷載路徑查詢。可替換 LoadPathEngine 的線性 traceLoadPath()。
 * 預期收益：1000+ 方塊結構的路徑查詢從 O(N) 降到 O(log²N)。</p>
 *
 * <h3>E-2: Euler Tour Trees（長期）</h3>
 * <p>來源：Stanford CS166。支援 O(log N) 的子樹聚合查詢（累積荷載、
 * 最大應力等）。可加速 BFSConnectivityAnalyzer 的結構分析。
 * 實作複雜度高，建議作為學術研究。</p>
 *
 * <h3>E-3: Gram-Schmidt 體素約束（長期）</h3>
 * <p>來源：MIG 2024（即時可破壞軟體）。使用 Gram-Schmidt 正交化
 * 處理體素間的幾何約束。適用於更精確的破壞模擬，
 * 但計算成本較高，需要 GPU 加速。</p>
 *
 * <h3>E-4: Corotational FEM（長期）</h3>
 * <p>來源：Berkeley 2009。支援大變形的有限元素方法。
 * 可替換 CoarseFEMEngine 實現即時變形模擬，
 * 但 per-element 旋轉矩陣計算成本高。
 * 建議評估 GPU 加速可行性後再實作。</p>
 *
 * <h3>E-5: evoxels 微結構模擬（學術）</h3>
 * <p>來源：arXiv 2025。可微分物理框架，支援體素級微結構優化。
 * 不適用於即時遊戲場景，但可用於離線結構設計輔助工具。</p>
 *
 * @since 3.0
 */
package com.blockreality.api.physics;
