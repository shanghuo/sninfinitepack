package com.infpack;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/** 服务器：客户端打开背包请求数据 → 从该玩家打开容器下发条目（分包）。 */
public class MsgBackpackRequestHandler implements IMessageHandler<MsgBackpackRequest, IMessage> {

    @Override
    public IMessage onMessage(MsgBackpackRequest message, MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        if (player != null && player.openContainer instanceof ContainerInfinitePack) {
            ((ContainerInfinitePack) player.openContainer).sendDataToClient();
        }
        return null;
    }
}
