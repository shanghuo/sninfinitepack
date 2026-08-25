package com.infpack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 *
 * 存储改造（方案 A）：条目数据从"物品 NBT"迁到"服务器文件"，物品 NBT 只存
 * infpack.uuid；序列化核心拆为 saveToNBT/loadFromNBT（供文件读写复用）。
 */
public class BackpackStorage {

    public static final String TAG_KEY = "infpack";
    public static final String TAG_UUID = "uuid";
    public static final String TAG_ENTRIES = "Entries";
    public static final String TAG_ITEM = "Item";
    public static final String TAG_DAMAGE = "Damage";
    public static final String TAG_TAG = "Tag";
    public static final String TAG_COUNT = "Count";
    public static final String TAG_LAST_ACCESS = "LastAccess";

    /** 条目：变体样本（stackSize=1）+ 计数（可负）+ 最近存取时戳（排序用）。 */
    public static class Entry {
        public final ItemStack sample;
        public int count;
        public long lastAccess;

        Entry(ItemStack sample, int count) {
            this.sample = sample;
            this.count = count;
            this.lastAccess = 0L;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    /** 从背包物品的 NBT 载入条目（旧格式：条目在物品 NBT，仅迁移时用）。 */
    public static BackpackStorage load(ItemStack backpack) {
        if (backpack == null || !backpack.hasTagCompound() || !backpack.getTagCompound().hasKey(TAG_KEY)) {
            return new BackpackStorage();
        }
        return loadFromNBT(backpack.getTagCompound().getCompoundTag(TAG_KEY));
    }

    /** 从纯 NBT 根载入条目（文件/物品 NBT 通用）。root 为 null 返回空。 */
    public static BackpackStorage loadFromNBT(NBTTagCompound root) {
        BackpackStorage storage = new BackpackStorage();
        if (root == null) {
            return storage;
        }
        NBTTagList list = root.getTagList(TAG_ENTRIES, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            ItemStack sample = stackFromNBT(e);
            if (sample != null && sample.getItem() != null) {
                Entry entry = new Entry(sample, e.getInteger(TAG_COUNT));
                entry.lastAccess = e.getLong(TAG_LAST_ACCESS);
                storage.entries.add(entry);
            }
        }
        return storage;
    }

    /** 保存条目到背包物品 NBT（旧格式，仅迁移/兼容用）。 */
    public void save(ItemStack backpack) {
        if (backpack == null) {
            return;
        }
        if (!backpack.hasTagCompound()) {
            backpack.setTagCompound(new NBTTagCompound());
        }
        NBTTagCompound root = new NBTTagCompound();
        saveToNBT(root);
        backpack.getTagCompound().setTag(TAG_KEY, root);
    }

    /** 序列化条目到纯 NBT 根（文件/物品 NBT 通用）。 */
    public void saveToNBT(NBTTagCompound root) {
        NBTTagList list = new NBTTagList();
        for (Entry entry : entries) {
            NBTTagCompound e = stackToNBT(entry.sample);
            e.setInteger(TAG_COUNT, entry.count);
            e.setLong(TAG_LAST_ACCESS, entry.lastAccess);
            list.appendTag(e);
        }
        root.setTag(TAG_ENTRIES, list);
    }

    // ------------------------------------------------------------ UUID（物品 NBT 只存这个）

    /** 读取背包物品的 UUID（新格式；无则返回 null）。 */
    public static String getUuid(ItemStack backpack) {
        if (backpack == null || !backpack.hasTagCompound() || !backpack.getTagCompound().hasKey(TAG_KEY)) {
            return null;
        }
        NBTTagCompound root = backpack.getTagCompound().getCompoundTag(TAG_KEY);
        return root.hasKey(TAG_UUID) ? root.getString(TAG_UUID) : null;
    }

    /** 确保背包物品有 UUID；没有则分配并写入物品 NBT（保留原有 infpack 根）。 */
    public static String ensureUuid(ItemStack backpack) {
        String uuid = getUuid(backpack);
        if (uuid != null && uuid.length() > 0) {
            return uuid;
        }
        uuid = UUID.randomUUID().toString();
        if (!backpack.hasTagCompound()) {
            backpack.setTagCompound(new NBTTagCompound());
        }
        NBTTagCompound root = backpack.getTagCompound().hasKey(TAG_KEY)
                ? backpack.getTagCompound().getCompoundTag(TAG_KEY) : new NBTTagCompound();
        root.setString(TAG_UUID, uuid);
        backpack.getTagCompound().setTag(TAG_KEY, root);
        return uuid;
    }

    /** 背包物品是否为旧格式（物品 NBT 内嵌条目且无 uuid，需要迁移）。 */
    public static boolean isLegacy(ItemStack backpack) {
        if (backpack == null || !backpack.hasTagCompound() || !backpack.getTagCompound().hasKey(TAG_KEY)) {
            return false;
        }
        NBTTagCompound root = backpack.getTagCompound().getCompoundTag(TAG_KEY);
        return !root.hasKey(TAG_UUID) && root.hasKey(TAG_ENTRIES);
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

    /** 取第 index 个条目的最近存取时戳（用于"最近存取"排序；越大越新）。 */
    public long getLastAccess(int index) {
        if (index < 0 || index >= entries.size()) {
            return 0L;
        }
        return entries.get(index).lastAccess;
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
            Entry e = entries.get(idx);
            e.count += stack.stackSize; // 同变体：计数累加
            e.lastAccess = stamp();
            return false;
        }
        int ins = Math.max(0, Math.min(index, entries.size()));
        Entry ne = new Entry(norm, stack.stackSize);
        ne.lastAccess = stamp();
        entries.add(ins, ne);
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
        e.lastAccess = stamp();
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

    /**
     * 存取时间戳：毫秒级 + 单调递增，保证同一 tick 内连续存取也能区分先后。
     * （world.getTotalWorldTime() 是 tick 粒度，同 tick 操作无法区分，曾导致"最近"排序失效。）
     */
    private long lastStamp = 0L;

    private long stamp() {
        long now = System.currentTimeMillis();
        if (now <= lastStamp) {
            now = lastStamp + 1;
        }
        lastStamp = now;
        return now;
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
