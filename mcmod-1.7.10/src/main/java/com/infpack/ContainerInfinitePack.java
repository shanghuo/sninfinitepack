package com.infpack;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
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
 * 存储改造（方案 A）：条目数据存服务器文件（world/data/sninfinitepack/<uuid>.nbt），
 * 物品 NBT 只存 infpack.uuid。客户端条目数据由服务器分包下发（MsgBackpackData），
 * 客户端显示以"乐观更新 + 服务器权威下发收敛"；操作后服务器保存文件并重新下发。
 */
public class ContainerInfinitePack extends Container {

    public static final int ENTRY_COLS = 9;
    public static final int ENTRY_ROWS = 6;
    public static final int ENTRY_VISIBLE = ENTRY_COLS * ENTRY_ROWS; // 54，占满 9x6 网格
    public static final int PLAYER_START = ENTRY_VISIBLE; // 54
    /**
     * 滚轮每次翻动的步长 = 一行（9 格）。
     * 逐条翻（1 格）太碎、整页翻（54 格）太跳，一行一行翻既保留上下文又够快。
     */
    public static final int SCROLL_STEP = ENTRY_COLS;

    private final EntityPlayer player;
    private ItemStack backpack; // 玩家背包中的实际引用
    private BackpackStorage storage; // 客户端由服务器下发数据填充

    /** 背包 UUID（物品 NBT infpack.uuid；服务器唯一标识）。 */
    private String uuid;

    /** 客户端：收到服务器下发的新数据（GUI 每 tick 消费并重算显示顺序）。 */
    private volatile boolean serverDataDirty = false;

    private final EntriesInventory entriesInv;

    private int scrollOffset = 0;
    private boolean deleteMode = false;

    /**
     * 显示顺序：过滤+排序后的真实条目索引列表（客户端计算并下发；服务器存接收值）。
     * volatile：网络线程写入、主线程读取，只做不可变数组引用赋值，安全。
     * null = 尚未收到顺序（按自然偏移映射）。
     */
    private volatile int[] displayOrder;

    /**
     * 渲染帧快照（仅客户端 GUI 使用）。
     *
     * 一帧之内，「滚动偏移 + 显示顺序 + 存储」会被读取多次：原版画图标读一次、
     * 计数覆盖层读一次、悬停 tooltip 再读一次。如果这期间状态发生变化，就会出现
     * 「图标还是旧的、右下角计数已经是新的」——表现就是玩家说的
     * 「滚轮一滚，鼠标位置那个计数就跟着变，而图标没变」。
     *
     * 因此 GUI 在每帧绘制前 beginRenderFrame() 锁定一份快照，帧内所有查询都走快照，
     * 保证同一帧内图标与计数必定来自同一份状态；endRenderFrame() 后恢复实时读取。
     */
    private int[] renderOrder;
    private int renderScroll;
    private BackpackStorage renderStorage;
    private boolean rendering;

    /** GUI 每帧绘制前调用：锁定本帧使用的（滚动偏移 / 显示顺序 / 存储）快照。 */
    public void beginRenderFrame() {
        this.renderOrder = displayOrder;
        this.renderScroll = scrollOffset;
        this.renderStorage = storage;
        this.rendering = true;
    }

    /** GUI 每帧绘制后调用：解除快照，恢复实时读取。 */
    public void endRenderFrame() {
        this.rendering = false;
        this.renderOrder = null;
        this.renderStorage = null;
    }

    private int lastSentMode = -1;
    private int lastSentScroll = -1;
    private int lastSentTotal = -1;

    public ContainerInfinitePack(EntityPlayer player) {
        this.player = player;
        this.backpack = findBackpack(player);
        this.entriesInv = new EntriesInventory(this);

        if (player.worldObj.isRemote) {
            // 客户端：条目数据由服务器分包下发，storage 初始为空
            this.storage = new BackpackStorage();
            this.uuid = BackpackStorage.getUuid(backpack);
        } else {
            // 服务器：旧格式迁移 → 确保 uuid → 从文件加载
            migrateLegacy(player);
            this.uuid = BackpackStorage.ensureUuid(backpack);
            this.storage = BackpackDataManager.ensure(player).load(uuid);
        }

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
        return rendering && renderStorage != null ? renderStorage : storage;
    }

    public int getScrollOffset() {
        return rendering ? renderScroll : scrollOffset;
    }

    public boolean getDeleteMode() {
        return deleteMode;
    }

    /** 显示用总条目数：客户端以自身 storage 为准（乐观更新 + 重载兜底，与格子一致）。 */
    public int getTotalEntries() {
        return storage.size();
    }

    /** 可见条目数：有显示顺序则按顺序长度（过滤后），否则=存储条目数。 */
    public int getVisibleCount() {
        int[] order = rendering ? renderOrder : displayOrder;
        return order != null ? order.length : getStorage().size();
    }

