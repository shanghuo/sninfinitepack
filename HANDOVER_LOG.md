# Infinite Pack（得一即无限背包）开发交接记录

> 本文档记录与用户的开发对话历程、关键决策、当前现状、构建与测试方法，供未来 AI / 协作者快速接手。最后更新：2026-08-25。

---

## 1. 项目一句话概述

Minecraft **1.7.10 / Forge 10.13.4.1614（GTNH 2.8.4 实测环境）** 的模组：玩家把任意物品放入背包物品后，可**无限取出**（属性/附魔/耐久/NBT 与放入时完全一致）。现为**计数制**——放入 +N、取出 -N、可为负（负数=无限透支），让玩家感知用了多少。

- MODID：`sninfinitepack`；显示名 `SN Infinite Pack`；版本 `1.0.2`（jar 名 `sninfinitepack-1.0.2.jar`）
- 源码：`docker/mcmod/src/main/java/com/infpack/`
- 成品：`docker/dist/sninfinitepack-1.0.2.jar`
- 语言：`assets/sninfinitepack/lang/{zh_CN,en_US}.lang`
- 合成配方：8 泥土（矿辞 `dirt`）围一圈 + 中间 1 木头（矿辞 `logWood`）

---

## 2. 对话历程与关键决策（逐轮）

### 第 1 轮 — 需求确认 + Docker 环境搭建
- **需求**：得一即无限。放入任一物品 → 无限取出；放入的物品带附魔/耐久/模组属性时，放入和取出必须一致；满耐久弓 vs 消耗过弓 = 两个独立条目，可选删除；删除后不可再取。
- **决策**：
  - 所有开发在 Docker 完成（容器 `mcmod-dev`，JDK25 构建 + jabel→J8 字节码，GTNHGradle 2.0.20 / Gradle 9.3.1）。
  - 网络代理 `http://PROXY_HOST:7890`（HTTP_PROXY/HTTPS_PROXY）。**网络故障由用户处理，不改源**。
  - **绝不动真实实例** `nw-mc-20251224`（用户正在玩）；复制整份到 `nw-mc-20251224-test` 用于测试。
- **实现**：`BackpackStorage`（NBT 精确匹配）、`NbtUtil`（深度 NBT 等价）、`ContainerInfinitePack`、`GuiInfinitePack`、`SlotInfiniteEntry`、`ItemInfinitePack`、`GuiHandler`、`CommonProxy`、`ClientProxy`。
- **关键坑**：本构建 classpath 是 **partial-MCP**，很多方法名是 **SRG（`func_*`）**。直接用 RFG 反编译源码 `docker/mcmod/build/rfg/minecraft-src/java/` 里的名字写，编译和运行都对（reobf 后无 MCP 残留）。
- **UI 决策（用户拍板）**：像箱子一样点击存入；**无丢弃槽、无提示文字行**；条目区 6 行（9×6=54 格）。

### 第 2 轮 — Bug：条目数增加但格子不显示
- 现象：放入后计数增加，但 54 个条目格空白。
- 排查：追踪 FML 客户端 GUI 打开流程（`FMLNetworkHandler.openGui` → `OpenGuiHandler` → `GuiContainer.initGui`）。**结论：`GuiContainer.initGui()` 会把客户端 `player.openContainer` 设成 GUI 容器并拿对 windowId**，同步本应可用。
- 实测日志（`fml-client-latest.log`）：**服务器端存入/删除全正常，但客户端 `reloadFromBackpack()` 从不触发** → 客户端 storage 一直是打开时的空状态。
- **决策（乐观更新，1.0.1）**：客户端显示改为**乐观更新为主**——`slotClick` 里客户端直接改自己的 `storage`（存入/删除即时生效，取出不改），**不依赖**服务器→客户端的槽位/NBT 同步；计数显示直接用 `storage.size()`。`reloadFromBackpack/needsReload` 保留作重开兜底（存储改造后由服务器下发取代）。

### 第 3 轮 — Bug：取出后玩家背包不即时刷新
- 现象：取出实际成功，但下方玩家背包不显示，重开才显示。
- 根因：`EntityPlayerMP.sendSlotContents` 在 `isChangingQuantityOnly=true` 时**直接不发槽位包**，而点包确认成功路径恰好是 true。
- **决策**：服务器取出 / Shift-存入清空玩家槽后，主动 `sendContainerAndContentsToPlayer`（全量 resync）→ 客户端即时显示。

