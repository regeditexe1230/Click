# 节点图「Scratch 式分类托盘」规格（用户 m08582 定案）

> 状态：定案待实现。所有条目都来自用户的 9 个选择（m08598 问卷）或我补的实现口径（用户未反对即按此做，做错了他会当场指出）。

## 1. 用户逐条决定

| # | 决定 | 备注 |
|---|------|------|
| 1 | 托盘**常驻底部**，高度 = **屏幕高度 1/4**，可一键收起 | 收起态见 §4 |
| 2 | 分类按 Scratch 风格分**三组**：事件 / 动作 / 控制 | 归类见 §2 |
| 3 | 「开始」变成**可拖出的块**，程序里最多一个；没有时点运行提示「请先拖出一个开始块」 | 引擎已能处理 `start == null` |
| 4 | **已有程序里的开始节点也一并删掉** | 破坏性 ⇒ 必须先备份（§6） |
| 5 | **长按拖出**、跟手影子、松手落在哪就放哪；**点一下**则落在画布中央 | 两种入口都给 |
| 6 | 这次只用**现有 6 种块** | 不新增块类型 |
| 7 | 高度按**屏幕高度的 1/4** | 本机 3200px → 800px = **228.6dp**（density **3.5**，560dpi；旧的 266dp 是按 3.0 算的错口径） |
| 8 | **不自动连线** | 连线仍靠拖端口 |
| 9 | **去掉**「从节点右侧圆点拖到空白处新建节点」的旧入口 | 只能从托盘取块 |

## 2. 分类归属（颜色取 `NodeGraphModel.kt:376-384` 现有映射）

- **事件**：开始（`START`，`PROGRAM_COLORS[0]` 主题紫）
- **动作**：点击（`CLICK`，`PROGRAM_COLORS[6]` 蓝）、滑动（`SWIPE`，`PROGRAM_COLORS[5]` 青）
- **控制**：延时（`DELAY`，`PROGRAM_COLORS[3]` 黄）、等待（`WAIT`，`PROGRAM_COLORS[2]` 橙）、循环（`LOOP`，`PROGRAM_COLORS[7]` 紫）

## 3. 现状（实现者需要知道的事实）

- `app/src/main/java/com/yjc/click/program/ProgramModel.kt:237 fun create(name: String): Program` —— 新建程序时**硬塞一个 START 节点**，这是要改的源头。
- `app/src/main/java/com/yjc/click/program/ProgramModel.kt:185 val start: ProgramNode? get() = nodes.firstOrNull { it.type == ProgramNodeType.START }`
- `app/src/main/java/com/yjc/click/program/ProgramModel.kt:200 if (to.type == ProgramNodeType.START) return EdgeError.TO_START`（开始节点不许有入边 ⇒ 迁移删节点时也要删掉这些边）
- `app/src/main/java/com/yjc/click/program/ProgramStore.kt:79 fun create(context: Context, name: String? = null): Program`
- `app/src/main/java/com/yjc/click/program/ProgramRunner.kt:121 val startNode = program.start` + `:122 if (startNode == null) { ... }`（现在是静默分支，要改成可见提示）
- `app/src/main/java/com/yjc/click/ui/program/NodeGraphModel.kt:208 fun addNode(node: ProgramNode, connectFrom: String?)`（新增块走这里，`connectFrom = null`）
- `app/src/main/java/com/yjc/click/ui/program/NodeGraphModel.kt:96 var pickNodeId: String?`（取点模式，跟托盘无关，别误删）
- 画布变换：`NodeGraphState.scale`（`MIN_SCALE = 0.3f` / `MAX_SCALE = 3f`）、`NodeGraphState.translation`；卡片尺寸 `NodeGraphMetrics.CARD_W = 132f` / `CARD_H = 84f`
- 旧的「拖到空白新建」入口：`app/src/main/java/com/yjc/click/ui/program/NodeGraphScreen.kt:293 private data class NewNodeAnchor(val canvas: Offset, val fromId: String)` 及其菜单/手势链路

## 4. 托盘形态（我补的口径）