    /**
     * 槽位 → 真实条目索引：按客户端下发的显示顺序（过滤+排序）映射。
     *  - 有显示顺序：越界（该槽在过滤/排序结果之外）= 空，返回 -1
     *  - 无显示顺序（尚未收到）：退化为自然偏移（scrollOffset + slotId）
     */
    public int getEntryIndexForSlot(int slotId) {
        int vis = (rendering ? renderScroll : scrollOffset) + slotId;
        int[] order = rendering ? renderOrder : displayOrder;
        if (order != null) {
            if (vis >= 0 && vis < order.length) {
                return order[vis];
            }
            return -1; // 过滤/排序范围外：该槽为空
        }
        return vis; // 尚未收到显示顺序：自然偏移
    }

    /** 服务器：接收客户端算好的显示顺序（过滤+排序后的真实条目索引）。 */
    public void setDisplayOrder(int[] order) {
        this.displayOrder = order; // 仅赋值不可变数组引用；滚动收敛交给主线程 detectAndSendChanges
    }

    /** 读取当前显示顺序（客户端重算后设置，GUI 展示用）。 */
    public int[] getDisplayOrder() {
        return displayOrder;
    }

    /** 背包 UUID（服务器唯一标识；客户端可能为 null 直到下发到达）。 */
    public String getUuid() {
        return uuid;
    }

    // ------------------------------------------------------------------ 存储（服务器文件）

    /** 服务器：把条目数据分包下发给打开该背包的客户端玩家。 */
    public void sendDataToClient() {
        if (player instanceof EntityPlayerMP && uuid != null && uuid.length() > 0) {
            int n = storage.size();
            int partCount = Math.max(1, (n + MsgBackpackData.PART_SIZE - 1) / MsgBackpackData.PART_SIZE);
            for (int p = 0; p < partCount; p++) {
                int from = p * MsgBackpackData.PART_SIZE;
                int cnt = Math.min(n, from + MsgBackpackData.PART_SIZE) - from;
                MsgBackpackData msg = new MsgBackpackData();
                msg.uuid = uuid;
                msg.partIndex = p;
                msg.partCount = partCount;
                msg.itemNames = new String[cnt];
                msg.damages = new int[cnt];
                msg.counts = new int[cnt];
                msg.lastAccesses = new long[cnt];
                msg.tags = new NBTTagCompound[cnt];
                for (int i = 0; i < cnt; i++) {
                    int idx = from + i;
                    ItemStack s = storage.getSample(idx);
                    msg.itemNames[i] = Item.itemRegistry.getNameForObject(s.getItem());
                    msg.damages[i] = s.getItemDamage();
                    msg.counts[i] = storage.getCount(idx);
                    msg.lastAccesses[i] = storage.getLastAccess(idx);
                    msg.tags[i] = s.hasTagCompound() ? s.getTagCompound() : null;
                }
                NetworkHandler.NETWORK.sendTo(msg, (EntityPlayerMP) player);
            }
            InfinitePackMod.LOG.info("[infpack] server send backpack data uuid={} entries={} parts={}", uuid, n, partCount);
        }
    }

    /** 客户端：应用服务器下发的条目数据（替代原物品 NBT reload 兜底）。 */
    public void applyServerData(String serverUuid, BackpackStorage serverStorage) {
        this.uuid = serverUuid;
        this.storage = serverStorage;
        clampScroll();
        this.serverDataDirty = true;
        InfinitePackMod.LOG.info("[infpack] client apply server data size={}", storage.size());
    }

    /** 客户端：GUI 每 tick 检查是否有服务器下发的新数据待刷新。 */
    public boolean consumeServerDataDirty() {
        boolean b = serverDataDirty;
        serverDataDirty = false;
        return b;
    }

