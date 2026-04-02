package com.blockreality.api.blueprint;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 藍圖 NBT 序列化/反序列化 — v3fix §2.3
 */
public class BlueprintNBT {

    private static final Logger LOGGER = LogManager.getLogger("BR-BlueprintNBT");

    /** 反序列化方塊數量上限（防止 DoS / OOM） */
    private static final int MAX_BLOCKS = 1_048_576;  // 1M，與 BRConfig.maxSnapshotBlocks 預設值一致

    /** 反序列化結構數量上限 */
    private static final int MAX_STRUCTURES = 10_000;

    private static final String TAG_VERSION     = "version";
    private static final String TAG_METADATA    = "metadata";
    private static final String TAG_NAME        = "name";
    private static final String TAG_AUTHOR      = "author";
    private static final String TAG_TIMESTAMP   = "timestamp";
    private static final String TAG_SIZE        = "size";
    private static final String TAG_BLOCKS      = "blocks";
    private static final String TAG_STRUCTURES  = "structures";
    private static final String TAG_POS         = "pos";
    private static final String TAG_BLOCK_STATE = "blockState";
    private static final String TAG_R_MATERIAL  = "rMaterial";
    private static final String TAG_STRUCTURE_ID = "structureId";
    private static final String TAG_IS_ANCHORED = "isAnchored";
    private static final String TAG_STRESS      = "stressLevel";
    private static final String TAG_IS_DYNAMIC  = "isDynamic";
    private static final String TAG_DYN_RCOMP   = "dynRcomp";
    private static final String TAG_DYN_RTENS   = "dynRtens";
    private static final String TAG_DYN_RSHEAR  = "dynRshear";
    private static final String TAG_DYN_DENSITY = "dynDensity";

    public static CompoundTag write(Blueprint bp) {
        CompoundTag root = new CompoundTag();
        root.putInt(TAG_VERSION, bp.getVersion());

        CompoundTag meta = new CompoundTag();
        meta.putString(TAG_NAME, bp.getName() != null ? bp.getName() : "");
        meta.putString(TAG_AUTHOR, bp.getAuthor() != null ? bp.getAuthor() : "");
        meta.putLong(TAG_TIMESTAMP, bp.getTimestamp());
        root.put(TAG_METADATA, meta);

        CompoundTag size = new CompoundTag();
        size.putInt("x", bp.getSizeX());
        size.putInt("y", bp.getSizeY());
        size.putInt("z", bp.getSizeZ());
        root.put(TAG_SIZE, size);

        ListTag blockList = new ListTag();
        for (Blueprint.BlueprintBlock b : bp.getBlocks()) {
            blockList.add(writeBlock(b));
        }
        root.put(TAG_BLOCKS, blockList);

        ListTag structList = new ListTag();
        for (Blueprint.BlueprintStructure s : bp.getStructures()) {
            structList.add(writeStructure(s));
        }
        root.put(TAG_STRUCTURES, structList);

        return root;
    }

    private static CompoundTag writeBlock(Blueprint.BlueprintBlock b) {
        CompoundTag tag = new CompoundTag();
        CompoundTag pos = new CompoundTag();
        pos.putInt("x", b.getRelX());
        pos.putInt("y", b.getRelY());
        pos.putInt("z", b.getRelZ());
        tag.put(TAG_POS, pos);

        if (b.getBlockState() != null) {
            tag.put(TAG_BLOCK_STATE, NbtUtils.writeBlockState(b.getBlockState()));
        }

        tag.putString(TAG_R_MATERIAL, b.getRMaterialId() != null ? b.getRMaterialId() : "");
        tag.putInt(TAG_STRUCTURE_ID, b.getStructureId());
        tag.putBoolean(TAG_IS_ANCHORED, b.isAnchored());
        tag.putFloat(TAG_STRESS, b.getStressLevel());

        if (b.isDynamic()) {
            tag.putBoolean(TAG_IS_DYNAMIC, true);
            tag.putDouble(TAG_DYN_RCOMP, b.getDynRcomp());
            tag.putDouble(TAG_DYN_RTENS, b.getDynRtens());
            tag.putDouble(TAG_DYN_RSHEAR, b.getDynRshear());
            tag.putDouble(TAG_DYN_DENSITY, b.getDynDensity());
        }

        return tag;
    }

    private static CompoundTag writeStructure(Blueprint.BlueprintStructure s) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", s.getId());
        tag.putFloat("compositeRcomp", s.getCompositeRcomp());
        tag.putFloat("compositeRtens", s.getCompositeRtens());

