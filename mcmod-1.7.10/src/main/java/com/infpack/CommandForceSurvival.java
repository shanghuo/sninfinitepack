package com.infpack;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/**
 * 强制生存指令：/snanginflock
 *
 *  - 无参数：返回帮助 + 当前开启/关闭状态。
 *  - 启用：/snanginflock <密码> <enable|on|1|true>   —— 开启并设置密码（每次开启重新设置）。
 *  - 停用：/snanginflock <密码> <disable|off|0|false> —— 关闭（需密码正确，关闭后密码失效）。
 *
 * 开启后对所有存档生效：创造创建的存档→禁止游玩（客户端全屏拦截）；
 * 生存创建的存档→玩家切创造自动改回生存。
 */
public class CommandForceSurvival extends CommandBase {

    @Override
    public String getCommandName() {
        return "snanginflock";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/snanginflock <密码> <enable|disable>";
    }

    // 默认需 OP 权限（CommandBase 默认 4 级）

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        String pass = args[0];
        String action = args[1].toLowerCase();
        if ("enable".equals(action) || "on".equals(action) || "1".equals(action) || "true".equals(action)) {
            if (ForceSurvivalConfig.enable(pass)) {
                ForceSurvivalHandler.onEnable(); // 开启死亡不掉落（记录开启前状态）
                msg(sender, "\u00a7a[\u5f3a\u5236\u751f\u5b58] \u5df2\u5f00\u542f\u5e76\u8bbe\u7f6e\u5bc6\u7801\uff0c\u5df2\u5f00\u542f\u6b7b\u4ea1\u4e0d\u6389\u843d\u3002");
            } else {
                msg(sender, "\u00a7c[\u5f3a\u5236\u751f\u5b58] \u5f00\u542f\u5931\u8d25\uff1a\u5bc6\u7801\u4e0d\u80fd\u4e3a\u7a7a\u3002");
            }
        } else if ("disable".equals(action) || "off".equals(action) || "0".equals(action) || "false".equals(action)) {
            if (ForceSurvivalConfig.disable(pass)) {
                ForceSurvivalHandler.onDisable(); // 恢复死亡不掉落到开启前状态
                msg(sender, "\u00a7a[\u5f3a\u5236\u751f\u5b58] \u5df2\u5173\u95ed\uff0c\u5bc6\u7801\u5df2\u5931\u6548\uff0c\u6b7b\u4ea1\u4e0d\u6389\u843d\u5df2\u6062\u590d\u3002");
            } else {
                msg(sender, "\u00a7c[\u5f3a\u5236\u751f\u5b58] \u5173\u95ed\u5931\u8d25\uff1a\u5bc6\u7801\u9519\u8bef\u6216\u672a\u8bbe\u7f6e\u3002");
            }
        } else {
            sendHelp(sender);
        }
    }

    private void sendHelp(ICommandSender sender) {
        msg(sender, "\u00a7e[\u5f3a\u5236\u751f\u5b58] " + (ForceSurvivalConfig.isEnabled() ? "\u5f53\u524d\uff1a\u5f00\u542f" : "\u5f53\u524d\uff1a\u5173\u95ed"));
        msg(sender, "\u00a77\u7528\u6cd5\uff1a");
        msg(sender, "\u00a77  /snanginflock <\u5bc6\u7801> <enable|on|1|true>   \u2014\u2014 \u5f00\u542f\u5e76\u8bbe\u7f6e\u5bc6\u7801");
        msg(sender, "\u00a77  /snanginflock <\u5bc6\u7801> <disable|off|0|false> \u2014\u2014 \u5173\u95ed\uff08\u9700\u5bc6\u7801\u6b63\u786e\uff09");
        msg(sender, "\u00a77\u5f00\u542f\u540e\u5bf9\u6240\u6709\u5b58\u6863\u751f\u6548\uff1a\u521b\u9020\u6a21\u5f0f\u521b\u5efa\u7684\u5b58\u6863\u2192\u7981\u6b62\u6e38\u73a9\uff1b\u751f\u5b58\u5b58\u6863\u2192\u5207\u521b\u9020\u81ea\u52a8\u6539\u56de\u751f\u5b58\u3002");
    }

    private void msg(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }
}