    /** 服务器：旧格式（条目在物品 NBT、无 uuid）→ 导入服务器文件并写入 uuid。 */
    private void migrateLegacy(EntityPlayer p) {
        ItemStack bp = findBackpack(p);
        if (BackpackStorage.isLegacy(bp)) {
            BackpackDataManager dm = BackpackDataManager.ensure(p);
            BackpackStorage old = BackpackStorage.load(bp); // 旧物品 NBT 条目
            String newUuid = BackpackStorage.ensureUuid(bp);
            dm.save(newUuid, old);
            // 清空物品 NBT 中的条目，只留 uuid
            NBTTagCompound root = bp.getTagCompound().getCompoundTag(BackpackStorage.TAG_KEY);
            root.removeTag(BackpackStorage.TAG_ENTRIES);
            p.inventory.markDirty();
            InfinitePackMod.LOG.info("[infpack] 迁移旧格式背包 uuid={} entries={}", newUuid, old.size());
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public ItemStack slotClick(int slotId, int clickedButton, int mode, EntityPlayer player) {
        if (slotId >= 0 && slotId < ENTRY_VISIBLE) {
            if (mode == 5) {
                return null; // 拖拽分发到条目槽无意义，忽略（避免误存入）
            }
            if (mode == 1) {
                // Shift+左键=整组、Shift+右键=1个：直接进玩家背包（不是到光标）
                return shiftToInventory(player, slotId, clickedButton == 1 ? 1 : -1);
            }
            int entryIndex = getEntryIndexForSlot(slotId);
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

            // 空手：取出到光标（左键=整组、右键=1个，与 MC 容器操作一致）。
            // 客户端乐观更新（减计数 + 物品放光标），服务器权威执行后 resync 收敛。
            if (entryIndex < 0 || entryIndex >= storage.size()) {
                return null;
            }
            int count = (mode == 0 && clickedButton == 1) ? 1 : -1;
            if (player.worldObj.isRemote) {
                ItemStack out = storage.withdraw(entryIndex, count);
                if (out != null && out.stackSize > 0) {
                    putOnCursor(player, out);
                }
                InfinitePackMod.LOG.info("[infpack] client WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
                return null;
            }
            withdrawToCursor(player, entryIndex, count);
            return null;
        }
        return super.slotClick(slotId, clickedButton, mode, player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (index >= 0 && index < ENTRY_VISIBLE) {
            // Shift+条目 = 取一整组到玩家背包
            int entryIndex = getEntryIndexForSlot(index);
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
            // 关闭时重新定位背包物品并保存到服务器文件（物品 NBT 只存 uuid，不存条目）
            ItemStack bp = findBackpack(player);
            if (bp != null) {
                this.backpack = bp;
                BackpackDataManager.ensure(player).save(uuid, storage);
                InfinitePackMod.LOG.info("[infpack] server close save uuid={} size={}", uuid, storage.size());
            }
        }
    }

    @Override
    public boolean enchantItem(EntityPlayer player, int id) {
        if (id == 0) {
            deleteMode = !deleteMode;
            return true;
        } else if (id == 1) {
            scroll(-SCROLL_STEP);
            return true;
        } else if (id == 2) {
            scroll(SCROLL_STEP);
            return true;
        }
        return false;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        clampScroll(); // 显示顺序变化（过滤/排序）后把滚动收敛到可见范围
        int mode = deleteMode ? 1 : 0;
        int total = getVisibleCount();
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

    /** Shift+点击条目：整组/单个直接进玩家背包（Shift+左键=整组、Shift+右键=1个，MC 习惯）。 */
    private ItemStack shiftToInventory(EntityPlayer p, int slotId, int amount) {
        int entryIndex = getEntryIndexForSlot(slotId);
        if (entryIndex >= 0 && entryIndex < storage.size()) {
            if (!p.worldObj.isRemote) {
                withdrawToInventory(p, entryIndex, amount); // amount<0=整组, >0=指定数量
            } else {
                storage.withdraw(entryIndex, amount);
                InfinitePackMod.LOG.info("[infpack] client SHIFT-WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
            }
        }
        return null;
    }

    /** 取出到光标（slotClick 左/右键用）：计数减少，物品放到玩家光标上，多余掉落。 */
    private void withdrawToCursor(EntityPlayer p, int entryIndex, int count) {
        ItemStack out = storage.withdraw(entryIndex, count); // 计数减少（可负），返回样本
        if (out == null || out.stackSize <= 0) {
            return;
        }
        putOnCursor(p, out);
        if (!p.worldObj.isRemote) {
            saveAndRefresh();
            resyncToClient(p);
            InfinitePackMod.LOG.info("[infpack] server WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
        }
    }

    /** 把物品放到玩家光标上：空手=直接放；同变体未满=合并；否则掉落（取出要求空手，此处仅防御）。 */
    private void putOnCursor(EntityPlayer p, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) {
            return;
        }
        ItemStack cur = p.inventory.getItemStack();
        if (cur == null) {
            p.inventory.setItemStack(stack);
        } else if (BackpackStorage.sameVariant(cur, stack) && cur.stackSize < cur.getMaxStackSize()) {
            int space = cur.getMaxStackSize() - cur.stackSize;
            int move = Math.min(space, stack.stackSize);
            cur.stackSize += move;
            stack.stackSize -= move;
            if (stack.stackSize > 0) {
                dropItem(p, stack);
            }
        } else {
            dropItem(p, stack);
        }
    }

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
        int max = Math.max(0, getVisibleCount() - ENTRY_VISIBLE);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset + delta));
    }

    /** 滚动收敛到可见范围（服务器 detectAndSendChanges 每 tick 调用；客户端重算顺序后调用）。 */
    public void clampScroll() {
        int max = Math.max(0, getVisibleCount() - ENTRY_VISIBLE);
        if (scrollOffset > max) {
            scrollOffset = max;
        }
    }

    private void saveAndRefresh() {
        // 服务器：保存到文件 + 下发权威数据给客户端（乐观更新随后被收敛）；
        if (!player.worldObj.isRemote) {
            BackpackDataManager.ensure(player).save(uuid, storage);
            sendDataToClient();
        }
        if (player != null) {
            player.inventory.markDirty();
        }
        clampScroll();
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
            int idx = container.getEntryIndexForSlot(i);
            BackpackStorage s = container.getStorage(); // 走帧快照：与计数覆盖层同源
            if (idx < 0 || idx >= s.size()) {
                return null;
            }
            return s.getDisplayStack(idx);
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
