# Infinite Pack（得一即无限背包）开发交接记录

> 本文档记录与用户的开发对话历程、关键决策、当前现状、构建与测试方法，供未来 AI / 协作者快速接手。最后更新：2026-09-25。

---

## 1. 项目一句话概述

Minecraft **1.7.10 / Forge 10.13.4.1614（GTNH 2.8.4 实测环境）+ 1.12.2 / Forge 14.23.5.2864（HMCL 实测环境）** 的模组：玩家把任意物品放入背包物品后，可**无限取出**（属性/附魔/耐久/NBT 与放入时完全一致）。现为**计数制**——放入 +N、取出 -N、可为负（负数=无限透支），让玩家感知用了多少。

- MODID：`sninfinitepack`；显示名 `SN Infinite Pack`；版本 `1.0.3`（**双版本 jar 命名带 mc 后缀**）
- 源码：`docker/mcmod-1.7.10/src/main/java/com/infpack/`（1.7.10 版）；`docker/mcmod-1.12.2/src/main/java/com/infpack/`（1.12.2 版）
- 成品：`docker/dist/sninfinitepack-1.0.3-mc1.7.10.jar`、`docker/dist/sninfinitepack-1.0.3-mc1.12.2.jar`（另有 -dev/-sources 同带 mc 后缀）
- 语言：`assets/sninfinitepack/lang/{zh_CN,en_US}.lang`
- 合成配方：8 泥土（矿辞 `dirt`）围一圈 + 中间 1 木头（矿辞 `logWood`）
- **兼容性（完整核查结论，2026-08-25）**：两个版本都只依赖 MC 原版 + Forge(FML) 核心 API（import 无任何第三方模组包；无运行时依赖；`mcmod.info` requiredMods/dependencies 全空；GTNHGradle/RFG 仅为构建插件不进产物）。**与标准 Forge 1.7.10 / 1.12.2 原版及基础模组环境兼容**，不依赖 GTNH 特有内容；1.7.10 已在 GTNH 2.8.4 实机验证，1.12.2 已在 HMCL Forge 14.23.5.2864 实机验证。不覆盖任何原版/模组内容（注册名带 modid、配方矿辞叠加、通道名全局唯一）。

---

## 2. 对话历程与关键决策（逐轮）

### 第 1 轮 — 需求确认 + Docker 环境搭建
- **需求**：得一即无限。放入任一物品 → 无限取出；放入的物品带附魔/耐久/模组属性时，放入和取出必须一致；满耐久弓 vs 消耗过弓 = 两个独立条目，可选删除；删除后不可再取。
- **决策**：
  - 所有开发在 Docker 完成（容器 `mcmod-dev`，JDK25 构建 + jabel→J8 字节码，GTNHGradle 2.0.20 / Gradle 9.3.1）。
  - 网络代理由 `docker/.env` 的 `PROXY_HOST` 注入（HTTP_PROXY/HTTPS_PROXY）。**网络故障由用户处理，不改源**。
  - **绝不动真实实例** `nw-mc-20251224`（用户正在玩）；复制整份到 `nw-mc-20251224-test` 用于测试。
- **实现**：`BackpackStorage`（NBT 精确匹配）、`NbtUtil`（深度 NBT 等价）、`ContainerInfinitePack`、`GuiInfinitePack`、`SlotInfiniteEntry`、`ItemInfinitePack`、`GuiHandler`、`CommonProxy`、`ClientProxy`。
- **关键坑**：本构建 classpath 是 **partial-MCP**，很多方法名是 **SRG（`func_*`）**。直接用 RFG 反编译源码 `docker/mcmod-1.7.10/build/rfg/minecraft-src/java/` 里的名字写，编译和运行都对（reobf 后无 MCP 残留）。
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
- **git 初始化并首次提交**：`c:\project\mc\docker` 建仓，分支 `main`，commit `101938c`。`.gitignore` 排除 `build|.gradle|run|out`、`test/`；`dist/` 入库（仅 sninfinitepack jar；已清掉 `build/libs` 里旧 infinitepack jar，防每次构建再复制进 dist）。
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

### 第 14 轮 — 多版本架构改造（1.7.10 + 1.12.2，2026-08-25）
- **用户需求**：0 依赖模组（避免与玩家装的模组/整合包冲突）+ 多版本开发（先 1.7.10 同时支持 1.12.2，未来可加更多版本）。
- **0 依赖确认**：`sninfinitepack-1.0.2.jar` 解包检查——除 `com/infpack/`、`META-INF/MANIFEST.MF`、`assets/`、`mcmod.info` 外**无任何第三方/GTNH 类**；GTNHGradle/RFG 只是构建工具链，不进产物。运行时只依赖 MC + Forge 核心。
- **架构决策（推荐并采纳）**：**每版本独立 Gradle 工程 + 共享构建脚本**，不抽 common（本 mod 规模小，25 个类全版本耦合，抽公共层性价比低；用双工程 + 统一命名替代）：
  - `docker/mcmod-1.7.10/`（GTNHGradle 2.0.20，1.7.10）
  - `docker/mcmod-1.12.2/`（裸 RetroFuturaGradle 2.0.3，1.12.2）
  - 两个工程同一容器 `mcmod-dev` 构建（Gradle 9.3.1 + JDK25；1.7.10 用 jabel→J8，1.12.2 用 Java 8 toolchain）。
- **目录重命名**：`docker/mcmod` → `docker/mcmod-1.7.10`（Move-Item，未动 git index）；`compose.yaml` 容器内路径统一 `/work/mcmod-1.7.10` + `/work/mcmod-1.12.2`。
- **产物命名**：dist 按 MC 版本隔离——`sninfinitepack-1.0.2-mc1.7.10.jar`、`sninfinitepack-1.0.2-mc1.12.2.jar`（dev/sources 也带 mc 后缀）；脚本 `build_release_1.7.10.sh`（1.7.10）+ `build_release_1.12.2.sh`（1.12.2），与工程目录/jar 命名完全对称。
- **1.7.10 构建回归通过**（重命名 + compose 改动后 BUILD SUCCESSFUL，产物 SHA256 与改动前一致）。
- **1.12.2 关键配置（RFG 2.0.3）**：`minecraft { mcVersion.set("1.12.2") }` → 自动 MCP stable_39 + Forge 1.12.2-14.23.5.2847；`injectedTags.put("VERSION", version)` 生成 `Tags.VERSION`；下载 vanilla/forge 偶发 Read timed out → `--max-workers 2` 解决（网络仍由用户负责）。

