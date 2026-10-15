# 翻转 / 转场量测夹具（tools/）

目的：任何一次改动动画之后，能在 2 分钟内拿到 **时间轴 + 角度 + 透视 + 尺寸** 的量化证据，
用来和 Windows 8 / WinJS 参考实现逐条对比。

## 文件

| 文件 | 作用 |
| --- | --- |
| `tools/video_to_frames.py` | mp4 → `<outdir>/frames/f_%06d.png` + `<outdir>/frames.csv`（`frame,t_ms,file,source`）。cv2 优先，**逐帧取 `CAP_PROP_POS_MSEC` 作真实时间**；无 cv2 时退回 ffmpeg（无 pts 索引）。 |
| `tools/flip_burst.sh` | 设备端 `/system/bin/sh` 里 `screencap -p` 连拍，时间戳取 `/proc/uptime`（厘秒）。用前必须把 CRLF 规范成 LF 再 push，否则 sh 报 `unmatched 'for'`。 |
| `tools/measure_flip.py` | **磁贴四点射影模型**（旧版翻转）：`--detect-card` 定位卡片；主流程输出每帧宽/左右边高/近远比/θ/F_est/缓动拟合。 |
| `tools/measure_pages.py` | **整页 turnstile 模型**（Win8/WP8 页面进出）：同时跟踪最多 2 个页面轮廓、左右边高比 → rotateY 角度、状态栏泄漏、顶/底栏静止区运动。 |
| `tools/capture_flip.ps1` | 编排脚本（设 scale → force-stop → 起 app → 点 tab → 抓 baseline → detect-card → screenrecord/burst → 抽帧 → 量测）。**当前跑不起来**，见文末。 |
| `tools/captures/scale_log.txt` | 每轮抓取的 `animator_duration_scale` 与 `lastUpdateTime`（证明量的是哪一版 APK）。 |

`measure_flip.py --page-mode …` 会把整条命令行转给 `measure_pages.py`（本脚本不认识的
flag 如 `--axis-x/--persp-f/--components` 原样透传），所以两条模型共用同一套
`--dir/--frames-csv/--stride/--tmin/--tmax/--out`。

## 用法

```powershell
# 1) 抽帧（真时间轴）
python tools\video_to_frames.py --video tools\captures\open600b\flip_open.mp4 --outdir tools\captures\open600b

# 2) 磁贴模型：宽/高/近远比/θ/缓动拟合
python tools\measure_flip.py --dir tools\captures\open600 --baseline tools\captures\open600\base.png `
  --seed 720,460 --window-rect 0,0,1440,3200 --flip-ms 600 --slow 1 --contam-top 758

# 3) 整页 turnstile 模型：两页轮廓 + 角度 + 状态栏 + 静止区
python tools\measure_flip.py --page-mode --dir tools\captures\open600b --stride 4 `
  --status-h 110 --axis-x -40 --persp-f 1.25
```

## 一轮真实输出（样例）

磁贴模型（`tools/captures/open600`，1440×3200、FLIP_MS=600、scale=1，装机 22:04:42）：

```
frames measured : 36   card=7  no-card=29  unmeasured=0
card rect       : (56, 378, 1328, 342)   window rect: (0, 0, 1440, 3200)
front (card) frames: t=0..986 ms  n=7
tile top-left y   : min=378 max=391 first=378 last=384  drift=+6 px over 7 frames
no-card frames     : t=1019..4647 ms  n=29
near/far ratio   : max=1.7878  -> F <= 1.769   [source F=1.50]
F_est from pixels: median=1.573  n=2   [source F=1.50]
theta timeline   : max=58.0 deg over 7 frames; first/last = 0.0/58.0 deg
easing fit       : T_est=1255 ms  t0_est=740 ms  rms=16.49 deg over 7 frames
                   expected FLIP_MS*slow = 600 ms -> ratio 2.092
```

单帧校验：首帧 `card_width_px=1211`、`near_far_ratio=1.1534`，与 F=1.5 的四点射影在
θ=12.3° 时的预测 `1/0.8672=1.1533` 吻合到 4 位。

