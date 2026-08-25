package com.infpack;

import java.util.Arrays;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagString;

/**
 * NBT 深度等价比较（1.12.2 版，MCP 方法名）。
 *
 * 1.12.2 的 NBTTagCompound 未重写 equals/hashCode（对象恒等），因此模组内比较必须自写。
 * 对 Compound 按键名排序后逐项比较，避免底层 HashMap 迭代顺序造成的伪差异；
 * 对 List 逐元素比较（元素顺序有意义）；基本类型直接比较值。
 */
public final class NbtUtil {

    private NbtUtil() {}

    /** 深度比较两个物品 NBT；null 与空 compound 视为等价。 */
    public static boolean tagsEqual(NBTTagCompound a, NBTTagCompound b) {
        if (a == null || a.getKeySet().isEmpty()) {
            a = null;
        }
        if (b == null || b.getKeySet().isEmpty()) {
            b = null;
        }
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return compoundEquals(a, b);
    }

    private static boolean compoundEquals(NBTTagCompound a, NBTTagCompound b) {
        Set<String> ka = a.getKeySet();
        Set<String> kb = b.getKeySet();
        if (ka.size() != kb.size()) {
            return false;
        }
        for (String key : ka) {
            if (!b.hasKey(key)) {
                return false;
            }
            if (!nbtEquals(a.getTag(key), b.getTag(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean nbtEquals(NBTBase a, NBTBase b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.getId() != b.getId()) {
            return false;
        }
        switch (a.getId()) {
            case 1: // TAG_Byte
                return ((NBTPrimitive) a).getByte() == ((NBTPrimitive) b).getByte();
            case 2: // TAG_Short
                return ((NBTPrimitive) a).getShort() == ((NBTPrimitive) b).getShort();
            case 3: // TAG_Int
                return ((NBTPrimitive) a).getInt() == ((NBTPrimitive) b).getInt();
            case 4: // TAG_Long
                return ((NBTPrimitive) a).getLong() == ((NBTPrimitive) b).getLong();
            case 5: // TAG_Float
                return Float.floatToIntBits(((NBTPrimitive) a).getFloat()) == Float
                        .floatToIntBits(((NBTPrimitive) b).getFloat());
            case 6: // TAG_Double
                return Double.doubleToLongBits(((NBTPrimitive) a).getDouble()) == Double
                        .doubleToLongBits(((NBTPrimitive) b).getDouble());
            case 7: // TAG_ByteArray
                return Arrays.equals(((NBTTagByteArray) a).getByteArray(), ((NBTTagByteArray) b).getByteArray());
            case 8: // TAG_String
                return ((NBTTagString) a).getString().equals(((NBTTagString) b).getString());
            case 9: // TAG_List
                return listEquals((NBTTagList) a, (NBTTagList) b);
            case 10: // TAG_Compound
                return compoundEquals((NBTTagCompound) a, (NBTTagCompound) b);
            case 11: // TAG_IntArray
                return Arrays.equals(((NBTTagIntArray) a).getIntArray(), ((NBTTagIntArray) b).getIntArray());
            default:
                return true; // TAG_End 等
        }
    }

    private static boolean listEquals(NBTTagList a, NBTTagList b) {
        if (a.tagCount() != b.tagCount()) {
            return false;
        }
        for (int i = 0; i < a.tagCount(); i++) {
            if (!nbtEquals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }
}
