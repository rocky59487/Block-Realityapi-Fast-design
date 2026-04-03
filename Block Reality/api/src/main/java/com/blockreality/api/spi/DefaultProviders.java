package com.blockreality.api.spi;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.material.BlockType;
import com.blockreality.api.physics.LoadPathEngine;
import com.blockreality.api.physics.RCFusionDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 預設 SPI 提供者工廠 — 隔離 {@link ModuleRegistry} 與具體實作之間的依賴。
 *
 * <p>★ 審計修復：解決 ModuleRegistry 的 DIP（依賴反轉原則）違規。
 * 原本 ModuleRegistry 直接 import LoadPathEngine、RCFusionDetector 等具體類別，
 * 現在這些 import 全部集中在此工廠類別中。ModuleRegistry 僅依賴此工廠和 SPI 接口。
 *
 * <p>好處：
 * <ul>
 *   <li>ModuleRegistry 不再依賴 physics 或 material 套件的具體實作</li>
 *   <li>測試時可以替換此工廠（或直接透過 set*() 注入 mock）</li>
 *   <li>未來可改為 ServiceLoader 機制，無需修改 ModuleRegistry</li>
 * </ul>
 */
public final class DefaultProviders {

    private DefaultProviders() {} // 工具類別，禁止實例化

    /**
     * 建立預設的 {@link ILoadPathManager} 實作。
     * 委託給 {@link LoadPathEngine} 的靜態方法。
     */
    public static ILoadPathManager createLoadPathManager() {
        return new ILoadPathManager() {
            @Override
            public boolean onBlockPlaced(ServerLevel level, BlockPos pos) {
                return LoadPathEngine.onBlockPlaced(level, pos);
            }

            @Override
            public int onBlockBroken(ServerLevel level, BlockPos brokenPos) {
                return LoadPathEngine.onBlockBroken(level, brokenPos);
            }

            @Override
            public int onBlockBrokenCached(ServerLevel level, BlockPos brokenPos,
                                            BlockPos cachedParent, float cachedLoad) {
                return LoadPathEngine.onBlockBrokenCached(level, brokenPos, cachedParent, cachedLoad);
            }

            @Override
            public BlockPos findBestSupport(ServerLevel level, BlockPos pos, RBlockEntity self) {
                return LoadPathEngine.findBestSupport(level, pos, self);
            }

            @Override
            public void propagateLoadDown(ServerLevel level, BlockPos startPos, float delta) {
                LoadPathEngine.propagateLoadDown(level, startPos, delta);
            }

            @Override
            public List<BlockPos> traceLoadPath(ServerLevel level, BlockPos pos) {
                return LoadPathEngine.traceLoadPath(level, pos);
            }
        };
    }

    /**
     * 建立預設的 {@link IFusionDetector} 實作。
     * 委託給 {@link RCFusionDetector} 的靜態方法。
     */
    public static IFusionDetector createFusionDetector() {
        return new IFusionDetector() {
            @Override
            public int checkAndFuse(ServerLevel level, BlockPos pos) {
                return RCFusionDetector.checkAndFuse(level, pos);
            }

            @Override
            public int checkAndDowngrade(ServerLevel level, BlockPos brokenPos, BlockType brokenType) {
                return RCFusionDetector.checkAndDowngrade(level, brokenPos, brokenType);
            }
        };
    }
}
