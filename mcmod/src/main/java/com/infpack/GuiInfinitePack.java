package com.infpack;

import java.util.Arrays;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 得一即无限背包 GUI。
 *
 * 布局（176x222，复用 vanilla 双箱背景 generic_54.png）：
 *  - 6x9 条目网格（可直接放入=存入、点击=取出），每格右下角 ∞
 *  - 右上角：取出/删除 模式切换按钮
 *  - 状态行（条目/页数）位于玩家物品栏上方
 *  - 删除模式：红色遮罩覆盖全部 6 行区域
 *
 * 客户端条目显示由 updateScreen 每 tick 从"已同步的背包物品 NBT"重载驱动
 * （见 ContainerInfinitePack.reloadFromBackpack），不依赖服务器槽位同步。
 */
@SideOnly(Side.CLIENT)
public class GuiInfinitePack extends GuiContainer {

    private static final ResourceLocation BG = new ResourceLocation("textures/gui/container/generic_54.png");

    private final ContainerInfinitePack container;
    private boolean deleteMode = false;
    private int tickCounter = 0;
    /** 删除模式二次确认：待删除的条目索引（-1 = 无）。 */
    private int pendingDelete = -1;

    public GuiInfinitePack(ContainerInfinitePack container) {
        super(container);
        this.container = container;
        this.xSize = 176;
        this.ySize = 222;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 客户端显示：乐观更新为主（slotClick 直接改 storage），
        // 这里再从"已同步到客户端的背包物品 NBT"重载兜底（引用变化时）。
        if (container.needsReload()) {
            container.reloadFromBackpack();
        }
        if (++tickCounter % 40 == 0) {
            InfinitePackMod.LOG.info("[infpack] client tick size={} scroll={} needsReload={}",
                    container.getTotalEntries(), container.getScrollOffset(), container.needsReload());
        }
        this.deleteMode = container.getDeleteMode();
        // 退出删除模式或待删除条目已不存在时，清除待确认标记
        if (!deleteMode || pendingDelete >= container.getStorage().size()) {
            pendingDelete = -1;
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float p_146976_1_, int p_146976_2_, int p_146976_3_) {
        this.mc.getTextureManager().bindTexture(BG);
        this.drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 标题（左对齐；游戏内不显示 SN，SN 只是模组名）
        this.fontRendererObj.drawString("\u5f97\u4e00\u5373\u65e0\u9650\u80cc\u5305", 8, 6, 0x404040);

        // 右上角模式切换按钮（右对齐，自动适配字体宽度，避免右侧溢出）
        String modeText = deleteMode ? "\u3010\u5220\u9664\u3011" : "\u3010\u53d6\u51fa\u3011";
        int modeX = this.xSize - this.fontRendererObj.getStringWidth(modeText) - 6;
        this.fontRendererObj.drawString(modeText, modeX, 6, deleteMode ? 0xFF0000 : 0x404040);

        // 状态行（玩家物品栏上方，不在条目格内；数字用缩写防溢出）
        int total = container.getTotalEntries();
        int scroll = container.getScrollOffset();
        int page = scroll / ContainerInfinitePack.ENTRY_VISIBLE + 1;
        int pages = Math.max(1, (int) Math.ceil(total / (double) ContainerInfinitePack.ENTRY_VISIBLE));
        int color = deleteMode ? 0xFF0000 : 0x404040;
        int statusY = 128; // 状态行整体上移 3px（约 0.3 字符高度）
        this.fontRendererObj.drawString("\u6761\u76ee " + compactCount(total), 8, statusY, color);
        this.fontRendererObj.drawString("\u9875 " + compactCount(page) + "/" + compactCount(pages), 80, statusY, color);
        if (deleteMode) {
            String hint = pendingDelete >= 0 ? "\u518d\u70b9\u4e00\u6b21\u786e\u8ba4" : "\u70b9\u51fb\u6761\u76ee\u5220\u9664";
            int hintX = this.xSize - this.fontRendererObj.getStringWidth(hint) - 6;
            this.fontRendererObj.drawString(hint, hintX, statusY, 0xFF0000);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);

        // 图标渲染在 z=100 且开启深度测试；这里关闭深度测试，让文本/高亮/遮罩覆盖在图标上层
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try {
            // 每个有内容的条目槽右下角画计数（正=白，负/0=红；大数缩写+超宽缩小保证放得下）
            for (int i = 0; i < ContainerInfinitePack.ENTRY_VISIBLE; i++) {
                Slot s = (Slot) this.inventorySlots.inventorySlots.get(i);
                int idx = container.getScrollOffset() + i;
                if (s.getHasStack() && idx >= 0 && idx < container.getStorage().size()) {
                    int count = container.getStorage().getCount(idx);
                    String text = compactCount(count);
                    int color = count >= 0 ? 0xFFFFFF : 0xFF4040;
                    int textW = this.fontRendererObj.getStringWidth(text);
                    int cx = guiLeft + s.xDisplayPosition + 17 - textW;
                    int cy = guiTop + s.yDisplayPosition + 9;
                    if (textW > 15) {
                        // 文本比槽位宽：等比缩小字体放进格子
                        float scale = 15.0f / textW;
                        GL11.glPushMatrix();
                        GL11.glTranslatef(cx, cy, 0.0f);
                        GL11.glScalef(scale, scale, 1.0f);
                        this.fontRendererObj.drawStringWithShadow(text, 0, 0, color);
                        GL11.glPopMatrix();
                    } else {
                        this.fontRendererObj.drawStringWithShadow(text, cx, cy, color);
                    }
                }
            }

            // 待删除条目高亮（黄色边框效果）
            if (pendingDelete >= 0) {
                int vis = pendingDelete - container.getScrollOffset();
                if (vis >= 0 && vis < ContainerInfinitePack.ENTRY_VISIBLE) {
                    Slot s = (Slot) this.inventorySlots.inventorySlots.get(vis);
                    drawRect(guiLeft + s.xDisplayPosition, guiTop + s.yDisplayPosition,
                            guiLeft + s.xDisplayPosition + 16, guiTop + s.yDisplayPosition + 16, 0x70FFAA00);
                }
            }

            // 删除模式：红色遮罩覆盖全部 6 行条目区
            if (deleteMode) {
                drawRect(guiLeft + 7, guiTop + 17, guiLeft + 7 + ContainerInfinitePack.ENTRY_COLS * 18 + 1,
                        guiTop + 17 + ContainerInfinitePack.ENTRY_ROWS * 18 + 1, 0x30FF0000);
            }
        } finally {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }

        // 悬停条目：tooltip 显示精确计数（格子里的数字可能被缩写）
        Slot hovered = getSlotAt(mouseX, mouseY);
        if (hovered != null && hovered.slotNumber >= 0 && hovered.slotNumber < ContainerInfinitePack.ENTRY_VISIBLE) {
            int idx = container.getScrollOffset() + hovered.slotNumber;
            if (idx >= 0 && idx < container.getStorage().size()) {
                int count = container.getStorage().getCount(idx);
                this.drawHoveringText(Arrays.asList("\u6570\u91cf: " + count), mouseX, mouseY, this.fontRendererObj);
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        int lx = mouseX - guiLeft;
        int ly = mouseY - guiTop;
        // 右上角模式切换按钮（与绘制同款右对齐命中框）
        String modeText = deleteMode ? "\u3010\u5220\u9664\u3011" : "\u3010\u53d6\u51fa\u3011";
        int modeX = this.xSize - this.fontRendererObj.getStringWidth(modeText) - 6;
        if (lx >= modeX - 2 && lx <= modeX + this.fontRendererObj.getStringWidth(modeText) + 2 && ly >= 4 && ly <= 14) {
            pendingDelete = -1;
            this.mc.playerController.sendEnchantPacket(this.inventorySlots.windowId, 0);
            return;
        }
        // 删除模式：空手左键点击条目需二次确认（第一次仅标记，第二次同格确认删除）
        if (deleteMode && button == 0 && this.mc.thePlayer.inventory.getItemStack() == null) {
            Slot slot = getSlotAt(mouseX, mouseY);
            if (slot != null && slot.slotNumber >= 0 && slot.slotNumber < ContainerInfinitePack.ENTRY_VISIBLE) {
                int idx = container.getScrollOffset() + slot.slotNumber;
                if (idx >= 0 && idx < container.getStorage().size()) {
                    if (pendingDelete == idx) {
                        pendingDelete = -1;
                        super.mouseClicked(mouseX, mouseY, button); // 第二次：确认删除
                    } else {
                        pendingDelete = idx; // 第一次：仅标记待删除
                    }
                    return;
                }
            }
            pendingDelete = -1; // 点击非条目区域：取消待确认
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    /** 返回鼠标所在的槽（自定义命中检测，覆盖 16x16 槽位区域）。 */
    private Slot getSlotAt(int mouseX, int mouseY) {
        for (int i = 0; i < this.inventorySlots.inventorySlots.size(); i++) {
            Slot slot = (Slot) this.inventorySlots.inventorySlots.get(i);
            int sx = guiLeft + slot.xDisplayPosition;
            int sy = guiTop + slot.yDisplayPosition;
            if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                return slot;
            }
        }
        return null;
    }

    /** 大数缩写：<1万 原样；<1亿 用万；否则用亿。负数前缀 "-"。 */
    private static String compactCount(int value) {
        if (value < 0) {
            return "-" + compactCount(-value);
        }
        if (value < 10000) {
            return Integer.toString(value);
        }
        if (value < 100000000) {
            long wan = value / 10000L;
            long dec = (value % 10000L) / 1000L;
            if (dec == 0 || wan >= 100) {
                return wan + "\u4e07"; // 万
            }
            return wan + "." + dec + "\u4e07";
        }
        long yi = value / 100000000L;
        long dec = (value % 100000000L) / 10000000L;
        if (dec == 0 || yi >= 100) {
            return yi + "\u4ebf"; // 亿
        }
        return yi + "." + dec + "\u4ebf";
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            pendingDelete = -1; // 滚动时取消待删除标记
            this.mc.playerController.sendEnchantPacket(this.inventorySlots.windowId, wheel > 0 ? 1 : 2);
        }
    }
}
