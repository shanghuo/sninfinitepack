package com.infpack;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * 服务器端自动化验证命令：/infpacktest [player]
 *
 * 在专用服务器上无玩家时使用 FakePlayer 运行，验证"得一即无限"的全部核心逻辑：
 *  - 相同变体吸收、不同耐久/附魔/NBT 独立成条目
 *  - 取出为深拷贝（NBT 完全一致）、无限取出
 *  - 删除条目后不再可取
 *  - 持久化（保存/载入）一致
 *  - 容器层交互（点击取出、删除模式、滚动）
 */
public class CommandInfPackTest extends CommandBase {

    private int passCount = 0;
    private int failCount = 0;

    @Override
    public String getCommandName() {
        return "infpacktest";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/infpacktest [player]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        passCount = 0;
        failCount = 0;
        InfinitePackMod.LOG.info("========== InfinitePack 自动化测试开始 ==========");

        EntityPlayer player = null;
        if (args.length > 0) {
            player = getPlayer(sender, args[0]);
        } else {
            player = findFirstOnlinePlayer();
        }
        boolean usingFake = false;
        if (player == null) {
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null) {
                WorldServer world = server.worldServerForDimension(0);
                player = FakePlayerFactory.getMinecraft(world);
                usingFake = true;
            }
        }
        log("目标玩家: " + (player == null ? "无" : player.getCommandSenderName())
                + (usingFake ? " (FakePlayer)" : ""));

        testStorage();
        if (player != null) {
            testContainer(player);
        } else {
            log("无玩家，跳过容器层测试。");
        }

