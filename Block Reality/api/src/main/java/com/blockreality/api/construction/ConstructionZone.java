package com.blockreality.api.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * 施工區域狀態機 — v3fix §3.2
 */
public class ConstructionZone {

    private static final Logger LOGGER = LogManager.getLogger("BR-Construction");

    private final UUID zoneId;
    private final BlockPos min;
    private final BlockPos max;
    private final String creatorName;
    private ConstructionPhase currentPhase;
    private int blocksPlacedInPhase;
    private long curingStartTick;
    private boolean curingComplete;

    public ConstructionZone(UUID zoneId, BlockPos min, BlockPos max, String creatorName) {
        this.zoneId = zoneId;
        this.min = min;
        this.max = max;
        this.creatorName = creatorName;
        this.currentPhase = ConstructionPhase.EXCAVATION;
        this.blocksPlacedInPhase = 0;
        this.curingStartTick = -1;
        this.curingComplete = false;
    }

    public UUID getZoneId() { return zoneId; }
    public BlockPos getMin() { return min; }
    public BlockPos getMax() { return max; }
    public String getCreatorName() { return creatorName; }
    public ConstructionPhase getCurrentPhase() { return currentPhase; }
    public int getBlocksPlacedInPhase() { return blocksPlacedInPhase; }
    public boolean isCuringComplete() { return curingComplete; }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
            && pos.getY() >= min.getY() && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    public int volume() {
        return (max.getX() - min.getX() + 1)
             * (max.getY() - min.getY() + 1)
             * (max.getZ() - min.getZ() + 1);
    }

    public boolean canPlace(String blockId) {
        return currentPhase.isAllowed(blockId);
    }

    public void recordBlockPlaced(BlockPos pos) {
        blocksPlacedInPhase++;
    }

    public boolean advance(ServerLevel level, ServerPlayer player) {
        if (currentPhase.isFinal()) {
            player.sendSystemMessage(Component.literal(
                "§c[BR-CI] Already in final phase (CURE). Cannot advance further."));
            return false;
        }

        ConstructionPhase oldPhase = currentPhase;
        currentPhase = currentPhase.next();
        blocksPlacedInPhase = 0;

        LOGGER.info("[CI] Zone {} advanced: {} → {}", zoneId, oldPhase, currentPhase);

        player.sendSystemMessage(Component.literal(String.format(
            "§6[BR-CI] §f工序推進：§e%s §f→ §a%s",
            oldPhase.getDisplayNameZh(), currentPhase.getDisplayNameZh()
        )));

        onPhaseEnter(level, player);
        return true;
    }

    public void setPhase(ConstructionPhase phase) {
        this.currentPhase = phase;
        this.blocksPlacedInPhase = 0;
    }

    private void onPhaseEnter(ServerLevel level, ServerPlayer player) {
        switch (currentPhase) {
            case POUR -> {
                player.sendSystemMessage(Component.literal(
                    "§6[BR-CI] §f澆灌階段 — 確認鋼筋和模板已就位後開始放置混凝土。"));
            }
            case CURE -> {
                curingStartTick = level.getServer().getTickCount();
                curingComplete = false;
                player.sendSystemMessage(Component.literal(
                    "§6[BR-CI] §f養護階段 — 混凝土開始凝固（需等待 2400 ticks / 2分鐘）。\n" +
                    "§7養護完成後將自動進行 RC 融合分析。"));
            }
            default -> { }
        }
    }

    public boolean checkCuring(long currentTick, int curingTicks) {
        if (currentPhase != ConstructionPhase.CURE) return false;
        if (curingComplete) return false;
        if (curingStartTick < 0) return false;

        if (currentTick - curingStartTick >= curingTicks) {
            curingComplete = true;
            LOGGER.info("[CI] Zone {} curing complete after {} ticks", zoneId, curingTicks);
            return true;
        }
        return false;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("zoneId", zoneId);
        tag.putInt("minX", min.getX());
        tag.putInt("minY", min.getY());
        tag.putInt("minZ", min.getZ());
        tag.putInt("maxX", max.getX());
        tag.putInt("maxY", max.getY());
        tag.putInt("maxZ", max.getZ());
        tag.putString("creator", creatorName);
        tag.putInt("phase", currentPhase.ordinal());
        tag.putInt("blocksPlaced", blocksPlacedInPhase);
        tag.putLong("curingStart", curingStartTick);
        tag.putBoolean("curingDone", curingComplete);
        return tag;
    }

    public static ConstructionZone load(CompoundTag tag) {
        UUID id = tag.getUUID("zoneId");
        BlockPos min = new BlockPos(tag.getInt("minX"), tag.getInt("minY"), tag.getInt("minZ"));
        BlockPos max = new BlockPos(tag.getInt("maxX"), tag.getInt("maxY"), tag.getInt("maxZ"));
        String creator = tag.getString("creator");

        ConstructionZone zone = new ConstructionZone(id, min, max, creator);
        zone.currentPhase = ConstructionPhase.values()[
            Math.min(tag.getInt("phase"), ConstructionPhase.values().length - 1)];
        zone.blocksPlacedInPhase = tag.getInt("blocksPlaced");
        zone.curingStartTick = tag.getLong("curingStart");
        zone.curingComplete = tag.getBoolean("curingDone");
        return zone;
    }
}
