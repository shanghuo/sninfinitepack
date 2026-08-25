package com.infpack;

import io.netty.buffer.ByteBuf;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * 客户端→服务器：下发「显示顺序」。
 *
 * 内容是过滤+排序后的真实条目索引列表（int[]），客户端算好、服务器原样存储。
 * 只在顺序真正变化时发送（避免每 tick 刷包）。
 */
public class MsgDisplayOrder implements IMessage {

    /** 有序的真实条目索引列表。 */
    public int[] order;

    /** 发送端构造。 */
    public MsgDisplayOrder(int[] order) {
        this.order = order;
    }

    /** 反序列化（FML 需要无参构造）。 */
    public MsgDisplayOrder() {
        this.order = new int[0];
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int len = buf.readInt();
        order = new int[len];
        for (int i = 0; i < len; i++) {
            order[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(order.length);
        for (int i = 0; i < order.length; i++) {
            buf.writeInt(order[i]);
        }
    }
}
