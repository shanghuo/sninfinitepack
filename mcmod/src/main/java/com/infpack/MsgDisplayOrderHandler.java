package com.infpack;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务器：把客户端算好的「显示顺序」应用到该玩家的背包容器。
 *
 * displayOrder 只做不可变数组引用赋值（volatile），网络线程写、主线程读安全；
 * 不做任何存储结构修改，滚动收敛由主线程的 detectAndSendChanges 完成。
 */
public class MsgDisplayOrderHandler implements IMessageHandler<MsgDisplayOrder, IMessage> {

    @Override
    public IMessage onMessage(MsgDisplayOrder message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        if (player != null && player.openContainer instanceof ContainerInfinitePack) {
            ((ContainerInfinitePack) player.openContainer).setDisplayOrder(message.order);
            InfinitePackMod.LOG.info("[infpack] server setDisplayOrder len={}", message.order.length);
        }
        return null;
    }
}
