package com.infpack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * 存储改造（方案 A）：背包条目数据管理器（服务器端）。
 *
 * 条目不再写在物品 NBT（物品 NBT 只存 infpack.uuid），而是持久化到
 * world/data/sninfinitepack/<uuid>.nbt —— 随存档走、天然按存档/按背包分开、
 * 无额外依赖。本类负责按 uuid 读写 + 缓存，服务器停止/世界保存时统一写回。
 *
 * 懒初始化：第一次打开背包时按玩家世界目录绑定（单机每次进世界自动重新绑定）。
 */
public class BackpackDataManager {

    private static final String SUB_DIR = "sninfinitepack";

    private static BackpackDataManager INSTANCE;

    private File dataDir;
    private final Map<String, BackpackStorage> cache = new HashMap<String, BackpackStorage>();

    private BackpackDataManager(File worldDir) {
        this.dataDir = new File(new File(worldDir, "data"), SUB_DIR);
        if (!this.dataDir.exists()) {
            this.dataDir.mkdirs();
        }
    }

    /** 按玩家世界懒初始化（服务器第一次打开背包时调用）。 */
    public static BackpackDataManager ensure(EntityPlayer player) {
        if (INSTANCE == null) {
            INSTANCE = new BackpackDataManager(player.worldObj.getSaveHandler().getWorldDirectory());
            InfinitePackMod.LOG.info("[infpack] BackpackDataManager init at {}", INSTANCE.dataDir);
        }
        return INSTANCE;
    }

    /** 服务器停止：全量写回并释放。 */
    public static void shutdown() {
        if (INSTANCE != null) {
            INSTANCE.saveAll();
            INSTANCE = null;
        }
    }

    public static BackpackDataManager instance() {
        return INSTANCE;
    }

    private File fileFor(String uuid) {
        return new File(dataDir, uuid + ".nbt");
    }

    /** 按 uuid 载入条目（缓存优先；无文件返回空 storage）。 */
    public BackpackStorage load(String uuid) {
        if (uuid == null || uuid.length() == 0) {
            return new BackpackStorage();
        }
        BackpackStorage cached = cache.get(uuid);
        if (cached != null) {
            return cached;
        }
        BackpackStorage storage = new BackpackStorage();
        File f = fileFor(uuid);
        if (f.exists()) {
            try {
                NBTTagCompound root = CompressedStreamTools.read(f);
                storage = BackpackStorage.loadFromNBT(root);
            } catch (IOException e) {
                InfinitePackMod.LOG.error("[infpack] 读取背包文件失败 uuid={} file={}", uuid, f, e);
            }
        }
        cache.put(uuid, storage);
        return storage;
    }

    /** 保存条目到文件 + 更新缓存。 */
    public void save(String uuid, BackpackStorage storage) {
        if (uuid == null || uuid.length() == 0) {
            return;
        }
        cache.put(uuid, storage);
        NBTTagCompound root = new NBTTagCompound();
        storage.saveToNBT(root);
        try {
            CompressedStreamTools.write(root, fileFor(uuid));
        } catch (IOException e) {
            InfinitePackMod.LOG.error("[infpack] 写入背包文件失败 uuid={}", uuid, e);
        }
    }

    /** 全量写回所有缓存（服务器停止时）。 */
    public void saveAll() {
        for (Map.Entry<String, BackpackStorage> e : cache.entrySet()) {
            String uuid = e.getKey();
            NBTTagCompound root = new NBTTagCompound();
            e.getValue().saveToNBT(root);
            try {
                CompressedStreamTools.write(root, fileFor(uuid));
            } catch (IOException ex) {
                InfinitePackMod.LOG.error("[infpack] 写回背包文件失败 uuid={}", uuid, ex);
            }
        }
    }
}
