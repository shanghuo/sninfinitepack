package com.infpack;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/** 客户端：收到强制生存拒绝 → 切主线程显示全屏拦截 GUI。 */
public class MsgForcedSurvivalDeniedHandler implements IMessageHandler<MsgForcedSurvivalDenied, IMessage> {

    @Override
    public IMessage onMessage(MsgForcedSurvivalDenied message, MessageContext ctx) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return null;
        }
        mc.func_152344_a(new Runnable() {
            @Override
            public void run() {
                if (mc.theWorld != null && !(mc.currentScreen instanceof GuiForcedSurvivalDenied)) {
                    mc.displayGuiScreen(new GuiForcedSurvivalDenied());
                }
            }
        });
        return null;
    }
}
