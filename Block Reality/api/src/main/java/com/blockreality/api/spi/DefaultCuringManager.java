package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of ICuringManager.
 *
 * Tracks concrete curing progress using a ConcurrentHashMap.
 * Each entry stores the start tick and total ticks required.
 *
 * Thread-safe for concurrent access.
 */
@ThreadSafe
public class DefaultCuringManager implements ICuringManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-CuringManager");

    /** Entry tracking curing progress */
    /** ★ review-fix #6: startTick 改為 final，在 @ThreadSafe 類別中確保 JMM 可見性 */
    private static class CuringEntry {
        final int totalTicks;
        final int startTick;

        CuringEntry(int totalTicks, int startTick) {
            this.totalTicks = totalTicks;
            this.startTick = startTick;
        }
    }

    private final Map<BlockPos, CuringEntry> curingBlocks = new ConcurrentHashMap<>();
    // #4 fix: volatile int → AtomicInteger，確保 @ThreadSafe 承諾
    private final AtomicInteger currentTick = new AtomicInteger(0);

    @Override
    public void startCuring(BlockPos pos, int totalTicks) {
        if (totalTicks <= 0) {
            LOGGER.warn("Attempted to start curing with non-positive totalTicks: {}", totalTicks);
            return;
        }

        BlockPos immutablePos = pos.immutable();
        CuringEntry entry = new CuringEntry(totalTicks, currentTick.get());
        CuringEntry prev = curingBlocks.put(immutablePos, entry);

        if (prev != null) {
            LOGGER.debug("Restarted curing at {} (was {} ticks remaining)",
                immutablePos, Math.max(0, prev.totalTicks - (currentTick.get() - prev.startTick)));
        }
    }

    @Override
    public float getCuringProgress(BlockPos pos) {
        CuringEntry entry = curingBlocks.get(pos);
        if (entry == null) return 0.0f;

        int elapsedTicks = currentTick.get() - entry.startTick;
        float progress = Math.min(1.0f, (float) elapsedTicks / entry.totalTicks);
        return Math.max(0.0f, progress);
    }

    @Override
    public boolean isCuringComplete(BlockPos pos) {
        CuringEntry entry = curingBlocks.get(pos);
        if (entry == null) return false;

        int elapsedTicks = currentTick.get() - entry.startTick;
        return elapsedTicks >= entry.totalTicks;
    }

    @Override
    public Set<BlockPos> tickCuring() {
        int tick = currentTick.incrementAndGet();

        // 收集已完成的位置並移除
        Set<BlockPos> completed = new HashSet<>();
        curingBlocks.entrySet().removeIf(entry -> {
            int elapsedTicks = tick - entry.getValue().startTick;
            if (elapsedTicks >= entry.getValue().totalTicks) {
                completed.add(entry.getKey());
                return true;
            }
            return false;
        });

        if (!completed.isEmpty()) {
            LOGGER.debug("[CuringManager] {} blocks completed curing", completed.size());
        }

        return completed;
    }

    @Override
    public void removeCuring(BlockPos pos) {
        curingBlocks.remove(pos);
    }

    @Override
    public int getActiveCuringCount() {
        return curingBlocks.size();
    }
}
