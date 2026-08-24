package com.infpack;

import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.ShapedOreRecipe;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        InfinitePackMod.itemInfinitePack = new ItemInfinitePack()
                .setUnlocalizedName("infinitePack")
                .setCreativeTab(InfinitePackMod.CREATIVE_TAB);
        GameRegistry.registerItem(InfinitePackMod.itemInfinitePack, "infinitePack");
        NetworkRegistry.INSTANCE.registerGuiHandler(InfinitePackMod.instance, new GuiHandler());
    }

    public void init(FMLInitializationEvent event) {
        // 合成配方：周围 8 个泥土 + 中间 1 个木头（原木）→ 无限背包
        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(InfinitePackMod.itemInfinitePack),
                "DDD", "DLD", "DDD",
                'D', "dirt",
                'L', "logWood"));
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandInfPackTest());
    }
}
