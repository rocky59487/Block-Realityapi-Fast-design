package com.blockreality.api.block;

import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.chisel.VoxelGrid;
import com.blockreality.api.material.BlockType;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.DynamicMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.registry.BRBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Block Reality 物理方塊實體 — 儲存材料參數與結構狀態。
 *
 * 架構決策 AD-1 (BlockEntity over Capability):
 *   1. NBT 持久化：save/load 自動處理材料+應力存檔
 *   2. Client 同步：getUpdateTag + ClientboundBlockEntityDataPacket
 *   3. Tick 能力：未來可加養護計時 (RC curing)
 *
 * 效能設計：
 *   - 同步節流：50ms 間隔，避免高頻 sendBlockUpdated 壅塞網路
 *   - 批量更新：setStressLevelBatch() 跳過逐一同步，flushSync() 統一觸發
 *
 * @since 1.0.0
 */
public class RBlockEntity extends BlockEntity {

    // ─── NBT 標籤常數 ───
    private static final String TAG_MATERIAL = "br_material";
    private static final String TAG_BLOCK_TYPE = "br_block_type";
    private static final String TAG_STRUCTURE = "br_structure_id";
    private static final String TAG_ANCHORED = "br_anchored";
    private static final String TAG_STRESS = "br_stress";
    // Legacy fields removed: supportParent, currentLoad (PFSF handles load on GPU)
    // DynamicMaterial 額外 NBT 標籤
    private static final String TAG_IS_DYNAMIC = "br_is_dynamic_mat";
    private static final String TAG_DYN_RCOMP = "br_dyn_rcomp";
    private static final String TAG_DYN_RTENS = "br_dyn_rtens";
    private static final String TAG_DYN_RSHEAR = "br_dyn_rshear";
    private static final String TAG_DYN_DENSITY = "br_dyn_density";
    // 雕刻狀態 NBT 標籤
    private static final String TAG_CHISEL_SHAPE = "br_chisel_shape";
    private static final String TAG_CHISEL_VOXELS = "br_chisel_voxels";

    // ─── 同步節流 ───
    private static final long SYNC_INTERVAL_MS = 50;

    // ─── 核心欄位 ───
    private RMaterial material = DefaultMaterial.CONCRETE;  // DEV-4 fix: 規格要求預設 CONCRETE
    private BlockType blockType = BlockType.PLAIN;
    private int structureId = -1;
    private boolean isAnchored = false;
    private volatile float stressLevel = 0.0f;

    /**
     * ★ B-2: RC 融合前的原始材料 — 降級時恢復用。
     * 當方塊從 REBAR/CONCRETE 升級為 RC_NODE 時保存原始材料，
     * 降級時可以精確恢復而非使用 DefaultMaterial 預設值。
     */
    @Nullable
    private RMaterial preFusionMaterial = null;

    /** 雕刻狀態 — 預設為完整方塊，向後相容 */
    private ChiselState chiselState = ChiselState.FULL;

    /**
     * ★ audit-fix M-4: 自訂形狀的 VoxelShape 快取。
     * 模板形狀已在 RBlock.SHAPE_CACHE 中快取，此處僅快取 CUSTOM 形狀。
     * setChiselState() 時清除。
     */
    @Nullable
    private transient net.minecraft.world.phys.shapes.VoxelShape cachedCustomShape = null;

    // ─── 區塊卸載標記 ───
    /** ★ H-1: 區塊正在卸載時為 true，setRemoved() 中跳過崩塌邏輯 */
    private transient boolean chunkUnloading = false;

    // ─── 同步控制 ───
    private volatile long lastSyncTime = 0;
    private volatile boolean pendingSync = false;
    /** ★ B-5 fix: 防止 scheduleDeferredFlush 重複排入佇列 */
    private final AtomicBoolean deferredFlushScheduled = new AtomicBoolean(false);

    public RBlockEntity(BlockPos pos, BlockState state) {
        super(BRBlockEntities.R_BLOCK_ENTITY.get(), pos, state);
    }

    // ─── Chunk Unload Support (v7 H-1) ───

    /**
     * ★ H-1: Called by Forge when this BE's chunk is being unloaded.
     * Sets the chunkUnloading flag so that setRemoved() can distinguish
     * between a chunk-unload removal (lightweight) and a player-break
     * removal (triggers cascade collapse).
     */
    @Override
    public void onChunkUnloaded() {
        this.chunkUnloading = true;
    }