- **展开态**：顶部一行三分类 chip + 右上「收起」箭头；下方是当前分类的块列表（横向排列，超出可横滚）。
- **收起态**：一条约 48dp 的横条（左「积木」标题、右展开箭头），画布几乎全高。
- 展开/收起状态写进 prefs、跨会话保持，**默认展开**。
- 程序里已经有 START 时，「开始」块 chip 变灰，点或拖都提示「已经有开始块了」。
- `running` 时托盘可见但**禁止拖拽/新建**（chip 灰掉）。
- 块 chip 外形与节点卡片同源：同色条 + 圆角 + 名称。
- 空画布提示语改成「从下方托盘拖出积木开始搭建」（5 种语言都要）。

## 5. 拖拽与落点

- **长按** chip（比长按阈值略短即可，不要和画布拖动打架）→ 生成跟手影子块（同 `CARD_W × CARD_H`、同色）→ 松手在手指处创建节点。
- **点一下** chip → 新节点落在**画布可视区中央**（可视区 = 托盘之上的区域）。
- 落点换算必须走画布变换：`canvas = (screen - translation) / scale`，影子块与最终节点位置需一致（验收容差 ≤ 一个卡片对角）。
- 拖动时手指进入托盘区域不创建节点（离开画布即取消）。
- 不自动连线；旧入口删除后，从端口拖到空白处**什么都不发生**（不弹菜单、不新建）。

## 6. 迁移（破坏性，必须可退）

- **一次性迁移**：所有已存程序删掉 `type == START` 的节点，以及所有挂在它上面的边（出边、入边都删），随后再次保存。
- **迁移前先备份**：把每个受影响的程序完整 JSON 写到 `filesDir/programs_pre_tray_backup/<programId>.json`（文件里带迁移时间戳字段），备份失败就**中止迁移**并保持原样。
- 只在 prefs 里没有 `tray_migration_v1` 标记时执行一次；迁移成功后落标记。
- 迁移后 `ProgramStore.load` 返回的程序里不含 START 节点。

## 7. 验收清单（逐条给证据）

1. 新建程序 → 画布空白、0 个节点。
2. 三个分类切换正确，6 个块都在对应组里（事件 1 / 动作 2 / 控制 3）。
3. 长按拖出 → 松手落点 = 手指位置（误差 ≤ 一个卡片对角）；点一下 → 落画布可视区中央。
4. 没有任何「开始」块时点运行 → 出现「请先拖出一个开始块」提示（截图或 logcat）。
5. 拖出一个开始块后，再点「开始」chip → 变灰/提示。
6. 已有程序（`程序 1`、`程序 1 副本`）打开后 = 0 节点，且 `filesDir/programs_pre_tray_backup/` 里有对应备份文件。
7. 旧入口不再新建节点（从端口拖到空白：无菜单、节点数不变）。
8. 托盘收起/展开正常；展开高度 = 屏高 1/4（本机 800px，允许 ±8px）。
9. **不回归**：turnstile 进出节点图动画、卡片按下缩放、连线、取点模式、撤销/重做、复制/粘贴/删除/重命名全都与改动前一致。

---

## 8. 改版定案（用户 m08642，**覆盖 §4 的形态**）

v1 那块固定面板被用户否决（原话：「不要现在这种，我要那种 md3 的滑动栏」）。用户新的三个回答 + 一句补充：

| 问题 | 用户选择 |
|------|----------|
| 托盘形式 | **MD3 可滑动底部面板**（顶部小拖柄 + Tab 分类） |
| 停靠高度 | **两档**：1/4 屏 ⇄ 收起，中间没有中间态 |
| 取块手势 | 长按拖出跟手 + 点一下落画布中央（保持 v1 已实现的行为） |
| 补充 | 「托盘中节点预览就应该是节点在画布上的样子」 |

### 8.1 外观与结构（必须照此）

