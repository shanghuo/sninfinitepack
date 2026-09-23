package com.infpack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;

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
            // 返回标题：走原版「保存并退出到标题」的同款安全序列（见 GuiIngameMenu id=1 分支）。
            //
            // 不能只调 displayGuiScreen(null)：
            //  1.12.2 的 NetworkManager.closeChannel() 会 channel.close().awaitUninterruptibly()
            //  阻塞客户端线程，而断开流程（NetHandlerPlayClient.onDisconnect → loadWorld(null)）
            //  会在别的线程把 world / player 置空；
            //  紧接着的 Minecraft.displayGuiScreen(null) 只要读到 world != null，就会走
            //  `this.player.getHealth()` 分支（Minecraft.java:1056）——world 与 player 是两个
            //  非 volatile 字段、不同步读取，一旦读到「world 还是旧的、player 已被置空」就会 NPE 崩溃。
            //  1.7.10 的 closeChannel 不做 await，断开晚一个 tick 才在主线程发生，所以同样的代码
            //  在 1.7.10 侥幸不崩。
            //
            // loadWorld(null) 在客户端线程内完成世界/玩家卸载，再显式传入非 null 的 GuiMainMenu，
            // 即可完全绕开上面那条 player 解引用（该分支只在 guiScreenIn == null 时才评估）。
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world != null) {
                mc.world.sendQuittingDisconnectingPacket();
            }
            mc.loadWorld((WorldClient) null);
            mc.displayGuiScreen(new GuiMainMenu());
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        // 拦截所有按键（含 Esc），不允许关闭本界面
    }
}
