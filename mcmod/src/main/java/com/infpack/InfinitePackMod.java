package com.infpack;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

/**
 * 得一即无限背包 (Infinite Pack)
 *
 * 放入任意物品得到一个"条目"，之后可无限取出（深拷贝样本，属性/附魔/耐久/NBT 完全一致）；
 * 仅当在背包中删除该条目后，才不再能取出。不同耐久/不同附魔/不同 NBT 视为不同条目，各自独立。
 */
@Mod(modid = InfinitePackMod.MODID, version = Tags.VERSION, name = InfinitePackMod.MODNAME,
        acceptedMinecraftVersions = "[1.7.10]")
public class InfinitePackMod {

    public static final String MODID = "sninfinitepack";
    public static final String MODNAME = "SN Infinite Pack";

    // ============================================================
    // 彩蛋（隐藏，不显示在任何游戏 UI）：SN 的全称是 snang
    // ============================================================
    private static final String SN_FULL_NAME = "snang";
    public static final Logger LOG = LogManager.getLogger(MODID);

    /** 背包物品 */
    public static Item itemInfinitePack;

    public static final CreativeTabs CREATIVE_TAB = new CreativeTabs(MODID) {
        @Override
        public Item getTabIconItem() {
            return itemInfinitePack;
        }
    };

    @Mod.Instance(MODID)
    public static InfinitePackMod instance;

    @SidedProxy(clientSide = "com.infpack.ClientProxy", serverSide = "com.infpack.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }
}