    /**
     * Returns true if this block entity is being removed due to chunk unload
     * (as opposed to player destruction or explosion).
     */
    public boolean isChunkUnloading() {
        return chunkUnloading;
    }

    // ─── Getters ───

    public RMaterial getMaterial() { return material; }
    public BlockType getBlockType() { return blockType; }
    public int getStructureId() { return structureId; }
    public boolean isAnchored() { return isAnchored; }
    public float getStressLevel() { return stressLevel; }
    public boolean hasPendingSync() { return pendingSync; }
    public boolean hasSupport() { return isAnchored; }
    @Nullable public RMaterial getPreFusionMaterial() { return preFusionMaterial; }
    public void setPreFusionMaterial(@Nullable RMaterial mat) { this.preFusionMaterial = mat; setChanged(); }

    /**
     * 取得自重 (kg) — density × fillRatio。
     * 半磚 fillRatio=0.5 → 重量減半。
     */
    public float getSelfWeight() {
        return (float) (material.getDensity() * chiselState.fillRatio());
    }

    public ChiselState getChiselState() { return chiselState; }

    public void setChiselState(ChiselState state) {
        this.chiselState = state != null ? state : ChiselState.FULL;
        this.cachedCustomShape = null; // ★ audit-fix M-4: 清除形狀快取
        setChanged();
        syncToClient();
    }

    /**
     * ★ audit-fix M-4: 取得或建立自訂形狀的 VoxelShape 快取。
     * 僅用於 CUSTOM 形狀（非模板），避免每次 getShape() 重建。
     */
    @Nullable
    public net.minecraft.world.phys.shapes.VoxelShape getCachedCustomShape() {
        return cachedCustomShape;
    }

    public void setCachedCustomShape(net.minecraft.world.phys.shapes.VoxelShape shape) {
        this.cachedCustomShape = shape;
    }

    // ─── Setters (with sync) ───

    public void setMaterial(RMaterial material) {
        this.material = material;
        setChanged();
        syncToClient();
    }

    public void setBlockType(BlockType blockType) {
        this.blockType = blockType;
        setChanged();
        syncToClient();
    }

    public void setStructureId(int structureId) {
        this.structureId = structureId;
        setChanged();
        // structureId 不需要同步到 client
    }

    public void setAnchored(boolean anchored) {
        this.isAnchored = anchored;
        setChanged();
    }

    /**
     * 設定應力值並立即同步到 client（節流版）。
     * 適用於單一方塊更新。
     */
    public void setStressLevel(float stressLevel) {
        this.stressLevel = Math.max(0f, Math.min(1f, stressLevel));
        setChanged();
        syncToClient();
    }

    /**
     * 設定應力值但不立即同步。
     * 適用於批量更新 — SPH 引擎一次更新數百方塊時使用。
     * 更新完成後呼叫 flushSync() 統一觸發同步。
     */
    public void setStressLevelBatch(float stressLevel) {
        this.stressLevel = Math.max(0f, Math.min(1f, stressLevel));
        this.pendingSync = true;
        setChanged();
    }

    /**
     * 統一觸發 client 同步 — 批量更新後呼叫。
     */
    public void flushSync() {
        if (pendingSync) {
            pendingSync = false;
            forceSyncToClient();
        }
    }

    // ─── Client 同步（50ms 節流） ───

    private void syncToClient() {
        long now = System.currentTimeMillis();
        if (now - lastSyncTime < SYNC_INTERVAL_MS) {
            pendingSync = true;
            // ★ W-10 fix: 安排延遲 flush，確保 pendingSync 不會永遠卡住
            scheduleDeferredFlush();
            return;
        }
        forceSyncToClient();
    }

    private void forceSyncToClient() {
        lastSyncTime = System.currentTimeMillis();
        pendingSync = false;
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * ★ W-10 fix: 延遲 flush — 在節流期結束後的下一個 tick 自動同步。
     * ★ B-5 fix: 加入 deferredFlushScheduled 防護，避免高頻呼叫重複排入佇列。
     * 使用 server.execute() 排入主執行緒佇列，確保在節流間隔過後執行。
     */
    private void scheduleDeferredFlush() {
        if (level == null || level.isClientSide) return;
        if (!deferredFlushScheduled.compareAndSet(false, true)) return; // ★ B-5: 已排程，不重複排入
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            serverLevel.getServer().execute(() -> {
                deferredFlushScheduled.set(false);
                if (pendingSync && level != null && !level.isClientSide) {
                    forceSyncToClient();
                }
            });
        }
    }