### 第 15 轮 — 1.12.2 API 适配 + Java8 字节码 + 模型大小写（2026-08-25）
- **API 适配（1.7.10 → 1.12.2 主要差异，已全部落地）**：
  - FML 包 `cpw.mods.fml` → `net.minecraftforge.fml`；`@Mod`/`@SidedProxy`/事件类同。
  - 物品注册：`GameRegistry.register(item)`（1.12.2 已移除）→ `ForgeRegistries.ITEMS.register(item.setRegistryName(...))`；`ForgeRegistries` 在 **`net.minecraftforge.fml.common.registry`** 包（非 `net.minecraftforge.registries`）。
  - 配方：`GameRegistry.addRecipe`（已移除）→ `ForgeRegistries.RECIPES.register(new ShapedOreRecipe(new ResourceLocation(group), result, ...).setRegistryName(...))`（矿辞配方同 1.7.10）。
  - Item：`onItemRightClick(World,EntityPlayer,EnumHand)` 返回 `ActionResult<ItemStack>`；无 IIcon（改模型 JSON）；`setUnlocalizedName` → `setTranslationKey`；`CreativeTabs.getTabIconItem` → `createIcon()` 返回 ItemStack。
  - Container：`slotClick(int,int,ClickType,EntityPlayer)`（第 3 参 ClickType）；`crafters/ICrafting/sendProgressBarUpdate` → `listeners/IContainerListener/sendWindowProperty`；`sendContainerAndContentsToPlayer` 已移除 → 逐槽 `SPacketSetSlot` resync；IInventory 新增 `getName/getDisplayName/hasCustomName/isUsableByPlayer/removeStackFromSlot/getField/setField/getFieldCount/isEmpty/clear`，删除 `getInventoryName/hasCustomInventoryName/isUseableByPlayer/getStackInSlotOnClosing`。
  - GUI：`fontRendererObj` → `fontRenderer`；`drawGuiContainerForegroundLayer(int,int)`（2 参，无 float）；`GuiTextField(int id, FontRenderer, ...)` 带 id 参数；`mouseClicked/keyTyped/handleMouseInput` 声明 `throws IOException`；`Slot.xDisplayPosition/yDisplayPosition` → `xPos/yPos`。
  - 命令：`getCommandName/getCommandUsage/addChatMessage/ChatComponentText` → `getName/getUsage/sendMessage/TextComponentString`；`execute(MinecraftServer,...)`；`getPlayer` 抛 `CommandException` 需捕获。
  - 服务器：`MinecraftServer.getServer()` 1.12.2 非静态 → `FMLCommonHandler.instance().getMinecraftServerInstance()`；`getConfigurationManager().playerEntityList/worldServers` → `getPlayerList().getPlayers()/worlds`；`WorldSettings.GameType` → 独立类 `net.minecraft.world.GameType`；`getSaveHandler().getWorldDirectory()`（1.12.2 仍是 getWorldDirectory 非 getWorldFolder）；`w.provider.getDimension()`。
  - 网络：包变；`Minecraft.func_152344_a` → `addScheduledTask`；`PacketBuffer.readString/readVarInt/readCompoundTag`（readCompoundTag 抛 IOException 需捕获，writeCompoundTag 不抛）。
  - 注册名：`Item.REGISTRY.getKey` 不存在 → `item.getRegistryName()`；反查用 `Item.REGISTRY.getObject(new ResourceLocation(name))`。
  - 附魔：1.12.2 `Enchantment` 无静态字段（SHARPNESS 等）→ `Enchantment.getEnchantmentByLocation("sharpness")`。
- **关键坑 1（Java 字节码版本）**：RFG 默认只对其内部任务用 Java 8，主 `compileJava` 用 JDK25 → 产出 **Java 25 字节码（major 69）**，1.12.2 FML 的 ASM 5.2 只支持到 52 → mod 被 FML 报 "corrupt zip" 忽略。**必须 `java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }`**（容器 JAVA8_HOME=/opt/jdk8）→ major 52，正常加载。
- **关键坑 2（模型路径大小写）**：1.12.2 `ResourceLocation` 把路径**小写化**（`infinitePack` → `infinitepack`）。模型文件、纹理、模型 JSON `layer0`、`ModelResourceLocation`、注册名全部要用**全小写 `infinitepack`**（lang 键 `item.infinitePack.name` 基于 unlocalized name 不受影响）。
- **关键坑 3（IInventory 空槽必须返回 ItemStack.EMPTY，不能 null）**：1.12.2 的 `Container.detectAndSendChanges` / `openGui→addListener→sendAllContents` 会遍历槽位并调用 `ItemStack.copy()`，空槽返回 `null` 会 NPE 崩溃（`Ticking player` + `FMLNetworkHandler.openGui` NPE）。`EntriesInventory.getStackInSlot/decrStackSize/removeStackFromSlot` 空态一律返回 `ItemStack.EMPTY`（1.7.10 允许 null，1.12.2 不允许）。
- **关键坑 4（ItemStack 空值语义：EMPTY ≠ null）**：1.12.2 光标/槽位空值是 `ItemStack.EMPTY` 而非 `null`。沿袭 1.7.10 的 `== null` / `!= null` 判断会全失效（`EMPTY` 非 null → `cursor != null` 恒 true → 空手误走"存入"分支），且 `setItemStack(null)`/`putStack(null)`/`setInventorySlotContents(null)` 会污染客户端光标为 null → 崩溃。**修复**：所有光标/槽位判断改 `isEmpty()`/`!isEmpty()`，设空一律 `ItemStack.EMPTY`。
- **关键坑 5（slotClick 不能返回 null）**：1.12.2 客户端 `PlayerControllerMP.windowClick` 会把本地 `slotClick` 的**返回值**作为 `CPacketClickWindow` 内容构造（`new CPacketClickWindow(..., itemstack)` → `itemstack.copy()`），`slotClick`/`transferStackInSlot` 返回 `null` 会 NPE 崩溃（`CPacketClickWindow.<init>` NPE）。**全部 `return null` 改为 `return ItemStack.EMPTY`**（findBackpack 等内部查找方法的 null 除外）。
- **实机验证（2026-08-25 最终通过）**：HMCL 实例 `1.12.2-Forge`（Forge 14.23.5.2864）加载成功——`Forge Mod Loader has successfully loaded 5 mods`（含 sninfinitepack），资源包 `SN Infinite Pack` 正常加载，**模型加载无报错**（全小写修复生效），正常进入世界。
- **1.12.2 测试注意**：用 `Start-Process HMCL --launch` 命令行启动不稳定（HMCL 多实例时可能不响应）；需先**杀干净所有 javaw/java 进程**再干净启动，或直接在 HMCL GUI 手动点启动。游戏进程是 `jre1.8.0_251\bin\java.exe ... net.minecraft.launchwrapper.Launch`（Java 8）。