整页模型（`tools/captures/open600b`，stride 4，旧版翻转的录像，仅作工具自测）：

```
page surface    : (39, 42, 47) +-8   P=1800 px (1.250 x width)  axis_x=-40
first motion    : t=786.10 ms   last motion: t=3159.20 ms
c1 height ratio : max=1.00  (model @80deg = 1.77, @0deg = 1.000)
static zones    : top-bar max change=156670 px, bottom-bar max change=287784 px
```

## 已知局限（都是踩过的坑）

1. **16dp 圆角 = 56px**：直接取最边列的高度会系统性低估约 76px。必须用中间
   `1 − 2·0.15` 的列做两轮抗差直线拟合并外推到两端（`fit_cols`/`fit_rms_px` 列记录了拟合范围）。
2. **screenrecord 是 VFR**：静止段几乎不出帧，运动段 47–120fps（本机 120Hz 屏）。
   必须用每帧 pts 计时；`index/fps` 会算出完全错误的时长。
3. **cv2.imwrite 对非 ASCII 路径静默失败**（本仓库路径含「Android项目」）：`frames.csv`
   有行而 `frames/` 是空的。已改为 `cv2.imencode(".png") + buf.tofile()`。
4. **同色合并**：被点的磁贴与它下面那张卡同为 `(39,42,47)`，磁贴长大时两者连成一块。
   `--contam-top`（下一张卡的顶）是必须的；band 法只用**上边界**这一个未被污染的信号，
   且有效列必须排除下一张卡抗锯齿的首行（余量 ≥4px，否则整张卡被并进列段，拟合 rms 爆表）。
5. **页面无法靠颜色区分**：列表页卡片、图页工具条、状态栏都有 `(39,42,47)`±8。
   可用的具名签名：页面底色恰好 `(17,18,23)`；图页点阵 `(60,63,70)`（tol 0：静止列表页
   134 px、0 px 落在状态栏；静止图页 127452 px、18 px 落在状态栏 —— tol 6 太松，
   列表页会误报 379 px）。
6. **角度反解依赖约定**：`sinθ = P·(r−1)/(u_far − r·u_near)`，`r = h_near/h_far`，
   `u = x − axis_x`。本机口径 `axis_x = −120px`（−40dp × 3.0 px/dp）、`P = 1800px`（600dp）
   ⇒ 80° 时**模型比 = 1.7393**（见下节）；若把转轴当成页面中心会得到 2.30（错档）。
   工具会把 `model @80deg` 打出来直接对照。
7. **量不到就说量不到**：外推落在 `[0.10,1.40]×最高列` 之外、或 rms 超限，一律标
   `unmeasured` + 原因，绝不插值假装测到（`state`/`note` 列）。
8. **burst 模式只有 ~1.9fps**（帧起点间隔 490–570ms，单帧 340–410ms）⇒ 时间分辨率 ±350ms，
   只适合「慢放（scale>1）时的粗测」；精测必须 screenrecord。

## 元数据纪律

每轮抓取前记录：源码 `FLIP_MS`/`BAR_FLIP_MS`/`HANDOFF`/`HANDOFF_GROW`/`PERSPECTIVE_DISTANCE_FACTOR`、
`versionName`、`dumpsys package com.yjc.click | grep lastUpdateTime`、
`settings get global animator_duration_scale`（前后各一次）。**源码与装机时间戳对不上就不许测**
——会量到另一版 APK。结束务必 `settings put global animator_duration_scale 1`。

## turnstile 角度的约定（口径已定：常量按 dp 读，判据 1.7393）

