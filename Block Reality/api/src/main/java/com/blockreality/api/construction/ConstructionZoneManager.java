package com.blockreality.api.construction;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.physics.RCFusionDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 施工區域管理器 — v3fix §3.2
 */
@javax.annotation.concurrent.ThreadSafe // ConcurrentHashMap for multi-player
public class ConstructionZoneManager extends SavedData {

    private static final Logger LOGGER = LogManager.getLogger("BR-CI-Manager");
    private static final String DATA_KEY = "blockreality_construction_zones";

    private final ConcurrentHashMap<UUID, ConstructionZone> zones = new ConcurrentHashMap<>();

    public static ConstructionZoneManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            ConstructionZoneManager::load,
            ConstructionZoneManager::new,
            DATA_KEY
        );
    }

    public ConstructionZoneManager() {}

    public ConstructionZone createZone(BlockPos min, BlockPos max, String creatorName) {
        UUID id = UUID.randomUUID();
        ConstructionZone zone = new ConstructionZone(id, min, max, creatorName);
        zones.put(id, zone);
        setDirty();
        LOGGER.info("[CI] Zone created: {} by {} ({} → {})", id, creatorName, min, max);
        return zone;
    }

    public boolean removeZone(UUID id) {
        ConstructionZone removed = zones.remove(id);
        if (removed != null) {
            setDirty();
            LOGGER.info("[CI] Zone removed: {}", id);
            return true;
        }
        return false;
    }

    @Nullable
    public ConstructionZone getZone(UUID id) {
        return zones.get(id);
    }

    @Nullable
    public ConstructionZone getZoneAt(BlockPos pos) {
        for (ConstructionZone zone : zones.values()) {
            if (zone.contains(pos)) return zone;
        }
        return null;
    }

    public Collection<ConstructionZone> getAllZones() {
        return Collections.unmodifiableCollection(zones.values());
    }

    public int getZoneCount() {
        return zones.size();
    }

    public void tickCuring(ServerLevel level, long currentTick) {
        int curingTicks = BRConfig.INSTANCE.rcFusionCuringTicks.get();
        for (ConstructionZone zone : zones.values()) {
            if (zone.checkCuring(currentTick, curingTicks)) {
                onCuringComplete(level, zone);
                setDirty();
            }
        }
    }

    private void onCuringComplete(ServerLevel level, ConstructionZone zone) {
        LOGGER.info("[CI] Zone {} curing complete — triggering RC fusion analysis", zone.getZoneId());

        int totalFusions = 0;
        BlockPos min = zone.getMin();
        BlockPos max = zone.getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    int fused = RCFusionDetector.checkAndFuse(level, new BlockPos(x, y, z));
                    totalFusions += fused;
                }
            }
        }

        if (totalFusions > 0) {
            LOGGER.info("[CI] Zone {} RC fusion complete: {} pairs fused",
                zone.getZoneId(), totalFusions);
        }

        for (ServerPlayer player : level.players()) {
            if (zone.contains(player.blockPosition()) ||
                player.blockPosition().closerThan(zone.getMin(), 32)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    String.format("§6[BR-CI] §a養護完成！§f區域 RC 融合分析完成 — %d 對鋼筋混凝土融合",
                        totalFusions)));
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag compound) {
        ListTag list = new ListTag();
        for (ConstructionZone zone : zones.values()) {
            list.add(zone.save());
        }
        compound.put("zones", list);
        return compound;
    }

    public static ConstructionZoneManager load(CompoundTag compound) {
        ConstructionZoneManager manager = new ConstructionZoneManager();
        ListTag list = compound.getList("zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ConstructionZone zone = ConstructionZone.load(list.getCompound(i));
            manager.zones.put(zone.getZoneId(), zone);
        }
        LOGGER.info("[CI] Loaded {} construction zones from world data", manager.zones.size());
        return manager;
    }
}
