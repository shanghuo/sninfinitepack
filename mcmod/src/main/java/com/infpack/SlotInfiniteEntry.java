package com.infpack;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 条目显示槽。
 *
 * 显示不依赖服务器→客户端的槽位同步（1.7.10 FML 的 GUI openContainer 并不指向 GUI 容器）。
 * 客户端与服务器都直接从自身 storage 读取；客户端的 storage 由 GUI 每 tick
 * 从"已同步的背包物品 NBT"重载（见 ContainerInfinitePack.reloadFromBackpack），保持新鲜。
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
        return null; // 无限，不减少
    }

    @Override
    public boolean getHasStack() {
        return getStack() != null;
    }

    @Override
    public boolean isItemValid(ItemStack stack) {
        return stack != null && stack.getItem() != InfinitePackMod.itemInfinitePack;
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