### 第 4 轮 — NEI/创造给的物品也能存入
- 用户问：为什么 NEI/创造模式点出来的物品也能存入并取出（没真实获得）。
- 解释：NEI 把物品放进光标，点条目格即触发"存入"；模组无法区分物品来源（同一个 ItemStack）。
- **用户决策：保持现状，不改**（真实生存服务器 NEI 一般无作弊，无影响）。

### 第 5 轮 — 崩溃排查（重要教训）
- 现象：测试中游戏崩溃。
- 根因：**在游戏运行时用 `Copy-Item -Force` 替换了 mods 里的 jar**，运行中 JVM 延迟加载 `NbtUtil.class` 读到被覆盖的空字节 → `NoClassDefFoundError` + ASM `Index 6 out of bounds for length 0`。jar 本身完好（崩溃栈是旧方法名 `insertEntry`，说明跑的是旧代码）。
- **决策（铁律）**：**绝不在游戏运行时替换 mods 目录里的 jar**。替换前必须确认游戏已关闭。

### 第 6 轮 — 数据存储 + 计数制改造 + 合成配方
- 用户问数据怎么存（是否 sqlite）→ 回答：**不是数据库，是背包物品自身 NBT**（`infpack.Entries` 列表）。
- **决策（计数版，用户要求）**：放入 `count += 放入数量`；取出 `count -= 取出数量`（**可为负 = 无限透支**）；删除移除条目。GUI 每条目右下角显示计数（**正=白、负/0=红**），让玩家想补回正数。
- **实现**：`BackpackStorage.Entry{sample, count}`；`deposit()`、`withdraw()`、`getSample/getCount/getDisplayStack`；NBT 每条目新增 `Count` 字段。
- **合成配方**（`CommonProxy.init`）：`ShapedOreRecipe`，8 `dirt` + 1 `logWood` → 背包。

### 第 7 轮 — GUI 体验 + 显示名修复（当前）
- 删除加**二次确认**：删除模式第一次左键点击仅标记（黄色高亮 `0x70FFAA00` + 状态文字变「再点一次确认」），再点同格才删除；点别处/滚轮/切模式取消。
- 计数文本被图标遮盖 → 修复：画文本/高亮/遮罩前 `GL11.glDisable(GL11.GL_DEPTH_TEST)`（图标在 z=100 且开深度测试），finally 恢复。
- 物品名显示 `item.infinitePack.name` → 根因：lang 键 `item.infinitepack.name`（小写）与 `setUnlocalizedName("infinitePack")` **大小写不匹配**。已改为 `item.infinitePack.name`。
- 沙砾不掉燧石：**与模组无关**——取出的沙砾是逐字节相同的普通物品，模组不碰掉落逻辑；原版 10% 概率，敲 64 个 0 个概率约 0.12%，基本排除脸黑，疑似 GTNH 掉落规则改动（建议用非背包沙砾对比验证）。

### 第 8 轮 — 计数显示优化 + UI 溢出修复（当前）
- **大数缩写**：格子里的计数改为缩写——`<1万` 原样；`<1亿` 用万（如 `1.2万`）；否则用亿（如 `3.4亿`）；负数前缀 `-`。缩写后仍超宽（16px 槽）时自动等比缩小字体，保证不越界。
- **悬停精确计数**：鼠标悬停条目格时 tooltip 显示**精确数量**（格子里的数字可能被缩写）。用 `GuiScreen.drawHoveringText(List,int,int,FontRenderer)`。
- **UI 右溢出修复**：右上角模式按钮（`【取出】/【删除】`）和删除模式提示文字原先固定 x 坐标，中文宽字体会超出 GUI 右缘约 2 字符。改为**右对齐自适应**（`xSize - stringWidth - 6`），状态行数字也用 `compactCount` 缩写防溢出；模式按钮点击命中框同步改为动态计算。

### 第 9 轮 — SN 改名 + 定版 1.0.0（当前）
- MODID `infinitepack` → `sninfinitepack`；显示名 → `SN Infinite Pack`；版本定为 `1.0.0` → jar `sninfinitepack-1.0.0.jar`。
- 资源目录 `assets/infinitepack/` → `assets/sninfinitepack/`；lang 键 `itemGroup.sninfinitepack`；物品显示名 `SN 得一即无限背包`；GUI 标题加 `SN ` 前缀。
- 配置：`gradle.properties`（modId/modName）、`addon.gradle`（modVersion）、`InfinitePackMod.java`（MODID/MODNAME）。
- ⚠️ MODID 变更 = 新 mod：需从测试实例 mods **移除旧的 `infinitepack-*.jar`**（否则两个 mod 同时加载）；旧背包物品失效（全新 1.0.0 起点）。

