/**
 * Block Reality v3.0 — 稀疏體素優化層。
 *
 * 此套件包含 1200×1200×300 範圍極限優化所需的核心資料結構與引擎：
 *
 * <h2>資料結構</h2>
 * <ul>
 *   <li>{@link com.blockreality.api.physics.sparse.VoxelSection} — 16³ 體素區段（三態：EMPTY/HOMOGENEOUS/HETEROGENEOUS）</li>
 *   <li>{@link com.blockreality.api.physics.sparse.SparseVoxelOctree} — Section-based 稀疏存取（取代 RWorldSnapshot 的 1D 陣列）</li>
 *   <li>{@link com.blockreality.api.physics.sparse.SparsePhysicsSnapshot} — SVO 到現有物理引擎的橋接層</li>
 * </ul>
 *
 * <h2>快照建構</h2>
 * <ul>
 *   <li>{@link com.blockreality.api.physics.sparse.IncrementalSnapshotBuilder} — 三階段增量式快照（骨架→延遲填充→增量同步）</li>
 * </ul>
 *
 * <h2>階層式物理引擎</h2>
 * <ul>
 *   <li>{@link com.blockreality.api.physics.sparse.RegionConnectivityEngine} — Layer 3: Section 級 Union-Find（全域連通性，每 5 秒）</li>
 *   <li>{@link com.blockreality.api.physics.sparse.CoarseFEMEngine} — Layer 2: Section 級粗粒度 FEM（應力粗估，每 1 秒）</li>
 *   <li>Layer 1: 現有 BFSConnectivityAnalyzer / BeamStressEngine（精確分析，按需觸發）</li>
 * </ul>
 *
 * @since v3.0 Phase 1-2
 */
package com.blockreality.api.physics.sparse;
