package com.blockreality.api.registry;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.fragment.StructureFragmentEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block Reality Entity type registry.
 *
 * Registered via {@code BREntities.ENTITIES.register(modBus)} in BlockRealityMod constructor.
 */
public class BREntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BlockRealityMod.MOD_ID);

    /**
     * Structure fragment entity — represents a physically simulated chunk of blocks
     * that detached from a collapsing structure and is now tumbling under gravity.
     *
     * Lifecycle: spawned by StructureFragmentManager → ticks StructureRigidBody →
     * settles → places rubble blocks → discards itself.
     */
    public static final RegistryObject<EntityType<StructureFragmentEntity>> STRUCTURE_FRAGMENT =
        ENTITIES.register("structure_fragment",
            () -> EntityType.Builder.<StructureFragmentEntity>of(
                    StructureFragmentEntity::new, MobCategory.MISC)
                .sized(1.0f, 1.0f)       // placeholder; overridden by rigid body worldAABB()
                .clientTrackingRange(64)  // visible up to 64 chunks from player
                .updateInterval(1)        // send position every tick (physics is fast)
                .fireImmune()             // blocks handle their own fire resistance
                .build("blockreality:structure_fragment")
        );
}
