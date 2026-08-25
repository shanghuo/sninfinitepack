package com.infpack;

import net.minecraft.client.Minecraft;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 客户端：收到强制生存拒绝 → 切主线程显示全屏拦截 GUI—— 1.12.2 版。 */
public class MsgForcedSurvivalDeniedHandler implements IMessageHandler<MsgForcedSurvivalDenied, IMessage> {

    @Override
    public IMessage onMessage(MsgForcedSurvivalDenied message, MessageContext ctx) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return null;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (mc.world != null && !(mc.currentScreen instanceof GuiForcedSurvivalDenied)) {
                    mc.displayGuiScreen(new GuiForcedSurvivalDenied());
                }
            }
        });
        return null;
    }
}
