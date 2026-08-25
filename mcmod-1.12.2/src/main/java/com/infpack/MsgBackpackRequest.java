package com.infpack;

import io.netty.buffer.ByteBuf;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** 客户端→服务器：请求下发当前打开背包的条目数据（打开 GUI 时发一次）—— 1.12.2 版。 */
public class MsgBackpackRequest implements IMessage {

    public MsgBackpackRequest() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}
}
