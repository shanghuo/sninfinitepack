package com.infpack;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 强制生存：服务器每 tick 检测（FMLCommonHandler bus 注册）—— 1.12.2 版。
 *
 *  - 存档创建为创造 + 强制开启 → 向在线玩家发 MsgForcedSurvivalDenied（客户端全屏拦截）。
 *  - 存档创建为生存 + 强制开启 → 玩家 gameType 为创造 → 自动改回生存（服务器权威，防绕过）。
 *  - 死亡不掉落（keepInventory）按维度记录：lock 影响某维度时记录其开启前状态并强制 true；
 *    关闭 lock 恢复已加载维度，未加载维度记录保留、加载后补恢复（跨存档/多维度均正确）。
 */
public class ForceSurvivalHandler {

    private int denyCooldown = 0; // 创造存档拒绝通知去重（每秒一次）
    private int keepInvCheckCooldown = 0; // keepInventory 检查降频（每秒一次）

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.getPlayerList() == null) {
            return;
        }
        if (ForceSurvivalConfig.isEnabled()) {
            if (server.getWorld(0) == null) {
                return;
            }
            // lock 开启期间：确保所有已加载维度死亡不掉落（keepInventory=true），进入新世界/维度也生效。
            // 每 tick 只是读检查（值已是 true 就不写）；降频到每秒一次更省。
            if (--keepInvCheckCooldown <= 0) {
                keepInvCheckCooldown = 20;
                ensureKeepInventoryOnAll(server);
            }

            GameType worldType = server.getWorld(0).getWorldInfo().getGameType();
            boolean worldCreative = worldType == GameType.CREATIVE;

            if (worldCreative) {
                // 创造创建的存档 + 强制生存 → 拒绝游玩（客户端全屏拦截；去重每秒一次）
                if (--denyCooldown <= 0) {
                    denyCooldown = 20;
                    for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
                        NetworkHandler.NETWORK.sendTo(new MsgForcedSurvivalDenied(), p);
                    }
                    InfinitePackMod.LOG.info("[infpack] 强制生存：拒绝创造存档游玩，在线玩家={}",
                            server.getPlayerList().getPlayers().size());
                }
                return;
            }

            // 生存创建的存档：玩家切创造 → 自动改回生存（防绕过）
            for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
                if (p.interactionManager.getGameType() == GameType.CREATIVE) {
                    p.setGameType(GameType.SURVIVAL);
                    InfinitePackMod.LOG.info("[infpack] 强制生存：玩家 {} 切创造，已改回生存", p.getName());
                }
            }
        } else {
            // lock 已关闭：补恢复未加载维度（加载后其 keepInventory 仍为 lock 期间的 true）
            recoverPendingKeepInv(server);
        }
    }

    /** 开启 lock：记录各已加载维度开启前状态并强制死亡不掉落。 */
    public static void onEnable() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        ensureKeepInventoryOnAll(server);
        InfinitePackMod.LOG.info("[infpack] lock 开启：已开启死亡不掉落");
    }

    /** 关闭 lock：恢复所有已加载维度到各自开启前状态；未加载维度记录保留（加载后补恢复）。 */
    public static void onDisable() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            for (WorldServer w : server.worlds) {
                if (w == null) {
                    continue;
                }
                String key = dimKey(w);
                if (ForceSurvivalConfig.hasPrevKeepInv(key)) {
                    w.getGameRules().setOrCreateGameRule("keepInventory",
                            ForceSurvivalConfig.getPrevKeepInv(key) ? "true" : "false");
                    ForceSurvivalConfig.removePrevKeepInv(key);
                }
            }
        }
        String suffix = ForceSurvivalConfig.hasAnyPrevKeepInv() ? "（未加载维度将在加载后恢复）" : "";
        InfinitePackMod.LOG.info("[infpack] lock 关闭：已恢复当前维度死亡不掉落{}", suffix);
    }

    /** 维度标识：存档文件夹:维度ID（跨存档唯一）。 */
    private static String dimKey(WorldServer w) {
        return w.getSaveHandler().getWorldDirectory().getName() + ":" + w.provider.getDimension();
    }

    /** lock 开启期间：首次影响的维度记录开启前状态，然后确保 keepInventory=true。 */
    private static void ensureKeepInventoryOnAll(MinecraftServer server) {
        for (WorldServer w : server.worlds) {
            if (w == null) {
                continue;
            }
            String key = dimKey(w);
            boolean cur = w.getGameRules().getBoolean("keepInventory");
            if (!ForceSurvivalConfig.hasPrevKeepInv(key)) {
                ForceSurvivalConfig.recordPrevKeepInv(key, cur);
            }
            if (!cur) {
                w.getGameRules().setOrCreateGameRule("keepInventory", "true");
            }
        }
    }

    /** lock 关闭后：对已加载维度补恢复残留记录（未加载维度加载后被 tick 触发恢复）。 */
    private static void recoverPendingKeepInv(MinecraftServer server) {
        if (!ForceSurvivalConfig.hasAnyPrevKeepInv()) {
            return;
        }
        for (WorldServer w : server.worlds) {
            if (w == null) {
                continue;
            }
            String key = dimKey(w);
            if (ForceSurvivalConfig.hasPrevKeepInv(key)) {
                w.getGameRules().setOrCreateGameRule("keepInventory",
                        ForceSurvivalConfig.getPrevKeepInv(key) ? "true" : "false");
                ForceSurvivalConfig.removePrevKeepInv(key);
                InfinitePackMod.LOG.info("[infpack] lock 关闭后补恢复维度 {} 死亡不掉落", key);
            }
        }
    }
}
