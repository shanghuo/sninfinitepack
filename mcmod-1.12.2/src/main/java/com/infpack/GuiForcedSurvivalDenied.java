package com.infpack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 全屏拦截：当前存档禁止游玩（强制生存）—— 1.12.2 版。
 *
 * 进入创造模式创建的存档且强制生存开启时显示。无法按 Esc/其它键关闭，
 * 只能「返回标题」（配合服务器端权威检测防绕过）。
 */
@SideOnly(Side.CLIENT)
public class GuiForcedSurvivalDenied extends GuiScreen {

    @Override
    public void initGui() {
        this.buttonList.clear();
        // 仅提供「返回标题」，不提供退出游戏
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height / 2 + 30, 200, 20,
                "\u8fd4\u56de\u6807\u9898")); // 返回标题
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRenderer, "\u5f53\u524d\u5b58\u6863\u7981\u6b62\u6e38\u73a9",
                this.width / 2, this.height / 2 - 50, 0xFF5555); // 当前存档禁止游玩
        this.drawCenteredString(this.fontRenderer,
                "\u5f3a\u5236\u751f\u5b58\u5df2\u542f\u7528\uff0c\u521b\u9020\u6a21\u5f0f\u521b\u5efa\u7684\u5b58\u6863\u4e0d\u5141\u8bb8\u8fdb\u5165",
                this.width / 2, this.height / 2 - 28, 0xAAAAAA);
        this.drawCenteredString(this.fontRenderer,
                "\u8bf7\u8fd4\u56de\u6807\u9898\uff0c\u6216\u5173\u95ed\u5f3a\u5236\u751f\u5b58\u540e\u91cd\u65b0\u8fdb\u5165",
                this.width / 2, this.height / 2 - 10, 0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            // 返回标题：发送断开包并关闭拦截界面（随后回到主菜单）
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world != null) {
                mc.world.sendQuittingDisconnectingPacket();
            }
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        // 拦截所有按键（含 Esc），不允许关闭本界面
    }
}
