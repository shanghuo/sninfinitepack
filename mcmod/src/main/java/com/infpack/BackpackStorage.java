package com.infpack;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * 得一即无限背包的存储逻辑（计数版）。
 *
 * 每个"条目" = 一个变体样本（物品+耐久+完整NBT 唯一）+ 一个计数 count。
 *  - 放入：同变体则 count += 放入数量；否则新增条目 count = 放入数量。
 *  - 取出：count -= 取出数量（允许为负 = 无限透支，显示欠账给玩家补回正数的动力）。
 *  - 删除：移除条目。
 */
public class BackpackStorage {

    public static final String TAG_KEY = "infpack";
    public static final String TAG_ENTRIES = "Entries";
    public static final String TAG_ITEM = "Item";
    public static final String TAG_DAMAGE = "Damage";
    public static final String TAG_TAG = "Tag";
    public static final String TAG_COUNT = "Count";

    /** 条目：变体样本（stackSize=1）+ 计数（可负）。 */
    public static class Entry {
        public final ItemStack sample;
        public int count;

        Entry(ItemStack sample, int count) {
            this.sample = sample;
            this.count = count;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    /** 从背包物品的 NBT 载入条目。 */
    public static BackpackStorage load(ItemStack backpack) {
        BackpackStorage storage = new BackpackStorage();
        if (backpack == null || !backpack.hasTagCompound() || !backpack.getTagCompound().hasKey(TAG_KEY)) {
            return storage;
        }
        NBTTagCompound root = backpack.getTagCompound().getCompoundTag(TAG_KEY);
        NBTTagList list = root.getTagList(TAG_ENTRIES, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            ItemStack sample = stackFromNBT(e);
            if (sample != null && sample.getItem() != null) {
                storage.entries.add(new Entry(sample, e.getInteger(TAG_COUNT)));
            }
        }
        return storage;
    }

    /** 保存条目到背包物品 NBT。 */
    public void save(ItemStack backpack) {
        if (backpack == null) {
            return;
        }
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (Entry entry : entries) {
            NBTTagCompound e = stackToNBT(entry.sample);
            e.setInteger(TAG_COUNT, entry.count);
            list.appendTag(e);
        }
        root.setTag(TAG_ENTRIES, list);
        if (!backpack.hasTagCompound()) {
            backpack.setTagCompound(new NBTTagCompound());
        }
        backpack.getTagCompound().setTag(TAG_KEY, root);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 取第 index 个条目的样本副本（stackSize=1）。 */
    public ItemStack getSample(int index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index).sample.copy();
    }

    /** 取第 index 个条目的计数（可负）。 */
    public int getCount(int index) {
        if (index < 0 || index >= entries.size()) {
            return 0;
        }
        return entries.get(index).count;
    }

    /** 显示用栈：样本副本（stackSize=1），数量由 GUI 画在槽位右下角。 */
    public ItemStack getDisplayStack(int index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index).sample.copy();
    }

    /**
     * 放入一个物品（追加到末尾）。同变体则计数增加；否则新增条目。
     *
     * @return true 表示新增了条目；false 表示合并到已有条目或非法输入。
     */
    public boolean addEntry(ItemStack stack, Item backpackItem) {
        return deposit(stack, backpackItem, entries.size());
    }

    /**
     * 放入一个物品并插入到指定位置（用于"像箱子一样点哪格放哪"）。
     * 同变体则计数增加；否则在 index（越界自动收敛）处插入新条目（计数=放入数量）。
     *
     * @return true 表示新增了条目；false 表示合并到已有条目或非法输入。
     */
    public boolean deposit(ItemStack stack, Item backpackItem, int index) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        ItemStack norm = normalize(stack);
        if (backpackItem != null && norm.getItem() == backpackItem) {
            return false; // 不能把背包放入自身
        }
        int idx = indexOf(norm);
        if (idx >= 0) {
            entries.get(idx).count += stack.stackSize; // 同变体：计数累加
            return false;
        }
        int ins = Math.max(0, Math.min(index, entries.size()));
        entries.add(ins, new Entry(norm, stack.stackSize));
        return true;
    }

    /**
     * 取出：计数减少（可为负 = 无限透支），返回一份样本（stackSize=数量）。
     * amount&lt;0 表示取一整组（maxStackSize）。
     */
    public ItemStack withdraw(int index, int amount) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        Entry e = entries.get(index);
        int n = amount < 0 ? e.sample.getMaxStackSize() : Math.min(amount, e.sample.getMaxStackSize());
        if (n <= 0) {
            return null;
        }
        e.count -= n;
        ItemStack out = e.sample.copy();
        out.stackSize = n;
        return out;
    }

    /** 删除第 index 个条目。 */
    public boolean removeEntry(int index) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        entries.remove(index);
        return true;
    }

    public boolean contains(ItemStack stack) {
        return indexOf(normalize(stack)) >= 0;
    }

    public int indexOf(ItemStack stack) {
        for (int i = 0; i < entries.size(); i++) {
            if (sameVariant(entries.get(i).sample, stack)) {
                return i;
            }
        }
        return -1;
    }

    /** 两个栈是否为同一"变体"（物品 + 耐久 + NBT 深度等价）。 */
    public static boolean sameVariant(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getItem() != b.getItem()) {
            return false;
        }
        if (a.getItemDamage() != b.getItemDamage()) {
            return false;
        }
        return NbtUtil.tagsEqual(a.getTagCompound(), b.getTagCompound());
    }

    private static ItemStack normalize(ItemStack stack) {
        ItemStack n = stack.copy();
        n.stackSize = 1;
        NBTTagCompound tag = n.getTagCompound();
        if (tag != null && tag.hasNoTags()) {
            n.setTagCompound(null);
        }
        return n;
    }

    /** 用物品注册名保存，避免 1.7.10 NBT short id 对大 id（>32767，如 GTNH）截断的问题。 */
    private static NBTTagCompound stackToNBT(ItemStack stack) {
        NBTTagCompound e = new NBTTagCompound();
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (name == null) {
            name = "null";
        }
        e.setString(TAG_ITEM, name);
        e.setInteger(TAG_DAMAGE, stack.getItemDamage());
        if (stack.hasTagCompound()) {
            e.setTag(TAG_TAG, stack.getTagCompound().copy());
        }
        return e;
    }

    private static ItemStack stackFromNBT(NBTTagCompound e) {
        String name = e.getString(TAG_ITEM);
        Item item = (Item) Item.itemRegistry.getObject(name);
        if (item == null) {
            return null; // 物品被移除/未加载，跳过该条目
        }
        ItemStack stack = new ItemStack(item, 1, e.getInteger(TAG_DAMAGE));
        if (e.hasKey(TAG_TAG)) {
            stack.setTagCompound(e.getCompoundTag(TAG_TAG));
        }
        return stack;
    }
}