### 第 10 轮 — SN 收尾澄清 + git 初始化 + 1.0.1 立项（2026-08-25）
- 命名澄清：**游戏内物品名/GUI 标题/创造标签都不加 SN**（仍是「得一即无限背包 / Infinite Pack」）；**模组名 `SN Infinite Pack` 与 modid `sninfinitepack` 保留**（只在模组列表/FML 日志可见，不影响游戏内 UI）。
- 代码彩蛋：`InfinitePackMod.SN_FULL_NAME = "snang"`（SN 全称，仅代码可见；已验证编译进 jar 的 `InfinitePackMod.class`，不丢）。
- 状态行整体上移 3px：`drawGuiContainerForegroundLayer` 的 y=131→128（约 0.3 字符高度）。
- **git 初始化并首次提交**：`c:\project\mc\docker` 建仓，分支 `main`，commit `101938c`。`.gitignore` 排除 `mcmod/build|.gradle|run|out`、`test/`；`dist/` 入库（仅 sninfinitepack jar；已清掉 `build/libs` 里旧 infinitepack jar，防每次构建再复制进 dist）。
- **1.0.1 立项**（用户确认，本轮不做）：排序可切换 + 搜索 + UI 精简 + 海量存储改造。详见「第 9 节 1.0.1 计划」。

### 第 11 轮 — 排序 + 搜索 + UI 精简（2026-08-25，版本 1.0.1）
- **架构（关键决策落地）**：客户端按本地化物品名过滤+排序，算出「显示顺序」（真实条目索引 int[]），经 FML 简易通道发给服务器；服务器只存该顺序用于槽位→条目映射。搜索/排序只在客户端做；排序模式无需同步（顺序列表本身携带信息）。
- **网络通道**：`NetworkHandler`（`newSimpleChannel("sninfpack")`）+ `MsgDisplayOrder`（int[] 序列化）+ `MsgDisplayOrderHandler`（服务器应用到 `player.openContainer`）。`displayOrder` 用 volatile 引用赋值（网络线程写/主线程读安全）；1.7.10 无 `addScheduledTask`，故不做主线程调度，只做不可变数组引用交接。
- **BackpackStorage**：`Entry` 新增 `long lastAccess`（NBT 标签 `LastAccess` 持久化）；`accessClock` 由容器每次操作前设为 `world.getTotalWorldTime()`（不设置时回退系统毫秒，测试可用）。
- **ContainerInfinitePack**：新增 `displayOrder` / `getEntryIndexForSlot(slotId)` / `getVisibleCount()` / `setDisplayOrder()`；`slotClick`/`transferStackInSlot` 的 entryIndex 全部改用 `getEntryIndexForSlot`；`detectAndSendChanges` 每 tick 收敛滚动（`clampScroll` 改 public，客户端重算后也调用）；`EntriesInventory.getStackInSlot` 用映射。
- **GuiInfinitePack**：标题「得一即无限背包」→「**无限背包**」；状态行 = 搜索框（`GuiTextField`，空态占位「搜索」，`Esc` 仍关闭 GUI）+ 排序切换（点击循环：默认/最近/数量升/数量降，非默认橙色高亮）+ 页数（右对齐缩写）；删除模式时搜索框隐藏、左侧显示删除提示；`orderDirty` → 下一 tick 重算，`Arrays.equals` 仅变化时发包。
- **排序**：默认=存入顺序（稳定排序保序）；最近存取=lastAccess 降序；数量升/降序。搜索按 `getDisplayName().toLowerCase().contains(q)`（中文 OK，客户端本地化）。
- 构建成功（jar 40184B，dist 含 dev/sources 三个 jar 均入库），已部署测试实例并校验 SHA256 一致；**海量存储未动**（需先定架构，见第 9.3 节）。

