package com.blockreality.fastdesign.registry;

import com.blockreality.fastdesign.FastDesignMod;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Fast Design 創造模式頁籤
 */
public class FdCreativeTab {

    // 直接建構 ResourceKey 避免 Registries.CREATIVE_MODE_TAB 的 NoSuchFieldError
    @SuppressWarnings("unchecked")
    private static final net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<CreativeModeTab>> CREATIVE_TAB_KEY =
        (net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<CreativeModeTab>>)
            (net.minecraft.resources.ResourceKey<?>)
            net.minecraft.resources.ResourceKey.createRegistryKey(
                new net.minecraft.resources.ResourceLocation("creative_mode_tab"));

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(CREATIVE_TAB_KEY, FastDesignMod.MOD_ID);

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
