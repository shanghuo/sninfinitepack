package com.infpack;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import net.minecraftforge.fml.common.network.IGuiHandler;

/**
 * GUI 注册（1.12.2 版，FML 简易 GUI 通道）。
 */
public class GuiHandler implements IGuiHandler {

    public static final int GUI_ID_BACKPACK = 0;

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_ID_BACKPACK) {
            return new ContainerInfinitePack(player);
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == GUI_ID_BACKPACK) {
            return new GuiInfinitePack(new ContainerInfinitePack(player));
        }
        return null;
    }
}