### 第 16 轮 — 全面 API 审查 + 远程修复（2026-08-25，用户远程）
- 用户远程无法实测，要求"完整排查一遍 API，不要试一下崩一下"。子代理逐文件审查 25 个类 + 人工交叉验证，修复如下：
- **崩溃级**：
  - **副手持背包右键 → 服务器 NPE**：`findBackpack` 原只扫 `getSizeInventory()`（1.12.2 只含 mainInventory 36 格，不含副手/盔甲）；副手持背包时 `backpack==null` → `ensureUuid(null)` NPE。修复：`findBackpack` 扫 `mainInventory + offHandInventory + armorInventory`；`ensureUuid` 开头加 `backpack==null → return null` 兜底。
  - **`SlotInfiniteEntry.decrStackSize` 返回 null** → 改 `ItemStack.EMPTY`（槽位契约，漏网）。
- **功能级**：
  - **Q 键(THROW)/双击(PICKUP_ALL) 在条目槽会误取出整组**：`slotClick` 显式忽略 `THROW`/`PICKUP_ALL`（返回 EMPTY）。
  - **删除模式带光标点条目会误存入**：删除模式优先（光标非空时忽略该格，不存入不删除）。
  - **`/infpacktest` 权限**：删除 `checkPermission=true` 重写，恢复默认 OP 4（避免非 OP 清空玩家背包）。
  - **服务器消息 handler 未切主线程**：`MsgBackpackRequestHandler`/`MsgDisplayOrderHandler` 用 `player.getServer().addScheduledTask` 切服务器主线程（避免与主线程并发访问 ArrayList）。

### 第 17 轮 — 物品栏图标问题诊断（2026-08-25，结论见第 18 轮）
- **静态审查结论**：注册名 `sninfinitepack:infinitepack`（全小写）、`ModelLoader.setCustomModelResourceLocation(item,0,"...#inventory")`、模型 `models/item/infinitepack.json`（layer0 `sninfinitepack:items/infinitepack`）、纹理 `textures/items/infinitepack.png`（16x16 RGBA 有效）全部一致；日志无模型/纹理错误；1.12.2 还有自动 fallback（物品 registry name 对应 `models/item/*.json`）。**链路静态验证无问题**。
- 已加 **ClientProxy 模型注册诊断日志**（`[infpack] ClientProxy 模型注册成功: registry=... model=...#inventory` / 失败 warn）。**下次实测时据此判断**：日志出现=模型注册执行（问题在别处，需用户反馈图标具体形态）；日志不出现=ClientProxy 未被使用（@SidedProxy 异常）。
- 全部修复已构建部署到 HMCL（67234B），**未开游戏**（等用户回来手动启动）。

### 第 18 轮 — 图标紫黑根因定位并修复（2026-08-25，用户实测）
- **用户实测**：除图标外其余功能全部正常（存入/取出/删除/强制生存都工作）；图标**紫黑方块**，Q 丢出是**紫黑正方体** = **missing model**（默认缺失模型是立方体），不是纹理缺失。
- **日志时序铁证**（latest.log）：
  ```
  [17:36:20] Reloading ResourceManager ... SN Infinite Pack
  [17:36:25] Created: 512x512 textures-atlas            ← 模型烘焙
  [17:36:26] [infpack] ClientProxy 模型注册成功 ...     ← 模型登记在烘焙【之后】
  ```
- **根因**：1.12.2 启动时序为 `preInit → 资源加载/模型烘焙 → init`。原代码在 **`init()`** 里调 `ModelLoader.setCustomModelResourceLocation` → ModelBakery 已收集完物品变体 → 物品落到 missing model（紫黑立方体）且**不报任何错误**（所以日志干净）。
- **修复**：把模型登记从 `ClientProxy.init()` **移到 `preInit()`**（`super.preInit` 注册物品之后），这是 1.12.2 标准做法。字节码已验证：新 jar 的 preInit 含 `setCustomModelResourceLocation("sninfinitepack:infinitepack","inventory")`，init 已清空。
- **部署状态（已部署 2026-08-25 17:46）**：用户关闭游戏后已替换 HMCL mods 里的 jar（`sninfinitepack-1.0.2-mc1.12.2.jar`，67236B）。下次进游戏应看到图标正常（若仍异常，看日志里 `ClientProxy 模型注册成功` 是否在 `Created: textures-atlas` 之前出现）。

### 第 19 轮 — 计数显示统一 + 取出改为「拿到光标」（2026-08-25，两版本）
- **用户反馈（对比两版本）**：①1.12.2 正数文本颜色不对/看不清；②1.7.10 数字文本大小有点过大；③物品很多时文本整体左偏移；④左键/右键单击应把东西**拿到鼠标上（光标）**，而非直接放进玩家背包（与 MC 容器操作习惯一致）。
- **修复 ③（真 bug，两版本）**：计数缩放分支原来用**未缩放**的 `cx = 右缘 - textW` 定位，缩放后实际宽度只有 15px → 文本整体偏左。改为**按缩放后实际宽度定位**（`cx = guiLeft + s.xPos + 17 - textW * scale`），文本右缘始终贴槽位右缘。
- **修复 ①②（两版本统一渲染）**：字号改**基础 0.85 倍**（比原版小一号，1.7.10 不再过大），仍超宽再等比缩小；1.12.2 之前"白色看不清"主因是含"万/亿"的文本宽度被高估 → 更早触发过度缩小 → 字迹偏小偏淡，统一字号后两版观感一致。颜色保持正数白 `0xFFFFFF`/非正数红 `0xFF4040`；**顺带修复 `count >= 0` 使 0 显示白色 → 改 `count > 0`，0 和负数都红色**（符合 README 设计）。统一走矩阵缩放路径。
- **修复 ④（两版本）**：`slotClick` 空手取条目不再进玩家背包，改为**拿到光标**——左键=整组、右键=1 个到 `player.inventory.setItemStack`（同变体未满则合并，多余掉落作防御）；Shift+条目仍整组进玩家背包（MC 习惯）。新增 `withdrawToCursor`/`putOnCursor`（服务器权威 + 客户端乐观：客户端本地减计数并放光标，服务器执行后 `saveAndRefresh` + `resyncToClient` 收敛）。1.7.10 的 `EntityPlayerMP.setItemStack` 自动发 `S2FPacketSetSlot(-1,-1)` 同步光标；1.12.2 的 `resyncToClient` 已含 `SPacketSetSlot(-1,-1,cursor)`。
- **构建/部署**：两版本构建成功（1.7.10 jar 63507B、1.12.2 jar 67561B，javap 验证含新方法）；已部署两测试实例并移除旧命名 jar（1.7.10 实例旧 `sninfinitepack-1.0.2.jar`、1.12.2 实例旧 `sninfinitepack-1.0.2.jar`），SHA256 与 dist 一致；游戏确认未运行（无 Prism/HMCL/游戏进程，javaw 7044 为 Services 会话后台服务）。**验证中**（需实测：取出到光标、计数显示观感）。