### 第 11 轮补 — 搜索/排序渲染 bug 修复（2026-08-25）
- 用户实测：搜索/排序"不符合预期，数据变了 UI 没刷新"。
- **根因**：`ContainerInfinitePack.getEntryIndexForSlot` 在显示顺序存在但 `vis` 越界（过滤/排序范围外）时返回 `vis`（自然偏移）→ **过滤范围外的槽位错误显示了自然偏移的条目**（如搜"土"只有 1 条可见，但 slot 1/2 仍显示 storage index 1/2 的其它条目），排序也被"部分自然偏移"干扰，看起来没生效。
- **修复**：显示顺序存在时越界返回 `-1`（空），仅当 `displayOrder == null`（尚未收到顺序）才退化自然偏移；所有调用点（EntriesInventory/drawScreen/hover/删除确认/slotClick/transferStackInSlot）均已处理 -1。
- 另给 `MsgDisplayOrderHandler` 加服务器收到顺序的日志，便于验证同步。

### 第 12 轮 — 存储改造（方案 A：文件 NBT，不用 sqlite）（2026-08-25，版本 1.0.1）
- **用户拍板**：确认方案一（文件 NBT）；顾虑①不同存档/用户是否分开 → 每个存档独立 world/data/，每个背包独立 UUID；②上万条是否卡顿 → 延迟保存 + 客户端分包下发 + 客户端排序/搜索毫秒级。
- **不选 sqlite 的理由**：①排序/搜索在客户端做（本地化中文），服务端 SQL 查询无用；②1.7.10/GTNH 打包 sqlite-jdbc（native）风险高体积大；③NBT 文件随存档走，备份/回滚天然正确。
- **新架构**：物品 NBT 只存 `infpack.uuid`；条目持久化到服务器 `world/data/sninfinitepack/<uuid>.nbt`（`CompressedStreamTools.write/read`）；旧格式（物品 NBT 内嵌 Entries）打开时自动迁移（导入文件 + 写 uuid + 清物品 NBT 条目）。
- **新增类**：`BackpackDataManager`（懒初始化按玩家世界目录、缓存 Map<uuid,storage>、saveAll 于 FMLServerStoppingEvent）；`MsgBackpackData`（服务器→客户端分包下发条目：注册名/damage/count/lastAccess/完整NBT，PART_SIZE=100）；`MsgBackpackRequest`（客户端→服务器请求数据）；两个 handler。
- **容器**：客户端 storage 由服务器下发填充（`applyServerData`），**删除原 `needsReload/reloadFromBackpack`**（物品 NBT 已无条目）；服务器操作后 `saveAndRefresh` = 写文件 + `sendDataToClient`（全量下发，客户端乐观更新随后被服务器权威数据收敛）；`onContainerClosed` 保存文件。
- **GUI**：打开时发 `MsgBackpackRequest`（每秒重试直到收到），`consumeServerDataDirty` → 重算显示顺序；数据源不再依赖物品 NBT 同步（绕开 isChangingQuantityOnly 吞包的老坑）。
- 客户端网络 handler 在 Netty 线程 → 用 `Minecraft.func_152344_a`（1.7.10 SRG 名）切主线程再改容器。
- 构建成功（jar 51050B，版本 1.0.1），已部署并校验 SHA256；**验证中**（需实测：迁移、打开显示、存取/排序/搜索）。

### 第 12 轮补 — 「最近」排序修复（2026-08-25）
- 用户实测：「最近」排序不太对，期望最新发生存入/取出的排最前。
- **根因**：时间戳原用 `world.getTotalWorldTime()`（tick 粒度 1/20s），同一 tick 内连续存取多个条目 → `lastAccess` 相同 → 稳定排序退化为插入顺序，无法反映最新操作（排序方向本身是对的：lastAccess 降序=最新在前）。
- **修复**：`BackpackStorage.stamp()` 改用 `System.currentTimeMillis()` + 单调递增（同毫秒连续操作也严格递增）；删除 `accessClock` 字段与容器的 `stamp(player)` 调用（不再依赖 world tick；测试命令不设置时钟也能工作）。

