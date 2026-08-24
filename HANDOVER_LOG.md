# Infinite Pack（得一即无限背包）开发交接记录

> 本文档记录与用户的开发对话历程、关键决策、当前现状、构建与测试方法，供未来 AI / 协作者快速接手。最后更新：2026-08-25。

---

## 1. 项目一句话概述

Minecraft **1.7.10 / Forge 10.13.4.1614（GTNH 2.8.4 实测环境）** 的模组：玩家把任意物品放入背包物品后，可**无限取出**（属性/附魔/耐久/NBT 与放入时完全一致）。v1.1.0 起改为**计数制**——放入 +N、取出 -N、可为负（负数=无限透支），让玩家感知用了多少。

- MODID：`sninfinitepack`；显示名 `SN Infinite Pack`；版本 `1.0.0`（jar 名 `sninfinitepack-1.0.0.jar`）
- 源码：`docker/mcmod/src/main/java/com/infpack/`
- 成品：`docker/dist/sninfinitepack-1.0.0.jar`
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
- **决策（v1.1.0）**：客户端显示改为**乐观更新为主**——`slotClick` 里客户端直接改自己的 `storage`（存入/删除即时生效，取出不改），**不依赖**服务器→客户端的槽位/NBT 同步；计数显示直接用 `storage.size()`。`reloadFromBackpack/needsReload` 保留作重开兜底。

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

---

## 3. 当前现状（v1.0.0 · SN 版）

### 已实现功能
- **存入**：手拿物品点条目格（插到该格）/ Shift+玩家背包物品整组存入 → `count += 数量`。
- **取出**：左键=整组、右键=1 个、Shift+条目=整组 → `count -= 数量`（可负，无限透支）。
- **删除**：右上角切换删除模式（红色遮罩），空手左键条目**两次确认**后删除该条目。
- **滚动**：鼠标滚轮翻页（每 54 条一页）。
- **计数显示**：条目格右下角数字，正=白、负/0=红；**大数缩写（万/亿）+ 超宽缩小字体**；**悬停显示精确数量 tooltip**。
- **变体精确匹配**：物品注册名 + 耐久 + 完整 NBT 深度等价 = 同一条目；满耐久弓 vs 耗弓、不同附魔/属性 = 独立条目。
- **合成配方**：8 泥土 + 1 木头。
- **持久化**：全部数据在背包物品 NBT（`infpack.Entries`，每条目 `{Item注册名, Damage, Tag, Count}`），随物品走，服务器权威。

### 已知问题 / 说明
- NEI/创造给的物品可存入并无限取出（模组无法区分来源，用户决定保持现状）。
- 沙砾不掉燧石与模组无关（见上）。
- `/infpacktest` 自动化测试命令已实现（`CommandInfPackTest`，含计数透支校验）但**从未在服务器跑通**（GTNH 服务器端受限），当前作为代码级测试参考。

### 数据模型（`BackpackStorage`）
- `Entry{sample(样本 stackSize=1), count}`；`entries: List<Entry>`。
- API：`load/save(ItemStack)`、`size/isEmpty`、`getSample/getCount/getDisplayStack`、`deposit(stack, backpackItem, index)`、`withdraw(index, amount)`、`removeEntry`、`contains/indexOf`、`sameVariant`。
- 显示用 `getDisplayStack` 返回 stackSize=1，数量由 GUI 画。

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
# 产物：c:\project\mc\docker\dist\sninfinitepack-1.0.0.jar
```

### 修改后必做
1. 用 SRG/反编译源里的方法名（partial-MCP）。
2. 构建成功后再安装。
3. **先确认游戏已关闭**，再 `Copy-Item` 覆盖 `nw-mc-20251224-test\...\.minecraft\mods\sninfinitepack-1.0.0.jar`（并**移除旧的 `infinitepack-*.jar`**；校验 SHA256 与 dist 一致）。
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

- [ ] 真实服务器部署（等待用户决策/提供 ServerPack）。
- [ ] `/infpacktest` 在真实服务器跑通。
- [ ] 共享仓库 / 公会共享背包（全新设计，当前无）。
- [ ] 创造模式禁止存入（可选，用户尚未决定）。
- [ ] 更多 UI 打磨（如条目名称 tooltip、按计数排序等）。