`axis_x = −40dp`（屏幕左边缘外 40dp；本机 3.0 px/dp ⇒ **−120px**）、
`P = 600dp`（本机 ⇒ **1800px**）、`BackwardOut` 分离场 `P = 800dp`（本机 ⇒ **2400px**）。
CSS px 是设备无关单位 ⇒ 常量按 dp 读；实现侧已核：`ProgramScreen.kt:1261 TURNSTILE_ORIGIN_X = (−40).dp`、
`L1264 TURNSTILE_PERSPECTIVE_IN = 600.dp`、`L1267 TURNSTILE_PERSPECTIVE_OUT = 800.dp`。
CSS `perspective(P) rotateY(θ)` 把点 `(x,0,0) → (x·cosθ, 0, −x·sinθ)`（+z 朝观察者），
缩放 `P/(P−z)` ⇒ 到转轴距离 `d = x + 40dp` 处 `scale(d) = P/(P + d·sinθ)`。
官方 `P=600dp / W=480dp / θ=80°` ⇒ 左 0.9384、右 0.5395（缩放不变量 `P/W = 1.25`）；
本屏内容宽也是 480dp ⇒ **80° 时左右边高比 = 1.7393**。
`tool measure_pages.py` 打印的 `model @80deg` 必须是 **1.7393**（`--axis-x -120`，本机口径）。
**`1.77` 是拿 40px 而非 40dp（120px）代入的错误口径，已作废**；打印 2.30 是把转轴当成页面中心（错档）。
**lead 更早给的期望值 3.10 同样作废。**

`perspective-origin` 默认在容器中心而非 `transform-origin`：竖直方向的缩放只取决于该点深度
（`height = H·P/(P−Z)`），所以「左右边高比」这个判据**不受**消失点/`perspective-origin` 位置影响
⇒ 它是**硬判据**。反过来，**水平摆放**（元素左边缘 x、可见宽度）**依赖消失点**，只有在
「实现声明复刻 CSS `perspective()` 函数语义」时才判 —— 本实现走 `setPolyToPoly` 四点射影
（`ProgramScreen.kt:485`），水平摆放**按它自己矩阵的自洽性判**，不要拿 CSS 函数形式算出的
−33.48 / 8.72dp 去卡它。

## 待查缺陷：实测时长与 `FLIP_MS` 不符（含证据）

录像与抽帧产物：`tools/captures/open600/`（进场 36 帧，t=0..4647ms）、
`tools/captures/close600/`（退场 524 帧，t=0..10773ms）；装机 `lastUpdateTime=2026-10-15 22:04:42`、
源码 `FLIP_MS=600`、`animator_duration_scale=1`（前后各回读一次，见 `tools/captures/scale_log.txt`）。

进场（`open600`）——**磁贴没走到 90° 就被整页图抢占了**：

| 帧 | t (ms) | 现象 |
| --- | --- | --- |
| f01–f03 | 0..925 | 静止列表页（未 tap） |
| f04 | 941.6 | 磁贴宽 1211px、`near_far_ratio=1.1534` ⇒ θ=12.34°（F=1.5 模型预测 1.1533） |
| f08 | 1019.2 | 磁贴还在（上边界 448） |
| f10 | 1019→1050 | 上边界 499，仍与下一张卡同色合并 |
| **f11** | **1111.0** | **单帧跳变：变化像素 92210、bbox 横跨 89..3195、mean\|Δ\|=4.49（全场最大）** |
| f12 | 1136.2 | 视觉确认：**整页节点图已完全露出**（工具条/点阵/节点/FAB/底栏齐全），无任何翻转中的磁贴 |
| f13–f27 | 1162..1466 | 残余运动 mean\|Δ\| 1.69 → 0.05（幅度递减，像栏位收缩的尾巴） |
| f29+ | ≥1500 | 完全静止 |

关键矛盾：θ 只走到 **58°**（最后一帧 f17 之前就无卡），而按 `FLIP_MS=600`、`HANDOFF=0.5`
正面应当活到 **tap+300ms**、整页要到 **tap+600ms**（g=1 才把底边推到 3200）才完全露出；
实测**tap 后约 170–200ms 图页就已整页铺满**（含 y≈2934 的 FAB 与底栏），
即 `g` 已是 1 —— 这只有 `progress` 被**直接置到 1** 才做得到。