### 第 13 轮 — 强制生存指令（2026-08-25，版本 1.0.2）
- **指令** `/snanginflock`：无参数 → 返回帮助 + 当前开启/关闭状态；`/snanginflock <密码> <enable|on|1|true>` 开启并设置密码（每次开启覆盖旧密码）；`/snanginflock <密码> <disable|off|0|false>` 关闭（校验密码，关闭后密码失效）。默认 OP 4 权限。
- **全局配置**：`config/sninfinitepack-forcesurvival.properties`（`ForceSurvivalConfig`），存 enabled + SHA-256 密码哈希（非明文）+ `prevKeepInventory`（开启前死亡不掉落状态），对所有存档生效；忘密码删配置即可重置。
- **死亡不掉落联动（按维度记录）**：开启 lock 自动开启 keepInventory（死亡不掉落）。**开启前状态按维度记录**（key=存档文件夹:维度ID，存 config `prevKeepInventory.<key>`）：lock 首次影响某维度时记录其开启前值并强制 true；lock 期间每秒保持所有已加载维度 true（玩家所在维度必加载，故任何维度死亡都不掉落）；关闭 lock 恢复各已加载维度到各自开启前状态，未加载维度的记录保留、加载后补恢复（跨存档/多维度均正确，避免"全局单值跨存档误还原"）。
- **服务器每 tick**（`ForceSurvivalHandler`，`TickEvent.ServerTickEvent`，注册到 FMLCommonHandler bus）：
  - 存档创建=创造 + 强制开启 → 向在线玩家发 `MsgForcedSurvivalDenied`（每秒去重），客户端显示全屏拦截。
  - 存档创建=生存 + 强制开启 → 玩家 gameType==CREATIVE → `p.setGameType(SURVIVAL)` 改回（服务器权威，防绕过）。
- **客户端全屏 GUI** `GuiForcedSurvivalDenied`：深色背景 + 「当前存档禁止游玩」+ 说明 + 「返回标题」按钮（无退出游戏）；`keyTyped` 全部拦截（Esc 无法关闭），只能返回标题。
- **关键点**：1.7.10 Forge 无 `PlayerGameTypeChangeEvent` → 改用每 tick 检测（防绕过更彻底）；「创建模式」用 `WorldInfo.getGameType()` 近似（受 /defaultgamemode 影响）；单机为软约束（玩家可改存档/config 文件）。
- 构建成功（jar 63177B），已部署测试实例，**验证中**。

---

## 3. 当前现状（1.0.2 · 文件 NBT 存储 + 强制生存）

### 已实现功能
- **存入**：手拿物品点条目格（插到该格）/ Shift+玩家背包物品整组存入 → `count += 数量`。
- **取出**：左键=整组、右键=1 个、Shift+条目=整组 → `count -= 数量`（可负，无限透支）。
- **删除**：右上角切换删除模式（红色遮罩），空手左键条目**两次确认**后删除该条目。
- **滚动**：鼠标滚轮翻页（每 54 条一页）。
- **计数显示**：条目格右下角数字，正=白、负/0=红；**大数缩写（万/亿）+ 超宽缩小字体**；**悬停显示精确数量 tooltip**。
- **变体精确匹配**：物品注册名 + 耐久 + 完整 NBT 深度等价 = 同一条目；满耐久弓 vs 耗弓、不同附魔/属性 = 独立条目。
- **合成配方**：8 泥土 + 1 木头。
- **持久化（存储改造）**：物品 NBT 只存 `infpack.uuid`；条目存服务器 `world/data/sninfinitepack/<uuid>.nbt`，随存档走，服务器权威；打开/操作时由服务器分包下发到客户端。

### 已知问题 / 说明
- NEI/创造给的物品可存入并无限取出（模组无法区分来源，用户决定保持现状）。
- 沙砾不掉燧石与模组无关（见上）。
- `/infpacktest` 自动化测试命令已实现（`CommandInfPackTest`，含计数透支校验）但**从未在服务器跑通**（GTNH 服务器端受限），当前作为代码级测试参考。

### 数据模型（`BackpackStorage`）
- `Entry{sample(样本 stackSize=1), count, lastAccess}`；`entries: List<Entry>`。
- API：`load/save(ItemStack)`、`size/isEmpty`、`getSample/getCount/getLastAccess/getDisplayStack`、`deposit(stack, backpackItem, index)`、`withdraw(index, amount)`、`removeEntry`、`contains/indexOf`、`sameVariant`。
- 显示用 `getDisplayStack` 返回 stackSize=1，数量由 GUI 画。

### 新增功能（1.0.1 排序/搜索 + 1.0.2 强制生存）
- **搜索框**：状态行左侧，按物品显示名（本地化）过滤，中文可用。
- **排序切换**：状态行点击循环 默认/最近/数量升/数量降；非默认橙色高亮。
- **显示顺序同步**：客户端算好 int[] 顺序发服务器，服务器 `getEntryIndexForSlot` 映射槽位→真实条目；排序/搜索下存取、删除、滚动均命中正确条目。
- **UI 精简**：标题「无限背包」；搜索框 + 排序切换 + 页数右对齐；删除模式隐藏搜索框显示删除提示。
- **强制生存（1.0.2）**：`/snanginflock <密码> <on|off>` 全局开关（启停支持 on/off、1/0、true/false）；创造创建的存档→客户端全屏「当前存档禁止游玩」（仅返回标题）；生存创建的存档→切创造自动改回生存；停用需密码正确，关闭后密码失效。

