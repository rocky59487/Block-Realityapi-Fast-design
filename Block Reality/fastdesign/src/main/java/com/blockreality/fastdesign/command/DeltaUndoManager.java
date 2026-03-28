package com.blockreality.fastdesign.command;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Delta Undo Engine — Fast Design v2.0
 *
 * 取代舊版全量快照 UndoManager。只儲存被修改方塊的差異記錄 (BlockChangeRecord)，
 * 大幅降低記憶體消耗，支援 50+ 步歷史回退，10 萬方塊操作也不卡頓。
 *
 * 設計參考：WorldEdit 的 EditSession 差異追蹤 + Litematica 的 Schematic 差異比較機制。
 *
 * 執行緒安全：使用 ConcurrentHashMap + ConcurrentLinkedDeque。
 */
public class DeltaUndoManager {

    private static final Logger LOGGER = LogManager.getLogger("FD-DeltaUndo");

    /** 每個玩家的最大回退步數 */
    private static final int MAX_HISTORY_STEPS = 50;

    // ─── 核心資料結構 ───

    /** 單一方塊的變更記錄 — 只存 pos + 舊狀態/新狀態 + NBT */
    public record BlockChangeRecord(
            BlockPos pos,
            BlockState oldState, @Nullable CompoundTag oldNbt,
            BlockState newState, @Nullable CompoundTag newNbt
    ) {}

    /** 一次操作的差異集合 */
    public record DeltaSnapshot(
            List<BlockChangeRecord> changes,
            String description,
            long timestamp
    ) {
        public int size() { return changes.size(); }
    }

    /** 玩家 UUID → Undo 堆疊 */
    private static final Map<UUID, Deque<DeltaSnapshot>> undoStacks = new ConcurrentHashMap<>();
    /** 玩家 UUID → Redo 堆疊 */
    private static final Map<UUID, Deque<DeltaSnapshot>> redoStacks = new ConcurrentHashMap<>();

    // ─── 差異捕獲 API ───

