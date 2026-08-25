package com.infpack;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

/**
 * 得一即无限背包物品：右键打开背包 GUI —— 1.12.2 版。
 */
public class ItemInfinitePack extends Item {

    public ItemInfinitePack() {
        this.setMaxStackSize(1);
        this.setMaxDamage(0);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (!world.isRemote) {
            player.openGui(InfinitePackMod.instance, GuiHandler.GUI_ID_BACKPACK, world,
                    (int) player.posX, (int) player.posY, (int) player.posZ);
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }
}