### 第 19 轮补 — 1.12.2 计数变深 + Shift 点击进背包（2026-08-25，用户实测）
- **用户反馈**：①1.12.2 数字整体偏深色（红白都变成接近黑灰/暗红）；②按住 Shift 单击左右键应直接进玩家背包（新逻辑只应影响不按 Shift 的单击）。
- **修复 ①（1.12.2 根因）**：用户看到"黑灰/暗红"= `drawStringWithShadow` 的**阴影色**（`(color & 0xFCFCFC)>>2`：白→`0xFF3F3F3F`、红→`0xFF3C1010`），主文本（原色）没显示。1.12.2 用**缓存式 `GlStateManager`**（`setColor`→`GlStateManager.color`，仅颜色变化才调 GL11；FontRenderer 不操作深度），而原代码用**原生 `GL11.glDisable(DEPTH_TEST)`** 关深度 → 状态缓存与实际不一致 → 深度测试未真正关闭 → 阴影(z=0)先画写入 depth、主文本(z=0) 被 `GL_LESS` 拒绝 → 只剩阴影色。**修复**：改用 `GlStateManager.disableDepth()/enableDepth()`（缓存一致，1.12.2 正确做法）+ **手动两步绘制**（黑阴影 `drawString(text,1,1,0xFF000000)` z=+1 先画 + 主色 `drawString(text,0,0,color)` z=-1 后画，z 偏移保证深度测试下主色也覆盖），保留阴影避免浅色图标看不清，不用 `drawStringWithShadow`。javap 验证 SRG 名 `GlStateManager.func_179097_i`(disableDepth)/`func_179094_E`(pushMatrix)/`func_179109_b`(translate)/`func_179152_a`(scale)/`func_179121_F`(popMatrix) 已打入。
- **修复 ②（两版本）**：`slotClick` 原未处理 `ClickType.QUICK_MOVE`（1.12.2）/`mode==1`（1.7.10），Shift+点击落入"空手取出→到光标"分支。**修复**：条目槽分支开头显式 `if (clickType == ClickType.QUICK_MOVE) return transferStackInSlot(player, slotId);`（1.12.2）/ `if (mode == 1) return transferStackInSlot(player, slotId);`（1.7.10）→ Shift+左右键整组直接进玩家背包。javap 验证 1.12.2 `ClickType.QUICK_MOVE` 分支、1.7.10 `func_82846_b`(transferStackInSlot) 调用已打入。
- **构建/部署**：1.7.10 jar 63528B、1.12.2 jar 67612B；已替换两测试实例 mods（仅剩新 jar，无旧命名），SHA256 与 dist 一致；用户确认游戏已关（用户会话无 java 进程）。**验证中**。

### 第 19 轮补 2 — 颜色再加固 + Shift 区分左右键（2026-08-25，用户实测）
- **用户反馈**：①1.12.2 文本颜色问题依旧；②Shift+右键应直接到包 1 个、Shift+左键才整组（不能 Shift 不分左右都整组）。
- **修复 ①（1.12.2 加固）**：在 `GlStateManager.disableDepth()` 基础上**临时把深度函数设为 `GL11.glDepthFunc(GL11.GL_ALWAYS)`**（兜底：即使深度测试被意外打开，计数文本也强制绘制覆盖图标），finally 恢复 `GL_LEQUAL`+`enableDepth`。绘制简化为**手动两步**（黑阴影 `drawString(text,1,1,0xFF000000)` + 主色 `drawString(text,0,0,color)`，去掉了上一轮的 z hack——由 GL_ALWAYS 保证主色覆盖）。javap 验证 `GL11.glDepthFunc` 调用（3 处）已打入。
- **修复 ②（两版本）**：Shift 分支不再委托 `transferStackInSlot`（它不区分左右），改在 `slotClick` 的 `QUICK_MOVE`（1.12.2）/`mode==1`（1.7.10）分支里**按 `dragType`/`clickedButton` 区分**：右键(1)=取 1 个、左键(0)=取整组，走新增的 `shiftToInventory(player, slotId, amount)`（内部 `withdrawToInventory` 进玩家背包；amount<0=整组、>0=数量）。javap 验证两版 `shiftToInventory` + 1.12.2 `ClickType.QUICK_MOVE` 分支已打入。
- **构建/部署**：1.7.10 jar 63680B、1.12.2 jar 67779B（10:27 构建）；已替换两测试实例（时间戳 18:27 + SHA256 97eb96/c76d04 与 dist 一致）；游戏已确认关闭（用户会话无 java 进程）。**验证中**。

