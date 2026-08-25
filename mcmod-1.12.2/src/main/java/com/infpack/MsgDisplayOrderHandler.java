package com.infpack;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务器：把客户端算好的「显示顺序」应用到该玩家的背包容器—— 1.12.2 版。
 *
 * displayOrder 只做不可变数组引用赋值（volatile），网络线程写、主线程读安全；
 * 为与其它操作一致，仍切到服务器主线程执行。
 */
public class MsgDisplayOrderHandler implements IMessageHandler<MsgDisplayOrder, IMessage> {

    @Override
    public IMessage onMessage(MsgDisplayOrder message, MessageContext ctx) {
        final EntityPlayerMP player = ctx.getServerHandler().player;
        if (player != null && player.openContainer instanceof ContainerInfinitePack) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                server.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (player.openContainer instanceof ContainerInfinitePack) {
                            ((ContainerInfinitePack) player.openContainer).setDisplayOrder(message.order);
                            InfinitePackMod.LOG.info("[infpack] server setDisplayOrder len={}", message.order.length);
                        }
                    }
                });
            }
        }
        return null;
    }
}