---

## 4. 环境与构建

### 目录结构（c:\project\mc）
- `nw-mc-20251224/` — **真实实例，用户正在玩，禁止修改/装 mod**（未装 infinitepack）。
- `nw-mc-20251224-test/` — **测试完整拷贝**，已装 infinitepack，内存 Min=1024/Max=4096。
- `docker/` — 开发环境：`mcmod/` 源码工程、`dist/` 成品、`scripts/` 脚本、`compose.yaml`、`build/rfg/` 反编译源。
- 容器：`mcmod-dev`（构建）、`storage-dev`、`deepseekai-dsh`、`builder` —— **只可启动/停止，不要删除后三者**。

### 构建命令
```powershell
# 启动开发容器（若 mcmod-dev 未运行）
docker compose -f c:\project\mc\docker\compose.yaml up -d

# 构建并复制到 dist
docker exec mcmod-dev bash /scripts/build_release.sh
# 产物：c:\project\mc\docker\dist\sninfinitepack-1.0.2.jar
```

### 修改后必做
1. 用 SRG/反编译源里的方法名（partial-MCP）。
2. 构建成功后再安装。
3. **先确认游戏已关闭**，再 `Copy-Item` 覆盖 `nw-mc-20251224-test\...\.minecraft\mods\sninfinitepack-1.0.2.jar`（并**移除旧版本 `sninfinitepack-*.jar` / `infinitepack-*.jar`**——同 modid 冲突会双加载；校验 SHA256 与 dist 一致）。
4. 让用户重启游戏（或代启动）。

---

## 5. 测试方法

### 启动测试实例
```powershell
Start-Process "C:\project\mc\nw-mc-20251224-test\prismlauncher.exe" -ArgumentList "--launch","GT_New_Horizons_2.8.4_Java_17-25"
```
- 账号：`test`（离线账号，Prism GUI 创建）。
- 开单人世界（创造模式方便测试），合成 8泥土+1木头 或创造拿背包。

### 验收清单
1. 背包显示名正常（非 `item.inf...`）。
2. 放入物品 → 条目出现、计数增加（白色正数）。
3. 取出 → 计数减少；一直取到负数（红色）仍能取出；玩家背包即时显示取出的物品。
4. 满耐久弓 vs 耗弓 = 两条独立条目；取出属性/附魔/NBT 与放入一致。
5. 删除模式：第一次点击仅高亮，第二次同格才删除；点别处取消。
6. 滚轮翻页正常。
7. 合成 8泥土+1木头 → 得到背包。

