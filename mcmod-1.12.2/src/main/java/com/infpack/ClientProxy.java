package com.infpack;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;

import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * 客户端代理 —— 1.12.2 版。
 *
 * 1.12.2 物品渲染改为模型系统（assets/.../models/item/infinitepack.json），
 * 必须在 preInit（ModelBakery 烘焙之前）把物品绑定到模型资源位置。
 * ⚠️ 关键坑：不能在 init() 里注册 —— 1.12.2 启动时序是
 *   preInit(物品/模型登记) → 资源加载/模型烘焙 → init；
 *   init() 里 setCustomModelResourceLocation 已太晚，ModelBakery 收集不到
 *   该物品变体 → 物品渲染成 missing model（紫黑立方体）且不报错。
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event); // 先注册物品（ForgeRegistries.ITEMS）与 GUI/网络
        // 模型登记必须在 preInit：super.preInit 已注册物品，此刻登记模型，
        // 之后 ModelBakery 烘焙时才能收集到该物品变体。
        Item item = InfinitePackMod.itemInfinitePack;
        if (item != null) {
            ModelLoader.setCustomModelResourceLocation(item, 0,
                    new ModelResourceLocation(InfinitePackMod.MODID + ":infinitepack", "inventory"));
            // 诊断日志：确认模型注册在客户端执行（图标不显示时据此判断是否 ClientProxy 未被使用）
            InfinitePackMod.LOG.info("[infpack] ClientProxy 模型注册成功: registry={} model={}#inventory",
                    item.getRegistryName(), InfinitePackMod.MODID + ":infinitepack");
        } else {
            InfinitePackMod.LOG.warn("[infpack] ClientProxy 模型注册失败: itemInfinitePack 为 null（ClientProxy 未正常初始化）");
        }
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }
}
