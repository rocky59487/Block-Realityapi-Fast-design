package com.blockreality.api.registry;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.block.RBlock;
import com.blockreality.api.item.ChiselItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block Reality 方塊註冊表。
 *
 * 目前註冊的方塊：
 *   - r_concrete: RC 混凝土方塊 (預設材料 STONE)
 *   - r_rebar:    鋼筋方塊
 *   - r_steel:    結構鋼方塊
 *   - r_timber:   木結構方塊
 *
 * 每個方塊都搭載 RBlockEntity，可獨立設定材料參數。
 * BlockItem 同步註冊，讓玩家可從創造模式取得方塊。
 */
public class BRBlocks {

    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, BlockRealityMod.MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, BlockRealityMod.MOD_ID);

    // ─── 方塊定義 ───

    public static final RegistryObject<Block> R_CONCRETE = BLOCKS.register("r_concrete",
        () -> new RBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .strength(3.0f, 9.0f)
            .sound(SoundType.STONE)
            .requiresCorrectToolForDrops()
        ));

    public static final RegistryObject<Block> R_REBAR = BLOCKS.register("r_rebar",
        () -> new RBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0f, 12.0f)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
        ));

    public static final RegistryObject<Block> R_STEEL = BLOCKS.register("r_steel",
        () -> new RBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(5.0f, 15.0f)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
        ));

    public static final RegistryObject<Block> R_TIMBER = BLOCKS.register("r_timber",
        () -> new RBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.0f, 3.0f)
            .sound(SoundType.WOOD)
        ));

    // ─── BlockItem 自動註冊 ───

    public static final RegistryObject<Item> R_CONCRETE_ITEM = ITEMS.register("r_concrete",
        () -> new BlockItem(R_CONCRETE.get(), new Item.Properties()));

    public static final RegistryObject<Item> R_REBAR_ITEM = ITEMS.register("r_rebar",
        () -> new BlockItem(R_REBAR.get(), new Item.Properties()));

    public static final RegistryObject<Item> R_STEEL_ITEM = ITEMS.register("r_steel",
        () -> new BlockItem(R_STEEL.get(), new Item.Properties()));

    public static final RegistryObject<Item> R_TIMBER_ITEM = ITEMS.register("r_timber",
        () -> new BlockItem(R_TIMBER.get(), new Item.Properties()));

    // ─── 雕刻刀 ───

    public static final RegistryObject<Item> CHISEL = ITEMS.register("chisel",
        () -> new ChiselItem());
}
