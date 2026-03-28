package com.blockreality.api.registry;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.block.RBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block Reality BlockEntity 類型註冊表。
 *
 * RBlockEntity 可搭載在所有 BRBlocks 的方塊上，
 * 透過 validBlocks 指定允許的方塊集合。
 */
public class BRBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BlockRealityMod.MOD_ID);

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<BlockEntityType<RBlockEntity>> R_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("r_block_entity",
            () -> BlockEntityType.Builder.of(
                RBlockEntity::new,
                BRBlocks.R_CONCRETE.get(),
                BRBlocks.R_REBAR.get(),
                BRBlocks.R_STEEL.get(),
                BRBlocks.R_TIMBER.get()
            ).build(null)  // datafixer = null (Forge 慣例)
        );
}
