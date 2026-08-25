package com.infpack;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketSetSlot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/**
 * 得一即无限背包容器 —— 1.12.2 版。
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

    private int lastSentMode = -1;
    private int lastSentScroll = -1;
    private int lastSentTotal = -1;

    public ContainerInfinitePack(EntityPlayer player) {
        this.player = player;
        this.backpack = findBackpack(player);
        this.entriesInv = new EntriesInventory(this);

        if (player.world.isRemote) {
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
        return player != null && player.world != null && player.world.isRemote;
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

    /** 可见条目数：有显示顺序则按顺序长度（过滤后），否则=存储条目数。 */
    public int getVisibleCount() {
        int[] order = displayOrder;
        return order != null ? order.length : storage.size();
    }

    /**
     * 槽位 → 真实条目索引：按客户端下发的显示顺序（过滤+排序）映射。
     *  - 有显示顺序：越界（该槽在过滤/排序结果之外）= 空，返回 -1
     *  - 无显示顺序（尚未收到）：退化为自然偏移（scrollOffset + slotId）
     */
    public int getEntryIndexForSlot(int slotId) {
        int vis = scrollOffset + slotId;
        int[] order = displayOrder;
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
                    ResourceLocation key = s.getItem().getRegistryName();
                    msg.itemNames[i] = key == null ? "null" : key.toString();
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
    public ItemStack slotClick(int slotId, int dragType, ClickType clickType, EntityPlayer player) {
        if (slotId >= 0 && slotId < ENTRY_VISIBLE) {
            if (clickType == ClickType.QUICK_CRAFT || clickType == ClickType.THROW || clickType == ClickType.PICKUP_ALL) {
                return ItemStack.EMPTY; // 拖拽/丢弃(Q)/收集(双击)对条目槽无意义，忽略（避免误取出整组）
            }
            if (clickType == ClickType.QUICK_MOVE) {
                // Shift+左键=整组、Shift+右键=1个：直接进玩家背包（不是到光标）
                return shiftToInventory(player, slotId, dragType == 1 ? 1 : -1);
            }
            int entryIndex = getEntryIndexForSlot(slotId);
            ItemStack cursor = player.inventory.getItemStack();

            // 删除模式：优先删除（不存入）；手里有物品时忽略该格点击，避免误存入
            if (deleteMode) {
                if (!cursor.isEmpty()) {
                    return ItemStack.EMPTY; // 删除模式带光标：忽略，不存入也不删除
                }
                if (entryIndex >= 0 && entryIndex < storage.size()) {
                    storage.removeEntry(entryIndex);
                    if (!player.world.isRemote) {
                        saveAndRefresh();
                        InfinitePackMod.LOG.info("[infpack] server DELETE idx={} size={}", entryIndex, storage.size());
                    } else {
                        InfinitePackMod.LOG.info("[infpack] client DELETE idx={} size={}", entryIndex, storage.size());
                    }
                }
                return ItemStack.EMPTY;
            }

            // 手里有物品：存入（插到该格位置）。不能存入背包本身。客户端乐观存入即时显示。
            if (!cursor.isEmpty()) {
                if (cursor.getItem() == InfinitePackMod.itemInfinitePack) {
                    return ItemStack.EMPTY; // 不消费光标
                }
                storage.deposit(cursor, InfinitePackMod.itemInfinitePack, entryIndex);
                if (!player.world.isRemote) {
                    saveAndRefresh();
                    InfinitePackMod.LOG.info("[infpack] server DEPOSIT slot={} entryIndex={} size={}", slotId, entryIndex, storage.size());
                } else {
                    InfinitePackMod.LOG.info("[infpack] client DEPOSIT slot={} entryIndex={} size={}", slotId, entryIndex, storage.size());
                }
                ItemStack oldCursor = cursor;
                player.inventory.setItemStack(ItemStack.EMPTY);
                return oldCursor;
            }

            // 空手：取出到光标（左键=整组、右键=1个，与 MC 容器操作一致）。
            // 客户端乐观更新（减计数 + 物品放光标），服务器权威执行后 resync 收敛。
            if (entryIndex < 0 || entryIndex >= storage.size()) {
                return ItemStack.EMPTY;
            }
            int count = (clickType == ClickType.PICKUP && dragType == 1) ? 1 : -1;
            if (player.world.isRemote) {
                ItemStack out = storage.withdraw(entryIndex, count);
                if (out != null && !out.isEmpty()) {
                    putOnCursor(player, out);
                }
                InfinitePackMod.LOG.info("[infpack] client WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
                return ItemStack.EMPTY;
            }
            withdrawToCursor(player, entryIndex, count);
            return ItemStack.EMPTY;
        }
        return super.slotClick(slotId, dragType, clickType, player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (index >= 0 && index < ENTRY_VISIBLE) {
            // Shift+条目 = 取一整组到玩家背包
            int entryIndex = getEntryIndexForSlot(index);
            if (entryIndex >= 0 && entryIndex < storage.size()) {
                if (!player.world.isRemote) {
                    withdrawToInventory(player, entryIndex, -1);
                } else {
                    storage.withdraw(entryIndex, -1);
                    InfinitePackMod.LOG.info("[infpack] client SHIFT-WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
                }
            }
            return ItemStack.EMPTY;
        }
        if (index >= PLAYER_START && index < PLAYER_START + 36) {
            // Shift+玩家背包物品 = 存入（客户端乐观存入，即时显示）
            Slot s = getSlot(index);
            ItemStack stack = s.getStack();
            if (!stack.isEmpty() && stack.getItem() != InfinitePackMod.itemInfinitePack) {
                storage.addEntry(stack, InfinitePackMod.itemInfinitePack);
                if (!player.world.isRemote) {
                    s.putStack(ItemStack.EMPTY);
                    saveAndRefresh();
                    InfinitePackMod.LOG.info("[infpack] server SHIFT-DEPOSIT size={}", storage.size());
                    // 清空了玩家槽位但槽位同步可能被 isChangingQuantityOnly 抑制，主动全量下发
                    resyncToClient(player);
                } else {
                    InfinitePackMod.LOG.info("[infpack] client SHIFT-DEPOSIT size={}", storage.size());
                }
            }
            return ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return findBackpack(player) != null;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (!player.world.isRemote) {
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
        clampScroll(); // 显示顺序变化（过滤/排序）后把滚动收敛到可见范围
        int mode = deleteMode ? 1 : 0;
        int total = getVisibleCount();
        if (mode != lastSentMode || scrollOffset != lastSentScroll || total != lastSentTotal) {
            for (IContainerListener c : this.listeners) {
                c.sendWindowProperty(this, 0, mode);
                c.sendWindowProperty(this, 1, scrollOffset);
                c.sendWindowProperty(this, 2, total);
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
            if (!p.world.isRemote) {
                withdrawToInventory(p, entryIndex, amount); // amount<0=整组, >0=指定数量
            } else {
                storage.withdraw(entryIndex, amount);
                InfinitePackMod.LOG.info("[infpack] client SHIFT-WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
            }
        }
        return ItemStack.EMPTY;
    }

    /** 取出到光标（slotClick 左/右键用）：计数减少，物品放到玩家光标上，多余掉落。 */
    private void withdrawToCursor(EntityPlayer p, int entryIndex, int count) {
        ItemStack out = storage.withdraw(entryIndex, count); // 计数减少（可负），返回样本
        if (out == null || out.getCount() <= 0) {
            return;
        }
        putOnCursor(p, out);
        if (!p.world.isRemote) {
            saveAndRefresh();
            resyncToClient(p);
            InfinitePackMod.LOG.info("[infpack] server WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
        }
    }

    /** 把物品放到玩家光标上：空手=直接放；同变体未满=合并；否则掉落（取出要求空手，此处仅防御）。 */
    private void putOnCursor(EntityPlayer p, ItemStack stack) {
        if (stack == null || stack.getCount() <= 0) {
            return;
        }
        ItemStack cur = p.inventory.getItemStack();
        if (cur.isEmpty()) {
            p.inventory.setItemStack(stack);
        } else if (BackpackStorage.sameVariant(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
            int space = cur.getMaxStackSize() - cur.getCount();
            int move = Math.min(space, stack.getCount());
            cur.setCount(cur.getCount() + move);
            stack.setCount(stack.getCount() - move);
            if (stack.getCount() > 0) {
                dropItem(p, stack);
            }
        } else {
            dropItem(p, stack);
        }
    }

    private void withdrawToInventory(EntityPlayer p, int entryIndex, int count) {
        ItemStack out = storage.withdraw(entryIndex, count); // 计数减少（可负），返回样本
        if (out == null || out.getCount() <= 0) {
            return;
        }

        ItemStack rest = mergeIntoInventory(out);
        if (rest != null && rest.getCount() > 0) {
            ItemStack cur = p.inventory.getItemStack();
            if (cur.isEmpty()) {
                p.inventory.setItemStack(rest);
            } else if (BackpackStorage.sameVariant(cur, rest) && cur.getCount() < cur.getMaxStackSize()) {
                int space = cur.getMaxStackSize() - cur.getCount();
                int move = Math.min(space, rest.getCount());
                cur.setCount(cur.getCount() + move);
                rest.setCount(rest.getCount() - move);
                if (rest.getCount() > 0) {
                    dropItem(p, rest);
                }
            } else {
                dropItem(p, rest);
            }
        }

        // 物品已进入玩家背包/光标，但槽位同步包可能被 isChangingQuantityOnly 抑制，
        // 主动全量下发窗口内容，让客户端即时显示（服务器端才调用）。
        if (!p.world.isRemote) {
            saveAndRefresh();
            resyncToClient(p);
            InfinitePackMod.LOG.info("[infpack] server WITHDRAW idx={} count={}", entryIndex, storage.getCount(entryIndex));
        }
    }

    /** 服务器端：向客户端全量重发本容器内玩家背包槽位（1.12.2 无 sendContainerAndContentsToPlayer，改用逐槽 SPacketSetSlot）。 */
    private void resyncToClient(EntityPlayer p) {
        if (p instanceof EntityPlayerMP) {
            EntityPlayerMP mp = (EntityPlayerMP) p;
            for (int i = PLAYER_START; i < PLAYER_START + 36; i++) {
                Slot slot = this.getSlot(i);
                mp.connection.sendPacket(new SPacketSetSlot(this.windowId, i, slot.getStack()));
            }
            mp.connection.sendPacket(new SPacketSetSlot(-1, -1, p.inventory.getItemStack()));
        }
    }

    private ItemStack mergeIntoInventory(ItemStack out) {
        if (out == null || out.getCount() <= 0) {
            return out;
        }
        mergeItemStack(out, PLAYER_START, PLAYER_START + 27, false); // 主物品栏
        if (out.getCount() > 0) {
            mergeItemStack(out, PLAYER_START + 27, PLAYER_START + 36, false); // 快捷栏
        }
        return out;
    }

    private void dropItem(EntityPlayer p, ItemStack stack) {
        EntityItem ei = new EntityItem(p.world, p.posX, p.posY + 0.5D, p.posZ, stack);
        ei.setPickupDelay(0);
        p.world.spawnEntity(ei);
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
        if (!player.world.isRemote) {
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
        // 主物品栏（含主手）
        for (int i = 0; i < p.inventory.mainInventory.size(); i++) {
            ItemStack s = p.inventory.mainInventory.get(i);
            if (!s.isEmpty() && s.getItem() == InfinitePackMod.itemInfinitePack) {
                return s;
            }
        }
        // 副手（1.12.2 新增，getSizeInventory 不含副手）
        for (int i = 0; i < p.inventory.offHandInventory.size(); i++) {
            ItemStack s = p.inventory.offHandInventory.get(i);
            if (!s.isEmpty() && s.getItem() == InfinitePackMod.itemInfinitePack) {
                return s;
            }
        }
        // 盔甲栏
        for (int i = 0; i < p.inventory.armorInventory.size(); i++) {
            ItemStack s = p.inventory.armorInventory.get(i);
            if (!s.isEmpty() && s.getItem() == InfinitePackMod.itemInfinitePack) {
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
        public boolean isEmpty() {
            return container.storage == null || container.storage.size() == 0;
        }

        @Override
        public ItemStack getStackInSlot(int i) {
            int idx = container.getEntryIndexForSlot(i);
            if (idx < 0 || idx >= container.storage.size()) {
                return ItemStack.EMPTY; // 1.12.2 空槽必须返回 EMPTY，不能返回 null（否则 Container 同步 NPE 崩溃）
            }
            return container.storage.getDisplayStack(idx);
        }

        @Override
        public ItemStack decrStackSize(int i, int n) {
            return ItemStack.EMPTY; // 无限，不减少
        }

        @Override
        public ItemStack removeStackFromSlot(int index) {
            return ItemStack.EMPTY; // 无限，不减少
        }

        @Override
        public void setInventorySlotContents(int i, ItemStack stack) {
            // 条目增删由 slotClick/transferStackInSlot 统一处理
        }

        @Override
        public String getName() {
            return "container.infinitepack";
        }

        @Override
        public ITextComponent getDisplayName() {
            return new TextComponentString(getName());
        }

        @Override
        public boolean hasCustomName() {
            return false;
        }

        @Override
        public int getInventoryStackLimit() {
            return 1;
        }

        @Override
        public void markDirty() {}

        @Override
        public boolean isUsableByPlayer(EntityPlayer p) {
            return true;
        }

        @Override
        public void openInventory(EntityPlayer player) {}

        @Override
        public void closeInventory(EntityPlayer player) {}

        @Override
        public boolean isItemValidForSlot(int i, ItemStack stack) {
            return true;
        }

        @Override
        public int getField(int id) {
            return 0;
        }

        @Override
        public void setField(int id, int value) {}

        @Override
        public int getFieldCount() {
            return 0;
        }

        @Override
        public void clear() {
            // 无限背包：条目增删由容器逻辑统一处理，无需清空
        }
    }
}
