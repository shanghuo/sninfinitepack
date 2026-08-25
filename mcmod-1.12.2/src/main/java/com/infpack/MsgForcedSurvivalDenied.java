package com.infpack;

import io.netty.buffer.ByteBuf;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** 服务器→客户端：当前存档被强制生存拒绝（客户端显示全屏拦截）—— 1.12.2 版。 */
public class MsgForcedSurvivalDenied implements IMessage {

    public MsgForcedSurvivalDenied() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}
}