    // ─── NBT 序列化 ───

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(TAG_MATERIAL, material.getMaterialId());
        // DynamicMaterial 序列化：存 ID 不夠，還需要四個數值
        boolean isDynamic = material instanceof DynamicMaterial;
        tag.putBoolean(TAG_IS_DYNAMIC, isDynamic);
        if (isDynamic) {
            tag.putDouble(TAG_DYN_RCOMP, material.getRcomp());
            tag.putDouble(TAG_DYN_RTENS, material.getRtens());
            tag.putDouble(TAG_DYN_RSHEAR, material.getRshear());
            tag.putDouble(TAG_DYN_DENSITY, material.getDensity());
        }
        tag.putString(TAG_BLOCK_TYPE, blockType.getSerializedName());
        tag.putInt(TAG_STRUCTURE, structureId);
        tag.putBoolean(TAG_ANCHORED, isAnchored);
        tag.putFloat(TAG_STRESS, stressLevel);
        // ★ B-2: 原始材料（RC 融合前）
        tag.putBoolean("br_has_prefusion", preFusionMaterial != null);
        if (preFusionMaterial != null) {
            tag.putString("br_prefusion_id", preFusionMaterial.getMaterialId());
        }
        // 雕刻狀態：模板只存 shape name，CUSTOM 額外存體素資料
        if (!chiselState.isFull()) {
            tag.putString(TAG_CHISEL_SHAPE, chiselState.shape().getSerializedName());
            if (chiselState.isCustom()) {
                chiselState.voxelGrid().saveToTag(tag);
            }
        }
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        if (tag.contains(TAG_MATERIAL)) {
            String matId = tag.getString(TAG_MATERIAL);
            if (tag.getBoolean(TAG_IS_DYNAMIC)) {
                // DynamicMaterial：從 NBT 重建完整的動態材料
                this.material = DynamicMaterial.ofCustom(
                    matId,
                    tag.getDouble(TAG_DYN_RCOMP),
                    tag.getDouble(TAG_DYN_RTENS),
                    tag.getDouble(TAG_DYN_RSHEAR),
                    tag.getDouble(TAG_DYN_DENSITY)
                );
            } else {
                this.material = DefaultMaterial.fromId(matId);
            }
        }
        if (tag.contains(TAG_BLOCK_TYPE)) {
            this.blockType = BlockType.fromString(tag.getString(TAG_BLOCK_TYPE));
        }
        if (tag.contains(TAG_STRUCTURE)) {
            this.structureId = tag.getInt(TAG_STRUCTURE);
        }
        if (tag.contains(TAG_ANCHORED)) {
            this.isAnchored = tag.getBoolean(TAG_ANCHORED);
        }
        if (tag.contains(TAG_STRESS)) {
            this.stressLevel = tag.getFloat(TAG_STRESS);
        }
        // ★ B-2: 原始材料復原
        if (tag.getBoolean("br_has_prefusion") && tag.contains("br_prefusion_id")) {
            this.preFusionMaterial = DefaultMaterial.fromId(tag.getString("br_prefusion_id"));
        } else {
            this.preFusionMaterial = null;
        }
        // 雕刻狀態復原（向後相容：無此標籤時預設 FULL）
        if (tag.contains(TAG_CHISEL_SHAPE)) {
            SubBlockShape shape = SubBlockShape.fromString(tag.getString(TAG_CHISEL_SHAPE));
            if (shape == SubBlockShape.CUSTOM && tag.contains(TAG_CHISEL_VOXELS)) {
                VoxelGrid grid = VoxelGrid.loadFromTag(tag);
                this.chiselState = new ChiselState(shape, grid);
            } else {
                this.chiselState = ChiselState.ofShape(shape);
            }
        } else {
            this.chiselState = ChiselState.FULL;
        }
        this.cachedCustomShape = null; // ★ audit-fix M-4: 載入 NBT 時清除形狀快取
    }

    // ─── Client 同步包 ───

    @Override
    @NotNull
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    @Nullable
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        }
    }
}
