package com.infpack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 得一即无限背包 GUI（排序 + 搜索 + UI 精简）—— 1.12.2 版。
 *
 * 布局（176x222，复用 vanilla 双箱背景 generic_54.png）：
 *  - 6x9 条目网格（可直接放入=存入、点击=取出），每格右下角计数
 *  - 右上角：取出/删除 模式切换按钮
 *  - 状态行（玩家物品栏上方）：搜索框 + 排序方式切换（循环点击）+ 页数
 *  - 删除模式：红色遮罩覆盖全部 6 行区域，状态行左侧显示删除提示
 *
 * 架构（关键决策）：客户端按本地化物品名过滤 + 排序，算出「显示顺序」
 * （真实条目索引 int[]）发服务器；服务器只存该顺序用于槽位→条目映射。
 * 排序/搜索只在客户端做（专用服务器无客户端语言，中文搜索两端不一致），
 * 排序模式无需同步（顺序列表本身携带信息）。
 *
 * 客户端条目显示由服务器分包下发（MsgBackpackData）驱动，不依赖槽位同步。
 */
@SideOnly(Side.CLIENT)
public class GuiInfinitePack extends GuiContainer {

    private static final ResourceLocation BG = new ResourceLocation("textures/gui/container/generic_54.png");

    // 状态行布局（GUI 相对坐标）
    private static final int SEARCH_X = 8;
    private static final int SEARCH_Y = 125;
    private static final int SEARCH_W = 76;
    private static final int SEARCH_H = 14;
    private static final int STATUS_Y = 128;

    /** 排序方式：0=默认(存入顺序) 1=最近存取 2=数量升序 3=数量降序。 */
    private static final int SORT_COUNT = 4;
    private static final String[] SORT_NAMES = { "\u9ed8\u8ba4", "\u6700\u8fd1", "\u6570\u91cf\u5347", "\u6570\u91cf\u964d" }; // 默认/最近/数量升/数量降

    private final ContainerInfinitePack container;
    private boolean deleteMode = false;
    private int tickCounter = 0;
    /** 删除模式二次确认：待删除的条目索引（-1 = 无）。 */
    private int pendingDelete = -1;

    private GuiTextField searchField;
    private int sortMode = 0;
    /** 上次发给服务器的顺序（用于变化检测，避免每 tick 刷包）。 */
    private int[] sentOrder = null;
    /** 需要重算顺序（存入/取出/删除/搜索/切排序后置位）。 */
    private boolean orderDirty = true;
    /** 上次用于计算的搜索词（变化检测）。 */
    private String lastSearchText = "";

    /** 是否已收到服务器下发的条目数据（未收到则定期重发请求）。 */
    private boolean dataReceived = false;
    private int dataRequestCooldown = 0;

    /** 本 tick 累积的滚轮方向（1=向上 / -1=向下 / 0=无）。同 tick 内多个事件合并为一次翻动。 */
    private int wheelDirection = 0;

    /** 滚轮诊断日志开关（排查"滚轮导致鼠标位置计数变化"用；确认结论后可改 false）。 */
    private static final boolean DEBUG_SCROLL = true;
    /** 上一帧鼠标下的条目信息（仅诊断日志用）。 */
    private int diagSlot = -1;
    private int diagIndex = -1;
    private String diagName = "-";
    private int diagCount = 0;

