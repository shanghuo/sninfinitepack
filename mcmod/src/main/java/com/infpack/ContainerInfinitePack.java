package com.infpack;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 得一即无限背包容器。
 *
 * 交互（像箱子一样）：
 *  - 手里有物品，点击任意条目格 = 存入（插到该格位置）
 *  - 空手左键条目 = 取一整组入玩家背包，右键 = 取 1 个
 *  - Shift+点击玩家背包物品 = 存入；Shift+点击条目 = 取一整组
 *  - 删除模式（右上角切换）：空手点击条目 = 删除
 *
 * 客户端本地不处理取出/删除（返回 null，与服务器一致即被确认）。
 * 客户端条目显示采用"乐观更新"：点击时客户端直接改自己的 storage（存入即显示、
 * 删除即消失、取出无限不变），不依赖服务器→客户端的槽位/NBT 同步；
 * 同时保留从已同步背包 NBT 重载的兜底（reloadFromBackpack/needsReload）。
 */
public class ContainerInfinitePack extends Container {

    public static final int ENTRY_COLS = 9;
    public static final int ENTRY_ROWS = 6;
    public static final int ENTRY_VISIBLE = ENTRY_COLS * ENTRY_ROWS; // 54，占满 9x6 网格
    public static final int PLAYER_START = ENTRY_VISIBLE; // 54

    private final EntityPlayer player;
    private ItemStack backpack; // 玩家背包中的实际引用
    private BackpackStorage storage; // 客户端可重载（见 reloadFromBackpack）

    /** 上次从背包 NBT 加载时的 infpack 根标签引用，用于客户端检测是否需重载。 */
    private NBTTagCompound lastLoadedRoot;

    private final EntriesInventory entriesInv;

    private int scrollOffset = 0;
    private boolean deleteMode = false;

    private int lastSentMode = -1;
    private int lastSentScroll = -1;
    private int lastSentTotal = -1;

    public ContainerInfinitePack(EntityPlayer player) {
        this.player = player;
        this.backpack = findBackpack(player);
        this.storage = BackpackStorage.load(backpack);
        this.lastLoadedRoot = getBackpackRoot(backpack);
        this.entriesInv = new EntriesInventory(this);

        for (int row = 0; row < ENTRY_ROWS; row++) {
            for (int col = 0; col < ENTRY_COLS; col++) {
                addSlotToContainer(new SlotInfiniteEntry(entriesInv, row * ENTRY_COLS + col,
                        8 + col * 18, 18 + row * 18));
            }
        }

        addPlayerSlots(player.inventory, 140);
        clampScroll();
    }

    // ------------------------------------------------------------------ 访问器

    public boolean isClientSide() {
        return player != null && player.worldObj != null && player.worldObj.isRemote;
    }

