package com.infpack;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * 简易网络通道（排序/搜索 + 显示数据下发）。
 *
 * 架构（关键决策）：客户端算好「显示顺序」（过滤+排序后的真实条目索引列表），
 * 通过本通道发给服务器；服务器只存这个顺序列表用于槽位→条目映射。
 * 搜索/排序只在客户端做（本地化物品名），排序模式本身无需同步（顺序列表携带信息）。
 */
public class NetworkHandler {

    public static final SimpleNetworkWrapper NETWORK =
            NetworkRegistry.INSTANCE.newSimpleChannel("sninfpack");

    private NetworkHandler() {}

    /** 注册全部消息（CommonProxy.preInit 调用）。 */
    public static void init() {
        NETWORK.registerMessage(MsgDisplayOrderHandler.class, MsgDisplayOrder.class, 0, Side.SERVER);
        NETWORK.registerMessage(MsgBackpackRequestHandler.class, MsgBackpackRequest.class, 1, Side.SERVER);
        NETWORK.registerMessage(MsgBackpackDataHandler.class, MsgBackpackData.class, 2, Side.CLIENT);
    }
}
