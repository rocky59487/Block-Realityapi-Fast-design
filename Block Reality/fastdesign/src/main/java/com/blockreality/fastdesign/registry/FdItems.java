package com.blockreality.fastdesign.registry;

import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.item.FdWandItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Fast Design 物品註冊表
 */
public class FdItems {

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, FastDesignMod.MOD_ID);

    // ─── FD 游標 (Blueprint Wand) ───
    public static final RegistryObject<FdWandItem> FD_WAND =
        ITEMS.register("fd_wand", FdWandItem::new);
}
