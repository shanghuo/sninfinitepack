package com.infpack;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端：收齐分片 → 合并成 BackpackStorage → 切主线程应用到打开容器并刷新显示。
 * 分片按 uuid 收集（TCP 有序，片序可靠）—— 1.12.2 版。
 */
public class MsgBackpackDataHandler implements IMessageHandler<MsgBackpackData, IMessage> {

    private static final Map<String, MsgBackpackData[]> PARTS = new HashMap<String, MsgBackpackData[]>();

    @Override
    public IMessage onMessage(final MsgBackpackData msg, MessageContext ctx) {
        final BackpackStorage storage;
        synchronized (PARTS) {
            MsgBackpackData[] parts = PARTS.get(msg.uuid);
            if (parts == null || parts.length != msg.partCount) {
                parts = new MsgBackpackData[msg.partCount];
                PARTS.put(msg.uuid, parts);
            }
            parts[msg.partIndex] = msg;
            boolean complete = true;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] == null) {
                    complete = false;
                    break;
                }
            }
            if (!complete) {
                return null; // 等后续分片
            }
            PARTS.remove(msg.uuid);
            storage = merge(parts);
        }
        // 客户端 handler 在 Netty 线程，切主线程再改 GUI/容器
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return null;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                EntityPlayer player = Minecraft.getMinecraft().player;
                if (player != null && player.openContainer instanceof ContainerInfinitePack) {
                    ((ContainerInfinitePack) player.openContainer).applyServerData(msg.uuid, storage);
                }
            }
        });
        return null;
    }

    private static BackpackStorage merge(MsgBackpackData[] parts) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (MsgBackpackData part : parts) {
            NBTTagList l = part.toNBT().getTagList(BackpackStorage.TAG_ENTRIES, 10);
            for (int i = 0; i < l.tagCount(); i++) {
                list.appendTag(l.getCompoundTagAt(i));
            }
        }
        root.setTag(BackpackStorage.TAG_ENTRIES, list);
        return BackpackStorage.loadFromNBT(root);
    }
}
