package com.blockreality.api.client.render;

import com.blockreality.api.physics.sparse.VoxelSection;

import javax.annotation.Nullable;

/**
 * 渲染管線的 Section 資料來源抽象。
 *
 * <p>★ 架構修復：解耦 {@link PersistentRenderPipeline} 與
 * {@code SparseVoxelOctree}（物理層）。
 *
 * <p>渲染管線只需知道「哪些 Section 需要重建」和「如何取得 Section 資料」，
 * 不需要知道底層是八叉樹、HashMap 或其他空間結構。
 *
 * <p>物理層的 {@code SparseVoxelOctree} 實作此介面（adapter pattern），
 * 渲染層只依賴此介面，不再直接 import 物理套件。
 *
 * @since 1.1.0
 */
public interface SectionDataSource {

    /**
     * 取得需要重建的 dirty Section key 列表。
     * @return dirty section keys，可能為空
     */
    Iterable<Long> getDirtySectionKeys();

    /**
     * 根據 Section 座標取得 VoxelSection。
     *
     * @param sx Section X 座標
     * @param sy Section Y 座標
     * @param sz Section Z 座標
     * @return VoxelSection，若不存在或為空則返回 null
     */
    @Nullable
    VoxelSection getSection(int sx, int sy, int sz);

    /**
     * 從 section key 提取 X 座標。
     */
    int sectionKeyX(long key);

    /**
     * 從 section key 提取 Y 座標。
     */
    int sectionKeyY(long key);

    /**
     * 從 section key 提取 Z 座標。
     */
    int sectionKeyZ(long key);
}