    public BackpackStorage getStorage() {
        return storage;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public boolean getDeleteMode() {
        return deleteMode;
    }

    /** 显示用总条目数：客户端以自身 storage 为准（乐观更新 + 重载兜底，与格子一致）。 */
    public int getTotalEntries() {
        return storage.size();
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public ItemStack slotClick(int slotId, int clickedButton, int mode, EntityPlayer player) {
        if (slotId >= 0 && slotId < ENTRY_VISIBLE) {
            if (mode == 5) {
                return null; // 拖拽分发到条目槽无意义，忽略（避免误存入）
            }
            int entryIndex = scrollOffset + slotId;
            ItemStack cursor = player.inventory.getItemStack();

            // 删除模式：空手点击删除该条目（客户端乐观删除，即时消失）
            if (deleteMode && cursor == null) {
                if (entryIndex >= 0 && entryIndex < storage.size()) {
                    storage.removeEntry(entryIndex);
                    if (!player.worldObj.isRemote) {
                        saveAndRefresh();
                        InfinitePackMod.LOG.info("[infpack] server DELETE idx={} size={}", entryIndex, storage.size());
                    } else {
                        InfinitePackMod.LOG.info("[infpack] client DELETE idx={} size={}", entryIndex, storage.size());
                    }
                }
                return null;
            }

            // 手里有物品：存入（插到该格位置）。不能存入背包本身。客户端乐观存入即时显示。
            if (cursor != null) {
                if (cursor.getItem() == InfinitePackMod.itemInfinitePack) {
                    return null; // 不消费光标
                }
                storage.deposit(cursor, InfinitePackMod.itemInfinitePack, entryIndex);
                if (!player.worldObj.isRemote) {
                    saveAndRefresh();
                    InfinitePackMod.LOG.info("[infpack] server DEPOSIT slot={} entryIndex={} size={}", slotId, entryIndex, storage.size());
                } else {
                    InfinitePackMod.LOG.info("[infpack] client DEPOSIT slot={} entryIndex={} size={}", slotId, entryIndex, storage.size());
                }
                ItemStack oldCursor = cursor;
                player.inventory.setItemStack(null);
                return oldCursor;
            }

            // 空手：取出（右键1个，其余整组）。客户端只减本地计数（物品由服务器下发+resync）
            if (entryIndex < 0 || entryIndex >= storage.size()) {
                return null;
            }
            int count = (mode == 0 && clickedButton == 1) ? 1 : -1;
            if (player.worldObj.isRemote) {
                storage.withdraw(entryIndex, count);
                InfinitePackMod.LOG.info("[infpack] client WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
                return null;
            }
            withdrawToInventory(player, entryIndex, count);
            return null;
        }
        return super.slotClick(slotId, clickedButton, mode, player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (index >= 0 && index < ENTRY_VISIBLE) {
            // Shift+条目 = 取一整组到玩家背包
            int entryIndex = scrollOffset + index;
            if (entryIndex >= 0 && entryIndex < storage.size()) {
                if (!player.worldObj.isRemote) {
                    withdrawToInventory(player, entryIndex, -1);
                } else {
                    storage.withdraw(entryIndex, -1);
                    InfinitePackMod.LOG.info("[infpack] client SHIFT-WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
                }
            }
            return null;
        }
        if (index >= PLAYER_START && index < PLAYER_START + 36) {
            // Shift+玩家背包物品 = 存入（客户端乐观存入，即时显示）
            Slot s = getSlot(index);
            ItemStack stack = s.getStack();
            if (stack != null && stack.getItem() != InfinitePackMod.itemInfinitePack) {
                storage.addEntry(stack, InfinitePackMod.itemInfinitePack);
                if (!player.worldObj.isRemote) {
                    s.putStack(null);
                    saveAndRefresh();
                    InfinitePackMod.LOG.info("[infpack] server SHIFT-DEPOSIT size={}", storage.size());
                    // 清空了玩家槽位但槽位同步可能被 isChangingQuantityOnly 抑制，主动全量下发
                    resyncToClient(player);
                } else {
                    InfinitePackMod.LOG.info("[infpack] client SHIFT-DEPOSIT size={}", storage.size());
                }
            }
            return null;
        }
        return null;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return findBackpack(player) != null;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (!player.worldObj.isRemote) {
            // 关闭时重新定位背包物品并保存，确保持久化到当前引用
            ItemStack bp = findBackpack(player);
            if (bp != null) {
                this.backpack = bp;
                storage.save(bp);
                InfinitePackMod.LOG.info("[infpack] server close save size={}", storage.size());
            }
        }
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int id) {
        if (id == 0) {
            deleteMode = !deleteMode;
            return true;
        } else if (id == 1) {
            scroll(-1);
            return true;
        } else if (id == 2) {
            scroll(1);
            return true;
        }
        return false;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        int mode = deleteMode ? 1 : 0;
        int total = storage.size();
        if (mode != lastSentMode || scrollOffset != lastSentScroll || total != lastSentTotal) {
            for (Object o : this.crafters) {
                ICrafting c = (ICrafting) o;
                c.sendProgressBarUpdate(this, 0, mode);
                c.sendProgressBarUpdate(this, 1, scrollOffset);
                c.sendProgressBarUpdate(this, 2, total);
            }
            lastSentMode = mode;
            lastSentScroll = scrollOffset;
            lastSentTotal = total;
        }
    }

    /** 客户端 Container 接收服务器进度条同步（0=模式 1=滚动）。 */
    @Override
    public void updateProgressBar(int id, int value) {
        if (id == 0) {
            this.deleteMode = value == 1;
        } else if (id == 1) {
            this.scrollOffset = value;
        }
    }

    // ------------------------------------------------------------------ 逻辑

    private void withdrawToInventory(EntityPlayer p, int entryIndex, int count) {
        ItemStack out = storage.withdraw(entryIndex, count); // 计数减少（可负），返回样本
        if (out == null || out.stackSize <= 0) {
            return;
        }

        ItemStack rest = mergeIntoInventory(out);
        if (rest != null && rest.stackSize > 0) {
            ItemStack cur = p.inventory.getItemStack();
            if (cur == null) {
                p.inventory.setItemStack(rest);
            } else if (BackpackStorage.sameVariant(cur, rest) && cur.stackSize < cur.getMaxStackSize()) {
                int space = cur.getMaxStackSize() - cur.stackSize;
                int move = Math.min(space, rest.stackSize);
                cur.stackSize += move;
                rest.stackSize -= move;
                if (rest.stackSize > 0) {
                    dropItem(p, rest);
                }
            } else {
                dropItem(p, rest);
            }
        }

        // 物品已进入玩家背包/光标，但槽位同步包可能被 isChangingQuantityOnly 抑制，
        // 主动全量下发窗口内容，让客户端即时显示（服务器端才调用）。
        if (!p.worldObj.isRemote) {
            saveAndRefresh();
            resyncToClient(p);
            InfinitePackMod.LOG.info("[infpack] server WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
        }
    }

    /** 服务器端：向客户端全量重发本容器所有槽位内容。 */
    private void resyncToClient(EntityPlayer p) {
        if (p instanceof EntityPlayerMP) {
            ((EntityPlayerMP) p).sendContainerAndContentsToPlayer(this, this.getInventory());
        }
    }

    private ItemStack mergeIntoInventory(ItemStack out) {
        if (out == null || out.stackSize <= 0) {
            return out;
        }
        mergeItemStack(out, PLAYER_START, PLAYER_START + 27, false); // 主物品栏
        if (out.stackSize > 0) {
            mergeItemStack(out, PLAYER_START + 27, PLAYER_START + 36, false); // 快捷栏
        }
        return out;
    }

    private void dropItem(EntityPlayer p, ItemStack stack) {
        EntityItem ei = new EntityItem(p.worldObj, p.posX, p.posY + 0.5D, p.posZ, stack);
        ei.delayBeforeCanPickup = 0;
        p.worldObj.spawnEntityInWorld(ei);
    }

    private void scroll(int delta) {
        int max = Math.max(0, storage.size() - ENTRY_VISIBLE);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset + delta));
    }

    private void clampScroll() {
        int max = Math.max(0, storage.size() - ENTRY_VISIBLE);
        if (scrollOffset > max) {
            scrollOffset = max;
        }
    }

    private void saveAndRefresh() {
        // 每次保存前重新定位背包物品，避免引用失效导致持久化丢失
        ItemStack bp = findBackpack(player);
        if (bp != null) {
            this.backpack = bp;
            storage.save(bp);
        }
        if (player != null) {
            player.inventory.markDirty();
        }
        clampScroll();
    }

    // ------------------------------------------------------------------ 客户端显示同步

    /** 客户端是否需要重载：背包物品的 infpack 根 NBT 引用是否变化（服务器每次保存会新建）。 */
    public boolean needsReload() {
        return getBackpackRoot(findBackpack(player)) != lastLoadedRoot;
    }

    /** 从"已同步到客户端的背包物品 NBT"重载条目（仅客户端 GUI updateScreen 调用）。 */
    public void reloadFromBackpack() {
        ItemStack bp = findBackpack(player);
        this.storage = BackpackStorage.load(bp);
        this.lastLoadedRoot = getBackpackRoot(bp);
        clampScroll();
        InfinitePackMod.LOG.info("[infpack] client reload storage size={} scroll={}", storage.size(), scrollOffset);
    }

    private static NBTTagCompound getBackpackRoot(ItemStack bp) {
        if (bp != null && bp.hasTagCompound() && bp.getTagCompound().hasKey(BackpackStorage.TAG_KEY)) {
            return bp.getTagCompound().getCompoundTag(BackpackStorage.TAG_KEY);
        }
        return null;
    }

    // ------------------------------------------------------------------ 内部

    private void addPlayerSlots(InventoryPlayer inv, int startY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(inv, col + row * 9 + 9, 8 + col * 18, startY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(inv, col, 8 + col * 18, startY + 58));
        }
    }

    private ItemStack findBackpack(EntityPlayer p) {
        if (p == null || p.inventory == null) {
            return null;
        }
        for (int i = 0; i < p.inventory.getSizeInventory(); i++) {
            ItemStack s = p.inventory.getStackInSlot(i);
            if (s != null && s.getItem() == InfinitePackMod.itemInfinitePack) {
                return s;
            }
        }
        return null;
    }

    /** 条目显示用库存：45 个可见槽，映射到 storage 的 scrollOffset 偏移处。 */
    private static class EntriesInventory implements IInventory {

        private final ContainerInfinitePack container;

        EntriesInventory(ContainerInfinitePack container) {
            this.container = container;
        }

        @Override
        public int getSizeInventory() {
            return ENTRY_VISIBLE;
        }

        @Override
        public ItemStack getStackInSlot(int i) {
            int idx = container.scrollOffset + i;
            if (idx < 0 || idx >= container.storage.size()) {
                return null;
            }
            return container.storage.getDisplayStack(idx);
        }

        @Override
        public ItemStack decrStackSize(int i, int n) {
            return null; // 无限，不减少
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int i) {
            return null;
        }

        @Override
        public void setInventorySlotContents(int i, ItemStack stack) {
            // 条目增删由 slotClick/transferStackInSlot 统一处理
        }

        @Override
        public String getInventoryName() {
            return "container.infinitepack";
        }

        @Override
        public boolean hasCustomInventoryName() {
            return false;
        }

        @Override
        public int getInventoryStackLimit() {
            return 1;
        }

        @Override
        public void markDirty() {}

        @Override
        public boolean isUseableByPlayer(EntityPlayer p) {
            return true;
        }

        @Override
        public void openInventory() {}

        @Override
        public void closeInventory() {}

        @Override
        public boolean isItemValidForSlot(int i, ItemStack stack) {
            return true;
        }
    }
}
