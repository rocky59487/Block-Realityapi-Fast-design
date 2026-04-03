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

    // ─── 版本遷移鏈 ─────────────────────────────────────────────────────────
    //  V0: 初始版本（無版本欄位或 version=0）— 無 metadata/size 標籤
    //  V1: 加入 metadata（name, author, timestamp）和 size 標籤
    //  V2: 加入 stressLevel、isDynamic、dynRcomp/Rtens/Rshear/Density 欄位
    //  每次新增欄位時 bump CURRENT_VERSION 並新增 migrateVxToVy() 方法。
    private static final int CURRENT_VERSION = 2;

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
        root.putInt(TAG_VERSION, CURRENT_VERSION);

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
        int version = root.getInt(TAG_VERSION);
        if (version < CURRENT_VERSION) {
            LOGGER.info("[BlueprintNBT] Migrating blueprint from v{} to v{}", version, CURRENT_VERSION);
            root = migrate(root, version);
        }

        Blueprint bp = new Blueprint();
        bp.setVersion(CURRENT_VERSION);

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

    // ═══════════════════════════════════════════════════════
    //  版本遷移鏈 — 每個步驟為純函數 CompoundTag → CompoundTag
    // ═══════════════════════════════════════════════════════

    /**
     * 依序執行遷移步驟，從 fromVersion 升級到 CURRENT_VERSION。
     */
    private static CompoundTag migrate(CompoundTag root, int fromVersion) {
        CompoundTag result = root.copy(); // 不修改原始資料
        if (fromVersion < 1) {
            result = migrateV0toV1(result);
        }
        if (fromVersion < 2) {
            result = migrateV1toV2(result);
        }
        result.putInt(TAG_VERSION, CURRENT_VERSION);
        return result;
    }

    /**
     * V0 → V1：補充缺失的 metadata 和 size 標籤。
     * V0 藍圖可能完全沒有 metadata 區塊。
     */
    private static CompoundTag migrateV0toV1(CompoundTag root) {
        if (!root.contains(TAG_METADATA)) {
            CompoundTag meta = new CompoundTag();
            meta.putString(TAG_NAME, "migrated_blueprint");
            meta.putString(TAG_AUTHOR, "unknown");
            meta.putLong(TAG_TIMESTAMP, System.currentTimeMillis());
            root.put(TAG_METADATA, meta);
            LOGGER.debug("[BlueprintNBT] V0→V1: Added default metadata");
        }
        if (!root.contains(TAG_SIZE)) {
            // 從方塊列表推算尺寸
            CompoundTag size = new CompoundTag();
            int maxX = 0, maxY = 0, maxZ = 0;
            ListTag blocks = root.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag block = blocks.getCompound(i);
                CompoundTag pos = block.getCompound(TAG_POS);
                maxX = Math.max(maxX, pos.getInt("x") + 1);
                maxY = Math.max(maxY, pos.getInt("y") + 1);
                maxZ = Math.max(maxZ, pos.getInt("z") + 1);
            }
            size.putInt("x", maxX);
            size.putInt("y", maxY);
            size.putInt("z", maxZ);
            root.put(TAG_SIZE, size);
            LOGGER.debug("[BlueprintNBT] V0→V1: Computed size {}x{}x{} from {} blocks",
                maxX, maxY, maxZ, blocks.size());
        }
        return root;
    }

    /**
     * V1 → V2：確保所有方塊擁有 stressLevel 和動態材料欄位。
     * V1 藍圖中這些欄位可能不存在，NBT getFloat/getBoolean 會回傳 0/false 預設值，
     * 因此此遷移主要是「顯式補欄位」以確保序列化完整性。
     */
    private static CompoundTag migrateV1toV2(CompoundTag root) {
        ListTag blocks = root.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
        int migrated = 0;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (!block.contains(TAG_STRESS)) {
                block.putFloat(TAG_STRESS, 0.0f);
                migrated++;
            }
            if (!block.contains(TAG_IS_DYNAMIC)) {
                block.putBoolean(TAG_IS_DYNAMIC, false);
            }
        }
        if (migrated > 0) {
            LOGGER.debug("[BlueprintNBT] V1→V2: Added stressLevel to {} blocks", migrated);
        }
        return root;
    }
}