### 第 19 轮补 3 — 定位并修复「白字变黑」（1.12.2 颜色真正根因）（2026-08-25，用户实测）
- **用户反馈**：白色数字"感觉像纯黑更多些"、字很细；红色"暗红就是暗红"（红色正常）。
- **根因（决定性）**：**红色正常、白色变黑** → 1.12.2 `GlStateManager.color` 是**缓存式**（`colorState` 初始=白色；仅当目标色≠缓存时才调 `GL11.glColor4f`）。手动两步中阴影画完实际 GL=黑，若某刻 `colorState` 缓存恰为白色，则主色白色 `setColor(1,1,1,1)` 因**缓存==白色→不调 GL11**，实际 GL 颜色仍停留在阴影遗留的黑色 → 白色主色用黑色绘制（=纯黑）；而红色 `setColor(1,0.25,0.25)`≠缓存→正常调 GL11→红色正常。只显示黑阴影 → 字显得细。
- **修复**：主色 `drawString` 前用**原生 `GL11.glColor4f(主色RGB, 1.0)` 强制设置实际 GL 颜色**（绕过 GlStateManager 缓存，白色也一定生效），再 `drawString`；字号 0.85→**0.9**（缓解字细）。javap 验证 `GL11.glColor4f`（1 处）+ `glDepthFunc`（3 处）已打入。
- **构建/部署**：仅 1.12.2 改动，jar 67832B（11:40 构建），已替换 HMCL 实例（时间戳 19:40 + SHA256 3631aa 与 dist 一致）；1.7.10 未改动（10:27 jar 含 Shift 改动）。杀掉了用户会话残留 java 进程 PID 10804。**验证中**。

### 第 19 轮补 4 — 白色仍完全不渲染 → 改 0xFFFEFE + drawStringWithShadow（2026-08-25，用户实测）
- **用户反馈**：完全没有白色（纯黑），与字号无关（字号改回 0.85）。
- **进一步定位**：红色正常、白色完全不渲染 → 纯白 `0xFFFFFF` 恰等于 `GlStateManager.colorState` 初始值 `(1,1,1,1)`，目标色==纯白时 `GlStateManager.color` 判断"无需设置"不调 GL11，而 GlStateManager 缓存与实际 GL 存在不同步 → 纯白用遗留色绘制（变黑）。GL11.glColor4f 强制设色理论上应绕过，但实测仍无白色（疑与 1.12.2 GlStateManager 内部状态机有关）。
- **修复（决定性）**：正数颜色改 **`0xFFFEFE`**（≈纯白，差 1/255 人眼不可辨），`≠` 缓存 → **强制触发 GL11 设色**；绘制改回 **`drawStringWithShadow(text, 0, 0, color)`**（自带阴影，阴影=深灰、主文本=近白，两次 renderString 颜色必然不同→都强制调 GL11→主文本一定以近白渲染）。字号 0.9→0.85（用户要求）。javap 验证：手动 `glColor4f` 已移除(=0)、`drawStringWithShadow` 调用在、`glDepthFunc`(GL_ALWAYS/LEQUAL) 在。
- **构建/部署**：仅 1.12.2，jar 67790B（11:46 构建），已替换 HMCL 实例（时间戳 19:46 + SHA256 27ffd7 与 dist 一致）；杀掉用户会话游戏进程 3 个（PID 3652/29052/12244，用户已开过游戏）。**验证中**。

### 第 19 轮补 5 — 1.12.2 计数渲染回退为与 1.7.10 完全一致（2026-08-25，用户要求）
- **用户决策**：1.12.2 数字文本显示问题大，先尽可能改到与 1.7.10 一致；**移除此前全部尝试**（GlStateManager.disableDepth、GL_ALWAYS/glDepthFunc、手动两步/GL11.glColor4f、0xFFFEFE）及尝试注释；用户待会自行排查。
- **改动（1.12.2 GuiInfinitePack.drawScreen）**：计数渲染回退为与 1.7.10 完全相同的路径——原生 `GL11.glDisable(GL11.GL_DEPTH_TEST)`（finally `glEnable`）+ `GL11.glPushMatrix/glTranslatef/glScalef/glPopMatrix` + `fontRenderer.drawStringWithShadow(text, 0, 0, color)`；颜色恢复 `count > 0 ? 0xFFFFFF : 0xFF4040`；字号 0.85；右对齐（`cx = guiLeft + xPos + 17 - textW*scale`）。移除 `GlStateManager` import。javap 验证：`GlStateManager`/`glColor4f`/`glDepthFunc` 引用均为 0，仅剩原生 GL11（glDisable/glEnable/pushMatrix/translate/scale/popMatrix）+ `drawStringWithShadow`(func_175063_a)。
- **构建/部署**：仅 1.12.2，jar 67752B（11:51 构建），已替换 HMCL 实例（时间戳 19:51 + SHA256 20c27a 与 dist 一致）；游戏确认关闭（无用户会话 java 进程）。**待用户排查**（若白色仍不显示，重点查 HMCL 环境的字体/着色器/其它 mod 对白色文本渲染的影响，1.7.10 同代码正常说明逻辑本身无问题）。

### 第 20 轮 — 1.12.2 计数文本显示真正根因 + K/M/G + 右缘内收（2026-08-25，两版本，用户实测通过）
- **用户反馈**：①1.12.2 计数文本颜色发糊/字号超小（1.7.10 正常），且"和中文（万/亿汉字）无关"；②数字右侧出去了约 3px；③缩写不要中文"万/亿"，改用 K/M/G。
- **根因 ①（决定性，两处叠加）**：
  - **blend/光照/COLOR_MATERIAL 遗留开启**：1.12.2 物品图标渲染（`RenderItem.renderItemModelIntoGUI`）结束只 `GlStateManager.enableBlend()` **无配对 disable**（1.7.10 的 `RenderItem` 渲染完会 `GL11.glDisable(GL11.GL_BLEND)`），且 `GuiContainer.drawScreen` 返回前 `RenderHelper.enableStandardItemLighting()` 开启光照+COLOR_MATERIAL → 计数文本在 blend 开+光照开下 `drawStringWithShadow` → 被混合/光照调制（发糊/残影）。**铁证**：Mojang 1.12.2 画物品数量文字（`renderItemOverlayIntoGUI`）前特意 `disableLighting + disableBlend`。
  - **unicodeFlag 小字形**：1.12.2 中文环境 `Minecraft.isUnicode()`=true → **所有字符（含 ASCII 数字）走 unicode 半宽小字形（约 4px）**；1.7.10 英文环境 unicodeFlag=false → 数字走 default.png 全宽字体 → 1.12.2 数字字号远小于 1.7.10（"超小字号"）。
