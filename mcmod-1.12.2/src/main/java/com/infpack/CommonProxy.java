package com.infpack;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.ShapedOreRecipe;

/**
 * 通用代理 —— 1.12.2 版。
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        InfinitePackMod.itemInfinitePack = new ItemInfinitePack()
                .setTranslationKey("infinitePack")
                .setCreativeTab(InfinitePackMod.CREATIVE_TAB);
        // 1.12.2：物品注册走 ForgeRegistries（GameRegistry.register 已移除），registry name 与 unlocalized name 分离
        // 注意：1.12.2 的 ResourceLocation 会把路径小写化，注册名/模型/纹理统一用全小写 infinitepack
        ForgeRegistries.ITEMS.register(InfinitePackMod.itemInfinitePack.setRegistryName(InfinitePackMod.MODID, "infinitepack"));
        NetworkRegistry.INSTANCE.registerGuiHandler(InfinitePackMod.instance, new GuiHandler());
        NetworkHandler.init(); // 显示顺序（排序/搜索）同步通道 + 强制生存消息
        // 强制生存：加载全局配置 + 注册服务器每 tick 检测
        ForceSurvivalConfig.load(event.getModConfigurationDirectory());
        FMLCommonHandler.instance().bus().register(new ForceSurvivalHandler());
    }

    public void init(FMLInitializationEvent event) {
        // 合成配方：周围 8 个泥土 + 中间 1 个木头（原木）→ 无限背包（矿辞配方）
        ShapedOreRecipe recipe = new ShapedOreRecipe(
                new ResourceLocation("sninfinitepack:backpack"),
                new ItemStack(InfinitePackMod.itemInfinitePack),
                "DDD", "DLD", "DDD",
                'D', "dirt",
                'L', "logWood");
        recipe.setRegistryName("sninfinitepack", "backpack");
        ForgeRegistries.RECIPES.register(recipe);
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandInfPackTest());
        event.registerServerCommand(new CommandForceSurvival()); // 强制生存指令
    }

    /** 服务器停止：全量写回背包数据文件。 */
    public void serverStopping(FMLServerStoppingEvent event) {
        BackpackDataManager.shutdown();
    }
}