    /**
     * 開始捕獲一組操作的「先前狀態」。
     * 呼叫者在操作前對所有目標位置調用此方法收集，操作完成後再調用 commitChanges()。
     */
    public static Map<BlockPos, BlockChangeRecord> captureBeforeState(
            ServerLevel level, Collection<BlockPos> positions) {
        Map<BlockPos, BlockChangeRecord> beforeMap = new LinkedHashMap<>();
        for (BlockPos pos : positions) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            CompoundTag nbt = null;
            BlockEntity be = level.getBlockEntity(immutable);
            if (be != null) { nbt = be.saveWithoutMetadata(); }
            // newState/newNbt 暫時設為 null，commitChanges 才填入
            beforeMap.put(immutable, new BlockChangeRecord(immutable, state, nbt, null, null));
        }
        return beforeMap;
    }

    /**
     * 捕獲操作完成後的狀態，生成差異快照並壓入 Undo 堆疊。
     * 只記錄真正發生改變的方塊，跳過 oldState == newState 的位置。
     */
    public static int commitChanges(UUID playerId, ServerLevel level,
                                     Map<BlockPos, BlockChangeRecord> beforeMap, String desc) {
        List<BlockChangeRecord> changes = new ArrayList<>();

        for (var entry : beforeMap.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockChangeRecord before = entry.getValue();

            BlockState newState = level.getBlockState(pos);
            CompoundTag newNbt = null;
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) { newNbt = be.saveWithoutMetadata(); }

            // 只記錄真正有改變的方塊（Delta 核心精神）
            if (!before.oldState().equals(newState) || !Objects.equals(before.oldNbt(), newNbt)) {
                changes.add(new BlockChangeRecord(pos, before.oldState(), before.oldNbt(), newState, newNbt));
            }
        }

        if (changes.isEmpty()) return 0;

        DeltaSnapshot snapshot = new DeltaSnapshot(List.copyOf(changes), desc, System.currentTimeMillis());
        pushUndo(playerId, snapshot);

        // 新操作發生時，清空 Redo 堆疊（標準行為）
        redoStacks.remove(playerId);

        LOGGER.debug("[DeltaUndo] Committed {} block changes for player {} ({})", changes.size(), playerId, desc);
        return changes.size();
    }

    // ─── Undo / Redo ───

    /**
     * 撤銷最近一次操作：將方塊還原到 oldState，並將當前狀態壓入 Redo 堆疊。
     * @return 還原的方塊數量，0 表示無可撤銷
     */
    public static int undo(UUID playerId, ServerLevel level) {
        Deque<DeltaSnapshot> stack = undoStacks.get(playerId);
        if (stack == null || stack.isEmpty()) return 0;

        DeltaSnapshot snapshot = stack.poll();
        if (snapshot == null) return 0;

        // 執行還原，同時收集 Redo 記錄
        List<BlockChangeRecord> redoChanges = new ArrayList<>();
        for (BlockChangeRecord rec : snapshot.changes()) {
            // 保存執行 undo 之前的當前狀態作為 redo
            BlockState currentState = level.getBlockState(rec.pos());
            CompoundTag currentNbt = null;
            BlockEntity currentBe = level.getBlockEntity(rec.pos());
            if (currentBe != null) { currentNbt = currentBe.saveWithoutMetadata(); }

            redoChanges.add(new BlockChangeRecord(rec.pos(), currentState, currentNbt, rec.oldState(), rec.oldNbt()));

            // 還原方塊
            level.setBlock(rec.pos(), rec.oldState(), 3);
            if (rec.oldNbt() != null) {
                BlockEntity be = level.getBlockEntity(rec.pos());
                if (be != null) {
                    be.load(rec.oldNbt());
                    be.setChanged();
                }
            }
        }

        // 壓入 Redo 堆疊
        pushRedo(playerId, new DeltaSnapshot(List.copyOf(redoChanges), snapshot.description(), System.currentTimeMillis()));

        return snapshot.size();
    }

    /**
     * 重做最近一次被撤銷的操作。
     * @return 重做的方塊數量，0 表示無可重做
     */
    public static int redo(UUID playerId, ServerLevel level) {
        Deque<DeltaSnapshot> stack = redoStacks.get(playerId);
        if (stack == null || stack.isEmpty()) return 0;

        DeltaSnapshot snapshot = stack.poll();
        if (snapshot == null) return 0;

        // 還原到 redo 目標狀態，同時收集 undo 記錄
        List<BlockChangeRecord> undoChanges = new ArrayList<>();
        for (BlockChangeRecord rec : snapshot.changes()) {
            BlockState currentState = level.getBlockState(rec.pos());
            CompoundTag currentNbt = null;
            BlockEntity currentBe = level.getBlockEntity(rec.pos());
            if (currentBe != null) { currentNbt = currentBe.saveWithoutMetadata(); }

            undoChanges.add(new BlockChangeRecord(rec.pos(), currentState, currentNbt, rec.newState(), rec.newNbt()));

            level.setBlock(rec.pos(), rec.newState(), 3);
            if (rec.newNbt() != null) {
                BlockEntity be = level.getBlockEntity(rec.pos());
                if (be != null) {
                    be.load(rec.newNbt());
                    be.setChanged();
                }
            }
        }

        pushUndo(playerId, new DeltaSnapshot(List.copyOf(undoChanges), snapshot.description(), System.currentTimeMillis()));
        return snapshot.size();
    }

    // ─── 堆疊管理 ───

    private static void pushUndo(UUID playerId, DeltaSnapshot snapshot) {
        Deque<DeltaSnapshot> stack = undoStacks.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        stack.push(snapshot);
        while (stack.size() > MAX_HISTORY_STEPS) { stack.removeLast(); }
    }

    private static void pushRedo(UUID playerId, DeltaSnapshot snapshot) {
        Deque<DeltaSnapshot> stack = redoStacks.computeIfAbsent(playerId, k -> new ConcurrentLinkedDeque<>());
        stack.push(snapshot);
        while (stack.size() > MAX_HISTORY_STEPS) { stack.removeLast(); }
    }

    public static int getUndoStackSize(UUID playerId) {
        Deque<DeltaSnapshot> stack = undoStacks.get(playerId);
        return stack == null ? 0 : stack.size();
    }

    public static int getRedoStackSize(UUID playerId) {
        Deque<DeltaSnapshot> stack = redoStacks.get(playerId);
        return stack == null ? 0 : stack.size();
    }

    @Nullable
    public static String peekUndoDescription(UUID playerId) {
        Deque<DeltaSnapshot> stack = undoStacks.get(playerId);
        if (stack == null || stack.isEmpty()) return null;
        DeltaSnapshot top = stack.peek();
        return top != null ? top.description() : null;
    }

    @Nullable
    public static String peekRedoDescription(UUID playerId) {
        Deque<DeltaSnapshot> stack = redoStacks.get(playerId);
        if (stack == null || stack.isEmpty()) return null;
        DeltaSnapshot top = stack.peek();
        return top != null ? top.description() : null;
    }

    /** 清除指定玩家的所有歷史記錄（斷線時呼叫）。 */
    public static void clear(UUID playerId) {
        undoStacks.remove(playerId);
        redoStacks.remove(playerId);
    }

    public static void onPlayerDisconnect(UUID playerId) {
        clear(playerId);
    }
}