- **修复（1.12.2 `GuiInfinitePack.drawScreen`）**：画计数文本前显式 `GlStateManager.disableLighting() + disableColorMaterial() + disableBlend() + enableAlpha()`（画完恢复，高亮/遮罩半透明仍需 blend）；计数文本绘制期间临时 `fontRenderer.setUnicodeFlag(false)`（数字走 default 字体，绘制后恢复；"万/亿"等中文不在 ASCII 表始终走 unicode 不受影响）。1.7.10 逻辑不变（其物品渲染后 blend 本来就关、英文环境 unicodeFlag=false），仅同步本次需求调整。
- **需求调整（两版本）**：
  - **右缘内收 4px**：`availW` 15→11、右缘 `guiLeft+xPos+17`→`+13`（左限制不变，长数字不再溢出槽外）。
  - **缩写改 K/M/G**：`<1000` 原样；`<100万` 用 K（`1.2K`）；`<10亿` 用 M（`1.2M`）；否则用 G（`1.5G`）；负数前缀 `-`。替换原"万/亿"逻辑（`compactCount`）。
- **构建/部署**：两版本 `--rerun-tasks` 强制重编译成功（gradle 对 bind-mount 文件时间戳判断偶发 up-to-date，需 `--rerun-tasks` 保险）；dist 四 jar 更新（1.7.10 63735B / 1.12.2 68032B）；已替换两测试实例 mods（备份 .bak，SHA256 与 dist 一致）；1.12.2 已启动实测。**用户实测两版本均通过**（颜色/字号/右缘/KMG 全部符合预期）。
- **经验**：gradle + Windows bind-mount 修改源码后偶发误判 up-to-date，构建前用 `--rerun-tasks` 保险；1.12.2 中文环境画 GUI 数字文字须 `disableBlend + disableLighting + setUnicodeFlag(false)`。

### 第 21 轮 — 换机接手 + 三个 Bug 修复（2026-09-23，分支 `fix/lock-crash-and-scroll`）
- **背景**：工作区从旧机搬到本机 `C:\projects\2608-mc`（旧机 Docker 损坏）。核对结论：仓库在 `docker/`（分支 `main`，HEAD `74b5582`），比根目录的 `docker-backup.bundle`（`cf33691`，08-25 20:50）**更新**——bundle 是旧机快照，里面还残留 `scripts/slw.txt` / `scripts/javap_out.txt` 两个调试残留文件，本地 `12a12fb` 已删除；本地另有 `74b5582` 两个清理提交。**版本无冲突、无丢失**，以本地 `main` 为准。
  - 本机需从零恢复：`mcmod-dev` 镜像、`docker_gradle-home` 卷都不存在（旧机 Docker 已毁），首次构建重新下载整套工具链。
  - 代理换成 `127.0.0.1:7897`；容器内必须用 `host.docker.internal:7897`（见第 4 节）。
- **Bug 1（1.12.2：创造拦截界面点「返回标题」崩溃）**
  - 根因：`GuiForcedSurvivalDenied.actionPerformed` 里 `mc.world.sendQuittingDisconnectingPacket()` 之后调 `mc.displayGuiScreen(null)`。1.12.2 的 `NetworkManager.closeChannel()` 是 `channel.close().awaitUninterruptibly()`（**阻塞客户端线程**），断开流程 `NetHandlerPlayClient.onDisconnect → Minecraft.loadWorld(null)` 会在另一线程把 `world`/`player` 置空；而 `Minecraft.displayGuiScreen(null)`（1.12.2 第 1056 行）只要 `world != null` 就会解引用 `this.player.getHealth()`。`world`/`player` 是两个非 volatile 字段、不同步读取，一旦读到「world 还是旧的、player 已置空」就 NPE → 崩溃（报告描述通常是 `Updating screen events`）。1.7.10 的 `closeChannel` **不做 await**，断开晚一个 tick 才在主线程发生，所以同款代码在 1.7.10 侥幸不崩——这正好解释了"1.7.10 正常"。
  - 修复：改用原版「保存并退出到标题」的同款安全序列（`GuiIngameMenu` id=1 分支）：`sendQuittingDisconnectingPacket()` → `mc.loadWorld(null)`（在客户端线程内完成世界/玩家卸载）→ `mc.displayGuiScreen(new GuiMainMenu())`。传非 null 的 `GuiMainMenu` 可完全绕开 `player.getHealth()` 那条分支。**只改 1.12.2**（1.7.10 用户实测正常，不动）。
- **Bug 2（1.7.10：滚轮"一个一个往后翻"）**
  - 根因：`enchantItem(id)` → `scroll(±1)`，即每次滚轮只挪 1 条；日志实证 `scroll=` 只出现 1/2/3/4。
  - 修复：步长改为 `SCROLL_STEP = ENTRY_COLS = 9`（**一行一行翻**）；并在 GUI 侧把**一个 tick 内的多个滚轮事件合并成一次翻动**（原来每个 LWJGL 事件都发一个包，自由滚轮会连翻）。
  - **1.12.2 同样存在**（代码同源），一并修。
- **Bug 3（1.7.10：滚轮导致鼠标位置的计数变化）—— 真根因已定位（2026-09-23，靠诊断日志实证）**
  - **日志证据**（`fml-client-latest.log` 滚轮测试段）：每次滚轮都紧跟一条
    `client SHIFT-WITHDRAW idx=<鼠标下那一格> count=...`，而且扣的是**一整组**
    （烟花 `1 → -63`、1 格的日志书 `0 → -1 → -2 → …`）。这些行**只有 client、没有 server 对应行**，
    说明该动作绕过了服务端事务、只在客户端本地改了计数 —— 所以画面上的数字一路变负。
  - **真根因**：GTNH（`lwjgl3ify`）环境下**滚轮事件会带上按键状态**。模组把滚轮事件透传给了
    `super.handleMouseInput()`，原版 `GuiScreen` 于是按鼠标按键事件处理，`GuiContainer` 最终把它
    变成作用在「鼠标下那一格」上的 **Shift+左键点击**（mode=1 / clickedButton=0）。而模组把
    "Shift+点击条目"定义为**取一整组进玩家背包**，所以每滚一格就整组扣掉那一格的计数；
    又因这次点击的服务端事务被拒，扣数只落在客户端本地。
  - **第一次修复（渲染帧快照）不是根因**：容器加的 `beginRenderFrame()/endRenderFrame()` 帧快照
    （保证图标与计数同源）是对的加固，但症状依旧 —— 问题不在渲染同源，而在**输入处理**。
    该快照保留（无害且更稳）。
  - **修复**：`GuiInfinitePack.handleMouseInput()` 一旦读到 `Mouse.getEventDWheel() != 0`，
    **只累积翻页方向并直接 return，绝不调用 `super.handleMouseInput()`**；再给
    `mouseClicked` / `mouseMovedOrUp`（1.12.2 是 `mouseReleased`）/ `mouseClickMove` 加
    `isWheelEvent()` 双保险。两版本都加。
  - **经验（铁律级）**：容器 GUI 里**滚轮事件绝不能透传给原版鼠标处理**（不同输入环境对 LWJGL
    滚轮事件的按键字段实现不一致）；另外**客户端的乐观更新必须有服务端对应动作**，否则一旦
    服务端事务被拒，本地计数就会单方面漂移（本次漂到了负数）。
  - `DEBUG_SCROLL` 诊断日志暂时保留，复测时用来确认滚轮不再产生 `SHIFT-WITHDRAW`；确认后可置 false。





