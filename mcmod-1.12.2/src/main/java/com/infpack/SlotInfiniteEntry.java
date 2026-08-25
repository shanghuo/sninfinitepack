package com.infpack;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 条目显示槽 —— 1.12.2 版。
 *
 * 显示不依赖服务器→客户端的槽位同步（1.12.2 FML 的 GUI openContainer 并不指向 GUI 容器）。
 * 客户端与服务器都直接从自身 storage 读取；客户端的 storage 由服务器分包下发填充。
 */
public class SlotInfiniteEntry extends Slot {

    public SlotInfiniteEntry(IInventory inv, int index, int x, int y) {
        super(inv, index, x, y);
    }

    @Override
    public ItemStack getStack() {
        return this.inventory.getStackInSlot(getSlotIndex());
    }

    @Override
    public void putStack(ItemStack stack) {
        // 服务器下发的槽位内容：不写回条目存储（条目增删由容器逻辑处理）
        this.onSlotChanged();
    }

    @Override
    public void onSlotChanged() {}

    @Override
    public ItemStack decrStackSize(int amount) {
        return ItemStack.EMPTY; // 无限，不减少（1.12.2 槽位契约要求 EMPTY 而非 null）
    }

    @Override
    public boolean getHasStack() {
        return !getStack().isEmpty();
    }

    @Override
    public boolean isItemValid(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() != InfinitePackMod.itemInfinitePack;
    }

    @Override
    public boolean canTakeStack(EntityPlayer player) {
        return false;
    }

    @Override
    public int getSlotStackLimit() {
        return 1;
    }
}
