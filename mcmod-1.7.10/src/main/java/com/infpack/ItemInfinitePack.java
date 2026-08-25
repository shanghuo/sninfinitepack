package com.infpack;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;

/**
 * 得一即无限背包物品：右键打开背包 GUI。
 */
public class ItemInfinitePack extends Item {

    public ItemInfinitePack() {
        this.setMaxStackSize(1);
        this.setMaxDamage(0);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (!world.isRemote) {
            player.openGui(InfinitePackMod.instance, GuiHandler.GUI_ID_BACKPACK, world,
                    (int) player.posX, (int) player.posY, (int) player.posZ);
        }
        return stack;
    }

    @SideOnly(Side.CLIENT)
    private IIcon icon;

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister register) {
        this.icon = register.registerIcon(InfinitePackMod.MODID + ":infinitePack");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconIndex(ItemStack stack) {
        return this.icon;
    }
}