### 第 22 轮 — 定版 1.0.3 + 公开发布（2026-09-25）
- **背景**：第 21 轮三个 Bug 修复已在两个测试实例实测通过，用户要求把最新版本推到 GitHub 并发新 release。
- **版本号**：`1.0.2` → `1.0.3`。改动点（5 处配置 + 2 处文档）：
  - `mcmod-1.7.10/addon.gradle` — `project.ext.modVersion`
  - `mcmod-1.12.2/build.gradle.kts` — `version`（并 `injectedTags.put("VERSION", version)` → 生成 `Tags.VERSION`）
  - `mcmod-1.12.2/src/main/resources/mcmod.info` — 1.12.2 的此处是**字面量**（1.7.10 用的是 `${modVersion}` 占位，随 addon.gradle 走）
  - `scripts/build_release_1.7.10.sh`、`scripts/build_release_1.12.2.sh` — dist 产物命名
  - `README.md` — 下载/安装处的 jar 名
- **重新构建**：两版本 jar 均按 1.0.3 重新构建，产物复制进 `dist/`（构建命令见第 4 节）。
- **顺带修复（1.12.2 产物版本号，1.0.2 及以前一直错）**：`build.gradle.kts` 里 `injectedTags.put("VERSION", version)` 的裸 `version` 解析到 `MinecraftExtension` **已废弃的 `version`（= mcVersion）**，所以 1.12.2 的 `Tags.VERSION` 实际是 `"1.12.2"`（已用 v1.0.2 发布产物 javap 复核确认）。改为 `project.version.toString()` 后，两版本 `Tags.VERSION` 与 `mcmod.info` 均为 `1.0.3`（1.7.10 一直是对的）。
- **本版发布内容（相对 1.0.2）**：
  1. 修复 1.12.2 强制生存拦截界面点「返回标题」崩溃（第 21 轮 Bug 1）。
  2. 修复鼠标滚轮导致前端 UI 计数变化（第 21 轮 Bug 3；真根因：滚轮事件透传给原版鼠标处理，被当成 Shift+左键取整组）。
  3. 调整鼠标滚轮翻页模式：由「一条一条」改为「一行一行」，并合并同一 tick 内的多次滚轮事件（第 21 轮 Bug 2）。
- **发布**：`main` 快进合并 `fix/lock-crash-and-scroll`，打 tag `v1.0.3` 后推送 GitHub；release 附件为 `sninfinitepack-1.0.3-mc1.7.10.jar` + `sninfinitepack-1.0.3-mc1.12.2.jar`。
- **安全核查（发布前全历史扫描）**：密码/token/API key/私钥/URL 内嵌凭据 **0 命中**；`.env` 未入库（仅 `.env.example` 占位）。唯一敏感项是旧机内网 IP `192.168.1.243`（仅出现在代理解释处），本轮已从 HEAD 文档移除；**它仍存在于更早的历史提交中**（彻底清除需要重写历史 + 强推，未执行）。
- **推送认证**：本机 HTTPS 直连 GitHub 被墙且无可用凭据（无 gh CLI、无 credential 缓存），改用已配置的 SSH key `~/.ssh/id_ed25519_github`（`git@github.com`，身份 `shanghuo`）推送。

---

## 3. 当前现状（1.0.3 · 文件 NBT 存储 + 强制生存）

### 已实现功能
- **存入**：手拿物品点条目格（插到该格）/ Shift+玩家背包物品整组存入 → `count += 数量`。
- **取出**：空手左键条目 = 整组**拿到光标**（鼠标上）、右键 = 1 个**拿到光标**、Shift+条目 = 整组进玩家背包（与 MC 容器操作习惯一致）→ `count -= 数量`（可负，无限透支）。
- **删除**：右上角切换删除模式（红色遮罩），空手左键条目**两次确认**后删除该条目。
- **滚动**：鼠标滚轮**一行一行翻**（步长 `SCROLL_STEP = ENTRY_COLS = 9`，见 `ContainerInfinitePack.enchantItem`）；GUI 侧把**一个 tick 内的多个 LWJGL 滚轮事件合并成一次翻动**（`GuiInfinitePack.wheelDirection` + `flushWheel()`），避免自由滚轮/高分辨率滚轮"一滚飞到底"。
- **计数显示**：条目格右下角数字，正=白、0/负=红；**统一 0.85 倍字号 + 大数缩写（K/M/G）+ 超宽再缩小 + 右缘对齐内收**（不左偏移、不溢出槽外）；**悬停显示精确数量 tooltip**。
  - **图标与计数同源（第 21 轮）**：GUI 每帧绘制前 `container.beginRenderFrame()` 锁定一份（滚动偏移 / 显示顺序 / 存储）快照，帧内所有查询（原版画图标、计数覆盖层、悬停 tooltip）都走快照，帧末 `endRenderFrame()` 解除。保证「右下角数字」永远是「该格实际画出的那个物品」的计数，不会出现"图标没变、数字却变了"。
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

### 目录结构（C:\projects\2608-mc）
- `nw-mc-20251224/` — **真实实例，用户正在玩，禁止修改/装 mod**（未装 infinitepack）。注：本机工作区只搬来了 `nw-mc-20251224-test/`。
- `nw-mc-20251224-test/` — **1.7.10 测试完整拷贝**，已装 infinitepack，内存 Min=1024/Max=4096。
- `hmcl/` — **1.12.2 测试实例**（HMCL 启动器 + `.minecraft/versions/1.12.2-Forge/`，Forge 14.23.5.2864，mods 已装 sninfinitepack）。
- `docker/` — 开发环境：`mcmod-1.7.10/`（1.7.10）+ `mcmod-1.12.2/`（1.12.2）两个版本源码工程、`dist/` 成品、`scripts/` 脚本、`compose.yaml`、`mcmod-1.7.10/build/rfg/` 反编译源。
- 容器：`mcmod-dev`（构建）—— **只可启动/停止，不要删除**。

