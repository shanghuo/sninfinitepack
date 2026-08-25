package com.infpack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 强制生存全局配置（config 目录，对所有存档生效）。
 *
 * 存 enabled + 密码哈希（SHA-256）。每次开启需设置密码（覆盖旧密码）；
 * 关闭需密码正确，关闭后密码即失效（passHash 清空）。
 * 忘密码：删除配置文件即可重置（或由控制台/OP 处理）。
 */
public class ForceSurvivalConfig {

    private static final String FILE_NAME = "sninfinitepack-forcesurvival.properties";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PASS_HASH = "passHash";
    private static final String KEY_PREV_KEEP_INV_PREFIX = "prevKeepInventory.";

    private static File file;
    private static boolean enabled = false;
    private static String passHash = null;
    /** 各维度（存档:维度ID）在 lock 影响前的 keepInventory 状态，关闭时各自还原。 */
    private static final Map<String, Boolean> prevKeepInvByDim = new HashMap<String, Boolean>();

    private ForceSurvivalConfig() {}

    /** preInit 调用：加载配置（config 目录）。 */
    public static void load(File configDir) {
        file = new File(configDir, FILE_NAME);
        Properties props = new Properties();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
                enabled = Boolean.parseBoolean(props.getProperty(KEY_ENABLED, "false"));
                passHash = props.getProperty(KEY_PASS_HASH, null);
                prevKeepInvByDim.clear();
                for (String name : props.stringPropertyNames()) {
                    if (name.startsWith(KEY_PREV_KEEP_INV_PREFIX)) {
                        String dimKey = name.substring(KEY_PREV_KEEP_INV_PREFIX.length());
                        prevKeepInvByDim.put(dimKey, Boolean.parseBoolean(props.getProperty(name, "false")));
                    }
                }
            } catch (IOException e) {
                InfinitePackMod.LOG.error("[infpack] 强制生存配置读取失败", e);
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 开启并设置密码（覆盖旧密码）。 */
    public static boolean enable(String pass) {
        if (pass == null || pass.length() == 0) {
            return false;
        }
        enabled = true;
        passHash = hash(pass);
        save();
        return true;
    }

    /** 关闭：校验密码正确才停用并清除密码（密码失效）。 */
    public static boolean disable(String pass) {
        if (passHash == null || passHash.length() == 0 || !hash(pass).equals(passHash)) {
            return false;
        }
        enabled = false;
        passHash = null;
        save();
        return true;
    }

    /** 仅重置为关闭（忘密码时删除配置文件即可，此处供控制台/内部调用）。 */
    public static void forceDisable() {
        enabled = false;
        passHash = null;
        save();
    }

    // ------------------------------------------------------------ keepInventory 开启前状态（按维度）

    /** 某维度（存档:维度ID）是否有 lock 影响前的 keepInventory 记录。 */
    public static boolean hasPrevKeepInv(String dimKey) {
        return prevKeepInvByDim.containsKey(dimKey);
    }

    /** 某维度 lock 影响前的 keepInventory 值。 */
    public static boolean getPrevKeepInv(String dimKey) {
        Boolean v = prevKeepInvByDim.get(dimKey);
        return v != null && v.booleanValue();
    }

    /** 记录某维度 lock 影响前的 keepInventory（首次被 lock 影响时调用）。 */
    public static void recordPrevKeepInv(String dimKey, boolean value) {
        prevKeepInvByDim.put(dimKey, Boolean.valueOf(value));
        save();
    }

    /** 移除某维度记录（已还原后）。 */
    public static void removePrevKeepInv(String dimKey) {
        if (prevKeepInvByDim.remove(dimKey) != null) {
            save();
        }
    }

    /** 是否还有待还原的维度记录（未加载维度在加载后补恢复）。 */
    public static boolean hasAnyPrevKeepInv() {
        return !prevKeepInvByDim.isEmpty();
    }

    private static void save() {
        if (file == null) {
            return;
        }
        Properties props = new Properties();
        props.setProperty(KEY_ENABLED, Boolean.toString(enabled));
        if (passHash != null) {
            props.setProperty(KEY_PASS_HASH, passHash);
        }
        for (Map.Entry<String, Boolean> e : prevKeepInvByDim.entrySet()) {
            props.setProperty(KEY_PREV_KEEP_INV_PREFIX + e.getKey(), Boolean.toString(e.getValue().booleanValue()));
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "SN Infinite Pack force-survival config");
        } catch (IOException e) {
            InfinitePackMod.LOG.error("[infpack] 强制生存配置写入失败", e);
        }
    }

    private static String hash(String pass) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(pass.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return pass; // 兜底（正常不会发生）
        }
    }
}
