package com.blockreality.api.fragment;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minecraft entity wrapping a physically simulated structure fragment.
 *
 * Design notes
 * ────────────
 * · Extends plain {@link Entity} (not LivingEntity/Mob) — no AI, health, or equipment.
 * · {@code noPhysics = true} disables vanilla movement/collision; BR rigid body owns physics.
 * · Block map is in LOCAL space (offsets from original CoM at spawn time).
 * · Rotation quaternion is synced each tick via four FLOAT SynchedEntityData slots.
 * · {@link IEntityAdditionalSpawnData} sends the full block map in the spawn packet
 *   so the client can render the fragment immediately without a separate packet.
 * · {@code isPersistenceRequired = false} / {@code shouldBeSaved = false}: fragments are
 *   transient — they are never written to disk. On reload the structure is already
 *   collapsed, so no fragment needs to be replayed.
 *
 * Settle path (server only):
 *   StructureRigidBody.tick() returns true → StructureFragmentManager.onFragmentSettle()
 *   places rubble blocks in world → entity.discard().
 */
public class StructureFragmentEntity extends Entity implements IEntityAdditionalSpawnData {

    private static final Logger LOGGER = LogManager.getLogger("BR-Fragment");

    // ─── SynchedEntityData keys (rotation quaternion) ───

    private static final EntityDataAccessor<Float> DATA_QX =
        SynchedEntityData.defineId(StructureFragmentEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_QY =
        SynchedEntityData.defineId(StructureFragmentEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_QZ =
        SynchedEntityData.defineId(StructureFragmentEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_QW =
        SynchedEntityData.defineId(StructureFragmentEntity.class, EntityDataSerializers.FLOAT);

    // ─── Per-fragment data ───

    /**
     * Block snapshot in LOCAL space (integer offsets from original CoM).
     * Populated on server at spawn; replicated to client via {@link #readSpawnData}.
     */
    private final Map<BlockPos, BlockState> localSnapshot = new HashMap<>();

    /**
     * Server-side rigid body. {@code null} on the client — client only renders.
     */
    private StructureRigidBody rigidBody = null;

    /** Fragment UUID for correlation with CollapseJournal (informational). */
    private UUID fragmentId = null;

    // ─── Constructors ───

    /**
     * Required no-arg entity type constructor (called by Minecraft on both sides).
     * Block map is empty until {@link #readSpawnData} is called on the client,
     * or the server-side constructor populates it.
     */
    public StructureFragmentEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true; // BR rigid body owns all physics
    }

    /**
     * Server-side spawn constructor.
     * Converts world-space block snapshot to LOCAL coordinates and builds the rigid body.
     *
     * @param frag  data snapshot from StructureFragmentDetector
     */
    public StructureFragmentEntity(EntityType<?> type, Level level, StructureFragment frag) {
        super(type, level);
        this.noPhysics = true;
        this.fragmentId = frag.id();

        // Convert world-space block positions → local (CoM-relative integer offsets).
        // Math.floor maps each world block corner to the local grid aligned to CoM.
        double comX = frag.comX(), comY = frag.comY(), comZ = frag.comZ();
        for (Map.Entry<BlockPos, BlockState> e : frag.blockSnapshot().entrySet()) {
            BlockPos world = e.getKey();
            BlockPos local = new BlockPos(
                (int) Math.floor(world.getX() - comX),
                (int) Math.floor(world.getY() - comY),
                (int) Math.floor(world.getZ() - comZ)
            );
            localSnapshot.put(local, e.getValue());
        }

        // Build the rigid body using the local block set (correct inertia about CoM).
        Set<BlockPos> localBlocks = localSnapshot.keySet();
        this.rigidBody = new StructureRigidBody(frag, localBlocks);
    }

    // ─── Entity overrides ───

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_QX, 0.0f);
        entityData.define(DATA_QY, 0.0f);
        entityData.define(DATA_QZ, 0.0f);
        entityData.define(DATA_QW, 1.0f); // identity quaternion w=1
    }

    /**
     * Server-side: advance physics, sync position and rotation.
     * Client-side: do nothing (entity position is updated by vanilla tracking).
     */
    @Override
    public void tick() {
        super.tick();

        if (level() instanceof ServerLevel serverLevel && rigidBody != null) {
            boolean settle = rigidBody.tick(serverLevel);

            // Update entity position to rigid-body CoM
            setPos(rigidBody.px, rigidBody.py, rigidBody.pz);

            // Sync rotation quaternion to clients via SynchedEntityData
            entityData.set(DATA_QX, (float) rigidBody.qx);
            entityData.set(DATA_QY, (float) rigidBody.qy);
            entityData.set(DATA_QZ, (float) rigidBody.qz);
            entityData.set(DATA_QW, (float) rigidBody.qw);

            // Update Minecraft's AABB for entity tracking range checks
            setBoundingBox(rigidBody.worldAABB());

            if (settle) {
                StructureFragmentManager.get(serverLevel).onFragmentSettle(this);
                discard();
            }
        }
    }

    // ─── Public accessors (used by renderer and manager) ───

    /** Block snapshot in LOCAL space (safe read-only view). */
    public Map<BlockPos, BlockState> getLocalSnapshot() {
        return Collections.unmodifiableMap(localSnapshot);
    }

    public float getRotQx() { return entityData.get(DATA_QX); }
    public float getRotQy() { return entityData.get(DATA_QY); }
    public float getRotQz() { return entityData.get(DATA_QZ); }
    public float getRotQw() { return entityData.get(DATA_QW); }

    // ─── IEntityAdditionalSpawnData ───

    /**
     * Server: write local block map into spawn packet.
     * Called once per client when this entity first enters tracking range.
     */
    @Override
    public void writeSpawnData(FriendlyByteBuf buffer) {
        buffer.writeVarInt(localSnapshot.size());
        for (Map.Entry<BlockPos, BlockState> e : localSnapshot.entrySet()) {
            buffer.writeBlockPos(e.getKey());
            buffer.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(e.getValue()));
        }
    }

    /**
     * Client: read local block map from spawn packet.
     * After this call the renderer has all data it needs.
     */
    @Override
    public void readSpawnData(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        localSnapshot.clear();
        for (int i = 0; i < count; i++) {
            BlockPos pos   = buffer.readBlockPos();
            int      stId  = buffer.readVarInt();
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(stId);
            if (state != null) localSnapshot.put(pos, state);
        }
    }

    // ─── Packet ───

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        // NetworkHooks packs writeSpawnData() alongside the vanilla entity packet
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    // ─── NBT (fragments are transient — not saved) ───

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) { /* transient — never loaded */ }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) { /* transient — never saved */ }

}