### 代理（2026-09-23 换机后更新）
- 代理从旧机的局域网地址换成本机 `127.0.0.1:7897`（Clash Verge / verge-mihomo）。
- **容器内不能写 `127.0.0.1`**（那是容器自己），`docker/.env` 必须写 `PROXY_HOST=host.docker.internal:7897`。
- 本机直连各源站也通（gradle/maven/forge/mojang/GTNH/adoptium/debian 实测全 OK），但保持走代理更稳、且不改任何镜像源。

### 构建命令
```powershell
# 启动开发容器（若 mcmod-dev 未运行）
docker compose -f C:\projects\2608-mc\docker\compose.yaml up -d

# 构建 1.7.10 并复制到 dist（jar 带 -mc1.7.10 后缀）
docker exec mcmod-dev bash /scripts/build_release_1.7.10.sh
# 产物：C:\projects\2608-mc\docker\dist\sninfinitepack-1.0.3-mc1.7.10.jar

# 构建 1.12.2 并复制到 dist（jar 带 -mc1.12.2 后缀）
docker exec mcmod-dev bash /scripts/build_release_1.12.2.sh
# 产物：C:\projects\2608-mc\docker\dist\sninfinitepack-1.0.3-mc1.12.2.jar
```

### 修改后必做
1. 用 SRG/反编译源里的方法名（1.7.10 partial-MCP；1.12.2 是 MCP 名，反编译源在各自 `build/rfg/minecraft-src/`）。
2. 构建成功后再安装。
3. **先确认游戏已关闭**，再 `Copy-Item` 覆盖对应实例 mods 目录：
   - 1.7.10 → `nw-mc-20251224-test\...\.minecraft\mods\sninfinitepack-1.0.3-mc1.7.10.jar`
   - 1.12.2 → `hmcl\.minecraft\versions\1.12.2-Forge\mods\sninfinitepack-1.0.3-mc1.12.2.jar`
   - 并**移除旧版本 `sninfinitepack-*.jar` / `infinitepack-*.jar`**（同 modid 冲突会双加载）；校验 SHA256 与 dist 一致。
4. 让用户重启游戏（或代启动）。

---

## 5. 测试方法

### 启动测试实例
```powershell
# 1.7.10（GTNH/Prism）
Start-Process "C:\projects\2608-mc\nw-mc-20251224-test\prismlauncher.exe" -ArgumentList "--launch","GT_New_Horizons_2.8.4_Java_17-25"
# 账号：`test`（离线账号，Prism GUI 创建）。

# 1.12.2（HMCL）
Start-Process "C:\projects\2608-mc\hmcl\HMCL-3.16.3.exe" -ArgumentList "--launch","1.12.2-Forge"
```
- 两个版本都开单人世界（创造模式方便测试），合成 8泥土+1木头 或创造拿背包。
- ⚠️ 用 `Start-Process` 启动 HMCL 后终端可能被游戏进程阻塞，后续命令用 `mode=async` 或先结束 java 进程。

### 验收清单
1. 背包显示名正常（非 `item.inf...`）。
2. 放入物品 → 条目出现、计数增加（白色正数）。
3. 空手左键条目 → 整组拿到**光标**（鼠标上）、右键 → 1 个到光标、Shift+条目 → 整组进玩家背包；计数减少；一直取到负数（红色）仍能取出；光标即时显示取出的物品。
4. 满耐久弓 vs 耗弓 = 两条独立条目；取出属性/附魔/NBT 与放入一致。
5. 删除模式：第一次点击仅高亮，第二次同格才删除；点别处取消。
6. 滚轮**一行一行**翻（每次 9 格）；一次滚动动作只翻一行（连滚/自由滚轮不会飞）；**滚轮绝对不会改动任何条目的计数**（鼠标下那一格的数字只随翻页换条目而变）。
   - 诊断：`GuiInfinitePack` 里 `DEBUG_SCROLL = true` 时，每次滚轮会打一行
     `[infpack] client SCROLL dir=.. scroll=.. hoverSlot=.. idx=.. item=.. count=..`；
     **复测要点**：滚轮期间日志里**不应再出现 `client SHIFT-WITHDRAW`**（那正是 Bug 3 的病灶），
     且 `count=` 只会因为鼠标下换了物品而变化。
7. 合成 8泥土+1木头 → 得到背包。

### 日志（重要）
- **`InfinitePackMod.LOG`（`[infpack]` 前缀）写到 `fml-client-latest.log`，不是 `latest.log`！**（1.7.10 GTNH）
- 1.7.10 路径：`nw-mc-20251224-test\instances\GT_New_Horizons_2.8.4_Java_17-25\.minecraft\logs\fml-client-latest.log`
- **1.12.2 路径：`hmcl\.minecraft\versions\1.12.2-Forge\logs\latest.log`**（1.12.2 写 latest.log，mod 加载/`[infpack]` 日志在此；用 `Select-String` 搜 `infpack`/`sninfinitepack`）。
- 崩溃报告：各自 `crash-reports\`。
- 服务器逻辑在单机里也跑在同一 JVM，日志同文件（线程前缀区分 `Server thread`/`Client thread`）。

---

## 6. 部署到真实服务器（用户决策，未执行）

- 同一份 jar 放入服务器 `mods/`；**服务端存储逻辑权威，服务端必须装**；客户端也装同 jar。
- 数据存服务器 `world/data/sninfinitepack/<uuid>.nbt`（物品 NBT 只存 uuid），无需额外数据库。
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
- [x] **1.0.1：海量存储改造（方案 A 文件 NBT）**（第 12 轮已完成；1.7.10 与 1.12.2 实机存取/排序/搜索均正常）。
- [ ] 真实服务器部署（等待用户决策/提供 ServerPack）。
- [ ] `/infpacktest` 在真实服务器跑通。
- [ ] 共享仓库 / 公会共享背包（全新设计，当前无）。
- [ ] 创造模式禁止存入（可选，用户尚未决定）。
- [x] **强制生存指令**（1.0.2 第 13 轮已实施；1.7.10 与 1.12.2 实机验证正常，详见第 10 节）：一条指令两参数（密码 + 启停），对所有存档生效；创造创建的存档→拒绝游玩（客户端全屏拦截）；生存创建的存档→切创造自动改回生存；停用需密码。

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