        ListTag anchors = new ListTag();
        for (int[] ap : s.getAnchorPoints()) {
            CompoundTag apt = new CompoundTag();
            apt.putInt("x", ap[0]);
            apt.putInt("y", ap[1]);
            apt.putInt("z", ap[2]);
            anchors.add(apt);
        }
        tag.put("anchorPoints", anchors);

        return tag;
    }

    public static Blueprint read(CompoundTag root) {
        Blueprint bp = new Blueprint();
        bp.setVersion(root.getInt(TAG_VERSION));

        CompoundTag meta = root.getCompound(TAG_METADATA);
        bp.setName(meta.getString(TAG_NAME));
        bp.setAuthor(meta.getString(TAG_AUTHOR));
        bp.setTimestamp(meta.getLong(TAG_TIMESTAMP));

        CompoundTag size = root.getCompound(TAG_SIZE);
        bp.setSizeX(size.getInt("x"));
        bp.setSizeY(size.getInt("y"));
        bp.setSizeZ(size.getInt("z"));

        ListTag blockList = root.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
        if (blockList.size() > MAX_BLOCKS) {
            LOGGER.warn("[BlueprintNBT] Block count {} exceeds limit {}, rejecting blueprint",
                blockList.size(), MAX_BLOCKS);
            throw new IllegalArgumentException(
                "Blueprint block count " + blockList.size() + " exceeds maximum " + MAX_BLOCKS);
        }
        for (int i = 0; i < blockList.size(); i++) {
            bp.getBlocks().add(readBlock(blockList.getCompound(i)));
        }

        ListTag structList = root.getList(TAG_STRUCTURES, Tag.TAG_COMPOUND);
        if (structList.size() > MAX_STRUCTURES) {
            LOGGER.warn("[BlueprintNBT] Structure count {} exceeds limit {}, rejecting blueprint",
                structList.size(), MAX_STRUCTURES);
            throw new IllegalArgumentException(
                "Blueprint structure count " + structList.size() + " exceeds maximum " + MAX_STRUCTURES);
        }
        for (int i = 0; i < structList.size(); i++) {
            bp.getStructures().add(readStructure(structList.getCompound(i)));
        }

        return bp;
    }

    private static Blueprint.BlueprintBlock readBlock(CompoundTag tag) {
        Blueprint.BlueprintBlock b = new Blueprint.BlueprintBlock();
        CompoundTag pos = tag.getCompound(TAG_POS);
        b.setRelPos(pos.getInt("x"), pos.getInt("y"), pos.getInt("z"));

        if (tag.contains(TAG_BLOCK_STATE)) {
            BlockState state = NbtUtils.readBlockState(
                BuiltInRegistries.BLOCK.asLookup(),
                tag.getCompound(TAG_BLOCK_STATE)
            );
            b.setBlockState(state);
        } else {
            b.setBlockState(Blocks.AIR.defaultBlockState());
        }

        b.setRMaterialId(tag.getString(TAG_R_MATERIAL));
        b.setStructureId(tag.getInt(TAG_STRUCTURE_ID));
        b.setAnchored(tag.getBoolean(TAG_IS_ANCHORED));
        b.setStressLevel(tag.getFloat(TAG_STRESS));

        if (tag.getBoolean(TAG_IS_DYNAMIC)) {
            b.setDynamic(true);
            b.setDynRcomp(tag.getDouble(TAG_DYN_RCOMP));
            b.setDynRtens(tag.getDouble(TAG_DYN_RTENS));
            b.setDynRshear(tag.getDouble(TAG_DYN_RSHEAR));
            b.setDynDensity(tag.getDouble(TAG_DYN_DENSITY));
        }

        return b;
    }

    private static Blueprint.BlueprintStructure readStructure(CompoundTag tag) {
        Blueprint.BlueprintStructure s = new Blueprint.BlueprintStructure();
        s.setId(tag.getInt("id"));
        s.setCompositeRcomp(tag.getFloat("compositeRcomp"));
        s.setCompositeRtens(tag.getFloat("compositeRtens"));

        ListTag anchors = tag.getList("anchorPoints", Tag.TAG_COMPOUND);
        for (int i = 0; i < anchors.size(); i++) {
            CompoundTag apt = anchors.getCompound(i);
            s.getAnchorPoints().add(new int[]{
                apt.getInt("x"), apt.getInt("y"), apt.getInt("z")
            });
        }

        return s;
    }
}