退场（`close600`）——**反方向慢了一个数量级**：tap（`input keyevent 4`）在 t≈1200ms，
整页图一直到 t≈2500 还在；磁贴色块 t=2604 才出现（`(56,332)-(1382,1101)` sz=469506）、
逐帧长大到 t=4109（sz=841415，顶边从 332 下移到 376 → 正在长回卡位），t=4159 起只剩单张卡面积
（439714 ≈ 1327×342 减圆角）。按「正面只在 p<0.5 出现」反推：磁贴出现在 tap+1.4s ⇒
**T_exit ≈ 2.8s**，与另一位同事连拍得到的 2.67s、以及本节录屏 6.98MB（vs 进场 811KB）
的密集程度一致。

**判断依据（是 spring 还是双触发？都不是）**：
- 不是 spring：正面阶段与总时长的比例仍符合 `HANDOFF=0.5` 的两段式（正面 0→58° 单调增、
  没有回弹/过冲；spring 的尾巴应当是长而平缓的渐近，而这里是一帧跳变）。
- 不是双触发：θ 全程**单调增**，没有回到 0° 的重启痕迹，也没有方向反转。
- 最像**提前 snap / 动画被取消**：进场 f11 那一帧的「单帧巨变 + 紧接着整页图无中间裁剪态」
  正是 `progress.snapTo(1f)`（旧版 `LaunchedEffect(openProgramId)` 分支）或
  「拿不到冻结卡片帧 → 覆盖层退回**未裁剪**的裸 `NodeGraphScreen`」两条路径的签名。
  两条路径都会让图形瞬时到终态，且都会在 tap 后 100–200ms 内发生。
- 退场慢 4.7 倍（2.8s vs 600ms）在同一个 APK、同一个 `animator_duration_scale=1` 下出现，
  说明两个方向**不是同一段代码/同一个 spec 在驱动**（或退场那一路走了不含 snap 的分支）。

复测口径（turnstile 版落地后）：进场应为 **300ms**（图页 80°→0°），退场应为 **128ms + 300ms**
（图页 0°→80° 的 128ms 分离场 + 列表页 −50°→0° 的 300ms）；超时/瞬移都按缺陷报。


## `tools/capture_flip.ps1` 状态与内联等效配方

**已修好、但还没端到端跑过**：原先 `pwsh -File tools\capture_flip.ps1 …` 立即
`ParserError: tools/capture_flip.ps1:388`（15 条连锁错误，报在 `"$($persp.value)"` 的 `$(` 上）。
真因**不是** `$(...)`：第 384 行末 `'--slow', "$Slow')` 把收尾的双引号写成了单引号
（`"$Slow')`），于是那个没闭合的 `'` 把后面所有内容都吞进字符串，解析器才在 388 行报错。
把那个字符改回 `"` 之后 `Parser.ParseFile` 报 **0 个错误**。尚未实际执行过一次完整抓取。

```powershell
adb -s <serial> shell am force-stop com.yjc.click
adb -s <serial> shell am start -W -n com.yjc.click/.MainActivity ; Start-Sleep 4
adb -s <serial> shell input tap 720 3060          # 程序 tab
Start-Sleep -Milliseconds 1500
adb -s <serial> shell screencap -p /sdcard/base.png ; adb -s <serial> pull /sdcard/base.png <outdir>\base.png
python tools\measure_flip.py --detect-card <outdir>\base.png     # 拿卡片矩形/底色
# 退场用 KEYCODE_BACK（BackHandler(openProgramId != null) 走的同一条 closeProgram 路径），
# 不要用工具条坐标点：UI dump 给的「关闭」中心 (126,112) 落在状态栏，tap 会打空。
$rec = Start-Job { param($s) adb -s $s shell screenrecord --time-limit 10 /sdcard/x.mp4 } -ArgumentList <serial>
Start-Sleep -Milliseconds 900 ; adb -s <serial> shell input tap 720 460
Start-Sleep -Seconds 10 ; Receive-Job $rec ; Remove-Job $rec -Force
adb -s <serial> pull /sdcard/x.mp4 <outdir>\x.mp4
```