    public GuiInfinitePack(ContainerInfinitePack container) {
        super(container);
        this.container = container;
        this.xSize = 176;
        this.ySize = 222;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.searchField = new GuiTextField(0, this.fontRenderer, SEARCH_X, SEARCH_Y, SEARCH_W, SEARCH_H);
        this.searchField.setMaxStringLength(24);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        flushWheel(); // 本 tick 累积的滚轮 → 合并成一次翻动
        // 条目数据由服务器分包下发（物品 NBT 只存 uuid）。
        // 打开时请求 + 每秒重试；收到后消费刷新标志 → 重算显示顺序。
        if (container.consumeServerDataDirty()) {
            dataReceived = true;
            orderDirty = true;
        }
        if (!dataReceived) {
            if (--dataRequestCooldown <= 0) {
                NetworkHandler.NETWORK.sendToServer(new MsgBackpackRequest());
                dataRequestCooldown = 20; // 每秒重试直到收到
            }
        }
        if (this.searchField != null) {
            this.searchField.updateCursorCounter();
            this.searchField.setVisible(!deleteMode);
        }
        if (++tickCounter % 40 == 0) {
            InfinitePackMod.LOG.info("[infpack] client tick size={} visible={} scroll={} sort={} q={} received={}",
                    container.getTotalEntries(), container.getVisibleCount(), container.getScrollOffset(),
                    sortMode, searchField == null ? "" : searchField.getText(), dataReceived);
        }
        this.deleteMode = container.getDeleteMode();
        // 退出删除模式或待删除条目已不存在时，清除待确认标记
        if (!deleteMode || pendingDelete >= container.getStorage().size()) {
            pendingDelete = -1;
        }

        // 有变化（存取/删除/搜索/切排序）→ 下一 tick 重算顺序，仅变化时发包
        if (this.searchField != null) {
            String currentSearch = this.searchField.getText().toLowerCase();
            if (orderDirty || !currentSearch.equals(lastSearchText)) {
                lastSearchText = currentSearch;
                recomputeAndSendOrder();
            }
        }
    }

    /** 按搜索词过滤 + 排序模式排序，算出显示顺序，更新本地并仅在变化时发给服务器。 */
    private void recomputeAndSendOrder() {
        BackpackStorage storage = container.getStorage();
        String q = searchField.getText().trim().toLowerCase();
        List<Integer> order = new ArrayList<Integer>();
        if (q.length() == 0) {
            for (int i = 0; i < storage.size(); i++) {
                order.add(Integer.valueOf(i));
            }
        } else {
            for (int i = 0; i < storage.size(); i++) {
                ItemStack s = storage.getSample(i);
                if (s != null && s.getDisplayName() != null && s.getDisplayName().toLowerCase().contains(q)) {
                    order.add(Integer.valueOf(i));
                }
            }
        }
        sortOrder(order, storage);
        int[] arr = new int[order.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = order.get(i).intValue();
        }
        container.setDisplayOrder(arr); // 客户端本地更新显示
        container.clampScroll(); // 过滤后本地收敛滚动，避免短暂空白（服务器随后会同步确认）
        if (!Arrays.equals(arr, sentOrder)) {
            sentOrder = arr;
            NetworkHandler.NETWORK.sendToServer(new MsgDisplayOrder(arr));
            InfinitePackMod.LOG.info("[infpack] client send order len={} q='{}' sort={}", arr.length, q, sortMode);
        }
        orderDirty = false;
    }

