package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;

/**
 * VoxelSection 內非空氣方塊遍歷的回呼介面。
 * 接收本地座標 (0~15) 和方塊狀態。
 */
@FunctionalInterface
public interface VoxelVisitor {
    void visit(int localX, int localY, int localZ, RBlockState state);
}
