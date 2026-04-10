package com.blockreality.fastdesign.registry;

import com.blockreality.fastdesign.FastDesignMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Fast Design 創造模式頁籤
 */
public class FdCreativeTab {

    // Use ResourceLocation form to avoid NoSuchFieldError on Registries.CREATIVE_MODE_TAB
    // in production Forge (the Registries class field uses SRG names in the universal jar).
    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(new ResourceLocation("creative_mode_tab"), FastDesignMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> FD_TAB = TABS.register("fd_tab",
        () -> CreativeModeTab.builder()
            .icon(() -> new ItemStack(FdItems.FD_WAND.get()))
            .title(Component.literal("Fast Design"))
            .displayItems((params, output) -> {
                output.accept(FdItems.FD_WAND.get());
            })
            .build()
    );
}