    private void sortOrder(List<Integer> order, BackpackStorage storage) {
        if (sortMode == 1) { // 最近存取（最新在前；同刻稳定保序）
            Collections.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Long.compare(storage.getLastAccess(b), storage.getLastAccess(a));
                }
            });
        } else if (sortMode == 2) { // 数量升序
            Collections.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Integer.compare(storage.getCount(a), storage.getCount(b));
                }
            });
        } else if (sortMode == 3) { // 数量降序
            Collections.sort(order, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Integer.compare(storage.getCount(b), storage.getCount(a));
                }
            });
        }
        // 默认（0）：存入顺序，不排序
    }

    private String getSortLabel() {
        return "\u6392\u5e8f:" + SORT_NAMES[sortMode]; // 排序:XX
    }

    private int getSortX() {
        return SEARCH_X + SEARCH_W + 8;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        this.mc.getTextureManager().bindTexture(BG);
        this.drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 标题（左对齐；游戏内不显示 SN，SN 只是模组名；精简为"无限背包"）
        this.fontRenderer.drawString("\u65e0\u9650\u80cc\u5305", 8, 6, 0x404040);

        // 右上角模式切换按钮（右对齐，自动适配字体宽度，避免右侧溢出）
        String modeText = deleteMode ? "\u3010\u5220\u9664\u3011" : "\u3010\u53d6\u51fa\u3011";
        int modeX = this.xSize - this.fontRenderer.getStringWidth(modeText) - 6;
        this.fontRenderer.drawString(modeText, modeX, 6, deleteMode ? 0xFF0000 : 0x404040);

        // 状态行（玩家物品栏上方）
        if (deleteMode) {
            // 删除模式：左侧显示删除提示（替代搜索框）
            String hint = pendingDelete >= 0 ? "\u518d\u70b9\u4e00\u6b21\u786e\u8ba4" : "\u70b9\u51fb\u6761\u76ee\u5220\u9664";
            this.fontRenderer.drawString(hint, SEARCH_X, STATUS_Y, 0xFF0000);
        } else {
            // 搜索框 + 空态占位提示
            this.searchField.drawTextBox();
            if (this.searchField.getText().isEmpty() && !this.searchField.isFocused()) {
                this.fontRenderer.drawString("\u641c\u7d22", SEARCH_X + 4, SEARCH_Y + 3, 0x555555);
            }
            // 排序切换（点击循环；非默认模式橙色高亮提示已启用排序）
            String sortLabel = getSortLabel();
            this.fontRenderer.drawString(sortLabel, getSortX(), STATUS_Y, sortMode != 0 ? 0xFF8000 : 0x404040);
        }

        // 页数（右对齐自适应，数字用缩写防溢出）
        int visible = container.getVisibleCount();
        int page = container.getScrollOffset() / ContainerInfinitePack.ENTRY_VISIBLE + 1;
        int pages = Math.max(1, (int) Math.ceil(visible / (double) ContainerInfinitePack.ENTRY_VISIBLE));
        String pageText = "\u9875 " + compactCount(page) + "/" + compactCount(pages);
        int px = this.xSize - this.fontRenderer.getStringWidth(pageText) - 6;
        this.fontRenderer.drawString(pageText, px, STATUS_Y, deleteMode ? 0xFF0000 : 0x404040);
    }

    /**
     * 每帧绘制入口：整帧锁定一份容器状态快照，保证「图标」与「右下角计数」必定同源——
     * 计数只随条目本身（存入/取出/删除）变化，不随滚轮翻页或同步时序变化。
     * 详见 {@link ContainerInfinitePack#beginRenderFrame()}。
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        container.beginRenderFrame();
        try {
            drawScreenFrame(mouseX, mouseY, partialTicks);
        } finally {
            container.endRenderFrame();
        }
    }

    private void drawScreenFrame(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);

        // 图标渲染在 z=100 且开启深度测试；这里关闭深度测试，让文本/高亮/遮罩覆盖在图标上层
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try {
            // 1.12.2 物品图标渲染后遗留 blend/光照/COLOR_MATERIAL 均开启，直接 drawStringWithShadow
            // 会被混合+光照调制（颜色发糊/残影）。与原版画物品数量前 disableBlend 一致，画文字前禁用。
            GlStateManager.disableLighting();
            GlStateManager.disableColorMaterial();
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            // 1.12.2 中文环境下 unicodeFlag=true，数字也用 unicode 小字形（半宽约 4px）渲染，
            // 远小于 1.7.10 英文环境 default 字体的正常字号。这里临时强制 unicodeFlag=false，
            // 让数字走 default 字体（全宽正常字号，随位数自动缩放）；"万/亿"等中文不受影响
            // （不在 ASCII 表，始终走 unicode）。
            boolean prevUnicode = this.fontRenderer.getUnicodeFlag();
            this.fontRenderer.setUnicodeFlag(false);
            // 每个有内容的条目槽右下角画计数（正=白，0/负=红；大数缩写+统一字号+右对齐）
            for (int i = 0; i < ContainerInfinitePack.ENTRY_VISIBLE; i++) {
                Slot s = (Slot) this.inventorySlots.inventorySlots.get(i);
                int idx = container.getEntryIndexForSlot(i);
                if (s.getHasStack() && idx >= 0 && idx < container.getStorage().size()) {
                    int count = container.getStorage().getCount(idx);
                    String text = compactCount(count);
                    int color = count > 0 ? 0xFFFFFF : 0xFF4040; // 0 和负数都是红色
                    int textW = this.fontRenderer.getStringWidth(text);
                    // 统一字号：基础 0.85 倍（比原版略小，避免数字过大/占满格子）；
                    // 仍超宽则等比缩小，保证放进格子。
                    float scale = 0.85f;
                    int availW = 11; // 槽内可用宽度（16px 槽，右缘内收 4px）
                    if (textW * scale > availW) {
                        scale = availW / (float) textW;
                    }
                    // 右对齐：按缩放后的实际宽度定位，文本右缘贴右缘（右缘内收 4px，避免
                    // 长数字溢出槽外；左侧限制保持不变）
                    float cx = guiLeft + s.xPos + 13 - textW * scale;
                    float cy = guiTop + s.yPos + 9;
                    GL11.glPushMatrix();
                    GL11.glTranslatef(cx, cy, 0.0f);
                    GL11.glScalef(scale, scale, 1.0f);
                    this.fontRenderer.drawStringWithShadow(text, 0, 0, color);
                    GL11.glPopMatrix();
                }
            }
            // 恢复 unicodeFlag
            this.fontRenderer.setUnicodeFlag(prevUnicode);
            // 恢复：后续高亮/遮罩的半透明 drawRect 依赖 blend 混合
            GlStateManager.enableLighting();
            GlStateManager.enableColorMaterial();
            GlStateManager.enableBlend();

            // 待删除条目高亮（黄色边框效果）
            if (pendingDelete >= 0) {
                int vis = pendingDelete - container.getScrollOffset();
                if (vis >= 0 && vis < ContainerInfinitePack.ENTRY_VISIBLE) {
                    Slot s = (Slot) this.inventorySlots.inventorySlots.get(vis);
                    drawRect(guiLeft + s.xPos, guiTop + s.yPos,
                            guiLeft + s.xPos + 16, guiTop + s.yPos + 16, 0x70FFAA00);
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
            int idx = container.getEntryIndexForSlot(hovered.slotNumber);
            if (idx >= 0 && idx < container.getStorage().size()) {
                int count = container.getStorage().getCount(idx);
                this.drawHoveringText(Arrays.asList("\u6570\u91cf: " + count), mouseX, mouseY, this.fontRenderer);
                // 记录本帧鼠标下的条目，供滚轮诊断日志使用（见 flushWheel）
                diagSlot = hovered.slotNumber;
                diagIndex = idx;
                diagName = String.valueOf(Item.REGISTRY.getNameForObject(
                        container.getStorage().getSample(idx).getItem()));
                diagCount = count;
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        int lx = mouseX - guiLeft;
        int ly = mouseY - guiTop;

        // 搜索框：点击聚焦/移动光标（删除模式时禁用）；点击框内则不处理槽位/按钮
        if (!deleteMode && this.searchField != null) {
            this.searchField.mouseClicked(lx, ly, button);
            if (lx >= SEARCH_X && lx <= SEARCH_X + SEARCH_W && ly >= SEARCH_Y && ly <= SEARCH_Y + SEARCH_H) {
                return;
            }
        }

        // 排序切换：点击排序标签循环切换（删除模式下也可切换）
        String sortLabel = getSortLabel();
        int sortX = getSortX();
        if (lx >= sortX - 2 && lx <= sortX + this.fontRenderer.getStringWidth(sortLabel) + 2
                && ly >= STATUS_Y - 3 && ly <= STATUS_Y + 9) {
            sortMode = (sortMode + 1) % SORT_COUNT;
            orderDirty = true;
            pendingDelete = -1;
            return;
        }

        // 右上角模式切换按钮（与绘制同款右对齐命中框）
        String modeText = deleteMode ? "\u3010\u5220\u9664\u3011" : "\u3010\u53d6\u51fa\u3011";
        int modeX = this.xSize - this.fontRenderer.getStringWidth(modeText) - 6;
        if (lx >= modeX - 2 && lx <= modeX + this.fontRenderer.getStringWidth(modeText) + 2 && ly >= 4 && ly <= 14) {
            pendingDelete = -1;
            this.mc.playerController.sendEnchantPacket(this.inventorySlots.windowId, 0);
            return;
        }
        // 删除模式：空手左键点击条目需二次确认（第一次仅标记，第二次同格确认删除）
        if (deleteMode && button == 0 && this.mc.player.inventory.getItemStack().isEmpty()) {
            Slot slot = getSlotAt(mouseX, mouseY);
            if (slot != null && slot.slotNumber >= 0 && slot.slotNumber < ContainerInfinitePack.ENTRY_VISIBLE) {
                int idx = container.getEntryIndexForSlot(slot.slotNumber);
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
        orderDirty = true; // 任何点击都可能改了条目（存入/取出/删除）→ 重算顺序
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        // 搜索框聚焦时优先接收按键（Esc 仍由父类处理关闭）
        if (!deleteMode && this.searchField != null && this.searchField.textboxKeyTyped(c, key)) {
            orderDirty = true;
            return;
        }
        super.keyTyped(c, key);
    }

    /** 返回鼠标所在的槽（自定义命中检测，覆盖 16x16 槽位区域）。 */
    private Slot getSlotAt(int mouseX, int mouseY) {
        for (int i = 0; i < this.inventorySlots.inventorySlots.size(); i++) {
            Slot slot = (Slot) this.inventorySlots.inventorySlots.get(i);
            int sx = guiLeft + slot.xPos;
            int sy = guiTop + slot.yPos;
            if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                return slot;
            }
        }
        return null;
    }

    /** 大数缩写：<1000 原样；<100万 用 K；<10亿 用 M；否则用 G。负数前缀 "-"。 */
    private static String compactCount(int value) {
        if (value < 0) {
            return "-" + compactCount(-value);
        }
        if (value < 1000) {
            return Integer.toString(value);
        }
        if (value < 1000000) { // < 1,000,000 → K
            long k = value / 1000L;
            long dec = (value % 1000L) / 100L;
            if (dec == 0 || k >= 100) {
                return k + "K";
            }
            return k + "." + dec + "K";
        }
        if (value < 1000000000L) { // < 1,000,000,000 → M
            long m = value / 1000000L;
            long dec = (value % 1000000L) / 100000L;
            if (dec == 0 || m >= 100) {
                return m + "M";
            }
            return m + "." + dec + "M";
        }
        long g = value / 1000000000L;
        long dec = (value % 1000000000L) / 100000000L;
        if (dec == 0 || g >= 100) {
            return g + "G";
        }
        return g + "." + dec + "G";
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            pendingDelete = -1; // 滚动时取消待删除标记
            // 一次滚轮动作可能派发多个 LWJGL 事件（自由滚轮 / 高分辨率滚轮），
            // 这里只累积方向，由 updateScreen 每 tick 合并成一次翻动，
            // 避免"滚一下直接飞到底"。
            wheelDirection = wheel > 0 ? 1 : -1;
        }
    }

    /** 把本 tick 累积的滚轮方向合并成一次翻动（步长 = 一行，见 SCROLL_STEP）。 */
    private void flushWheel() {
        if (wheelDirection == 0) {
            return;
        }
        int dir = wheelDirection;
        wheelDirection = 0;
        if (DEBUG_SCROLL) {
            InfinitePackMod.LOG.info("[infpack] client SCROLL dir={} scroll={} hoverSlot={} idx={} item={} count={}",
                    dir, container.getScrollOffset(), diagSlot, diagIndex, diagName, diagCount);
        }
        this.mc.playerController.sendEnchantPacket(this.inventorySlots.windowId, dir > 0 ? 1 : 2);
    }
}