- 面板按 MD3 large bottom sheet 规格：顶部圆角 **28dp**、surface 用 `surfaceContainerHigh`（**必须与画布背景明确不同色**，v1 的「同色无分界」要修掉）、顶上是 MD3 `DragHandle`（32×4dp 圆角横杠，居中，上下各 22dp 内边距）。
- **只有拖柄区域可上下拖**（`draggable` / `anchoredDraggable` 挂在拖柄上），面板其余部分不参与纵向拖动。**不要用 `BottomSheetScaffold`** —— 它自带的面板拖动会和「长按块卡片向上拖出」打架。
- 拖柄下面一行 `TabRow`（MD3 indicator），三个 Tab：事件 / 动作 / 控制；下面是当前分类的块列表（横向滚动）。
- 收起态 = 只剩拖柄（约 32–48dp 高，含手势栏安全边距）；展开态 = 面板总高 = **屏高 1/4**（本机 3200px → 800px）。
- 两档之间跟手拖动，松手按 50% 阈值吸附到最近一档；拖动过程中画布不动。
- 收起/展开状态仍存 prefs、默认展开。

### 8.2 块预览必须与画布节点一致

- 用户原话：「托盘中节点预览就应该是节点在画布上的样子」⇒ **复用画布上同一个节点卡片 Composable**（左色条、圆角、图标、标题、参数占位同一套），尺寸取 `NodeGraphMetrics.CARD_W × CARD_H`，参数显示该类型的默认值。
- 端口圆点保留外观（“就是画布上的样子”），但在托盘里**不参与任何交互**（不可点、不可连线）。
- 长按拖出时的跟手影子块用同一个 Composable ⇒ 拖动过程与落地节点完全同形。

### 8.3 验收增补（并入 §7 一起做）

- 展开态面板高度 = 800px ± 8；收起态只留拖柄；拖动跟手、松手吸附最近档。
- 面板 surface 与画布背景**肉眼可分**：在截图上取面板内一点与画布上一点的像素色值，两者不能相等。
- 长按块卡片向上拖出时**面板不动**（只有影块跟手）：给出拖动前后面板顶部 y 值对照。
- 点一下块卡片 ⇒ 落画布可视区中央（可视区 = 面板之上）。
- 块卡片与画布节点卡片外观一致：并排截图对照。

## 9. 用户第二轮反馈（2026-10-16 23:5x 报，已建 task-3）

用户原话：「现在的问题在事件栏中的那个开始节点，右边被裁切了一部分，还有，每个节点的接线柱应该也有遮挡关系，也就是和节点一起，不是始终置于顶层」。

### 9.1 托盘块端口被裁（缺陷 1）

- 现象与证据：`程序 1` 里已有 START ⇒「事件」分类的「开始」块被压暗到 `alpha = 0.45f`；其**输出端口圆点只剩内半边**（裁切对照图：`%TEMP%\click-verify\crop_event_block.png`，真机 `lastUpdateTime 2026-10-16 23:11:00`）。
- 根因：`Modifier.graphicsLayer { alpha }` 在 `alpha < 1` 时会建**离屏层**，离屏层按自己的 bounds 裁剪，而端口圆心正好落在 132×84dp 盒子的左右边缘上。
- 定案修法：自然尺寸盒子左右各留 `TrayMetrics.PORT_PAD = 7.dp`（≥ 端口半径 5.5dp + 半个描边），端口坐标整体加该偏移；上下的端口有 14dp 边距、**不需要余量**。外层尺寸与影子块同步保持同形。
- 验收：压暗块与不压暗块的端口都必须是**完整圆**（不许半圆）。

### 9.2 画布端口恒定置顶（缺陷 2）

- 现象：所有节点的接线柱永远画在所有卡片之上，节点叠放时**下层节点的端口会穿出上层卡片**。
- 根因：绘制栈是「边 → 逐节点 `NodeCard` → 两个 `fillMaxSize` 覆盖层」，端口在一个覆盖层里**遍历全部节点一次性画**，与所属节点的层序脱钩。
- 定案修法：端口跟自己的节点同层序（随节点卡片一起绘制）；**不动**端口命中/连线手势（按距离命中那套与视觉层无关）。
- 验收：用 `程序 1` 里 10 个坐标完全相同的 WAIT 节点（`(139.71,285.43)`）验证——**下层节点被上层卡片盖住的端口不许露在上层卡片之上**，同时上层节点自己的端口仍正常显示。改前 FAIL、改后 PASS，各留截图。
- 红线：本次修复**不得修改用户 `程序 1` 的节点数据**（不新增/删除/移动/撤销）。
