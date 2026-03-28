package com.blockreality.fastdesign.command;

import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.fastdesign.config.FastDesignConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * /fd undo 還原系統 — v3fix §3.6
 *
 * Thread-safe: 使用 ConcurrentHashMap + ConcurrentLinkedDeque。
 * 清理: 透過 {@link #onPlayerDisconnect(UUID)} 釋放斷線玩家的記憶體。
 */
public class UndoManager {

    private static final Map<UUID, Deque<UndoSnapshot>> stacks = new ConcurrentHashMap<>();

    public record BlockRecord(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {}
    public record UndoSnapshot(List<BlockRecord> records, String description) {}

    public static void pushSnapshot(UUID playerId, ServerLevel level,
                                     PlayerSelectionManager.SelectionBox box, String desc) {
        List<BlockRecord> records = new ArrayList<>();
        for (BlockPos pos : box.allPositions()) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            CompoundTag nbt = null;
            BlockEntity be = level.getBlockEntity(immutable);
            if (be != null) { nbt = be.saveWithoutMetadata(); }
            records.add(new BlockRecord(immutable, state, nbt));
        }

        pushToStack(playerId, new UndoSnapshot(List.copyOf(records), desc));
    }

    public static void pushSnapshotForPositions(UUID playerId, ServerLevel level,
                                                 Collection<BlockPos> positions, String desc) {
        List<BlockRecord> records = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            CompoundTag nbt = null;
            BlockEntity be = level.getBlockEntity(immutable);
            if (be != null) { nbt = be.saveWithoutMetadata(); }
            records.add(new BlockRecord(immutable, state, nbt));
        }

        pushToStack(playerId, new UndoSnapshot(List.copyOf(records), desc));
    }

    /**
     * 共用的 push 邏輯 — 限制 stack 大小，超過上限丟棄最舊的快照。
     */
    private static void pushToStack(UUID playerId, UndoSnapshot snapshot) {
        Deque<UndoSnapshot> stack = stacks.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        int maxStack = FastDesignConfig.getUndoStackSize();
        // 先 push 再 trim，避免丟掉新快照
        stack.push(snapshot);
        while (stack.size() > maxStack) {
            stack.removeLast();
        }
    }

    public static int undo(UUID playerId, ServerLevel level) {
        Deque<UndoSnapshot> stack = stacks.get(playerId);
        if (stack == null || stack.isEmpty()) { return 0; }

        UndoSnapshot snapshot = stack.poll(); // poll = thread-safe pop
        if (snapshot == null) return 0;

        for (BlockRecord rec : snapshot.records()) {
            // 先設定方塊，再還原 BlockEntity 資料
            level.setBlock(rec.pos(), rec.state(), 3);
            if (rec.nbt() != null) {
                BlockEntity be = level.getBlockEntity(rec.pos());
                if (be != null) {
                    be.load(rec.nbt());
                    be.setChanged(); // 標記已變更，確保 chunk 存檔
                }
            }
        }
        return snapshot.records().size();
    }

    public static int getStackSize(UUID playerId) {
        Deque<UndoSnapshot> stack = stacks.get(playerId);
        return stack == null ? 0 : stack.size();
    }

    @Nullable
    public static String peekDescription(UUID playerId) {
        Deque<UndoSnapshot> stack = stacks.get(playerId);
        if (stack == null || stack.isEmpty()) return null;
        UndoSnapshot top = stack.peek();
        return top != null ? top.description() : null;
    }

    /**
     * 清除指定玩家的所有 undo 記錄。
     * 應在玩家斷線時呼叫以防止記憶體洩漏。
     */
    public static void clear(UUID playerId) {
        stacks.remove(playerId);
    }

    /**
     * 玩家斷線清理 — 由 FastDesignMod 的 PlayerLoggedOutEvent 呼叫。
     */
    public static void onPlayerDisconnect(UUID playerId) {
        clear(playerId);
    }
}
