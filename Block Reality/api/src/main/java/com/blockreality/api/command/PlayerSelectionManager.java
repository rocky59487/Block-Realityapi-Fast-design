package com.blockreality.api.command;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家選取區域管理器 — v3fix §2.1
 */
@javax.annotation.concurrent.ThreadSafe // ConcurrentHashMap for multi-player selections
public class PlayerSelectionManager {

    private static final Map<UUID, BlockPos> pos1Map = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> pos2Map = new ConcurrentHashMap<>();

    public static void setPos1(UUID playerId, BlockPos pos) {
        pos1Map.put(playerId, pos.immutable());
    }

    public static void setPos2(UUID playerId, BlockPos pos) {
        pos2Map.put(playerId, pos.immutable());
    }

    @Nullable
    public static BlockPos getPos1(UUID playerId) {
        return pos1Map.get(playerId);
    }

    @Nullable
    public static BlockPos getPos2(UUID playerId) {
        return pos2Map.get(playerId);
    }

    public static boolean hasSelection(UUID playerId) {
        return pos1Map.containsKey(playerId) && pos2Map.containsKey(playerId);
    }

    public static SelectionBox getSelection(UUID playerId) {
        BlockPos p1 = pos1Map.get(playerId);
        BlockPos p2 = pos2Map.get(playerId);
        if (p1 == null || p2 == null) {
            throw new IllegalStateException("Selection incomplete: set pos1 and pos2 first");
        }

        BlockPos min = new BlockPos(
            Math.min(p1.getX(), p2.getX()),
            Math.min(p1.getY(), p2.getY()),
            Math.min(p1.getZ(), p2.getZ())
        );
        BlockPos max = new BlockPos(
            Math.max(p1.getX(), p2.getX()),
            Math.max(p1.getY(), p2.getY()),
            Math.max(p1.getZ(), p2.getZ())
        );

        return new SelectionBox(min, max);
    }

    public static void clear(UUID playerId) {
        pos1Map.remove(playerId);
        pos2Map.remove(playerId);
    }

    public record SelectionBox(BlockPos min, BlockPos max) {
        public int volume() {
            return (max.getX() - min.getX() + 1)
                 * (max.getY() - min.getY() + 1)
                 * (max.getZ() - min.getZ() + 1);
        }

        public int sizeX() { return max.getX() - min.getX() + 1; }
        public int sizeY() { return max.getY() - min.getY() + 1; }
        public int sizeZ() { return max.getZ() - min.getZ() + 1; }

        public Iterable<BlockPos> allPositions() {
            return BlockPos.betweenClosed(min, max);
        }
    }
}
