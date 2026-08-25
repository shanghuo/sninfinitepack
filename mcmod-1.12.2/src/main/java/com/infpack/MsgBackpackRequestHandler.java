package com.infpack;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 服务器：客户端打开背包请求数据 → 从该玩家打开容器下发条目（分包）—— 1.12.2 版。 */
public class MsgBackpackRequestHandler implements IMessageHandler<MsgBackpackRequest, IMessage> {

    @Override
    public IMessage onMessage(MsgBackpackRequest message, MessageContext ctx) {
        final EntityPlayerMP player = ctx.getServerHandler().player;
        if (player != null && player.openContainer instanceof ContainerInfinitePack) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                // 切到服务器主线程再读写容器（避免与主线程存取并发访问 ArrayList）
                server.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (player.openContainer instanceof ContainerInfinitePack) {
                            ((ContainerInfinitePack) player.openContainer).sendDataToClient();
                        }
                    }
                });
            }
        }
        return null;
    }
}