### 日志（重要）
- **`InfinitePackMod.LOG`（`[infpack]` 前缀）写到 `fml-client-latest.log`，不是 `latest.log`！**
- 路径：`nw-mc-20251224-test\instances\GT_New_Horizons_2.8.4_Java_17-25\.minecraft\logs\fml-client-latest.log`
- 崩溃报告：同目录 `crash-reports\`。
- 服务器逻辑在单机里也跑在同一 JVM，日志同文件（线程前缀区分 `Server thread`/`Client thread`）。

---

## 6. 部署到真实服务器（用户决策，未执行）

- 同一份 jar 放入服务器 `mods/`；**服务端存储逻辑权威，服务端必须装**；客户端也装同 jar。
- 数据跟随背包物品 NBT 持久化，无需额外数据库。
- 当前为**按物品独立**（一人一包各存各的），**无共享仓库设计**。
- GTNH 服务器端部署曾被尝试但受阻（GTNH 自定义 Forge 校验问题 / 标准 Forge 1.7.10 universal 已从 maven 移除 / ServerPack 直链未找到），已搁置待用户提供 ServerPack 或允许装。

---

## 7. 铁律 / 经验教训（供未来 AI 参考）

1. **绝不在游戏运行时替换 mods 的 jar**（会崩，见第 5 轮）。
2. 方法名用 SRG/反编译源（partial-MCP）；验证用 `inspect_jar*.sh` javap。
3. 客户端显示用乐观更新，不要依赖服务器槽位同步（`isChangingQuantityOnly` 会吞包；FML GUI 同步脆弱）。
4. 日志在 `fml-client-latest.log`（GTNH 里 `InfinitePackMod.LOG` 不走 latest.log）。
5. 不要动 `nw-mc-20251224`（真实实例）、不要删 `storage-dev/deepseekai-dsh/builder` 容器。
6. 网络故障让用户处理，**不改源/镜像**。
7. 用户 Git 规则：可读状态；回滚/提交必须经用户确认；默认不 add/commit。

---

## 8. 待办 / 可选方向

- [x] **1.0.1：排序切换 + 搜索 + UI 精简**（第 11 轮已完成，见第 9 节）。
- [x] **1.0.1：海量存储改造（方案 A 文件 NBT）**（第 12 轮已完成，验证中）。
- [ ] 真实服务器部署（等待用户决策/提供 ServerPack）。
- [ ] `/infpacktest` 在真实服务器跑通。
- [ ] 共享仓库 / 公会共享背包（全新设计，当前无）。
- [ ] 创造模式禁止存入（可选，用户尚未决定）。
- [x] **强制生存指令**（1.0.2 第 13 轮已实施，验证中；详见第 10 节）：一条指令两参数（密码 + 启停），对所有存档生效；创造创建的存档→拒绝游玩（客户端全屏拦截）；生存创建的存档→切创造自动改回生存；停用需密码。

---

## 9. 1.0.1 计划（排序/搜索/UI 精简 + 海量存储：已全部实施完成）

### 9.1 目标功能（用户原话归纳）
1. **顺序可切换排序**：`最近存取 / 数量升序 / 数量降序`（建议另保留 `默认=存入顺序` 作基态）。
2. **搜索**：按物品名过滤。
3. **UI 精简**：GUI 标题「得一即无限背包」→「**无限背包**」；状态行加**搜索框 + 排序方式切换**。
4. **海量存储**：数据从「单物品 NBT 列表」迁到**独立存储文件 / 按背包 ID 索引**（解决条目上千时的 NBT 膨胀/数据包限制/O(n) 存入）。

### 9.2 关键技术情报（本轮已验证，直接可用）
- **FML 简易网络通道**（同步搜索/顺序到服务器）：
  - `NetworkRegistry.INSTANCE.newSimpleChannel("name")` → `SimpleNetworkWrapper`（包 `cpw.mods.fml.common.network.simpleimpl`）。
  - 消息接口：`IMessage`（`fromBytes/toBytes(ByteBuf)`）、`IMessageHandler<REQ,REPLY>`（`onMessage(REQ, MessageContext)`）。
  - 注册：`wrapper.registerMessage(Handler.class, Msg.class, discriminator, Side.SERVER)`；客户端发：`wrapper.sendToServer(msg)`；服务端取玩家：`ctx.getServerHandler().playerEntity`。
- **推荐架构（关键决策）：客户端算好「显示顺序」，发给服务器**
  - 客户端按**本地化物品名**过滤+排序（中文搜索 OK），把**有序的真实条目索引列表**（int[]）通过上面的包发给服务器；服务器存为 `displayOrder`；`slotClick` 用 `displayOrder[scrollOffset+slotId]` 映射真实条目。
  - **为什么不能两端各自过滤**：专用服务器无客户端语言，`getDisplayName()` 是英文/未本地化名 → 中文搜索两端结果不一致 → 点击映射错乱。所以**过滤只在客户端做，服务器只收顺序列表**。
  - 排序模式因此**无需同步**（顺序列表本身携带信息），GUI 本地保存 sortMode 即可。
- **实现要点**：
  - `BackpackStorage.Entry` 加 `long lastAccess`（用 `world.getTotalWorldTime()`，随 NBT 持久化）→ 支撑「最近存取」。
  - `EntriesInventory.getStackInSlot(i)` → `storage.getDisplayStack(displayOrder[scrollOffset+i])`。
  - `slotClick` 的 `entryIndex` 全部改用 `getEntryIndexForSlot(slotId)`。
  - 客户端任何存取/删除/搜索/切排序后 **markDirty → 下一 tick 重算顺序，仅在变化时发包**（避免每 tick 刷包）。
  - 搜索框用 1.7.10 `GuiTextField`（注意本构建方法名 SRG/MCP 混用，先查反编译源 `build/rfg/.../GuiTextField.java`）。
  - 物品显示名：`ItemStack.getDisplayName()`（确认 MCP 名）。

### 9.3 海量存储（大改，需先定架构再动）
- 现状：条目全在物品 NBT，**客户端显示也依赖从物品 NBT 重载/乐观更新**。迁到独立文件后客户端就拿不到数据 → **显示同步方案必须一起改**。
- 方案 A（推荐）：物品 NBT 只存 `UUID`；服务器端 `BackpackDataManager` 把条目持久化到世界目录文件（如 `world/data/sninfinitepack/<uuid>.nbt`）；客户端显示数据由服务器经自定义包下发。
- 方案 B（轻改）：保留物品 NBT，仅分页/压缩缓解，不根治。用户明确要「独立存储文件/按背包 ID 索引」，倾向 A。
- 注意 1.7.10 数据包大小限制（大 NBT 同步/登录会出问题）。

### 9.4 建议实施顺序
1. 先做**排序**（默认/最近/数量↑/数量↓）——不依赖搜索，先建 displayOrder 骨架。
2. 再做**搜索框 + 过滤 + 网络同步顺序**。
3. 最后做**UI 精简**（标题「无限背包」+ 搜索框 + 排序切换布局）。
4. 海量存储作为独立大项，先出架构方案给用户确认再动。

### 9.5 验收
- 切排序后格子顺序正确；搜索能按中文名过滤；搜索/排序状态下存入、取出、删除都命中正确条目；重开后顺序/搜索合理。

---

## 10. 新需求评估：强制生存指令（1.0.2 第 13 轮已实施，验证中）

### 10.1 需求（用户原话归纳）
- 一条指令，两个参数：`pass`（密码）与启停（enable/disable）。启用时设置密码；对所有存档生效。
- 启用后按每个存档**创建时的模式**处理：
  - 创建时是**创造** → 直接拒绝游玩（全屏拦截「当前存档禁止游玩」），即使玩家输入命令改生存也不行。
  - 创建时是**生存** → 允许玩；但用 /gamemode 或其它方式切成创造 → **自动改回生存**。
- 停用需密码正确才停用。

### 10.2 可行性结论：**可行**（1.7.10 Forge 均支持），但有三处需注意

| 需求点 | 1.7.10 方案 | 难度 |
|---|---|---|
| 指令两参数 | `CommandBase`（同 `CommandInfPackTest`） | 低 |
| 全局配置（所有存档生效） | 存 `config/sninfinitepack/` 文件（不随存档），含 `enabled + 密码` | 低 |
| 识别存档创建模式 | `world.getWorldInfo().getGameType()`（level.dat GameType，创建时设定） | 中 |
| 创造存档拒绝游玩 | 服务器每 tick → 发 `MsgForcedSurvivalDenied`，客户端全屏 GUI 拦截（仅返回标题） | 中 |
| 生存存档切创造→改回 | 每 tick 检测（1.7.10 无 `PlayerGameTypeChangeEvent`）→ `setGameType(SURVIVAL)` | 低 |

### 10.3 关键注意点 / 风险
1. **「创建模式」是近似**：`WorldInfo.getGameType()` 是存档默认模式（创建时设定），但 `/defaultgamemode` 会改它 → 严格"创建时"需在首次进入时快照记录（可接受近似）。
2. **单机玩家可改文件**：客户端玩家有存档/config 文件完全访问权，任何强制都是**软约束**（玩家可编辑 level.dat 或删配置绕过）。要硬约束需外部手段（只读权限/独立服务端），需用户明确是否可接受软约束。
3. **密码安全**：建议存哈希（SHA-256+salt），勿明文；忘密码 → 删除配置文件即可重置（需 OP/控制台操作）。
4. **"全屏拦截"实现**：服务器 `PlayerLoggedInEvent`/世界加载检查创造存档 → kick 回标题并提示；若需真正的"全屏界面"则要客户端 GUI（较重，可后置）。
5. 集成服务器（单人）踢出的玩家体验：回标题 + 明确提示。

### 10.4 实施要点（1.0.2 第 13 轮已实施）
1. `ForceSurvivalConfig`：config 文件存 enabled/密码哈希（SHA-256，非明文）。
2. `CommandForceSurvival`：`/snanginflock <pass> <enable|on|1|true|disable|off|0|false>`（校验密码、设置/停用；无参返回帮助+状态）。
3. 每 tick 检测（1.7.10 无 `PlayerGameTypeChangeEvent`）：生存存档 + 强制生存 + 变创造 → 改回生存；防绕过。
4. 每 tick：创造存档 + 强制生存 → 发 `MsgForcedSurvivalDenied`，客户端全屏拦截（仅返回标题）。
5. 客户端全屏拦截 GUI（`GuiForcedSurvivalDenied`）：已实现（仅返回标题，无退出游戏）。
