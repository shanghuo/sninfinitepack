package com.infpack;

import java.io.IOException;

import io.netty.buffer.ByteBuf;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;

import cpw.mods.fml.common.network.simpleimpl.IMessage;

/**
 * 服务器→客户端：下发一个背包的条目数据（存储改造）。
 *
 * 客户端不再从物品 NBT 读条目（物品 NBT 只存 uuid），改为服务器打开时分包下发。
 * 每条目 = 注册名 + damage + count + lastAccess + 可选完整 NBT（保证取出一致/显示真实）。
 */
public class MsgBackpackData implements IMessage {

    /** 每片最大条目数（控制单包体积）。 */
    public static final int PART_SIZE = 100;

    public String uuid = "";
    public int partIndex;
    public int partCount;
    public String[] itemNames;
    public int[] damages;
    public int[] counts;
    public long[] lastAccesses;
    public NBTTagCompound[] tags;

    public MsgBackpackData() {}

    /** 把本片条目转成 NBT 根（供客户端合并后 loadFromNBT）。 */
    public NBTTagCompound toNBT() {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        int n = itemNames == null ? 0 : itemNames.length;
        for (int i = 0; i < n; i++) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString(BackpackStorage.TAG_ITEM, itemNames[i]);
            e.setInteger(BackpackStorage.TAG_DAMAGE, damages[i]);
            e.setInteger(BackpackStorage.TAG_COUNT, counts[i]);
            e.setLong(BackpackStorage.TAG_LAST_ACCESS, lastAccesses[i]);
            if (tags[i] != null) {
                e.setTag(BackpackStorage.TAG_TAG, tags[i]);
            }
            list.appendTag(e);
        }
        root.setTag(BackpackStorage.TAG_ENTRIES, list);
        return root;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        PacketBuffer pb = new PacketBuffer(buf);
        try {
            this.uuid = pb.readStringFromBuffer(64);
            this.partIndex = pb.readVarIntFromBuffer();
            this.partCount = pb.readVarIntFromBuffer();
            int n = pb.readVarIntFromBuffer();
            itemNames = new String[n];
            damages = new int[n];
            counts = new int[n];
            lastAccesses = new long[n];
            tags = new NBTTagCompound[n];
            for (int i = 0; i < n; i++) {
                itemNames[i] = pb.readStringFromBuffer(512);
                damages[i] = pb.readInt();
                counts[i] = pb.readInt();
                lastAccesses[i] = pb.readLong();
                tags[i] = pb.readBoolean() ? pb.readNBTTagCompoundFromBuffer() : null;
            }
        } catch (IOException e) {
            InfinitePackMod.LOG.error("[infpack] MsgBackpackData.fromBytes 失败", e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        PacketBuffer pb = new PacketBuffer(buf);
        try {
            pb.writeStringToBuffer(uuid);
            pb.writeVarIntToBuffer(partIndex);
            pb.writeVarIntToBuffer(partCount);
            int n = itemNames == null ? 0 : itemNames.length;
            pb.writeVarIntToBuffer(n);
            for (int i = 0; i < n; i++) {
                pb.writeStringToBuffer(itemNames[i]);
                pb.writeInt(damages[i]);
                pb.writeInt(counts[i]);
                pb.writeLong(lastAccesses[i]);
                if (tags[i] != null) {
                    pb.writeBoolean(true);
                    pb.writeNBTTagCompoundToBuffer(tags[i]);
                } else {
                    pb.writeBoolean(false);
                }
            }
        } catch (IOException e) {
            InfinitePackMod.LOG.error("[infpack] MsgBackpackData.toBytes 失败", e);
        }
    }
}
