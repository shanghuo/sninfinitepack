package com.infpack;

import java.util.Arrays;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;

/**
 * NBT 深度等价比较。
 *
 * 1.7.10 的 NBTTagCompound 未重写 equals/hashCode（对象恒等），因此模组内比较必须自写。
 * 对 Compound 按键名排序后逐项比较，避免底层 HashMap 迭代顺序造成的伪差异；
 * 对 List 逐元素比较（元素顺序有意义）；基本类型直接比较值。
 */
public final class NbtUtil {

    private NbtUtil() {}

    /** 深度比较两个物品 NBT；null 与空 compound 视为等价。 */
    public static boolean tagsEqual(NBTTagCompound a, NBTTagCompound b) {
        if (a == null || a.hasNoTags()) {
            a = null;
        }
        if (b == null || b.hasNoTags()) {
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
        Set<String> ka = a.func_150296_c();
        Set<String> kb = b.func_150296_c();
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
                return ((NBTBase.NBTPrimitive) a).func_150290_f() == ((NBTBase.NBTPrimitive) b).func_150290_f();
            case 2: // TAG_Short
                return ((NBTBase.NBTPrimitive) a).func_150289_e() == ((NBTBase.NBTPrimitive) b).func_150289_e();
            case 3: // TAG_Int
                return ((NBTBase.NBTPrimitive) a).func_150287_d() == ((NBTBase.NBTPrimitive) b).func_150287_d();
            case 4: // TAG_Long
                return ((NBTBase.NBTPrimitive) a).func_150291_c() == ((NBTBase.NBTPrimitive) b).func_150291_c();
            case 5: // TAG_Float
                return Float.floatToIntBits(((NBTBase.NBTPrimitive) a).func_150288_h()) == Float
                        .floatToIntBits(((NBTBase.NBTPrimitive) b).func_150288_h());
            case 6: // TAG_Double
                return Double.doubleToLongBits(((NBTBase.NBTPrimitive) a).func_150286_g()) == Double
                        .doubleToLongBits(((NBTBase.NBTPrimitive) b).func_150286_g());
            case 7: // TAG_ByteArray
                return Arrays.equals(((NBTTagByteArray) a).func_150292_c(), ((NBTTagByteArray) b).func_150292_c());
            case 8: // TAG_String
                return ((NBTTagString) a).func_150285_a_().equals(((NBTTagString) b).func_150285_a_());
            case 9: // TAG_List
                return listEquals((NBTTagList) a, (NBTTagList) b);
            case 10: // TAG_Compound
                return compoundEquals((NBTTagCompound) a, (NBTTagCompound) b);
            case 11: // TAG_IntArray
                return Arrays.equals(((NBTTagIntArray) a).func_150302_c(), ((NBTTagIntArray) b).func_150302_c());
            default:
                return true; // TAG_End 等
        }
    }

    private static boolean listEquals(NBTTagList a, NBTTagList b) {
        if (a.tagCount() != b.tagCount()) {
            return false;
        }
        for (int i = 0; i < a.tagCount(); i++) {
            NBTBase ea = listElement(a, i);
            NBTBase eb = listElement(b, i);
            if (!nbtEquals(ea, eb)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读取列表第 i 个元素（通用类型）。1.7.10 无通用访问器，先按 Compound 读取；
     * 若抛出 ClassCastException（元素非 compound），则回退到按字符串形式比较。
     */
    private static NBTBase listElement(NBTTagList list, int i) {
        try {
            return list.getCompoundTagAt(i);
        } catch (ClassCastException e) {
            // 非 compound 元素：用 toString（列表元素顺序固定，基本类型输出确定）
            return new NBTTagString("$toString:" + list.toString() + ":" + i);
        }
    }
}