        InfinitePackMod.LOG.info("========== InfinitePack 测试结束: 通过 " + passCount + ", 失败 " + failCount
                + " ==========");
    }

    // ------------------------------------------------------------------ Part A: 纯存储逻辑

    private void testStorage() {
        ItemStack backpack = new ItemStack(InfinitePackMod.itemInfinitePack);
        BackpackStorage storage = BackpackStorage.load(backpack);

        ItemStack diamond = new ItemStack(Items.diamond, 3);

        // 1. 放入钻石 → 1 条
        check("放入钻石后 size==1", storage.addEntry(diamond, InfinitePackMod.itemInfinitePack) && storage.size() == 1);
        // 2. 再放相同钻石 → 吸收，仍 1 条
        check("相同钻石被吸收（仍 1 条）", !storage.addEntry(new ItemStack(Items.diamond, 1), InfinitePackMod.itemInfinitePack)
                && storage.size() == 1);

        // 3. 满耐久弓
        ItemStack fullBow = new ItemStack(Items.bow);
        check("放入满耐久弓后 size==2", storage.addEntry(fullBow, InfinitePackMod.itemInfinitePack) && storage.size() == 2);

        // 4. 消耗过耐久的弓（damage=50）
        ItemStack usedBow = new ItemStack(Items.bow);
        usedBow.setItemDamage(50);
        check("放入消耗弓(dmg50)后 size==3（满弓与耗弓各自独立）",
                storage.addEntry(usedBow, InfinitePackMod.itemInfinitePack) && storage.size() == 3);

        // 5. 附魔剑（NBT）
        ItemStack sword = new ItemStack(Items.diamond_sword);
        sword.addEnchantment(Enchantment.sharpness, 4);
        check("放入附魔剑后 size==4", storage.addEntry(sword, InfinitePackMod.itemInfinitePack) && storage.size() == 4);

        // 6. 相同附魔剑 → 吸收
        ItemStack sword2 = new ItemStack(Items.diamond_sword);
        sword2.addEnchantment(Enchantment.sharpness, 4);
        check("相同附魔剑被吸收", !storage.addEntry(sword2, InfinitePackMod.itemInfinitePack) && storage.size() == 4);

        // 7. 不同附魔等级 → 新条目
        ItemStack sword3 = new ItemStack(Items.diamond_sword);
        sword3.addEnchantment(Enchantment.sharpness, 1);
        check("不同附魔等级为独立条目（size==5）",
                storage.addEntry(sword3, InfinitePackMod.itemInfinitePack) && storage.size() == 5);

        // 8. 自定义 NBT（如模组属性）→ 独立条目
        ItemStack machine = new ItemStack(Items.iron_ingot);
        machine.setTagCompound(new NBTTagCompound());
        machine.getTagCompound().setTag("infpackTestEU", new NBTTagInt(123456));
        check("自定义NBT为独立条目（size==6）",
                storage.addEntry(machine, InfinitePackMod.itemInfinitePack) && storage.size() == 6);

        // 9. 取出一致性：满弓 vs 耗弓 各是各的
        ItemStack gotFull = storage.getSample(1);
        ItemStack gotUsed = storage.getSample(2);
        check("取出满弓与耗弓各自独立", gotFull != null && gotUsed != null
                && gotFull.getItemDamage() == 0 && gotUsed.getItemDamage() == 50
                && !BackpackStorage.sameVariant(gotFull, gotUsed));

        // 10. 取出 NBT 完全一致（附魔剑）
        ItemStack gotSword = storage.getSample(3);
        check("取出附魔剑 NBT 完全一致", gotSword != null
                && BackpackStorage.sameVariant(sword, gotSword)
                && NbtUtil.tagsEqual(sword.getTagCompound(), gotSword.getTagCompound()));

        // 11. 取出自定义 NBT 完全一致
        ItemStack gotMachine = storage.getSample(5);
        check("取出自定义NBT完全一致", gotMachine != null
                && NbtUtil.tagsEqual(machine.getTagCompound(), gotMachine.getTagCompound()));

        // 12. 可透支取出：计数可为负但始终能取出（无限）
        int before12 = storage.getCount(0);
        boolean infinite = true;
        for (int i = 0; i < 100; i++) {
            ItemStack got = storage.withdraw(0, 1);
            if (got == null || got.getItem() != Items.diamond) {
                infinite = false;
                break;
            }
        }
        check("连续取出100次均成功（无限）", infinite);
        check("取出后计数减少且可为负", storage.getCount(0) == before12 - 100);

        // 13. 删除条目
        check("删除耗弓条目", storage.removeEntry(2) && storage.size() == 5);
        check("删除后不再包含耗弓变体", !storage.contains(usedBow));
        check("删除不影响其他条目", storage.getSample(1) != null
                && storage.getSample(1).getItemDamage() == 0 && storage.size() == 5);

        // 14. 持久化往返
        storage.save(backpack);
        BackpackStorage reloaded = BackpackStorage.load(backpack);
        check("持久化往返后条目数一致", reloaded.size() == storage.size() && reloaded.size() == 5);
        check("持久化往返后附魔剑一致",
                reloaded.indexOf(sword) >= 0 && NbtUtil.tagsEqual(sword.getTagCompound(),
                        reloaded.getSample(reloaded.indexOf(sword)).getTagCompound()));
        check("持久化往返后满弓一致", reloaded.indexOf(fullBow) >= 0
                && reloaded.getSample(reloaded.indexOf(fullBow)).getItemDamage() == 0);

        // 15. 防止把背包放入自身
        check("不允许放入背包本身", !storage.addEntry(backpack, InfinitePackMod.itemInfinitePack));
    }

    // ------------------------------------------------------------------ Part B: 容器层逻辑

    private void testContainer(EntityPlayer player) {
        // 清理玩家背包（FakePlayer 可能残留）
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            player.inventory.setInventorySlotContents(i, null);
        }
        player.inventory.setItemStack(null);

        ItemStack backpack = new ItemStack(InfinitePackMod.itemInfinitePack);
        player.inventory.addItemStackToInventory(backpack);

        // 先放两个不同条目：钻石、耗弓
        BackpackStorage storage = BackpackStorage.load(backpack);
        storage.addEntry(new ItemStack(Items.diamond, 1), InfinitePackMod.itemInfinitePack);
        ItemStack usedBow = new ItemStack(Items.bow);
        usedBow.setItemDamage(25);
        storage.addEntry(usedBow, InfinitePackMod.itemInfinitePack);
        storage.save(backpack);

        ContainerInfinitePack container = new ContainerInfinitePack(player);
        player.openContainer = container;

        check("容器构建成功，条目数==2", container.getStorage().size() == 2);

        // 点击第 0 个条目（钻石）左键取出整组 → 进入玩家背包
        player.inventory.setItemStack(null);
        container.slotClick(0, 0, 0, player);
        boolean hasDiamond = false;
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack s = player.inventory.getStackInSlot(i);
            if (s != null && s.getItem() == Items.diamond) {
                hasDiamond = true;
            }
        }
        check("容器左键取出钻石进入背包", hasDiamond);

        // 右键第 1 个条目（耗弓）取 1 个
        player.inventory.setItemStack(null);
        container.slotClick(1, 1, 0, player);
        boolean hasBow = false;
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack s = player.inventory.getStackInSlot(i);
            if (s != null && s.getItem() == Items.bow && s.getItemDamage() == 25) {
                hasBow = true;
            }
        }
        check("容器右键取出耗弓1个", hasBow);

        // 删除模式：切换后点击删除第 0 条（钻石）
        container.enchantItem(player, 0);
        check("切换为删除模式", container.getDeleteMode());
        container.slotClick(0, 0, 0, player);
        check("删除模式下点击删除条目（size==1）", container.getStorage().size() == 1);
        check("删除后钻石不可再取", !container.getStorage().contains(new ItemStack(Items.diamond, 1)));

        // 关回删除模式
        container.enchantItem(player, 0);
        check("切回取出模式", !container.getDeleteMode());

        // 滚动
        int before = container.getScrollOffset();
        container.enchantItem(player, 2); // 向下
        check("滚动偏移变化", container.getScrollOffset() != before);

        // Shift 取出（transferStackInSlot）
        player.inventory.setItemStack(null);
        int invCountBefore = countItem(player, Items.bow);
        container.transferStackInSlot(player, 0);
        int invCountAfter = countItem(player, Items.bow);
        check("Shift取出到背包", invCountAfter > invCountBefore);

        // 关闭容器不崩
        container.onContainerClosed(player);
        check("容器关闭正常", true);
    }

    private int countItem(EntityPlayer p, net.minecraft.item.Item item) {
        int c = 0;
        for (int i = 0; i < p.inventory.getSizeInventory(); i++) {
            ItemStack s = p.inventory.getStackInSlot(i);
            if (s != null && s.getItem() == item) {
                c += s.stackSize;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ 工具

    private EntityPlayer findFirstOnlinePlayer() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            List<?> list = server.getConfigurationManager().playerEntityList;
            for (Object o : list) {
                if (o instanceof EntityPlayer) {
                    return (EntityPlayer) o;
                }
            }
        }
        return null;
    }

    private void check(String name, boolean ok) {
        if (ok) {
            passCount++;
            log("  [PASS] " + name);
        } else {
            failCount++;
            log("  [FAIL] " + name);
        }
    }

    private void log(String msg) {
        InfinitePackMod.LOG.info("[infpacktest] " + msg);
    }
}
