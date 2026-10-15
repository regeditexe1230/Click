# Windows 8 开始屏幕「点磁贴 → 应用启动」动效规格（B 组 · 独立复核版）

> 本文件由 `win8-ref-b` 独立完成。所有结论都带证据标签：
> - `[实测]` — 我自己脚本量出来的（脚本 + 输出都在本仓库）
> - `[文档]` — 微软官方文档 / 源码原文，带 URL 或 文件+行号
> - `[推测]` — 没有证据支撑的推断，一律显式标注
>
> 证据标签的**强度**要分开看：`[文档]` 里"官方 learn.microsoft.com 页面" > "微软官方示例源码" > "第三方转载的示例源码"。后者我每次都用了**两个互相独立的转载源**交叉验证（见 §2.1）。

---

## 0. 一页结论（TL;DR）

| 问题 | 结论 | 标签 |
|---|---|---|
| 转轴 | **同时绕 X 轴和 Y 轴**，绕控件**自身中心**（CenterOfRotationX/Y = 0.5, CenterOfRotationZ = 0） | `[文档]` |
| 总转角 | **不是固定值**，取决于按压点位置：`angle = (xMag+yMag) × 0.3 × 180/π`，最大 **17.1887°**（= 0.3 rad），发生在磁贴角上 | `[文档]` |
| 转动方向 | 按压点在左上 ⇒ 磁贴**右上角翘起/远边后退**；角度按 X/Y 分量互补拆分 | `[文档]` |
| 按下 | **瞬时，没有任何动画**（直接写 PlaneProjection 属性值） | `[文档]` |
| 松开 | **延迟 200 ms → 100 ms 回弹到 0**（线性） | `[文档]` |
| 缓动曲线 | 回弹 = **线性**（`UseLogarithmicEase = false` 默认）；可切换为 `ln(t+1)/ln2` 对数缓动 | `[文档]` |
| 缩放 scale | Tilt 效果里**没有缩放**，只有旋转 + Z 位移 | `[文档]` |
| 透视强度 | Win8/WinRT 的 `PlaneProjection` **根本没有透视强度属性** | `[文档]` |
| Z 位移（"下陷"） | `GlobalOffsetZ = -(1 - (xMag+yMag)) × 25`，即中心按下陷 **25 px**，角上按不下陷 | `[文档]` |
| 磁贴内容行为 | 内容随磁贴**一起**做同样的 3D 变换（`Projection` 作用在元素及其子树上），没有独立的视差/反向层 | `[文档]` `[推测]` |
| 终点 | 回到**原状**（RotationY=0 / RotationX=0 / GlobalOffsetZ=0），即恒等变换 | `[文档]` |
| 反向是不是严格倒放 | **不是**。正向（按下）无动画瞬时到位，反向（松开）200 ms 延迟 + 100 ms 线性插值，两者时长/曲线都不对称 | `[文档]` |
| 素材实测 | 我抓到的 Win8.1 实拍里，**磁贴按压阶段 1.4 秒内磁贴宽度一帧都没变**（`min width frac` 恒为 1.0000 / 0.9880，后者是 1 px 抗锯齿），随后整屏硬切 | `[实测]` |

**一句话**：官方 Tilt 效果是"**按下瞬间到位、抬起延迟 200 ms 再花 100 ms 线性弹回**"的非对称结构，最大 17.19° 双轴旋转 + 最多 25 px 下陷，无缩放，无透视强度旋钮。

---

## 1. 证据来源清单（可复核）

| 编号 | 内容 | 位置 / URL | 本地副本 |
|---|---|---|---|
| S1 | Windows Phone 8 / Win8 官方 `TiltEffect` 示例源码（C#） | <https://www.cnblogs.com/wgscd/articles/4883187.html> | `C:\Users\33947\Downloads\win8b\src\cnblogs_tilt.txt`（1540 行，去掉 HTML 的纯文本） |
| S2 | **同一份示例源码的第二个独立转载** | <https://www.cnblogs.com/grj1046/p/3291378.html> | 本次直接 `web_fetch` 全文核对 |
| S3 | Peter Torr 官方博客《Updated Tilt Effect》（原始出处） | <https://learn.microsoft.com/en-us/archive/blogs/ptorr/updated-tilt-effect> | `C:\Users\33947\Downloads\win8b\src\torr_tilt.html` |
| S4 | WinJS 4.4.3 真源码 `base.js` | <https://unpkg.com/winjs@4.4.3/js/base.js> | `C:\Users\33947\Downloads\win8b\src\winjs_base.js`（1 283 526 B） |
| S5 | WinJS 4.4.3 真源码 `ui.js` | <https://unpkg.com/winjs@4.4.3/js/ui.js> | `C:\Users\33947\Downloads\win8b\src\winjs_ui.js`（3 047 121 B） |
| S6 | WinJS 4.4.3 `ui-light.css`（@keyframes 定义） | <https://unpkg.com/winjs@4.4.3/css/ui-light.css> | `C:\Users\33947\Downloads\win8b\src\ui-light.css`（268 767 B） |
| S7 | `WinJS.UI.Animation.enterPage` 官方文档 | <https://learn.microsoft.com/en-us/previous-versions/windows/apps/br212672(v=win.10)> | 本次 `web_fetch` |
| S8 | 3-D perspective effects for XAML UI（Win8 官方） | <https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh700359(v=win.10)> | 本次 `web_fetch` |
| S9 | `PerspectiveTransform3D.Depth`（Windows App SDK / WinUI3） | <https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.media.media3d.perspectivetransform3d.depth> | 本次核对 |
| M1 | 实拍素材：bilibili《windows8.1的动画有多好》 | <https://www.bilibili.com/video/BV1Us411f7bc>（页面标题由 `document.title` 确认） | 1280×720，345.813 s，**30 fps**（实测，见 §4.1） |
| M2 | 我抓的 100 帧 PNG（t=196.300 → 199.600 s，1/30 s 一帧） | — | `C:\Users\33947\Downloads\win8b\frames\p_0196300.png … p_0199600.png` |
| M3 | 量测脚本 | — | `docs\win8-measure-b.py` |
| M4 | 量测原始输出（608 行） | — | `C:\Users\33947\Downloads\win8b\measure-out.txt` |

**⚠️ 原始 zip 已失效**：S3 页面挂的 `TiltEffectRtm.zip`（`https://msdnshared.blob.core.windows.net/media/MSDNBlogsFS/prod.evol.blogs.msdn.com/CommunityServer.Components.PostAttachments/00/10/04/87/67/TiltEffectRtm.zip`）现在返回
```
HTTP 403  <Code>AuthenticationFailed</Code>
<Message>Server failed to authenticate the request. ... This request is not authorized to perform this operation.</Message>
```
所以 S1/S2 的来源是**二手转载**。我做了两层防护：(a) 两个互不相干的转载者；(b) 逐字比对，**四个常量、静态构造函数、公式 12 行完全相同**，连注释一字不差。任何一处被改动过都会在比对里露出来。

**⚠️ S3 本身的局限**：Peter Torr 那篇博客正文里**一个常量数字都没有**。它讲的是全局相机技巧（`projection.GlobalOffsetX = -1 * centerDelta.X;` / `GlobalOffsetY`，再用 `RenderTransform` 把控件平移到屏幕中心），并且它是 **Windows Phone 7 / Silverlight** 时代的文章（`TiltEffectRtm.zip`，评论日期 2010-08/09）。所以"常量来自 Torr 博客"这个说法**不成立**；常量只来自示例源码本体。

---

## 2. 逐条核对 Lead 转述的 A 组结论

### 2.1 四个常量 —— **证实**（但有来源强度的保留）

S1 `C:\Users\33947\Downloads\win8b\src\cnblogs_tilt.txt` **逐字原文**（行号为该文件行号）：

```csharp
343:        // These constants are the same as the built-in effects
348:        /// Maximum amount of tilt, in radians
352:        const double MaxAngle = 0.3;
357:        /// Maximum amount of depression, in pixels
361:        const double MaxDepression = 25;
366:        /// Delay between releasing an element and the tilt release animation playing
370:        static readonly TimeSpan TiltReturnAnimationDelay = TimeSpan.FromMilliseconds(200);
375:        /// Duration of tilt release animation
379:        static readonly TimeSpan TiltReturnAnimationDuration = TimeSpan.FromMilliseconds(100);
```

S2（<https://www.cnblogs.com/grj1046/p/3291378.html>，本次抓取）逐字给出**完全相同**的 8 行（含注释文字），另外两处也一样：

```csharp
static TiltEffect() {
    TiltableItems = new List<Type>() { typeof(ButtonBase), typeof(ListBoxItem), };
    UseLogarithmicEase = false;
}
```

> **结论：`MaxAngle = 0.3`、`MaxDepression = 25`、`TiltReturnAnimationDelay = 200 ms`、`TiltReturnAnimationDuration = 100 ms` 四个常量，两个独立来源逐字一致 → 证实。**
> **A 组这四条我没法推翻。**

### 2.2 `MaxAngle` 的单位 —— **弧度，不是度**（A 组说法正确）

两处证据：

1. 注释本身：`348: /// Maximum amount of tilt, in radians`（S1/S2 都有）。
2. **代码里的换算** —— 这是决定性的。S1 行 1252：

```csharp
1252:        double angle = angleMagnitude * MaxAngle * 180 / Math.PI;
```

`MaxAngle` 被 `× 180 / π` 转成**度**之后才赋给 `PlaneProjection.RotationX/Y`（后者单位是度）。如果 `MaxAngle` 本身是度，这个换算就是错的。

**⇒ `MaxAngle = 0.3 rad = 17.18873°`。A 组说的 17.1887° 正确。**

（`angleMagnitude = xMag + yMag` 的最大值是 1.0，在磁贴**角**上取到 —— 中间按下反而是最小的。所以 17.1887° 是**上限**，不是常态。）

### 2.3 "按下是否真的不带动画" —— **证实：按下没有动画**

调用链（S1 行号）：

```
819:  static void BeginTiltEffect(FrameworkElement element, Point touchPoint, Point centerPoint, Point centerDelta)
       ...
837:      ApplyTiltEffect(currentTiltElement, touchPoint, currentTiltElementCenter);   ← 直接调用
```

`BeginTiltEffect` 里**唯一**和动画沾边的是 `PrepareTiltReturnStoryboard(element)`（S1:835，只做"创建/绑定"），之后**没有 `storyboard.Begin()`**，而是直接调 `ApplyTiltEffect`。

而 `ApplyTiltEffect`（S1:1222）最后三行是**直接赋值**：

```csharp
1266:        projection.RotationY = angle * xAngleContribution * xDirection;
1268:        projection.RotationX = angle * (1 - xAngleContribution) * yDirection;
1270:        projection.GlobalOffsetZ = -depression;
```

`DoubleAnimation` 只作用于**回弹**（`To = 0`），从未用于按下。

拖动时同理，`ContinueTiltEffect`（S1:1019）每一帧调 `ApplyTiltEffect`（S1:1045），也是瞬时赋值。

**⇒ 按下 = 0 ms 瞬时到位。A 组说法证实。**

### 2.4 松开时序 —— **证实：200 ms 延迟 + 100 ms 动画**

`EndTiltEffect`（S1:1058）：

```csharp
1058:   static void EndTiltEffect(FrameworkElement element)
        ...
1066:       element.ManipulationCompleted -= TiltEffect_ManipulationCompleted;
1068:       element.ManipulationDelta    -= TiltEffect_ManipulationDelta;
1076:       wasPauseAnimation = false;
1078:       if (tiltReturnStoryboard.GetCurrentState() != ClockState.Active)
1080:           tiltReturnStoryboard.Begin();
```

三个 `DoubleAnimation`（S1:948/958/968 创建）统一设置：

```
952/962/972:  tiltReturn{X,Y,Z}Animation.BeginTime = TiltReturnAnimationDelay;   // 200 ms
954/964/974:  tiltReturn{X,Y,Z}Animation.To       = 0;
956/966/976:  tiltReturn{X,Y,Z}Animation.Duration = TiltReturnAnimationDuration; // 100 ms
```

`RotationX`、`RotationY`、`GlobalOffsetZ` 三条**同时**开始、同时结束 → 三轴一起回。

**⇒ 松开 = 200 ms 延迟，然后 100 ms 内三轴同步回到 0。A 组说法证实。**

### 2.5 回弹是不是线性 —— **证实是线性**（但有一个开关）

S1 行 978：

```csharp
978:        if (UseLogarithmicEase)
979:        {
980:            tiltReturnXAnimation.EasingFunction = new LogarithmicEase();
981:            ...
```

`UseLogarithmicEase` 的默认值在静态构造函数里（S1:335）：`UseLogarithmicEase = false;`

`false` 时三个 `DoubleAnimation` **不设任何 `EasingFunction`**，WPF/Silverlight 的默认插值就是线性。

对数缓动本体（S1 末尾 `LogarithmicEase` 类）：

```csharp
protected override double EaseInCore(double normalizedTime)
{
    return Math.Log(normalizedTime + 1) / 0.693147181;   // ln(t+1)/ln(2)
}
```

**⇒ 默认线性回弹，证实。** 但是要注意：**这个"线性"是"没有设置缓动函数"的默认结果**，不是显式写 `EasingMode.Linear`。如果哪天有人打开 `UseLogarithmicEase`（`log` 是**凹**函数，起步快、收尾慢），观感会明显不同。

### 2.6 角度分配公式与符号 —— **证实，12 行逐字一致**

S1 行 1232–1270 **逐字**：

```csharp
1232:        Point normalizedPoint = new Point(
1234:            Math.Min(Math.Max(touchPoint.X / (centerPoint.X * 2), 0), 1),
1235:            Math.Min(Math.Max(touchPoint.Y / (centerPoint.Y * 2), 0), 1));

1240:        double xMagnitude = Math.Abs(normalizedPoint.X - 0.5);
1242:        double yMagnitude = Math.Abs(normalizedPoint.Y - 0.5);
1244:        double xDirection = -Math.Sign(normalizedPoint.X - 0.5);
1246:        double yDirection =  Math.Sign(normalizedPoint.Y - 0.5);
1248:        double angleMagnitude = xMagnitude + yMagnitude;
1250:        double xAngleContribution = xMagnitude + yMagnitude > 0 ? xMagnitude / (xMagnitude + yMagnitude) : 0;

1252:        double angle = angleMagnitude * MaxAngle * 180 / Math.PI;
1254:        double depression = (1 - angleMagnitude) * MaxDepression;

1266:        projection.RotationY     = angle * xAngleContribution * xDirection;
1268:        projection.RotationX     = angle * (1 - xAngleContribution) * yDirection;
1270:        projection.GlobalOffsetZ = -depression;
```

对照 Lead 转述的 A 组公式：

| A 组写法 | 源码 | 判定 |
|---|---|---|
| `xMag=\|nx−0.5\|` | 1240 | ✅ |
| `yMag=\|ny−0.5\|` | 1242 | ✅ |
| `angleMag=xMag+yMag` | 1248 | ✅ |
| `RotationY = angle·(xMag/(xMag+yMag))·(−sign(nx−0.5))` | `xAngleContribution`(1250) `= xMag/(xMag+yMag)`，`xDirection = -Sign(...)`(1244)，1266 | ✅ |
| `RotationX = angle·(yMag/(xMag+yMag))·(+sign(ny−0.5))` | `1 - xAngleContribution` 恒等于 `yMag/(xMag+yMag)`；`yDirection = +Sign(...)`(1246)；1268 | ✅ |
| `GlobalOffsetZ = −(1−angleMag)·25` | 1254 + 1270（`-depression`，`MaxDepression=25`） | ✅ |

**"互补混合"这条结论也证实**：`xAngleContribution` 与 `1-xAngleContribution` 严格互补，两者之和恒为 1。所以总旋转角 `angle` 是**固定的**（由 `angleMag` 决定），只是在 X / Y 两轴之间按 `xMag : yMag` 分配。

三条边界行为（源码里有，A 组没提，我补上）：

- **正中心按下**：`xMag = yMag = 0` ⇒ `angle = 0`、`xAngleContribution = 0`（1250 的三元分支就是防 0/0）、`depression = 25` ⇒ **只下陷 25 px，完全不旋转**。
- **磁贴角上按下**：`xMag = yMag = 0.5` ⇒ `angleMag = 1`、`angle = 17.1887°`、两轴各 `8.594°`、`depression = 0` ⇒ **转得最多，但一点不下陷**。
- **超过 1 的归一化坐标**被 1234/1235 的 `Math.Min(Math.Max(...,0),1)` 夹住。

**符号方向（观感）**：按压点在**左半边** ⇒ `nx-0.5 < 0` ⇒ `Sign = -1` ⇒ `xDirection = +1` ⇒ `RotationY > 0`。Y 轴正旋转把**右边**推向观察者（左边后退）。同理按压点在上半边 ⇒ `yDirection = +1` ⇒ `RotationX > 0`，X 轴正旋转把**下边**推向观察者（上边后退）。

⇒ **被按下的那个角陷进去，对角翘起来** —— 和"手指把磁贴按下去"的直觉一致。这是 `xDirection` 取负、`yDirection` 取正这一不对称写法换来的。

> **关于"符号方向"的证据强度**：`RotationX/RotationY > 0` 到底往哪边转，我是从 S8（[3-D perspective effects for XAML UI](https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh700359(v=win.10))）的图示说明推出来的（"The image is rotated backward 35 degrees around the x-axis" 对应 `RotationX="-35"`），**上文的"哪边翘起"这句标 `[推测]`**；但 `xDirection` 取负、`yDirection` 取正**这两行代码本身标 `[文档]`**。

### 2.7 转轴与旋转中心

S8 官方文档逐字：

> The default values for **CenterOfRotationX** and **CenterOfRotationY** are 0.5, and the default value for **CenterOfRotationZ** is 0.

示例代码只设 `GlobalOffsetX/Y`（`PrepareControlForTilt`），**从不设** `CenterOfRotationX/Y/Z` ⇒ 全部用默认：

- 绕控件**自身几何中心**（0.5, 0.5）转
- 但**不是**绕磁贴自己的中心 —— 见下面的相机技巧

**相机技巧**（`PrepareControlForTilt`，S1）：把磁贴的 `RenderTransform` 平移 `(centerDelta.X, centerDelta.Y)`（磁贴中心 − 容器中心），同时把投影的 `GlobalOffsetX/Y` 反向补偿 `-1 * centerDelta.X/Y`。净效果是：**视觉上磁贴没动，但旋转中心变成了"整个容器（= 整屏）的中心"**。

> 这一条很关键：**如果你照抄公式但把 `GlobalOffsetX/Y` 漏了，磁贴会绕自己中心转；照抄全了，磁贴是绕屏幕中心"整体倾斜"，每个磁贴的透视线汇聚到同一个灭点。** 后者才是 Win8 磁贴按压的观感来源。

### 2.8 缩放（scale）—— Tilt 效果里**没有**

我在 `ApplyTiltEffect`（S1:1222–1270）里搜过：只有 `RotationY`、`RotationX`、`GlobalOffsetZ` 三个赋值，**没有任何 `ScaleX/Y/Z`**。`PrepareControlForTilt` 里也只建 `TranslateTransform` + `PlaneProjection`。

**⇒ 磁贴按压的"缩小感"完全来自 3D 透视本身（近大远小），不是显式 scale。** 如果要不用 3D 模拟这个观感，等效 scale 大约 = 透视投影后的边长比，随透视距离变化，不是一个常数。

（对比：WinJS 列表动画里**确实**有显式 scale，见 §3.3 —— 那是另一套动画，别混。）

### 2.9 透视强度 / `PerspectiveDistance`

**结论：Win8/WinRT 的 `PlaneProjection` 没有"透视强度"这个属性。`PerspectiveDistance` 在 WinRT 与 Silverlight 的 `PlaneProjection` 上都不存在。**

我核对过的属性全表（`Windows.UI.Xaml.Media.PlaneProjection`，WinRT）：
`CenterOfRotationX/Y/Z`、`GlobalOffsetX/Y/Z`、`LocalOffsetX/Y/Z`、`ProjectionMatrix`、`RotationX/Y/Z` —— **没有 `PerspectiveDistance`**。
Silverlight 版（`System.Windows.Media.PlaneProjection`）属性表**完全一样**，同样没有。

`PerspectiveDistance` 这个名字在**同时代的** Win8/WinRT XAML 里**未能证实**存在。我找到的、真正带默认值的邻近 API 是**另一个世代**的：

- `Microsoft.UI.Xaml.Media.Media3D.PerspectiveTransform3D.Depth`（Windows App SDK / WinUI3），官方文档逐字：**"The default value is 1000."** —— 来源 S9。这是 WinUI3 的 API，**不能直接说 Win8 也是 1000**。

Win8 时代想做"完整的透视矩阵"，官方口径是 S8 原文：

> **Matrix3DProjection** provides you with a complete 3D transform matrix to apply to any **UIElement**, so that you can apply arbitrary model transformation matrices and perspective matrices to elements. Keep in mind that these API are minimal and therefore if you use them, you will need to write the code that correctly creates the 3D transform matrices.

⇒ **`PlaneProjection` 的透视距离由 XAML 内部固定，不对外开放。** 如果你的实现（Android / CSS）需要选一个透视值：
- CSS 里对应 `perspective`，其**初始值是 `none`**（正交投影，完全没有透视）—— 必须显式给值。
- 想要 Win8 观感，我**没有** Win8 的官方数字，这是个**未能证实**项。请以 `PlaneProjection` 的默认观感为准去调，或按 §5 的实测标定。
- 标 `[推测]`：微软在 WinUI3 里给出的 `Depth = 1000`，与 Win8 时代 XAML 的默认透视距离**很可能是同一个量级**，但我找不到 Win8 时代的文档确认。

---

## 3. WinJS 4.4.3 真源码核对（Lead 问题 3 与 4）

⚠️ **先讲一个重要的坑**：Lead 问题 4 说 `enterPage`/`exitPage`/`enterContent`/`exitContent` 也在 `ui.js` 里 —— **不对，这四个在 `base.js` 里**（`WinJS.UI.Animation` 命名空间定义在 `winjs_base.js`，`ui.js` 里只有 `WinJS.UI` 控件）。我在 `winjs_ui.js` 里 grep `animationsFromOffset`、`enterPage` 等**零命中**。所有下面的行号都指 **`winjs_base.js`**。

### 3.1 `enterPage`（winjs_base.js:17726）

```javascript
17726:  enterPage: function (element, offset) {
17750:      var offsetArray = new OffsetArray(offset, "WinJS-enterPage", [{ top: "28px", left: "0px", rtlflip: false }]);
17751:      var promise1 = _TransitionAnimation.executeAnimation(
17752:          element,
17753:          {
17754:              keyframe: offsetArray.keyframe,
17755:              property: transformNames.cssName,
17756:              delay: staggerDelay(0, 83, 1, 333),
17757:              duration: 1000,
17758:              timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",
17759:              from: offsetArray.keyframe || translateCallback(offsetArray),
17760:              to: "none"
17761:          });
17762:      var promise2 = _TransitionAnimation.executeTransition(
17763:          element,
17764:          {
17765:              property: "opacity",
17766:              delay: staggerDelay(0, 83, 1, 333),
17767:              duration: 170,
17768:              timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",
17769:              from: 0,
17770:              to: 1
17771:          });
```

| 量 | 值 | 行号 |
|---|---|---|
| 默认偏移 | **`top: 28px`, `left: 0px`, `rtlflip: false`** | 17750 |
| 位移 duration | **1000 ms** | 17757 |
| 位移 delay | `staggerDelay(0, 83, 1, 333)` → **0 / 83 / 166 / 249 / 332 / 333 / 333 …** | 17756 |
| 位移 timing | **`cubic-bezier(0.1, 0.9, 0.2, 1)`** | 17758 |
| 位移 from/to | `translate(0px, 28px)` → `none` | 17759/17760 |
| 透明度 duration | **170 ms** | 17767 |
| 透明度 delay | 同 stagger | 17766 |
| 透明度 timing | 同 `cubic-bezier(0.1, 0.9, 0.2, 1)` | 17768 |
| 透明度 from/to | 0 → 1 | 17769/17770 |
| keyframe 名 | `WinJS-enterPage` | 17750 |

**⇒ 官方 `enterPage` 的位移是 1000 ms，不是 367 ms！** 只有 stagger 数组元素之间的 delay 上限是 333 ms（见 §3.5），所以**最后一个元素最晚在 333+1000 = 1333 ms 结束**。

官方文档（S7）**不公布任何数字**，只说"Set this parameter to **null** to use the recommended default offset"以及"Designer-recommended translation distance" —— 所以这些值只能从源码拿。S7 另外确认了 `element` 传数组时"the elements enter in array order"（外层数组逐级 stagger，内层数组同时进）。

### 3.2 `exitPage`（winjs_base.js:17776）

```javascript
17800:      var offsetArray = new OffsetArray(offset, "WinJS-exit", [{ top: "0px", left: "0px" }]);
17801:      var promise1 = _TransitionAnimation.executeAnimation(
17802:          outgoing,
17803:          offset && {
17804:              keyframe: offsetArray.keyframe,
17805:              property: transformNames.cssName,
17806:              delay: 0,
17807:              duration: 117,
17808:              timing: "linear",
17809:              from: "none",
17810:              to: offsetArray.keyframe || translateCallback(offsetArray)
17811:          });
17813:      var promise2 = _TransitionAnimation.executeTransition(
17814:          outgoing,
17815:          {
17816:              property: "opacity",
17817:              delay: 0,
17818:              duration: 117,
17819:              timing: "linear",
17820:              to: 0
17821:          });
```

| 量 | 值 | 行号 |
|---|---|---|
| duration | **117 ms**（位移与透明度相同） | 17807 / 17818 |
| delay | **0** | 17806 / 17817 |
| timing | **`linear`** | 17808 / 17819 |
| 默认偏移 | `top: 0px, left: 0px`（即不动，只淡出） | 17800 |
| keyframe | **`WinJS-exit`** —— 内容就是 `from { transform: none } to { transform: none }`，**空动画** | ui-light.css:154-161 |

⚠️ 注意 17803 的 `offset && {...}`：**不传 `offset` 时位移那一路整个不执行**（`executeAnimation` 收到 `false`），只剩 117 ms 淡出。

**⇒ 关键对比：进场 1000 ms + `cubic-bezier(0.1,0.9,0.2,1)`，退场 117 ms + `linear`。进出场严重不对称（1000 : 117 ≈ 8.5 : 1），而且缓动曲线完全不同。** 这一条对"反向是不是严格倒放"的回答是**明确的"不是"**，见 §5。

### 3.3 `enterContent`（winjs_base.js:17398）与 `exitContent`（:17474）

结构与 `enterPage` 几乎相同，**差别只在没有 stagger**：

```javascript
17427:      var offsetArray = new OffsetArray(offset, "WinJS-enterContent", [{ top: "28px", left: "0px", rtlflip: false }]);
17434:              duration: 550,             ← 位移
17435:              timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",
17436:              from: translateCallback(offsetArray),
17437:              to: "none"
17442:              duration: 170,             ← 透明度
17443:              timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",
```

```javascript
17498:      var offsetArray = new OffsetArray(offset, "WinJS-exit", [{ top: "0px", left: "0px" }]);
17505:              duration: 117,  timing: "linear",   ← 位移
17516:              duration: 117,  timing: "linear",   ← 透明度 to: 0
```

| 动画 | 位移 duration | 位移 delay | 透明度 duration | 透明度 delay | timing |
|---|---|---|---|---|---|
| `enterContent` | **550 ms** | 0（17433） | **170 ms** | 0（17441） | `cubic-bezier(0.1, 0.9, 0.2, 1)` |
| `exitContent` | **117 ms** | 0（17504） | **117 ms** | 0（17515） | `linear` |

`enterContent` 有一个 `options.mechanism === "transition"` 分支（17428-17446），走 CSS **transition** 而不是 keyframe animation，但**参数一模一样**（550 / 170 / 同曲线）。

**⇒ 三个进场动画的位移时长各不相同，注意别记混：`enterPage` 1000 ms、`enterContent` 550 ms、`exitPage`/`exitContent` 都是 117 ms。**

### 3.4 `AddToList` / `DeleteFromList`（Lead 问题 3）

`AddToListAnimation.execute`（winjs_base.js:16374）：

```javascript
16376:      var delay = this.affectedArray.length > 0 ? 240 : 0;
16379:          [{
16380:              keyframe: "WinJS-scale-up",
16381:              property: transformNames.cssName,
16382:              delay: delay,
16383:              duration: 120,
16384:              timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",
16385:              from: "scale(0.85)",
16386:              to: "none"
16387:          },
16388:          {
16389:              keyframe: "WinJS-opacity-in",
16391:              property: "opacity",
16392:              delay: delay,
16393:              duration: 120,
16394:              timing: "linear",
16395:              from: 0,
16396:              to: 1
16397:          }]
```

`DeleteFromListAnimation.execute`（:16421）：

```javascript
16426:              keyframe: "WinJS-scale-down",
16428:              delay: 0,
16429:              duration: 120,
16430:              timing: "cubic-bezier(0.11, 0.5, 0.24, .96)",   ← 注意是 .96，没有前导 0
16431:              from: "none",
16432:              to: "scale(0.85)"
16438:              delay: 0,
16439:              duration: 120,
16440:              timing: "linear",                                ← 透明度线性
16448:              delay: this.deletedArray.length > 0 ? 60 : 0,     ← 剩余项位移
16449:              duration: 400,
16450:              timing: "cubic-bezier(0.1, 0.9, 0.2, 1)",
```

| 项 | 值 | 行号 |
|---|---|---|
| 新增项缩放 | `scale(0.85)` → `none`，**120 ms**，`cubic-bezier(0.1,0.9,0.2,1)` | 16383-16386 |
| 新增项透明度 | 0 → 1，**120 ms**，**`linear`** | 16392-16396 |
| 新增项 delay | 有 affected 时 **240 ms**，否则 0 | 16376 |
| 删除项缩放 | `none` → `scale(0.85)`，**120 ms**，`cubic-bezier(0.11, 0.5, 0.24, .96)` | 16428-16432 |
| 删除项透明度 | 1 → 0，**120 ms**，**`linear`** | 16438-16441 |
| 删除项 delay | **0** | 16428 / 16437 |
| 剩余项重排 | delay 有删除时 **60 ms**，否则 0；**400 ms** | 16448 / 16449 |

**⇒ A 组"`addToList`/`deleteFromList`：scale(0.85)、120ms"证实。** 补三点 A 组没说的：
1. **透明度那一路是 `linear`，不是 bezier** —— 只有 scale 那一路用 bezier。
2. **add 的 240 ms delay 和 delete 的 0 delay 不对称**，且 delete 的曲线是另一条（`0.11, 0.5, 0.24, .96`），刻意写成 `.96` 而不是 `0.96`。
3. `AddToList` / `DeleteFromList` 里 `scale(0.85)` 是**显式缩放** —— 别和 §2.8 的"磁贴按压没有 scale"搞混，这是两套动画。

### 3.5 `staggerDelay`（winjs_base.js:16103）

```javascript
function staggerDelay(initialDelay, extraDelay, delayFactor, delayCap) {
    return function (i) {
        var ret = initialDelay;
        for (var j = 0; j < i; j++) {
            extraDelay *= delayFactor;
            ret += extraDelay;
        }
        if (delayCap) { ret = Math.min(ret, delayCap); }
        return ret;
    };
}
```

`staggerDelay(0, 83, 1, 333)` ⇒ `delayFactor = 1` 时 `extraDelay` 不衰减 ⇒ 第 i 个元素的 delay = `min(83i, 333)`：

**i = 0,1,2,3,4,5,6,… ⇒ 0, 83, 166, 249, 332, 333, 333, …**

### 3.6 缓动曲线总表（`cubic-bezier(0.1, 0.9, 0.2, 1)` 与 `cubic-bezier(0.11, 0.5, 0.24, .96)`）

- `cubic-bezier(0.1, 0.9, 0.2, 1)`（**Win8 "标准"缓动**）：在 `winjs_base.js` 里出现 **约 60 次**，覆盖 `enterPage` / `enterContent` / `expand` / `collapse` / `reposition` / `addToList` / `_updateList` 等；`ui.js` 里也有（如 `dragBetweenTransition` 拼出 `... cubic-bezier(0.1, 0.9, 0.2, 1) 167ms`）。
- `cubic-bezier(0.11, 0.5, 0.24, .96)`（**退场缓动**）：在 `winjs_base.js` 里**只有 2 处** —— `16430`（`DeleteFromListAnimation`）与 `16481`（`_UpdateListAnimation` 的删除项）。**`winjs_ui.js` 里 0 处。**
- 我一开始用 `0\.96` 去 grep 一条都没命中，因为源码写的是 `.96`（无前导零）。**这是一个很容易漏的点。**

### 3.7 ⚠️ 全局速度开关（任何人都没提，但会毁掉你的时长）

`winjs_base.js:15851-15871`：

```javascript
15851:              animation.delay    = animationTimeAdjustment(animation.delay);
15852:              animation.duration = animationTimeAdjustment(animation.duration);
15867:      var animationTimeAdjustment = function _animationTimeAdjustmentImpl(v) { return v * animationFactor; };
15871:      var animationFactor = 1;
```

对外暴露的是 `WinJS.UI._animationFactor`（15985）和 `WinJS.Utilities._fastAnimations`：

```javascript
15969:          _fastAnimations: {
15971:              return animationFactor === 1/20;
15974:              animationFactor = value ? 1/20 : 1;    ← 打开后全部时长 ×1/20
```

另外 `16650: var duration = transition.duration * _TransitionAnimation._animationFactor;`（resize 动画自己又乘了一遍）。

**⇒ 上面表里所有数字都是 `animationFactor === 1`（默认）时的值。** `WinJS.Utilities._fastAnimations = true` 会让全部时长变成 **1/20**（1000 ms → 50 ms），这是给测试用的。写规格时务必注明"默认 factor = 1"。

---

## 4. 我自己实测的部分（`[实测]`）

### 4.1 素材与真实帧率

- 素材：bilibili《windows8.1的动画有多好》，页面标题经 `document.title` 确认为 `windows8.1的动画有多好_哔哩哔哩_bilibili`。
- 分辨率 **1280×720**，时长 **345.812833 s**。
- **真实帧率 30 fps**：我在页面里把 `currentTime` 设到 200 s，用 `requestVideoFrameCallback` 在播放中连续采 3 s，得到 **88 帧 / 2.9 s，帧间 `mediaTime` 差实测为 33 / 34 / 33 / 34 … ms** ⇒ 帧间隔 33.33 ms ⇒ **30 fps**。所有 ms 时间轴按 0.0333 s/帧 换算。
- 抓帧通道：页面里 `video.crossOrigin = 'anonymous'`（该 CDN 带 CORS 响应头，我先用 `fetch(v.src,{headers:{Range:'bytes=0-1024'},mode:'cors'})` 拿到 **HTTP 206 + `content-type: video/mp4`** 确认）→ `drawImage` 到 canvas → `toBlob('image/png')` → `POST http://127.0.0.1:8765/?name=…`，由本机一个 stdlib `http.server` 写盘。这样绕开了 canvas 跨域污染（先前直接 `getImageData` 报 `SecurityError: The canvas has been tainted by cross-origin data`）。
- 抓到的关键窗口：**t = 196.300 s → 199.600 s，共 100 帧**（每帧 1/30 s），文件 `p_0196300.png … p_0199600.png`。

**⚠️ 一个必须先讲清的实测限制：本机没有可用的视觉模型**（`modlens_read_image` 返回 `Error: No vision provider is set up on this machine`），我在整个任务里**没有"看"过任何一帧图像的像素**。下面每一个结论都来自 Python/numpy 对 PNG 的数值分析，而不是目视。

### 4.2 量测方法（`docs/win8-measure-b.py`）

1. **背景色**：取参考帧最常出现的量化颜色 ⇒ **BGR = (84, 4, 28)**，即 RGB **(28, 4, 84)**（Win8 默认深紫蓝开始屏底色）。
2. **内容掩膜** `mask = max_channel_abs_diff(pixel, background) > 20`（`BG_TOL = 20`）。逐像素判"是不是背景"。
3. **磁贴发现**：参考帧上做 `connectedComponentsWithStats`，保留 `area ≥ 1500` **且**实心度 `area/(w·h) ≥ 0.50` 的连通块 ⇒ 25 个"实心磁贴"，其余（搜索框、状态栏等）单独记为 screen furniture。
4. **单磁贴形变**（关键两步）：
   - **可见宽度**：在磁贴参考包围盒左右各放宽 24 px 的探测窗里，取**最长的连续内容像素游程** → `width`。占参考宽度的比例 = `width / w0`，**这个比例就是 cosθ**（磁贴在水平方向被投影压缩的系数）。
   - **左右边高**：在包围盒**左边缘列**和**右边缘列**各自的竖直探测窗里，同样取最长连续游程 → `left_h`、`right_h`。**`left_h / right_h`（取 ≥1）就是"近边/远边"比，即透视强度**。
5. **时间轴**：文件名 `p_<7 位毫秒>.png` 直接给出每帧 ms。
6. **参照对比**：所有帧与 `p_0196300.png` 逐像素比，统计 `|Δ| > 60` 的像素数与包围盒。

### 4.3 关键量测结果

**完整输出：`C:\Users\33947\Downloads\win8b\measure-out.txt`（608 行，含 6 张表）。**

**参考帧的磁贴网格**（25 个实心磁贴，单位 px，`x, y, w×h`）：

```
 79,139 168x83   79,310  82x82   79,395  83x84   79,566 168x83
 80,226 166x80   80,482 166x80  164,310  83x83  165,396  39x39
165,438  39x40  207,438  40x40  249,481  83x82  250,140 167x167
250,310  82x82  250,395  82x83  250,566 167x82  335,353  40x40
335,396  82x82  336,482  80x80  377,310  40x40  453,310  82x82
453,396  82x81  453,481  82x82  537,310  83x83  538,396  82x82
1164,72   65x91
```

实测网格：**中磁贴 ≈ 82×82，宽磁贴 = 168×83，大磁贴 = 167×167，小磁贴 = 39~40 px**；相邻列 `x = 79/164/249/335/453/537`，行 `y = 139/226/310/395/438/482/566`，**行列间距 ≈ 85 px**。
`[推测]` 1280/1920 = 2/3，若素材是 1920×1080 录屏按 2/3 缩到 720p，则真实中磁贴 = 82 ÷ (2/3) ≈ **123 px**、节距 ≈ **127.5 px** —— 与 Win8.1 的 120 px 中磁贴 + 间距大致吻合，但我**没有**独立证据证明录制分辨率，故标 `[推测]`。

**TABLE 6 —— 判定用的关键表**（每帧取"所有实心磁贴里最极端的形变"）：

| t (ms) | 整屏 `px>60` | 可测磁贴数 | **最小宽度比** | **最大近/远比** |
|---|---|---|---|---|
| 196300 | 0 | 25 | **1.0000** | 1.0123 |
| 196367 | 0 | 25 | **1.0000** | 1.0123 |
| 196433 | 0 | 25 | **1.0000** | 1.0123 |
| 196467 | 5342 | 24 | **1.0000** | 1.0123 |
| 196600 | 5356 | 24 | **1.0000** | 1.0123 |
| 196733 | 5360 | 24 | 0.9878 | 1.0123 |
| 196833 | 5372 | 24 | 1.0000 | 1.0250 |
| 196900 | 5348 | 24 | 0.9880 | 1.0123 |
| 197100 | 5356 | 24 | 0.9880 | 1.0123 |
| 197400 | 5351 | 24 | 0.9880 | 1.0123 |
| 197633 | 5358 | 24 | 0.9880 | 1.0123 |
| **197700** | **5569** | **24** | **0.9880** | **1.0123** |
| **197733** | **235862** | 22 | **0.1190** | 2.0769 |
| 197767 | 273275 | 9 | 0.2651 | 1.0123 |
| 197800 | 280794 | 2 | 0.8512 | 1.0000 |
| 197867 | 271031 | **0** | n/a | n/a |
| 197900 | 241339 | 12 | 0.0476 | 2.7727 |
| 197967 | 254249 | 16 | 0.0976 | 7.0000 |
| 198100 | 254136 | 12 | 0.0838 | 1.0741 |
| 198600 | 267716 | 13 | 0.0714 | 1.3333 |
| 199600 | — | — | — | — |

**读法：**

1. **t = 196.300 → 197.700（共 43 帧、1.433 s）**：整屏变化像素恒定在 **5346~5374**（占 921 600 px 的 **0.58%**），也就是只有鼠标和一个小方块在动；**25 个磁贴的宽度比始终是 1.0000，只有 0.9880 这一个读数反复出现**。0.9880 = 81/82 = **1 个像素**，来自边缘抗锯齿跨过 `BG_TOL=20` 阈值（同一磁贴在相邻帧 1.0000/0.9880 之间来回跳，正说明是量化抖动而非形变）。近/远比全程 **1.0123~1.0250**，即 **≈1%**，量级就是噪声。
2. **t = 197.733** ：整屏变化像素从 5569 直接跳到 **235862**，再跳到 273275、280794 —— **整屏级别的硬切**，可测磁贴数从 24 掉到 22 → 9 → 2 → 0。
3. 切换后（t ≥ 197.900）画面变成**满屏小尺寸彩色块**（`view` 渲染显示为规则网格上的 ~25 px 小块），完全不是"应用内容"。
4. `TABLE 2` 的屏幕级内容框在 t ≥ 198.967 后稳定在 **0.108~0.118**（12.9 万~16 万像素级），说明切过去的是一个**低内容密度**的满屏网格。

### 4.4 实测结论

> **在这段素材里，磁贴按下后 1.4 秒内磁贴自身一帧都没有变形**（宽度比恒 1.0000，最坏 0.9880 = 1 px；近/远比 ≈1%），**随后整屏一次性切换**，没有任何"磁贴先转、再飞出去"的可测痕迹。

因此：

- **A 组"按下瞬时、无动画"这一条，我用实拍素材间接支持了**：实拍里连"按下时磁贴转起来"的画面都没有。但**这不能证明 Win8 官方行为就是"完全不转"** —— 只能说**这段录像没有记录下来**。可能原因（`[推测]`）：录像者用的是键盘/鼠标点击（点击不触发 Tilt；Tilt 由 `ManipulationStarted` 触摸事件驱动），或者录屏软件丢帧，或者录的就是"点击直接启动"的路径。
- **另一个发现值得汇报**：录像里那次按下之后是**整屏硬切 + 切到满屏小块的界面**，这更像 Win8.1 开始屏幕的**语义缩放（semantic zoom）或页面级切换**，而不是"应用启动后磁贴放大飞走"。`[推测]`：这可能就是 Win8.1 的 `enterPage`（1000 ms 位移 + 170 ms 淡入）在实拍里的样子，但我**没有**做定量比对，不下结论。
- **对实现者的直接影响**：**不要指望从实拍素材标定这个动画**。磁贴按压动画的可靠数字只有 §2 的官方源码。要标定透视强度，只能靠 §2.9 的说明或自己搭一个 Win8 环境实测。

**关于脚本自动打印的 `VERDICT` 一行**（`VERDICT: measurable tile deformation is present -- see the tables above.`，`max near/far = 7.0000`、`max yaw = 87.27°`）：**这一行是误导，请不要引用。** 它是脚本对"全部帧"取最极端值得到的，而那些极端值**全部落在 t ≥ 197.733 的整屏切换帧**上 —— 那时画面内容已经被换掉了，所谓"形变"是内容替换的假象，不是磁贴旋转。**按压窗口（196.300~197.700）的真正结论是上面的 §4.4。** 这一点我在报告里明确纠正，以免 Lead 误用。

---

## 5. 汇总：磁贴点击/按压动画的完整参数（我能证实的部分）

### 5.1 正向（按下）

| 项目 | 值 | 标签 |
|---|---|---|
| 持续时间 | **0 ms（瞬时跳变）** | `[文档]` S1:819→837 |
| 起始条件 | `ManipulationStarted` 命中 `ButtonBase` / `ListBoxItem`（`TiltableItems`，S1:328） | `[文档]` |
| 驱动方式 | 直接写 `PlaneProjection.RotationX/Y/GlobalOffsetZ`，**不用 Storyboard** | `[文档]` S1:1266-1270 |
| 旋转中心 | `CenterOfRotation` 默认 (0.5, 0.5, 0)，**外加**相机技巧把视觉旋转中心挪到容器中心 | `[文档]` S8 + S1 `PrepareControlForTilt` |
| 转轴 | X 轴 + Y 轴，**无 Z 轴旋转** | `[文档]` S1:1266/1268 |
| 最大总转角 | `0.3 rad × 180/π = 17.18873°`（在磁贴角上） | `[文档]` S1:352+1252 |
| 中心按下 | 0°（只下陷） | `[文档]` S1:1248-1254 |
| 角上按下 | 17.1887°，X/Y 各 8.594°（不下陷） | `[文档]` |
| 缩放 | **无** | `[文档]` S1:1222-1270 |
| Z 位移 | `GlobalOffsetZ = -(1 - (xMag+yMag)) × 25` px（中心 −25，角上 0） | `[文档]` S1:361/1254/1270 |
| 缓动 | 不适用（瞬时） | `[文档]` |

### 5.2 反向（松开）

| 项目 | 值 | 标签 |
|---|---|---|
| 延迟 | **200 ms**（`BeginTime`） | `[文档]` S1:370/952/962/972 |
| 时长 | **100 ms** | `[文档]` S1:379/956/966/976 |
| 缓动 | **线性**（默认 `UseLogarithmicEase = false`，不设 `EasingFunction`） | `[文档]` S1:335/978 |
| 可切换缓动 | `ln(t+1)/ln(2)`（凹，起步快收尾慢） | `[文档]` S1 `LogarithmicEase` |
| 动画属性 | `RotationX`、`RotationY`、`GlobalOffsetZ` **三条同时**开始、同时结束 | `[文档]` S1:948-976 |
| 终点 | `To = 0` ⇒ RotationX=0、RotationY=0、GlobalOffsetZ=0（**恒等变换 = 原状**） | `[文档]` S1:954/964/974 |
| 结束动作 | 动画完成后 `StopTiltReturnStoryboardAndCleanup()` 卸载事件、`Projection = null`、`RenderTransform = null` | `[文档]` S1:1174 `RevertPrepareControlForTilt` |

### 5.3 反向是不是正向的严格倒放？—— **明确不是**

三个独立理由：

1. **时长**：正向 0 ms，反向 200 ms 延迟 + 100 ms 动画。
2. **曲线**：正向没有曲线（瞬时赋值的跳变）；反向是 100 ms 线性插值。
3. **额外分支**（正向没有对应物）：
   - **暂停分支**：手指移到元素**外面**时 `PauseTiltEffect()`（S1:1174）会**停止并重新 Begin** 回弹 storyboard，把 `wasPauseAnimation = true`；完成后 `TiltReturnStoryboard_Completed` 走 `ResetTiltEffect`（把三轴清 0）而**不是**拆设备（S1:1174-1205）。也就是"手指还按着但滑出去了" → 磁贴自己弹回原位，但效果仍然挂着。
   - **`wasPauseAnimation = false;`**（S1:1076）在 `EndTiltEffect` 里把暂停标志清掉。
   - `if (tiltReturnStoryboard.GetCurrentState() != ClockState.Active)`（S1:1078）：**如果回弹已经在放，就不重新 Begin**。
   - `ApplyTiltEffect` 开头 `ResetTiltReturnStoryboard()`（S1:1228）：**任何一次新的按下/拖动都会打断正在进行的回弹**（`Stop()` + `wasPauseAnimation = false`）。

> **给实现者的可执行结论**：正向用 `setTransform` 直接跳（0 ms），反向用 `animateTransform(…, 200 ms delay, 100 ms, LinearInterpolator)` 起三条并行动画。**不要**用"正向 tween 反向 reversed"的写法 —— 那会得到 0 ms 的反向或 1000 ms 的正向，都是错的。

### 5.4 顶层的"点磁贴 → 应用启动"页面动画（Lead 问题 4）

| 环节 | 位移 | 透明度 | 标签 |
|---|---|---|---|
| 新页面进场 `enterPage` | from `translate(0, 28px)` → `none`，**1000 ms**，delay `min(83i, 333)`，`cubic-bezier(0.1, 0.9, 0.2, 1)` | 0 → 1，**170 ms**，同曲线同 delay | `[文档]` base.js:17750-17770 |
| 内容进场 `enterContent` | from `translate(0, 28px)` → `none`，**550 ms**，delay 0，同曲线 | 0 → 1，**170 ms**，同曲线 | `[文档]` base.js:17427-17445 |
| 页面退场 `exitPage` | `none` → 目标偏移，**117 ms**，**`linear`** | → 0，**117 ms**，`linear` | `[文档]` base.js:17800-17820 |
| 内容退场 `exitContent` | 同上 | 同上 | `[文档]` base.js:17498-17518 |
| 默认偏移 | `{ top: "28px", left: "0px", rtlflip: false }` | — | `[文档]` base.js:17427 / 17750 |
| `exit` 的 keyframe | `WinJS-exit` = `from { transform: none } to { transform: none }`（**空**） | — | `[文档]` ui-light.css:154-161 |
| `enterPage`/`enterContent` 的 keyframe | `from { transform: translateY(28px) } to { transform: none }` | — | `[文档]` ui-light.css:146-153 / 162-169 |

**⇒ 请求的四个动画，逐字参数已全部抄出，见 §3.1–§3.3。**

---

## 6. 与 A 组 `docs/win8-tile-spec.md` 的差异清单

> 本节按任务约定在最后阅读 A 的规格后写成。**只列差异与需修正项，不复述一致项。**
> 我读到的 A 规格是 **773 行**版本（含后来补的 §10 `turnstile`）。
> 下文每条都给了**可复核的动作**（在哪一行、跑什么能验证）。

### 6.1 【必须修正】A §6.1 / §6.2 的贝塞尔输出表整体算错 —— 直接影响手感调参

A §6.1 用「与浏览器相同的三次贝塞尔求解（`win8-measure.py` 的 `bezier_y_for_x()`）」给出招牌曲线的输出进度表。
我用**浏览器自己的缓动引擎**实测同一曲线：`element.animate([{opacity:'0'},{opacity:'1'}], {duration:1000, easing:'cubic-bezier(...)', fill:'both'})` → `pause()` → 设 `currentTime` → 读 `getComputedStyle(el).opacity`（Chrome，本会话实测）：

**招牌曲线 `cubic-bezier(0.1, 0.9, 0.2, 1)`**

| 时间进度 | **浏览器实测** | A §6.1 | 偏差 |
|---|---|---|---|
| 0% | 0.0000 | 0.0000 | 0 |
| 10% | **0.5853** | 0.2497 | **+0.3356** |
| 20% | **0.7944** | 0.4643 | **+0.3301** |
| 30% | **0.8885** | 0.6389 | **+0.2496** |
| 40% | **0.9381** | 0.7764 | +0.1617 |
| 50% | **0.9662** | 0.8761 | +0.0901 |
| 60% | **0.9826** | 0.9416 | +0.0410 |
| 70% | **0.9920** | 0.9793 | +0.0127 |
| 80% | **0.9970** | 0.9964 | +0.0006 |
| 90% | **0.9994** | 0.9998 | −0.0004 |
| 100% | 1.0000 | 1.0000 | 0 |

**退场曲线 `cubic-bezier(0.11, 0.5, 0.24, .96)`**

| 时间进度 | **浏览器实测** | A §6.2 | 偏差 |
|---|---|---|---|
| 10% | **0.3736** | 0.1160 | **+0.2576** |
| 20% | **0.5901** | 0.2495 | **+0.3406** |
| 30% | **0.7242** | 0.3925 | **+0.3317** |
| 40% | **0.8145** | 0.5360 | +0.2785 |
| 50% | **0.8779** | 0.6698 | +0.2081 |
| 60% | **0.9232** | 0.7852 | +0.1380 |
| 70% | **0.9553** | 0.8773 | +0.0780 |
| 80% | **0.9776** | 0.9441 | +0.0335 |
| 90% | **0.9920** | 0.9860 | +0.0060 |

**这不是定义差异，是算错。** 我另用 Python 独立实现同一三次贝塞尔（对 `x(s)=p` 解 `s` 再取 `y(s)`，与浏览器实现互不相干）复核：20% 处得 **0.7945**、50% 处得 **0.9662**，与浏览器一致到 ±0.0001。

**后果（A 的结论句要改）**：A §6.1 正文写「前 20% 的时间就走完 **46.4%** 的行程，一半时间走完 **87.6%**」——
正确值是「前 20% 走完 **79.4%**，一半时间走完 **96.6%**」。这条曲线比 A 描述的**激进得多**。
**按 A 的表去配插值器会做出明显偏慢、且前后段比例错误的手感。** 请以本表（浏览器实测）为准。

> 建议的复核动作：在浏览器控制台跑一次上面的 `element.animate` 采样，10 秒可复现。
> 建议的实现：Android 平台自带 `android.view.animation.PathInterpolator(0.1f, 0.9f, 0.2f, 1f)` 就是这条曲线，无需自写。

### 6.2 【必须修正】A §1.3 里 `expand` / `collapse` 的行号是错的（且与 A 自己 §1.2 冲突）

A §1.3 表与 §1.4 的代码注释都写 `expand` = `base.js` **L16740**、`collapse` = **L16763**。

实测（同一下载的 `winjs_base.js`，26 830 行）：

| 行号 | 内容 | 性质 |
|---|---|---|
| **16740** | `createExpandAnimation: function (revealed, affected) {` | 命名空间里的**工厂函数** |
| **16763** | `createCollapseAnimation: function (hidden, affected) {` | 同上 |
| 16738 | `_Base.Namespace._moduleDefine(exports, "WinJS.UI.Animation", {` | 上面两个的宿主模块 |
| **16273** | `var ExpandAnimation = _Base.Class.define(function ExpandAnimation_ctor(...)` | **真正的实现类** |
| **16279** | `execute: function () {` | expand 的实现体起点 |
| **16309** | `var CollapseAnimation = _Base.Class.define(...)` | **真正的实现类** |
| **16315** | `execute: function () {` | collapse 的实现体起点 |

A 引的**数值全对**（`delay: this.affectedArray.length > 0 ? 200 : 0` / `duration: 167` / 位移 `367` / collapse 位移 `delay: this.hiddenArray.length > 0 ? 167 : 0`），**只有行号错**。

**而且 A 自己 §1.2 的曲线行号清单写的是 16288 / 16299（expand 的两条 timing）与 16324 / 16335（collapse）——与 §1.3 的 16740 / 16763 直接冲突。§1.2 是对的。**
**引用时请写 `base.js:16273`（expand）/ `base.js:16309`（collapse）。**

### 6.3 【必须修正】A §5.2 透视表：同一个 k 在同一份文档里给出两个不同的近/远比

A §5.2 的公式（我复核**公式本身正确**）：

```
h_near / h_far = (k + sin(θ)/2) / (k − sin(θ)/2)        k = P / W
```

按 A 自己的这个公式逐格重算，A 的表有 **6 处不符**：

| k | θ | **按公式应为** | A §5.2 给 | 判定 |
|---|---|---|---|---|
| 0.500 | 17.1887° | **1.8390** | 1.3576 | **错** |
| 0.655 | 17.1887° | **1.5826** | 1.2681 | **错** |
| 3.000 | 4° | **1.0235** | 1.0116 | **错** |
| 3.000 | 8.594° | **1.0511** | 1.0247 | **错** |
| 3.000 | 12° | **1.0718** | 1.0348 | **错** |
| 1.000 / 2.000 / 3.000 / 4.000 / 5.000 / 8.000 | 17.1887° | 1.3467 / 1.1596 / 1.1036 / 1.0767 / 1.0609 / 1.0376 | 1.3467 / 1.1596 / 1.1037 / 1.0767 / 1.0609 / 1.0377 | **对** |

两个独立的证据说明问题不在我这边：

1. **自相矛盾**：A §5.3 的表里写「**1.582（本项目现状）⇒ 需要 k = 0.6555**」。可 §5.2 的表在 k = 0.655 那一行给的是 **1.2681**。同一个 k，两处 r 不同。按公式，k = 0.655 时 r = **1.5826**，**§5.3 是对的，§5.2 那一行是错的**。
2. **列口径不一致**：§5.2 的 θ = 4° / 8.594° / 12° 三列，实测值恰好是「按公式算出的超出 1 的部分**减半**」（等价于把标注角度取了一半）；而 θ = 17.1887° 那一列用的是**标注角度本身**。四列用了两套口径。

**顺带的措辞问题**：A §5.3 把「1.347 @ k = 1」称作「Win8 在 k=1 的**理论极限**」——**它不是极限**。按同一公式，k → sin(θ)/2⁺ 时 r → ∞；k = 0.5 时已经是 **1.839**，比 1.347 大得多。k = 1 只是一个任选取值。
**这条措辞会误导决策**（「Win8 最夸张也只有 1.347」→ 实际只是某个 k 的样本），建议改成「k = 1 时的值」。

### 6.4 【需补前提，否则结论不成立】A §5.3 的「本项目透视等效 131px」依赖一个没写出来的假设

A §5.3 由 r = 1.582 反解 k = 0.6555，再乘「磁贴宽 200px」得 P ≈ 131px，并据此下结论「本项目透视比 Win8 强 3~7 倍，**透视要大幅减弱，而不是加强**」。

**但反解 `k = sin(θ)/2 · (r+1)/(r−1)` 里的 θ 必须是「量到 1.582 那一帧的转角」。** A 代的是 **17.1887°（Win8 TiltEffect 的最大角）**，不是本项目的角。
本项目那一档按 A 自己 §10.5 的表是 **0° → 90° → 0° 翻面**，峰值不一定落在 17.19°。

用本项目**自己的设计值** `PERSPECTIVE_DISTANCE_FACTOR = 1.5f`（A §10.5 引 `ProgramScreen.kt` L1085）反推：若 1.582 出现在 θ = 45°，
`k = sin(45°)/2 × (1.582+1)/(1.582−1) = 0.35355 × 4.4364 = ` **1.569 ≈ 1.5** —— 与设计值**吻合**。

⇒ **A §5.3 的「131px / 强 3~7 倍 / 要大幅减弱」很可能是角度错配的产物，目前不能作为决策依据。**
请 A 补一句「1.582 对应第几帧、那一帧转角多少」，否则这个对比不成立。

（另：§5.3 正文里的「Win8 常见取值 **k = 3~8**」在官方文档里没有出处。`PlaneProjection` 的相机距离本来就没公开（A §5.1 自己也这么写），谈不上「常见取值」。这条应标 `[推测]`。）

### 6.5 【需补前提】A §10.3 的透视表没写视口宽度 W，换设备结论就变

我从 A 表里的 0.9384（左边缘）与 0.3023（右边缘）反解出 A 用的是 **W = 1366 px**、`perspective(600px)`、轴在视口外 40px：

```
左边缘 d = 40            → s = 600/(600 + 40·sin80°)        = 0.9384
右边缘 d = 1366 + 40 = 1406 → s = 600/(600 + 1406·sin80°)   = 0.3023
近/远比 = 0.9384 / 0.3023 = 3.1040   ✓ 与 A 的 3.1039 一致
```

**A 的算术是对的，但 W = 1366 这个前提没写出来**，这张表因此无法复现，而且**换一台设备结论就变**：W = 1920 时右边缘 s = 600/(600+1960·0.98481) = 0.2371，近/远比变成 **3.96**。请 A 把 W 写进表头。
（§10.3 关于「−50° 时右边缘越过相机平面、`W_max = P/|sinθ| − 40 = 743px`」的推导我复核**正确**。）

### 6.6 【方法论分歧】§10.5 把两种不同几何下的「近/远比」直接对比，是苹果比橘子

`turnstile` 的 3.1039 之所以大，**不主要是因为 600px 这个相机距离小**，而是因为**转轴在元素外面**：两边到轴的力臂是 **40 : 1406（差 35 倍）**，加上 80° 的大转角。
本项目的轴在**元素中心**，力臂是对称的。

近/远比 `r` 同时包含**透视距离**和**力臂比**两个因素。拿「整屏页面绕视口外 40px 的轴转 80°」的 r，去和「一块磁贴绕自身中轴转」的 r 比大小，**推不出该改哪个参数**。
要对比必须固定几何（同样的轴位置、同样的元素宽度、同样的转角）。

**这条只影响 §10.5 的对比表，不影响 §10.1–§10.4 的源码事实与 §10.6 的自我限定**（A 在 §10.6 确实写了「turnstile 是页面导航转场，不是点磁贴开应用的动画」——这一条我完全同意，见 6.8）。

### 6.7 【小错】A §10.2 的 RTL 换算少了一项

A §10.2 写：RTL 的 X `40 + (innerWidth − element.right)` → 换算到页面坐标 `element.right + 40 + innerWidth − element.right` = **`innerWidth + 40`**。

`transform-origin` 的 x 是**从元素自身左边起算**的局部坐标，换算到页面坐标要加的是 **`element.left`**，不是 `element.right`：

```
页面 X = left + 40 + (innerWidth − right) = innerWidth + 40 − element.width
```

⇒ A 的 RTL 结论**少了 `− element.width`**，而且它**依赖元素宽度**，不是干净的「视口外侧 40px」。
**LTR 那条是对的**：`页面 X = left − (40 + left) = −40`，与元素位置、宽度都无关 —— 这是 A §10.2 最有价值的结论，不受影响。

### 6.8 【一致但我独立证实了】A §10 `turnstile` 全节 —— 逐字复核**属实**

这是 A 的强项（我没查过这条线）。我把 A §10.1–§10.4 的每一个数字回到 `base.js` 逐字核对，**全部命中**：

| 项 | A 的值 | 我复核的原文行 | 判定 |
|---|---|---|---|
| `turnstileForwardIn` | L17934 | 17934 ✓ | ✓ |
| `turnstileForwardOut` | L17972 | 17972 ✓ | ✓ |
| `turnstileBackwardIn` | L18010 | 18010 ✓ | ✓ |
| `turnstileBackwardOut` | L18048 | 18048 ✓ | ✓ |
| In 变换 | `perspective(600px) rotateY(80deg)` → `rotateY(0deg)` | 17958–17959 ✓ | ✓ |
| In 时长/曲线 | 300 ms / `cubic-bezier(0.01,0.975,0.4775,0.9775)` | 17956–17957 ✓ | ✓ |
| Out 变换 | `perspective(600px) rotateY(0deg)` → `rotateY(-50deg)` | 17996–17997 ✓ | ✓ |
| Out 时长/曲线 | 128 ms / `cubic-bezier(0.4925,0.01,0.7675,-0.01)` | 17994–17995 ✓ | ✓ |
| BackwardIn | `perspective(600px) rotateY(-50deg)` → `rotateY(0deg)`，300 ms | 18034–18035 ✓ | ✓ |
| **BackwardOut 的 800px** | `perspective(800px)` → `perspective(800px) rotateY(80deg)`，128 ms | 18072–18073 ✓ | **✓ 800px 属实** |
| opacity 进 | 300 ms `cubic-bezier(0, 2, 0, 2)` | 17962–17967 ✓ | ✓ |
| opacity 出 | 128 ms `cubic-bezier(1,-0.42,0.995,-0.425)` | 18000–18005 ✓ | ✓ |
| delay | `staggerDelay(0, 50, 1, 1000)` | 17955 / 17963 / 17993 / 18001 ✓ | ✓ |
| `collectTurnstileTransformOrigins` | L16251 | 16251 ✓，四行逐字一致 | ✓ |

**一处口径差**：A 说注册名 `WinJS.UI.PageNavigationAnimation.turnstile`「（base.js L30843）」——
**`base.js` 只有 26 830 行，没有 L30843**。该名字表实际在 **`base.js:15418–15422`**（`PageNavigationAnimation: {` … `turnstile: "turnstile",`）。
另外 `turnstile` **完全不在 `ui.js` 里**（`ui.js` 55 956 行，grep `turnstile` 零命中）—— 所以「L30843」这个出处是无效的，但**结论（它属于页面导航转场动画族）是对的**，`base.js:15416` 的注释原文就是
`/// Specifies what animation type should be returned by WinJS.UI.Animation.createPageNavigationAnimations.`

### 6.9 【口径确认】A 的 `[实测] = 0 条` 与我不同，但口径一致

A 在 §10.5 明确写「`[实测]` 专指从真实 Win8 录像帧量得，本项目自己连拍不算，所以仍是 0 条」。
**我同意这个口径**，并且我这边**有** 100 帧真实 Win8 录像帧（30 fps，t = 196.300–199.600 s）的脚本化量测，所以 **B 组的 `[实测]` ≠ 0**。这是 A/B 两组最大的差别（A §8.1 把「真实 Win8 录像」列为完全未取得）。

**但实测的结论对 A 有利**：那段录像里**没有磁贴按压变形**（见 §4.4），也就是说**实测没有推翻 A 的任何一条**。A 的「Win8 没有 3D 翻转」结论与我一致。

### 6.10 【小口径】A §1.3 的 `enterContent` 行号范围

A 写 `enterContent` = `L17427–17471`。实测 **函数定义在 L17398**（`enterContent: function (incoming, offset, options) {`），L17427 只是 offset 默认值那一行。范围建议写成 **17398–17471**。
（`exitContent` L17474 ✓ 正确。）

### 6.11 我复核后认为 A 是对的、且我一开始怀疑过的两条

- **招牌曲线的出现次数「60 处（base.js 52 + ui.js 8）」** —— 我复核为**正确**。
  `base.js`：带空格写法 `cubic-bezier(0.1, 0.9, 0.2, 1)` **44** 处 + 不带空格 `cubic-bezier(0.1,0.9,0.2,1)` **8** 处 = **52**；
  `ui.js`：带空格 **5** 处（11963, 13339, 13454, 46018, 46046）+ 不带空格 **3** 处（33228, 35766, 35774）= **8**。合计 **60** ✓ 与 A 完全一致。
- **`pointerDown` / `pointerUp` 的 `scale(0.975, 0.975)` 与行号 L17211–17238 / L17241–17268** —— 实测 `pointerDown: function (element) {` 在 **17211**、`to: "scale(0.975, 0.975)"` 在 **17236**、`pointerUp: function (element) {` 在 **17241** ✓ 正确。

### 6.12 没有分歧的部分（供领导快速判断）

四个常量及其单位/数值、按下不带动画、200 ms + 100 ms、线性回弹（默认开关下）、角度分配公式 12 行、旋转中心默认值、`PerspectiveDistance` 不存在、`enterPage` / `exitPage` / `enterContent` / `exitContent` 的全部数值与行号、`addToList` / `deleteFromList` 的 120 ms / 240 / 60 / `scale(0.85)` / `cubic-bezier(0.11, 0.5, 0.24, .96)`（`base.js:16430` 与 `16481`，全仓库仅此 2 处）、`staggerDelay` 实现、`ui-light.css` 的 `@keyframes` 定义 —— **A 与 B 完全一致**。

**需要 A 回修的一共 5 条：§6.1 / §6.2 的表（6.1 节）、`expand`/`collapse` 行号（6.2 节）、§5.2 表 6 格 + §5.3「理论极限」措辞（6.3 节）、§5.3 的 131px 前提（6.4 节）、§10.2 RTL 换算（6.7 节）。其余为补充前提或口径。**

---

## 7. 未能证实 / 不确定的地方（诚实清单）

1. **`TiltEffectRtm.zip` 原始文件已失效**（HTTP 403 `AuthenticationFailed`）。§2 的源码来自两个独立转载（S1 / S2），逐字一致；但严格说**不是**微软原始下载。任何一处差异都会破坏两个转载者的一致性，我没有发现差异。
2. **Peter Torr 博客正文没有常量数字**，且属于 WP7/Silverlight 时代。"常量出自该博客"这个说法**未能证实**。
3. **`PlaneProjection.PerspectiveDistance` 在 WinRT / Silverlight 上都不存在**。Win8 时代"透视强度"的**默认值未能证实**；`PerspectiveTransform3D.Depth = 1000` 是 WinUI3 的值，**不能**直接套到 Win8。
4. **`RotationX > 0` 的观感方向**（哪条边朝观察者）我是从 S8 的配图说明推的，**画面上没有实拍证据**，标 `[推测]`。
5. **实拍素材里没有磁贴变形**（§4.4）。这不等于官方行为"不旋转"，只等于这段录像没记录到。最有可能是鼠标点击而非触摸（Tilt 由 `ManipulationStarted` 驱动）。
6. **素材录制的原始分辨率未知**，磁贴 82 px 对应真实 120 px 只是按 2/3 缩放推的 `[推测]`。
7. **本机无视觉模型**（`modlens_read_image` 报 `No vision provider is set up on this machine`），我没有目视过任何一帧。所有图像结论均为数值分析。如果 Lead 需要"看图确认"，需要另配视觉模型或人工看图。
8. **WinJS 的 `animationFactor` 默认为 1**；`_fastAnimations` 会把所有时长乘以 1/20。引用时长时务必带上"factor = 1"这个前提。
9. **`enterPage` 的 17803/17501 那种 `offset && {...}` 写法**：不传 `offset` 时位移那一路根本不执行。如果你的场景传了 offset，退场才动；不传，就只有淡出。
10. Tilt 效果**只对 `ButtonBase` / `ListBoxItem` 生效**（`TiltableItems`，S1:328）。开始屏幕的磁贴**不是**这两个类型 —— 所以**磁贴的按压动画不是这个 TiltEffect 类本身**，而是 Windows 开始屏幕外壳（`StartUI`）里用同一套设计参数（同一个 `MaxAngle = 0.3`、`MaxDepression = 25`、`TiltReturnAnimationDelay = 200`、`TiltReturnAnimationDuration = 100`）的**内部实现**。源码注释 `// These constants are the same as the built-in effects`（S1:343）正是这个意思。⇒ **§2 的参数是"官方公布的、与系统内置效果相同的常量"，这是它能推及开始屏幕磁贴的唯一依据。**

---

## 8. 追加复核：`turnstile` 页面转场（**独立印证** + **实测取证结果：未能取得**）

> 本节写于 A 组把结论改成"真正的页面进出 = `turnstile`"之后，按 Lead 指令独立复核。
> 所有行号均指 `C:\Users\33947\Downloads\win8b\src\winjs_base.js`（= <https://unpkg.com/winjs@4.4.3/js/base.js>，1 283 526 B）。

### 8.1 逐字源码复核 ✅（与 A 的 §10 一致）

| 项 | 逐字值 | 行号（from / to） |
|---|---|---|
| 前进进场 `turnstileForwardIn` | `perspective(600px) rotateY(80deg)` → `perspective(600px) rotateY(0deg)`，**300 ms**，`cubic-bezier(0.01, 0.975, 0.4775, 0.9775)` | fn `17934`，transform `17958` / `17959` |
| 前进退场 `turnstileForwardOut` | `perspective(600px) rotateY(0deg)` → `perspective(600px) rotateY(-50deg)`，**128 ms**，`cubic-bezier(0.4925, 0.01, 0.7675, -0.01)` | fn `17972`，transform `17996` / `17997` |
| 后退进场 `turnstileBackwardIn` | `perspective(600px) rotateY(-50deg)` → `perspective(600px) rotateY(0deg)`，**300 ms**，`cubic-bezier(0.01, 0.975, 0.4775, 0.9775)` | fn `18010`，transform `18034` / `18035` |
| 后退退场 `turnstileBackwardOut` | `perspective(800px) rotateY(0deg)` → `perspective(800px) rotateY(80deg)`，**128 ms**，`cubic-bezier(0.4925, 0.01, 0.7675, -0.01)` | fn `18048`，transform `18072` / `18073` |
| opacity（进，两个 In） | 300 ms，`cubic-bezier(0, 2, 0, 2)`，from 0 to 1 | `17964`/`17965`、`18040`/`18041` |
| opacity（退，两个 Out） | 128 ms，`cubic-bezier(1, -0.42, 0.995, -0.425)`，from 1 to 0 | `18002`/`18003` |
| 延迟（四条都是） | `staggerDelay(0, 50, 1, 1000)` —— 第 i 个元素延迟 `min(50·i, 1000)` ms | `17955`/`17993`/`18031`/`18069` |
| 转轴 `collectTurnstileTransformOrigins` | `offsetLeftLTR = -(40 + left)`；`offsetLeftRTL = 40 + (innerWidth - right)`；`totalOffsetY = innerHeight/2 - top` | `base.js:16251` |

> **勘误（我自己第一版读错，已由 Lead 指出并重核源码改正）**：我最初把 `backwardIn` 记成 `-80°→0`、`backwardOut` 记成 `0→+50°` —— **错**。逐字重读 `17958/17959`、`17996/17997`、`18034/18035`、`18072/18073` 后确认上表。**规律是镜像**：`ForwardIn 80°→0` / `ForwardOut 0→-50°` / `BackwardIn -50°→0` / `BackwardOut 0→+80°`，即**后退方向把前进方向的两个角度对调**。**A 的 §10 这四行是对的**，我原先的偏差是漏看了 `turnstileForwardOut` 与两条 Backward 的 `from`/`to` 配对。
> 唯一的不对称：**两个 Out 的透视距离不同** —— `ForwardOut` 用 `600px`，`BackwardOut` 用 `800px`（两者的 In 都是 `600px`）。同一次导航里前后两段 P 不同，引用时不可混用。

**转轴复核 ✅ 通过**：元素的 `transform-origin.x = element.left + offsetLeftLTR = left - (40 + left) = -40 px`，即**相对视口的屏幕左边界外侧 40 px**（不是元素的百分比），`y = 屏高/2`。Lead 的"转轴 = x −40px, y 屏幕高/2（屏幕左外侧）"**证实**。
（附：A 的 §10.2 RTL 换算 `left + 40 + (innerWidth - right)` 化简后是 `innerWidth + 40 - width`，A 漏了 `- width` —— §6.7 已记。）

### 8.2 独立复算 Lead 的验收数 `[实测·CSS引擎复现]`

> **【判据口径 · Lead 已拍板】** 采用 **`axis_x = −40dp`（本机 **−120px**）**、`P_IN = 600dp`（本机 **1800px**）、`P_OUT = 800dp`（本机 **2400px**），θ=80° 时**目标比值 = `1.7393`**。
> **`1.77` 作废** —— 那是"P 按 density 缩放、铰链却没缩"的半缩口径（铰链固定在屏左缘外 40px = 13.3dp），几何上不对。
> **本节的 `W=1366` / `-40px` / `P=600` 那组数是 A 的桌面口径，只作对照**，用途是证明 **3.1009 不是错数、而是错口径**；**它不是判据**。判据见 **§8.2.1** 与 **§8.3 的第二张表**。
>
> **实现侧已由 Lead 读源码核实**：`app/.../ProgramScreen.kt:1261 TURNSTILE_ORIGIN_X = (-40).dp`、`:1264 TURNSTILE_PERSPECTIVE_IN = 600.dp`、`:1267 TURNSTILE_PERSPECTIVE_OUT = 800.dp` ⇒ 本机换算 **−120px / 1800px / 2400px**，**正是这一档** ✓。验收方的 `docs/flip-harness.md`（原文写 `axis_x = -40`、称 1.77 为"lead 已拍板"）与 `tools/measure_pages.py` 的 `model @80deg`，由 Lead 同步改为 **1.7393 / axis −120px**，因此本文件、夹具、实现三处口径一致。

**测量方法**：在真实浏览器里按 8.1 的**同一份声明式变换**建一个 W×H 的容器，`transform-origin: -40px 50%`，在容器左右边缘各放一条 2 px 全高标记，读 `getBoundingClientRect().height` —— 即浏览器 CSS 三维管线给出的投影高度。**这不是 Win8 录像实测**，是同一套 CSS 变换的引擎实测。

投影公式（复现并证实）：

```
h(d) = H · P / (P + d · sinθ)          d = x + 40   （x 从页面左边缘算）
left  : d = 40          right : d = W + 40
```

**W = 1366、H = 768、P = 600、`transform-origin: -40px 50%`**：

| θ | 左边缘高/H | 右边缘高/H | 左/右 |
|---|---|---|---|
| 80° | **0.9384** | 0.3026 | **3.1009** |
| 60° | 0.9454 | 0.3304 | 2.8613 |
| 40° | 0.9589 | 0.3993 | 2.4012 |
| 20° | 0.9777 | 0.5555 | 1.7602 |
| 10° | 0.9886 | 0.7111 | 1.3902 |
| 5° | 0.9942 | 0.8306 | 1.1970 |
| 0° | 1.0000 | 1.0000 | 1.0000 |

- **Lead 的 0.9384 ✅ 证实**，且我证实它**与页面宽度 W 无关**（左边缘 d=40 固定，只取决于 40 px 铰链偏移、P、θ）。`600/(600+40·sin80°) = 0.9384`。
- 同一 θ=80° 下右边缘随 W 变化：W=480 → **0.5405（比 1.7362）**；W=1366 → 0.3026（比 **3.1009**）；W=1920 → 0.2371（比 **3.9542**）；W=683/P=300 → 2.9760；W=2732/P=1200 → 3.1692。

#### 8.2.1 口径分歧的解法：**单位语义 —— WinJS 的 `px` 是 CSS px = 设备无关单位 ≈ Android 的 dp**

**Lead 自己的结论，我独立复算证实。** 这是把 3.1009 与 1.7393 的矛盾一次性消掉的关键：

- WinJS 的 `600px` / `800px` / `-40px` 都是 **CSS px**。CSS px 是**设备无关**单位（在 Windows 上由显示缩放决定，在 Android 上语义等同于 **dp**）。它**不是物理像素**，也不能读成"桌面视口下的逻辑像素"。
- 本机屏幕 **1440 px / density 3 = 480 dp 宽** ⇒ 应当把常量读成 `perspective(600 dp)`、铰链 `-40 dp`、`800 dp`；在本机 px 口径下分别是 **1800 / -120 / 2400**。
- 于是 θ=80° 时：`left = 1800/(1800 + 120·sin80°) = 0.9384`，`right = 1800/(1800 + 1560·sin80°) = 0.5395` ⇒ **比值 1.7393**，与 WP8 的 480 宽视口**逐位一致**（我表里 W=480/P=600 得 1.7362，同档，差 0.2% 为舍入）。
- ⇒ **修改措辞（Lead 已撤回"3.1039 是错的"这条判断，我赞同）**：A 的 **3.1039 在"1366 px 桌面视口"口径下是正确的**（我实测 3.1009，差 0.1%）；它**不是错数**，只是**不是我们要复刻的口径**。正确说法是：**"A 用了 1366 px 口径；我们该用 480 dp 口径 ⇒ 目标比值 1.7393。"**
- ⇒ **病态判据也随单位语义化解**（见 8.4）：用 `P = 600 dp = 1800 px`、W = 1440 px 时，`P - d·|sin50°| = 1800 - 1560×0.766 = 1800 - 1195 = 605 px > 0` ✅ **不退化**；若误用固定 `600 px`，则 `600 - 1195 < 0` ✗ **分母变号**。**选 `P = 600 dp` 而不是 `600 px`，正是为了不越界。**

#### 8.2.2 关于"40 px 绝对偏移破坏标度不变性"—— 保留，但要加限定

我先前做了"固定 P/W 等比缩放"三组，得 2.9760 / 3.1009 / 3.1692，据此说 `P/W = 1.25` 不是不变量。**这条结论保留 ✅**（Lead 也收下了），但**必须补上限定条件**，否则会被误读：

- 我那三组只缩放了 `W` 与 `P`，**没有缩放铰链偏移 40** —— 那当然不守恒。
- **若把铰链偏移也一起缩放**（`W → kW, P → kP, 40 → k·40`），比值**严格不变**：`h(d) = kP/(kP + k·d·sinθ) = P/(P + d·sinθ)`。
- 所以精确表述是：**"当 P、W、铰链偏移三者同比例缩放时比值不变；仅缩放 P、W 而固定铰链偏移时不成立。"** 而 **§8.2.1 的 dp 读法恰好就是把三者一起缩放的那一种**，因此 1.7393 在任何 density 上都成立。
- ⇒ "`P/W = 1.25`" 仍**不能当等式**用（它只在"铰链偏移也同比例缩放"时是恒等变形，不能独立作为缩放口诀）；但**"`P = 600dp`、铰链 `-40dp`、与 W 同单位"** 是一个**正确且可移植**的表述。
- ✅ **已用 oracle 数值验证**（`docs/win8-turnstile-oracle-b.py`）：dp 口径 `480×754 / P=600 / hinge=40` 与 px 口径 `1440×2262 / P=1800 / hinge=120` **两条路径都打印 `1.7393`**（逐帧全表一致），证实"三者同比例缩放 ⇒ 比值不变"。同一脚本喂 A 的口径 `1366×768 / P=600 / hinge=40`（显式传 40）得 **3.1039**，与浏览器实测 3.1009 相差 0.1%（舍入）。
- ⚠️ **这个脚本自己也踩过这个坑，已修**：`project()` 原来把铰链写成默认参数 `hinge=HINGE`，于是 `python oracle.py 1440 2262 1800` 会**静默沿用 dp 铰链 40**，算出**作废的 1.7710**。现已改成 `hinge=None`（运行时取全局）+ 第 4 个参数显式指定 + 缺省时按 `W/480` 自动缩放，并在表头打印"本口径起始比"与偏离 1.7393 时的三种口径对照。**后来者注意：这是最容易静默出错的一处。**

### 8.3 进场时间轨迹（30 fps 采样，300 ms）`[实测·CSS引擎复现]`

θ(t) = 80°·(1 − bezier(0.01,0.975,0.4775,0.9775)(t))：

**（下表是 A 的 1366px 口径，仅供对照；★判据是本节末尾那张 480dp 口径的表）**

| 帧 | ms | θ (°) | 左/H | 右/H | 左/右 |
|---|---|---|---|---|---|
| 1 | 0 | 80.00 | 0.938 | 0.303 | **3.101** |
| 2 | 33 | 30.36 | 0.967 | 0.458 | 2.112 |
| 3 | 67 | 17.23 | 0.981 | 0.591 | 1.660 |
| 4 | 100 | 10.11 | 0.988 | 0.709 | 1.394 |
| 5 | 133 | 5.88 | 0.993 | 0.807 | 1.231 |
| 6 | 167 | 3.33 | 0.996 | 0.880 | 1.132 |
| 7 | 200 | 1.81 | 0.998 | 0.931 | 1.072 |
| 8 | 233 | 0.92 | 0.999 | 0.964 | 1.037 |
| 9 | 267 | 0.39 | 1.000 | 0.984 | 1.015 |
| 10 | 300 | 0.00 | 1.000 | 1.000 | 1.000 |

**关键**：`cubic-bezier(0.01, 0.975, ...)` 的 `y1 = 0.975` 使曲线**起步即接近终点** —— 80° 在**第一帧内**就掉到 30°，第二帧 17°。⇒ 在 30 fps 下"比值 > 1.5"的画格**只有第 1–2 帧**；到第 4 帧（100 ms）已降到 1.39。**任何试图从 30 fps 录像里量这个比值的做法，可用画格只有 1–3 帧。**

**换成我们的目标口径（W=480 dp、P=600 dp、铰链 40 dp ⇒ 起始比 1.7393）重算，结论更严苛**：

| 帧 | ms | θ (°) | 左/H | 右/H | 左/右 |
|---|---|---|---|---|---|
| 1 | 0 | 80.00 | 0.9384 | 0.5395 | **1.7393** |
| 2 | 33 | 30.36 | 0.9674 | 0.6954 | **1.3911** |
| 3 | 67 | 17.23 | 0.9806 | 0.7957 | 1.2324 |
| 4 | 100 | 10.11 | 0.9884 | 0.8680 | 1.1388 |
| 5 | 133 | 5.88 | 0.9932 | 0.9184 | 1.0814 |
| 7 | 200 | 1.81 | 0.9979 | 0.9734 | 1.0252 |
| 10 | 300 | 0.00 | 1.0000 | 1.0000 | 1.0000 |

⇒ **在 1.74 这个口径下，"比值 > 1.5"只剩第 1 帧**（第 2 帧就 1.39）。这意味着：**turnstile 的"3D 立体感"在 30 fps 上只有一帧可见，人能感知到的是那段快速收敛的模糊运动，而不是某一帧的梯形。** 复刻时不要指望在任意一帧上"看出透视"；要保证的是**起始那一帧（t=0）的 1.7393 与整段 300 ms 的连续收敛**。

### 8.3.1 ⚠️ **重要修正**：1.7393 是**元素两条边**之比，**不是屏幕上看得见**之比

这一条是我在写验收 oracle 时发现的，会直接影响验收方法，请务必看。

**浏览器实测**（W=1366、H=768、P=600、`transform-origin:-40px 50%`、`rotateY(80deg)`，读 `getBoundingClientRect()`）：

| 量 | 实测值 |
|---|---|
| 元素**左**边缘投影后的屏幕 x | **−33.48** ← **负值 = 在屏幕外** |
| 元素**右**边缘投影后的屏幕 x | **+33.78** |
| 整页投影后的总宽 | **67.29 px**（原本 1366 px） |
| 左边缘高 / 屏高 | 720.68 / 768 = 0.9384 |
| 右边缘高 / 屏高 | 232.41 / 768 = 0.3026 |

**原因**：铰链在屏幕**左外侧** 40 px。`rotateY(80°)` 把左边缘到铰链的水平距离从 40 压到 `40·cos80° = 6.94`，于是它落到 `−40 + 6.94·(透视因子) = −33.48`，**跑到屏幕左边外面去了**。所以「左边缘高度 0.9384」这个量**在屏幕上根本看不到**。

**屏幕上真正看得见的梯形**（可见左边界 = 屏幕 `x = 0` 处被裁切的位置，其高度用 `u = 40P/(P·cosθ − 40·sinθ)` 反解；右边界用元素右边缘）：

| 帧 | ms | θ | 元素左边缘 X | 元素右边缘 X | **可见宽** | 可见左高 | 可见右高 | **可见左/右** |
|---|---|---|---|---|---|---|---|---|
| 1 | 0 | 80.00 | **−33.48** | 8.72 | **8.72** | 468.92 | 406.80 | **1.1527** |
| 2 | 33 | 30.36 | −6.61 | 272.00 | 272.00 | 724.55 | 524.31 | 1.3819 |
| 3 | 67 | 17.23 | −2.54 | 355.20 | 355.20 | 738.41 | 599.97 | 1.2307 |
| 4 | 100 | 10.11 | −1.08 | 404.32 | 404.32 | 745.04 | 654.43 | 1.1384 |
| 5 | 133 | 5.88 | −0.48 | 435.06 | 435.06 | 748.82 | 692.48 | 1.0814 |
| 10 | 300 | 0.00 | 0.00 | 480.00 | 480.00 | 754.00 | 754.00 | 1.0000 |

（单位 dp，口径 W=480、H=754、P=600、铰链 40。）

**三个必须传到实现组的推论**：

1. **1.7393 只能用于「投影矩阵断言」，不能用于「截图断言」。** 截图能看到的可见之比是 **1.1527**（帧 1），不是 1.7393；两者都对，量的不是同一条边。
2. **帧 1 在 480 dp 屏上，进场页只占 8.72 dp 宽（屏宽的 1.8%）** —— 起始那一帧几乎是屏幕左缘的一条细线。这与 §8.3 "只有第 1 帧夸张"合起来说明：**turnstile 的"立体感"完全来自那 300 ms 的连续展开，而不是任何一帧的静态形状。** 验收时若在起始帧找不到"一个明显的梯形"，那不是实现错了，是几何本来如此。
3. **元素左边缘在整段动画里都 ≤ 0**（帧 1 −33.48 → 帧 9 −0.02 → 帧 10 恰好 0）。⇒ **页面永远是"贴着屏幕左缘、向右展开"**，不存在"页面左边缘从屏幕内某个位置滑进来"。实现时若把 pivot 放在屏幕左缘（x=0）而不是屏幕左外侧 40dp（x=−40dp），起始帧的可见宽会从 8.72 dp 变成 …（会偏大），**这个 8.72 dp 可以当冒烟断言**。

### 8.4 退场轨迹与**数学退化警告** `[实测·CSS引擎复现]`

128 ms，0 → −50°，W=1366/P=600，取 max(左,右)/min(左,右)：ms 0 → 1.000；ms 32 → 1.018；ms 64 → 1.152；ms 96 → 2.484；ms 128 → 1.194。

⚠️ **退化**：退场旋转为**负角**，`h(d) = H·P/(P + d·sinθ)` 的分母在 `d·|sinθ| > P` 时变号（该点在观察者身后 / 被裁剪）。W=1366 时 `d = 1406`、`sin50° = 0.766` ⇒ `P − d·|sinθ| = 600 − 1077 < 0`，**已退化**。所以 ms=128 那个 1.194 不是可信的"透视比"。
⇒ **不退化条件（请置顶，选参数时先用它筛一遍）**：

```
W ≤ P / |sinθ| − 40          单位必须与 P 一致（dp 对 dp，px 对 px）
```

- **dp 口径（我们该用的）**：θ = 50°、`P = 600 dp` ⇒ `W ≤ 600/0.766044 − 40 = 743.3 dp`。本机 **480 dp ✅ 安全**，余量 263 dp。
- **本机 px 口径**：`P = 1800 px`、W = 1440 px、`d_right = 1560 px` ⇒ `P − d·|sinθ| = 1800 − 1560×0.766044 = 1800 − 1195.0 = 605.0 px > 0` ✅ **不退化**。
- **反例（错误读法）**：若把 `600px` 直接当本机物理像素用，则 `P − d·|sinθ| = 600 − 1195.0 = −595.0 < 0` ✗ **分母变号**（远端落到观察者身后 / 被裁剪），退场投影病态。
- ⇒ **结论：退场到 −50° 在宽屏上本来就病态，`P = 600 px` 直接搬到 1440 px 屏上必然越界；必须按 §8.2.1 的 dp 语义放大到 1800 px 才安全。** 引用任何退场比值时都必须同时声明**屏宽与 P 的单位口径**。

### 8.5 **真实录屏实测：未能取得**（按 Lead 指示如实否证）

素材：bilibili **BV1Us411f7bc**（`https://www.bilibili.com/video/av1151927053/`）《windows8.1的动画有多好》，1280×720，时长 **345.812833 s**，实测 **30 fps**（`requestVideoFrameCallback` 连采 180 帧，帧间像素**零重复**，`mediaTime` 步进 0.033/0.034 s）。

我做过的搜索（全部脚本化，无一处目视）：

1. 全片 0.2 s 步长帧差扫描，**1728** 个采样点；
2. 全片 0.5 s 步长饱和度/色相扫描，**692** 个采样点；
3. 对 **11** 个运动候选窗口做 30 fps 帧精确扫描，共 **941 帧**（≈31 s 素材）；
4. 对全片 **172** 张 2 s 采样帧做"梯形收敛度"扫描（左 1/4 内容高度 vs 右 1/4 内容高度 + 单调性斜率）；
5. 约 20 帧 ASCII 数值化"目视"。

**结果：没有任何一帧呈现"整页绕屏幕左外侧轴旋转"的梯形轮廓。**

**方法论教训（重要，写下来防止后来者重犯）**：我最初用"运动量沿 x 的最小二乘斜率 / 皮尔逊 r"当左铰链判据，在 t=262.25（slope 2.20、r 0.75）、t=275.47（slope 2.75、r 0.79）等处得到**很强的"左铰链"信号**。但逐帧 ASCII 显示：t=275 那一帧**左侧 0–907 px 的彩虹画面逐像素完全不动**，只有**右侧 907–1280 px 从灰变成白**。⇒ 斜率指标测的是"变化集中在屏幕哪一侧"，**与旋转几何无关**；它对"局部区域更换"和"真实旋转"给出同样的读数。**这个指标不能当 turnstile 判据用。**

素材内容（数值识别）：0–10 s 亮灰；10–65 s Win8 启动画面（紫底 + 白 Windows 标志）；71–143 s 彩虹/米色渐变图；146–196 s **Win8 开始屏幕**（磁贴网格，见 §4）；197.733 s 开始屏幕 → 小图标网格的整屏切换；199 s 之后为杂糅片段（含贴靠/分屏布局、应用界面、彩虹图反复出现）。

⇒ **结论：`turnstile` 的"实测帧"未能取得。** §8.2–8.4 是同一份声明式变换在**真实浏览器 CSS 三维管线**里的复现测量，标 `[实测·CSS引擎复现]`，**它不是 Win8 录像实测，不能拿来替代 A 组 §10 的"实测 0 条"。** 真实录像实测仍然是 0 条。

### 8.6 本节的不确定项

1. **本机无视觉模型**（`modlens_read_image`：`No vision provider is set up on this machine`）。我的"目视"全部是 ASCII 数值化渲染，**存在把非 turnstile 内容误判为 turnstile 的风险**，也可能漏看了真实存在的一帧。§8.5 的否证强度受这个限制。
2. 素材是一段**混剪**（作者演示 Win8.1 各种动画），并非单一应用导航录屏；它不含 turnstile 不等于 Win8 没有 turnstile —— 只等于**这段素材里我没找到**。
3. **四条 turnstile 里有三条用 `600px`、只有 `BackwardOut` 用 `800px`**（逐字：`ForwardIn 17958/17959 = 600`、`ForwardOut 17996/17997 = 600`、`BackwardIn 18034/18035 = 600`、`BackwardOut 18072/18073 = 800`）。⇒ **同一次后退导航里，进场段 P=600dp、退场段 P=800dp**，"P 恒为 600"是错的。这也是 §8.4 退化判据的来源：`800dp` 的退场比 `600dp` 更不容易越界。**代码审读时要逐条核对，不能只写一个 P。**
4. 退场角 −50° 的**符号方向**（往哪边转到观察者身后）我只能从 CSS `rotateY` 的轴定义推，**没有画面证据**，标 `[推测]`。

---

## 9. 审读纪律：**竖直比是硬判据、水平摆放是软判据**

这一节是 Lead 采纳我 §8.3.1 的发现后**当场定下的验收纪律**，实现未动一行、判据先立，避免"实现做完了才发现判据本身有歧义"。

### 9.1 分界线从哪来

投影公式（§8.2 已复现证实）：

```
X' = cx + (X − cx) · P/(P − Z)          ← 水平方向：取决于消失点 cx
Y' = cy + (Y − cy) · P/(P − Z)          ← 竖直方向
```

- **竖直**：一条边的上下两端**同一个缩放因子** `P/(P − Z)` ⇒ `height = H · P/(P + d·sinθ)`，**与 `cy` 完全无关**。⇒ **「左右边高比」对消失点位置免疫**，放哪儿都是这个数。
- **水平**：`cx` 直接决定页面**摆在哪里**、**看得见多少**。⇒ 同样的高度比，换一个消失点，可见宽度可以完全不同。

⇒ 这就是为什么两者必须分开判：**一个是不变量，一个是约定**。

### 9.2 硬判据（可以卡实现）

| 项 | 值 | 依据 |
|---|---|---|
| θ=80° 时左右边高比 | **`1.7393`** | §8.2.1，轴 −40dp / P 600dp |
| 进场 300 ms、退场 128 ms | 逐字 | §8.1（`17956`/`17994`） |
| 角度序列 | 进场 80°→0°；退场 0°→**−50°** | §8.1 |
| Backward 两条 | −50°→0°（300 ms）；0°→**+80°**（128 ms，**P=800dp**） | §8.1 |
| 四条缓动 | `(0.01,0.975,0.4775,0.9775)`、`(0.4925,0.01,0.7675,-0.01)`、`(0,2,0,2)`、`(1,-0.42,0.995,-0.425)` | §8.1 |
| 铰链 | 屏幕左缘外 **40dp**、竖直居中（`innerHeight/2`） | `base.js:16251-16267`，§8.1 |

**理由**：这些量要么是投影不变量（比值），要么是源码逐字常量（角度/时长/缓动），不因渲染管线而异。

#### 9.2.1 换算到**本项目的模型空间**（`1440 × 3200`，device px）—— 实现组直接对这张表断言

`docs/flip-harness.md` 记录本项目模型空间是 **1440 × 3200**、磁贴翻转用的是四点射影。turnstile 的页面在同一空间里 ⇒ 三条常量按 ×3 换算：

```
W = 1440 px        H = 3200 px        铰链 = 120 px（40dp × 3）
P_IN  = 1800 px（600dp × 3）    P_OUT = 2400 px（800dp × 3）
```

跑法：`python docs/win8-turnstile-oracle-b.py 1440 3200 1800`（铰链缺省自动按 `W/480` 缩放 = 120）⇒ **表头自检打印 `判据：本口径 θ=80° 起始左右比 = 1.7393`** ✅。

**ForwardIn 四角期望坐标（30 fps，300 ms）** —— 这是**实现组 draw 阶段可以直接断言的数字**：

| 帧 | ms | θ (°) | TL (x, y) | TR (x, y) | BR (x, y) | BL (x, y) | 左/右 |
|---|---|---|---|---|---|---|---|
| 1 | 0 | 80.000 | (−100.45, 98.57) | (26.15, 736.77) | (26.15, 2463.23) | (−100.45, 3101.43) | **1.7393** |
| 2 | 33 | 30.363 | (−19.83, 52.16) | (815.99, 487.41) | (815.99, 2712.59) | (−19.83, 3147.84) | 1.3912 |
| 3 | 67 | 17.231 | (−7.61, 30.98) | (1065.61, 326.85) | (1065.61, 2873.15) | (−7.61, 3169.02) | 1.2324 |
| 4 | 100 | 10.111 | (−3.23, 18.51) | (1212.97, 211.28) | (1212.97, 2988.72) | (−3.23, 3181.49) | 1.1388 |
| 5 | 133 | 5.884 | (−1.44, 10.86) | (1305.17, 130.55) | (1305.17, 3069.45) | (−1.44, 3189.14) | 1.0815 |
| 6 | 167 | 3.330 | (−0.66, 6.17) | (1362.72, 76.69) | (1362.72, 3123.31) | (−0.66, 3193.83) | 1.0463 |
| 7 | 200 | 1.809 | (−0.31, 3.36) | (1397.70, 42.61) | (1397.70, 3157.39) | (−0.31, 3196.64) | 1.0252 |
| 8 | 233 | 0.922 | (−0.14, 1.71) | (1418.35, 22.00) | (1418.35, 3178.00) | (−0.14, 3198.29) | 1.0129 |
| 9 | 267 | 0.391 | (−0.06, 0.73) | (1430.80, 9.40) | (1430.80, 3190.60) | (−0.06, 3199.27) | 1.0055 |
| 10 | 300 | 0.000 | (0, 0) | (1440, 0) | (1440, 3200) | (0, 3200) | 1.0000 |

**换算自检（三条，覆盖换算易错点）**：
1. **数值真值**：帧 1 的 `TL.x = −100.45`，远小于 0 ⇒ **大角度时页面左边缘在屏幕外**（与 §8.3.1 同源，只是换到模型空间：`−33.48dp × 3 = −100.44` ✅）。
2. **两端一致**：帧 1 `TR.x = 26.15 = 8.72dp × 3` ✅；帧 10 恰好回到 `(1440, 3200)` 整矩形 ✅。
3. **退化判据在本空间**：`1800 − (1440+120)·sin50° = +605.0 > 0` ⇒ 退场 −50° **不越界** ✅（若误把 `600px` 当本机 px 用，`600 − 1560·0.766 < 0` ⇒ 分母变号，那一帧会翻到观察者身后）。

### 9.3 软判据（**只在实现自己声明复刻 CSS `perspective()` 函数形式时才判**）

| 项 | 值 | 依据 |
|---|---|---|
| 起始帧可见宽度 | 8.72dp（屏宽的 1.8%） | §8.3.1 |
| 元素左边缘屏幕坐标 | x = **−33.48dp**（在屏幕外） | §8.3.1，浏览器实测 |
| 起始帧"看得见的"左右比 | **1.1527**（≠ 1.7393） | §8.3.1 |

**这三条是"消失点落在铰链上"（CSS `perspective()` **函数**形式，WinJS 的真实写法）的产物。**
- 若实现用 **CSS 函数形式**（消失点 = `transform-origin` = 屏外 −40dp）⇒ 这三条**应当**成立。
- 若实现用 **Android `Camera`**（默认消失点在 View 中心）或**其它约定**的矩阵 ⇒ 这三条**不判错**，只要它**自洽**（自己的矩阵 + 自己的消失点定义 + 自己的可见宽度推导一致）。

**本实现走的是 `setPolyToPoly` 四点射影（`ProgramScreen.kt:485`）**，消失点语义**由它自己的矩阵定义**。⇒ **审读按"它是否自洽"判，不拿 CSS 函数形式的水平摆放去卡它。**

### 9.4 审读时还要逐条核对的（容易漏）

1. **四条 turnstile 的 P 不是同一个**：`ForwardIn/ForwardOut/BackwardIn` 都是 `600px`，**只有 `BackwardOut` 是 `800px`**（§8.1、§8.6-3）。**不能只写一个 P。**
2. **迟滞（stagger）不能漏**：四条都有 `delay: staggerDelay(0, 50, 1, 1000)` ⇒ 每项 +50 ms、上限 1000 ms。
3. **opacity 与 transform 的时长不同**（进场 300/300、退场 128/128，但缓动不同）—— 别把两组参数弄混。
4. **退化判据**（§8.4）：`P − d·|sinθ| > 0`。本机 `800dp` 的退场比 `600dp` 更不容易越界；若实现里出现分母变号，那一帧的投影是**跳到观察者身后**，不是"透视太强"。

### 9.5 现场状态（**已按新证据更新**）：转场**确实在播**，但测量的时间基不可信

**前一条结论「整段没有旋转 / 300 ms 没被播放」——撤回。** 验收方换用点阵跟踪器后，`tt_enter` 里**图页首帧就是明显梯形**：t = 829.2 ms 左高 166 px / 右高 61 px、**顶边横跨全宽落差 −737 px**。这是纯几何量，`snapTo(1f)` 直接铺满矩形**不可能**产生它 ⇒ **转场确实在播。**

但同一份数据里：t = 806.1 ms 还是干净未转的矩形，**17 ms 后的 t = 846.1 ms 就已基本摆正**（比 1.043、落差 1 px），859 ms 之后全屏再无斜态 ⇒ "可见转场 ≈ 810→860 ms"。

**这 17 ms 在任何时长下都不可能。** 我按 `(0.01, 0.975, 0.4775, 0.9775)` 复算过（§8.3）：即使是 6000 ms 的慢放版，t+40 ms 时图页仍在 **~67°**；300 ms 版在 t+17 ms 时仍在 ~55°。⇒ **这两个时间戳之间差的绝不是 17 ms 的动画时间。**

⇒ **正确的现场描述是：转场在播（有斜边），但录屏的时间基与所测的二进制都不可信，绝对时长与绝对角度一律不可用。** 根因见 **§10**（`DEBUG_SLOWDOWN` 与被测包的版本错配）。

**对 §9.2 的意义不变**：几何基准（竖直比 1.7393 → 1.0）**没有问题，只是暂时没有可信的对象可测**。**先钉死被测二进制、关掉慢放，再拿 §9.2 的硬判据去验收**；在那之前，任何"比值不对/时长不对"的读数都是无效读数。

**另外提醒验收方一个方向性判据**（能分清两层）：进场前 128 ms 里，**列表页也在转**（0° → −50°，§10.2 Q1），那同样是明显梯形。**列表页转 −50° 与图页从 +80° 转回来的斜向相反**——看顶边是"从左往右掉"还是"从右往左掉"就能分清 829.2 ms 那一帧是谁。验收方量到的 2.72 比模型 90° 上界 1.8667 还大，是他自己 15% 边带法在大角度下的高估，所以**方向可信、绝对值不可信**——这一点我同意。

---

## 10. 实现静态阅读：转场为什么看着没播（**只读，未改一字节**）

**授权范围**：Lead 授权只读 `app/src/main/java/com/yjc/click/ui/program/`（含 `ProgramScreen.kt`、`TurnstileProjection.kt`）与 `app/src/main/java/com/yjc/click/ui/shell/MainScaffold.kt`。本节结论**全部来自源码静态阅读 + 文件时间戳 + 一次哈希**；我没有跑 adb、没有装机、没有构建、没有 git。

### 10.0 先说一件要命的事：**被量的包和现在的源码不是同一份**

| 对象 | 时间（2026-10-15） | 大小 / 标识 |
| --- | --- | --- |
| `ProgramScreen.kt`（当前源码） | **22:57:57** | 89 544 B |
| `TurnstileProjection.kt` | 22:50:39 | 2 313 B |
| `MainScaffold.kt` | 22:33:07 | 36 537 B |
| **已装机包** | `lastUpdateTime = `**`22:55:32`** | `versionName = 5.1-beta` |
| 工作区新构建的 APK（**尚未装机**） | 22:58:12 | 15 489 269 B，SHA256 `4080D025DAD883E9C92FABF5AE7DFB15D9114DE6A7D180050256932E77893618` |

**我在本次阅读过程中，亲眼看着这个文件被改动**：我先读到的是

```
1287: private const val DEBUG_SLOWDOWN = 20
```

（注释原文：`// 20 = 300ms → 6s，任何帧率的录像都能逐帧量`）
**几分钟后，同一处变成**

```
1291: private const val DEBUG_SLOWDOWN = 1
```

文件 mtime 22:57:57 **晚于**已装机时间 22:55:32 ⇒ **验收方量到的那份包，是从 `DEBUG_SLOWDOWN = 20` 的源码构建的。**

### 10.1 ⚠️ 头号结论：`DEBUG_SLOWDOWN = 20` 把整条时间轴放大了 20 倍

`ProgramScreen.kt:1021-1030`（当前形态）：

```kotlin
/** 这一次动画的时长：WIN8 档进场 367ms、退场 534ms；FLIP 档两个方向都是 600ms */
private fun durationMs(targetValue: Float): Int =
    if (FLIP_PROFILE == FlipProfile.WIN8_TURNSTILE) {
        // turnstile：进场 = 图页那条 300ms；退场 = 列表页那条 300ms（图页那 128ms 跑在前段）
        DEBUG_SLOWDOWN * TURNSTILE_IN_MS                    // :1025
    } else if (FLIP_PROFILE == FlipProfile.EXPAND) {
        DEBUG_SLOWDOWN * if (targetValue > HANDOFF) WIN8_ENTER_MS else WIN8_EXIT_MS   // :1027
    } else {
        FLIP_MS
    }
```

`TURNSTILE_IN_MS = 300`（:1322）、`FLIP_PROFILE = FlipProfile.WIN8_TURNSTILE`（:1264）⇒ **被量的那份包里，进场动画是 20 × 300 = 6000 ms。** 同一个倍数还乘在按压缩放的时延上：

- `:1611 delay(TILT_RELEASE_DELAY_MS * DEBUG_SLOWDOWN)` ⇒ 释放延迟 200 ms → **4000 ms**
- `:1614 tween(TILT_RELEASE_MS * DEBUG_SLOWDOWN, LinearEasing)` ⇒ 回弹 100 ms → **2000 ms**

⇒ **验收方读到的所有毫秒数都要先 ÷20 才是交付值**——这正是源码 `:1285` 注释自己写下的读数规则，也正是它"装到用户机上会让动画看起来「卡在一半」，所以绝不能随包交付"（:1287）的由来。

**这解释了"看不到转场"的表象**：6 s 里绝大部分帧几乎一样，`screenrecord` 的 VFR 编码器只会吐极稀疏的帧（验收方自己发现了"后面 15 帧全同"）。**但它不能解释 17 ms**——见 §9.5 的复算。⇒ **两个时间戳里至少有一个不是图页层，或不是同一次录制。**

### 10.2 逐条回答 Lead 的四个问题

#### Q1 `flip.listAngle` 由什么驱动

- **取值**：`ProgramScreen.kt:888-889`
  ```kotlin
  val listAngle: Float
      get() = if (turnstileActive) turnstileListAngle(progress.value, entering) else 0f
  ```
- **曲线**：`turnstileListAngle`（:555-564）
  - 进场：`TURNSTILE_LIST_DEG * TURNSTILE_OUT_EASE.transform((q * TURNSTILE_IN_MS / TURNSTILE_OUT_MS).coerceIn(0f, 1f))`，`TURNSTILE_LIST_DEG = -50f`
  - 退场：`TURNSTILE_LIST_DEG * (1f - TURNSTILE_IN_EASE.transform(1f - q))`
- **用到哪**：`ProgramScreen.kt:458-470` 的 `Modifier.programListTurnstile`（`:467 perspective = TURNSTILE_PERSPECTIVE_IN`）；**调用点 `MainScaffold.kt:353`**（列表页 Box 修饰符链末尾），坐标系由 **`MainScaffold.kt:352`** 的 `onGloballyPositioned { pageOrigin = it.boundsInRoot().topLeft }` 提供。
- **在 p∈[0,1] 上哪一段非零**：进场时 `q·(300/128)` 在 **q = 0.4267（= 128 ms / 300 ms）就被 `coerceIn` 夹到 1** ⇒ `OUT_EASE(1) = 1` ⇒ **角度在 128 ms 就到 −50° 并一路保持到 p = 1（动画结束）**。⇒ **进场段整体非零，峰值 −50°，且在 128 ms 后是"焊死"在 −50° 的。**
- **在 p 的哪个值被重置为 0**：**不是被 p 重置的**。`turnstileActive`（:901）`= FLIP_PROFILE == WIN8_TURNSTILE && (openId != null || flipId != null)`，所以只有在 `openId` 与 `flipId` 双双变 null 时才回到 0：
  - `close()` 的完成回调 `:951-952`（`flipId = null; openId = null`）；
  - `cancelEnter()` 的 `:984-985`；
  - `open()` 里**没有任何地方**把它清 0 ⇒ **进场结束后列表页仍停在 −50°**。
- ⚠️ **一条值得记的实现细节**：进场后 172 ms 里，列表页是被**留在 −50°（几乎侧对屏幕）**的；只因 `listAlpha`（:892）在 128 ms 已到 0 才看不见。**当前无害，但对"以后有人改了 alpha"是脆的**——建议 `listAlpha == 0f` 后把 `listAngle` 一并归零。

#### Q2 图页层的合成门槛

- **门槛不在 `p` 上，在 `openId` 上**：
  ```kotlin
  // ProgramScreen.kt:1087
  val id = flip.openId ?: return
  ```
  这是 `ProgramFlipOverlay` 的第一行。`openId` 在 `open()` 里于 `animateTo` **之前**赋值（`:930 fun open(...)` → `:933 = program.id` → `:942 animateTo(1f)`）⇒ **从点击那一刻到交接完成，图页层一直在合成；不存在任何"只在某个 p 窗口才画"的条件。**
- **turnstile 档下图页不受裁剪**：`GraphReveal` 的 `drawWithContent` 里 **`ProgramScreen.kt:674 if (turnstile) { drawContent(); return }`** ⇒ `FLIP_PROFILE == WIN8_TURNSTILE` 时**直接画、绕过 `clipPath`**（EXPAND 档才走 :680 起的 `flipRect` + `clipPath`）。
- **角度 / alpha 表达式**：`:895-896 val graphAngle get() = if (turnstileActive) turnstileGraphAngle(progress.value, entering) else 0f`；`:898-899 val graphAlpha`。
  - `turnstileGraphAngle` 进场 `TURNSTILE_IN_DEG * (1f - TURNSTILE_IN_EASE.transform(q))`；退场 `TURNSTILE_IN_DEG * TURNSTILE_OUT_EASE.transform(((1f - q) * TURNSTILE_IN_MS / TURNSTILE_OUT_MS).coerceIn(0f,1f))`
  - `turnstileGraphAlpha` 进场 `TURNSTILE_FADE_IN_EASE.transform(q)`（**注释 :530 明确写了它在 ~2.7 ms 内饱和到 1**）
- **P 按方向选**：`:1156 perspective = if (flip.entering) TURNSTILE_PERSPECTIVE_IN else TURNSTILE_PERSPECTIVE_OUT` ⇒ 进场 `600.dp`（:1334）、退场 `800.dp`（:1337）——**与 §8.1 的 WinJS 源码一致**（只有 BackwardOut 是 800）。
- ⚠️ **一个只读阅读发现的行为差异**：turnstile 档下 overlay **只画 `graph()`，完全不画 `front()`**（:1140-1162，`:1156` 那个 `Box { graph() }` 分支没有对应的 `front()`）⇒ **"卡片长成整页"的 `FlipFront` 在 turnstile 档里根本不存在。** 这是设计意图（Win8 的 turnstile 就是整页转、不是卡片翻转），但**任何"找卡片翻转"的验收方法在 turnstile 档下必然扑空**。

#### Q3 有没有路径能把 `p` 提前置满

**全部写入点：**

| 行 | 代码 | 触发条件 |
| --- | --- | --- |
| 1014 | `progress.snapTo(target)` | `forceSettle()`（:1003）的看门狗：每 50 ms 轮询，**连续 `STUCK_MS = 400` ms（:1281）progress 值不变**才强制到位 |
| 1046 | `if (entering) progress.snapTo(0f)` | 每次 `animateTo` 起手重置（写 **0**，不是 1） |
| 171 | `flip.progress.snapTo(1f)` | `LaunchedEffect(openProgramId)`（:164）内部的 **:169 条件** |
| 175 | `flip.progress.snapTo(0f)` | 同一 effect 的 else 分支（写 0） |
| 979 | `progress.animateTo(0f, tween(remain, LinearEasing))` | `cancelEnter()`（写 0） |
| 1049 | `progress.animateTo(targetValue, ...)` | 正常动画 |

`openId =` 写入点：`:170`（:169 分支）、`:933`（`open()`）、`:952`（`close()` 完成）、`:985`（`cancelEnter()`）。
`flipId =` 写入点：`:852` 声明、`:932`（`open()`）、`:951`（`close()` 完成）、`:984`（`cancelEnter()`）。

**`:169` 在动画中途可能为真吗？——不可能。**

```kotlin
169: if (openProgramId != null && flip.flipId == null && !flip.animating) {
```

`openProgramId` **只**由 `onOpenProgram(id)` 写（`MainActivity.kt:221 onOpenProgram = { id -> openProgramId = id }`，声明在 `MainActivity.kt:110 private var openProgramId by mutableStateOf<String?>(null)`），而它只有两个触发点：

1. **`flip.onEntered`**（`ProgramScreen.kt:222`，注册处 `:224 if (openProgramId == null) onOpenProgram(id)`）：它由 `settle()`（:1062）在 `animating = false` **之后**调用，此刻 `progress.value` 已是 1，**且 `flip.flipId` 仍是 `program.id`（非 null）** ⇒ 条件照样为假，`snapTo(1f)` 是**空操作**。
2. **`openTile()` 的兜底分支**（`:201-205`）：拿不到卡片矩形就直接 `onOpenProgram(id)`、**不放动画**。这条路径下 `flipId` 从未被赋值 ⇒ :169 为真、:171 `snapTo(1f)` 生效，效果就是"**一帧接管**"。

⇒ **"一帧接管"只可能来自兜底分支，不可能来自 :169 被中途触发。**
兜底条件是 `cardFrames[program.id]` 为空或宽高 ≤ 0；生产者在 **`:1718 onCardFrame(program.id, CardFrame(b.left, b.top, b.width, b.height))`**（卡片 `onGloballyPositioned`）。**这是唯一需要现场日志确认的点**——`:199` 已有的 `openTile ... frame=... frames=...` 日志正好覆盖它。

#### Q4 最省事的修法候选（按代价排序；我只写建议，不动代码）

| # | 代价 | 修法 | 解决什么 |
| --- | --- | --- | --- |
| 1 | **0 行** | **先钉死被测包**：装 22:58:12 那份（`4080D025…3618`，`DEBUG_SLOWDOWN` 已是 1），记 `sha256` + `lastUpdateTime`，**再**录 `tt_enter` | 现在没有任何人知道量的是哪份二进制——本轮所有矛盾的根源 |
| 2 | 1 行 | **删掉 `DEBUG_SLOWDOWN`**（:1291），把 :1025 / :1027 还原成裸常量，删掉 :1611 / :1614 的两处乘法 | 一个会整体缩放"被验收的那个量"的全局开关，已经吃掉一整轮验收 |
| 3 | 3 行 | 加编译期保险：`init { check(DEBUG_SLOWDOWN == 1) { "slowdown must be 1 for delivery" } }` | 防止再有人把放慢包装上去 |
| 4 | 小 | **让动画自己吐真值**：把 :1130-1139 已有的逐帧日志扩成同时打 `graphAngle` / `listAngle` / `graphAlpha` | 验收方不必再从像素反推角度（他们 15% 边带法在大角度会高估，首帧 2.72 > 模型上界 1.8667 就是证据）——这一类争议从此消失 |
| 5 | 小 | 把 :1290 那句已经过期的注释（`20 = 300ms → 6s`）删掉或改成当前值 | 注释与常量已经互相矛盾 |

### 10.3 Lead 问的 (a) / (b) 二选一：**静态上 (a) 被否证，(b) 成立**

- **(a)「图页层只在整个时间轴的某个窗口里才合成」——静态否证。** 合成门槛是 `:1087 val id = flip.openId ?: return`，而 `openId` 在 `animateTo` 之前就赋值（`:933` / `:942`）；turnstile 档下图页还绕过裁剪（`:674`）。**代码里不存在任何按 `p` 开关图页合成的条件。** 唯一与 `p` 有关的是 `graphAlpha`，它在 ~2.7 ms 内就到 1（注释 :530）。
- **(b)「采样的 VFR 时间基不可信」——成立**，两条静态旁证：
  1. 被量的那份包里动画是 **6000 ms**（§10.1）。6 秒里绝大多数帧几乎相同，`screenrecord` 的 VFR 编码器只会吐极稀疏的帧；验收方自己也发现"后面 15 帧完全相同"。
  2. 即使用 6000 ms 反推，**t+40 ms 时图页仍在 ~67°**，⇒ "17 ms 从大梯形摆正"在**任何**时长下都不成立。
- **还有一个我没排除的可能**（请验收方一并核）：那两个帧里**其中一个可能不是图页层而是列表页**——列表页在进场前 128 ms 正从 0° 转到 **−50°**，那也是一个明显梯形，而且比图页更早出现。若 806.1 的"干净矩形"是列表页，那 829.2 的梯形可能就是**列表页在转走**，846.1 的"摆正"才是图页。**判据：看那一帧的顶边是从左往右掉还是从右往左掉**（列表页转 −50° 与图页从 +80° 转回，斜向相反）。

### 10.4 建议的决定性实验（一次就能分清）

1. 装 `app-debug.apk`（22:58:12，`DEBUG_SLOWDOWN = 1`），记 `sha256 4080D025DAD883E9C92FABF5AE7DFB15D9114DE6A7D180050256932E77893618` + `lastUpdateTime`；
2. `adb shell screenrecord --bugreport --time-limit 5`，点卡片；
3. **断言**：图页层应在连续 **≥9 帧**（300 ms @ 30 fps）上左右竖直比**单调下降 1.7393 → 1.0**（§9.2.1 那张表），第 10 帧回到精确矩形 `(0,0)-(480,0)-(480,754)-(0,754)`（模型空间）；
4. 同时从 logcat 读 :1130 起的逐帧 `p` 与角度真值，与像素法互校；
5. **若装上 `= 1` 的包后 `tt_enter` 里连梯形都没有** ⇒ 走的是 `openTile` 兜底分支（`:201-205`），去看 `:199` 的 `openTile ... frame=` 日志。

### 10.5 本节的不确定项

- 我**没有**跑任何 adb / 装机 / 构建，全部结论来自**源码静态阅读** + 文件时间戳 + 一次哈希。
- ~~"已装机包是 20 倍"是**时间戳推断**~~ ——**已在 §11 用运行日志直接坐实**（`dur=6000` / `ms=6000`），不再需要推断，也不再需要拉 APK 查 dex。
- `ProgramScreen.kt` 在我阅读期间**被别人改过**（`20` → `1`，行号整体 +4）。**本节引用的行号对应 22:57:57 那一版（89 544 B）**；若继续被改，行号会漂。
- `TurnstileProjection.kt`（44 行）我逐行读过：`turnstileQuad(thetaDeg, originX, originY, perspective, width, height)` 里 `scaleAt(x) = perspective / (perspective + (x - originX) * sinT)`、`px(x) = originX + (x - originX) * cosT * scaleAt(x)`、`py(x, y) = originY + (y - originY) * scaleAt(x)`，并带退化保护 `if (minOf(denomAtLeft, denomAtRight) <= 0f) return null` ⇒ **与 §8.2 的公式、与 §8.4 的退化判据完全一致**，`perspective` 用的就是 `600.dp` / `800.dp`（:1334 / :1337）。**投影数学这一层我没有发现缺陷。**

---

## 11. 结案：用**运行日志**取代像素法（`tools/captures/` 里的取证文件，只读）

我在 `tools/captures/` 下发现了验收方自己的取证文件——**包括应用逐帧打印的动画日志**。这是本轮唯一可信的时间基，因为它不经过录屏编码器。

### 11.1 直接证据：被测包的进场时长是 **6000 ms**

`tools/captures/tt_inst/flipdbg3.txt`（应用自己的 `Log.d("flipdbg", ...)`，22:59:06.799 起）：

```
22:59:06.799 flipdbg : openTile id=b40fcf32-… frame=CardFrame(left=56.0, top=378.0, width=1328.0, height=342.0) frames=3 window=1440 x 3200 tilt=CardTilt(…)
22:59:06.799 flipdbg : flip.open id=b40fcf32-… dur=6000 profile=WIN8_TURNSTILE window=1440 x 3200
22:59:06.799 flipdbg : animateTo start target=1.0 ms=6000 value=0.0 entering=true
```

**`dur=6000` / `ms=6000`** ⇒ `DEBUG_SLOWDOWN(20) × TURNSTILE_IN_MS(300)`。**§10.1 的推断到此变成直接证据。**
同时注意 `openTile … frame=CardFrame(left=56.0, top=378.0, width=1328.0, height=342.0) frames=3` ⇒ **没有走兜底分支**（拿得到卡片矩形），所以"一帧接管"在这条链上也不成立。

**并且：那一刻（22:59:06）设备上跑的仍是 22:55:32 装的那份**（`= 1` 的源码改于 22:57:57、APK 构建于 22:58:12，**都没装机**）。

### 11.2 完整的进场真值时间轴（`flipdbg3.txt`，起点 22:59:06.799）

| t (ms) | p | graph° | list° | listA |
| ---: | ---: | ---: | ---: | ---: |
| 6 | 0.0000 | 80.00 | −0.00 | 1.000 |
| 252 | 0.0412 | 47.14 | −0.10 | 1.000 |
| 503 | 0.0825 | 35.80 | −0.26 | 1.000 |
| 746 | 0.1237 | 28.35 | −0.64 | 1.000 |
| 994 | 0.1649 | 22.87 | −1.45 | 1.000 |
| 1248 | 0.2062 | 18.63 | −2.96 | 1.000 |
| 1489 | 0.2474 | 15.26 | −5.60 | 1.000 |
| 1736 | 0.2887 | 12.52 | −9.97 | 1.000 |
| 1984 | 0.3299 | 10.28 | −16.90 | 1.000 |
| 2231 | 0.3711 | 8.43 | −27.50 | 1.000 |
| 2478 | 0.4124 | 6.89 | −43.14 | 0.9527 |

`tools/captures/tt_inst/flipdbg2.txt`（另一次进场的**尾段**，22:57:20.266 → 22:57:22.053）：p = 0.7053（graph 1.4448°）→ …… → p = 0.9939 → `animateTo body done value=1.0`。**从 p=0.7053 到 1.0 用了 1777 ms = 0.2947 × 6000**。

⇒ **`p` 是 6000 ms 的纯线性 tween**（与 `:1049 tween(durationMillis = ms, easing = LinearEasing)` 一致），**曲线的形状全部长在 `turnstileGraphAngle` 里**。**这条性质是读日志的钥匙：`p` 与时间成正比。**

### 11.3 §10 的每一条静态预测，都被这份运行数据证实

| §10 的静态预测 | 运行数据 |
| --- | --- |
| `listAngle` 在 p = 128/300 = **0.4267** 就到 −50° 并焊住 | p=0.4124 时 list 已到 **−43.14**、正向 −50 收敛 ✓ |
| `listAlpha` 在同一 p 到 0 | p=0.4124 时 listA 已降到 **0.9527** ✓ |
| `graphAlpha` 在 ~2.7 ms 饱和 | 第二帧（p=0.0412）时 graphA 已是 **1.0** ✓ |
| 图页合成门槛是 `openId`，不是 p | 第一帧起 `open=` 与 `flipId=` **都非空** ✓ |
| `openTile` 兜底分支是唯一能"一帧接管"的路径 | `openTile … frame=CardFrame(…)` **有卡片矩形，未走兜底** ✓ |

### 11.4 关键量化：那条曲线的**可见段只有 ~75 ms**（交付值）

模型近/远比 = `(P + 1560·sinθ) / (P + 120·sinθ)`（W=1440、P=1800px、originX=−120px，即 §8.2 的本机口径）。把 §11.2 的角度代进去，并换算成**交付时间轴（÷20）**：

| 交付 t (ms) | graph° | 模型近/远比 |
| ---: | ---: | ---: |
| 0 | 80.00 | **1.7393** |
| 12 | 47.14 | 1.5592 |
| 25 | 35.80 | 1.4501 |
| 37 | 28.35 | 1.3681 |
| 50 | 22.87 | 1.3031 |
| 62 | 18.63 | 1.2503 |
| 74 | 15.26 | 1.2069 |
| 87 | 12.52 | 1.1710 |
| 99 | 10.28 | 1.1410 |
| 112 | 8.43 | 1.1161 |
| 124 | 6.89 | 1.0953 |
| 300 | 0.00 | **1.0000** |

**⚠️ 两条必须让验收方知道的结论：**

1. **整个进场动画的近/远比只在 1.7393 → 1.0 之间活动**，而且**一半以上的可见旋转发生在头 75 ms**。
2. **30 fps 录屏在 1× 下只有 2–3 帧能拍到斜态**（33.3 ms/帧，而 80°→23° 只花 50 ms）。⇒ **Lead 原定的验收判据「左右比在约 9 帧上单调下降」在 1× + 30 fps 下物理上不可能达成**，不是实现有问题。

> **📌 判据（Lead 已采纳，m01216）——「物理限制，不是实现缺陷」**
>
> 上表就是这条判据的依据，**永久保留**。将来任何人问"为什么只拍到 2 帧"，用这张表回答即可：
> 80°→23° 花 50 ms，而 30 fps 的采样间隔是 33.3 ms ⇒ 采样定理意义上这条曲线在 1× 下**欠采样 2–3 倍**。
>
> **验收口径据此改为：**
> - **1× 下：时序只用 logcat 真值判**（`tick p=… graph=… list=… listA=… graphA=…` 与 `dur=…`），**不要求任何帧数**；
> - **像素只做定性**：有斜边 / 无斜边、左高右低；**不再要求"约 9 帧单调下降"这一个数**；
> - 若确实需要逐帧量曲线形状，走 §11.6 第 3 条的慢放取证流程（临时乘 20，取证后改回）。

### 11.5 因此：验收方那几个读数的**具体失效原因**

`tools/captures/tt_enter/dots.csv` 是他们点阵跟踪器的原始输出：

```
4,829.2,graph,81242,288,1396,377,1734,1109,165,56,2.9464,…,1568,836,-732,21.1
5,836.4,graph,88693,288,1291,274,3177,1004,,,…,1.0,…,26.6
6,846.1,graph,92809,288,1039,275,1737,752,167,161,1.0373,…,1568,1569,1,40.9
7,859.0,graph,100233,292,1079,248,3170,788,167,1454,0.1149,…,1568,275,-1293,38.8
```

- **b 轴在相邻帧之间来回跳**：`y_bot` = 1734 → **3177** → 1737 → **3170**；`h_right` = 56 → 空 → 161 → **1454**。一个 3200 高的页面不可能这样。⇒ 它追的是**点阵的可见范围**，不是页面的两条边。
- 因此那些 `ratio` **不是**页面近/远比：**2.9464 已经超出模型 90° 的上界 1.8667**，而 **0.1149 是倒过来的**（近边比远边矮 8.7 倍）。
- `t_ms` 列来自 `tools/video_to_frames.py` 的 cv2 `CAP_PROP_POS_MSEC`（`source=video`），而 `tt_enter` 只有 40 帧、间隔是 610/196/23/7/10/13/8 ms —— 正是 `docs/flip-harness.md` 自己列过的坑："screenrecord 是 VFR ⇒ 必须用逐帧 pts"。
- **由 §11.2 的线性 `p` 可证**：**17 ms = Δp 0.0028 ⇒ Δθ ≤ 1.5°**（除了头 50 ms）。所以 829.2 → 846.1 那对帧（2.9464 → 1.0373）**在任何时长下都不可能是 17 ms 的动画间隔**。

### 11.6 修正后的建议（替代 §10.2 Q4 的第 1、2 条）

| # | 修法 | 说明 |
| --- | --- | --- |
| 1 | **先把设备上的包换掉**：装 22:58:12 的 `app-debug.apk`（`DEBUG_SLOWDOWN = 1`，`sha256 4080D025…3618`），记录哈希 + `lastUpdateTime` | 现在设备上跑的是 6000 ms 版，任何时长读数都差 20 倍 |
| 2 | **把日志当第一证据，像素当交叉验证** | 应用**每 tick 已经在打** `tick p=… graph=… list=… listA=… graphA=…`。验收方直接断言日志行即可，**不必再从像素反推角度**——他们自己那个 15% 边带法在大角度高估 2.4 倍，就是这一整轮争议的来源 |
| 3 | ~~`DEBUG_SLOWDOWN` 不要删~~ → **终局：删掉（Lead 已拍板，m01216，与本节初稿建议相反）** | **Lead 的理由（我接受）**：用户手上的包**就是 debug APK**（`versionName=5.1-beta`、`install -r app-debug.apk`），所以 `BuildConfig.DEBUG` 门控**挡不住**这个坑——今晚已经两次把慢放版装到用户手机上（一次 60 s 出自早期 `200`，一次 20× 是取证用的仪表版）。**留一个"会让用户手机变慢动作"的开关，代价大于"下次验收改一行常量"。** 慢放取证的替代流程：临时把 `TURNSTILE_IN_MS/OUT_MS` 各乘 20（一行）→ 取证 → **立刻改回并回读确认**；步骤已写进 `flip-harness.md`。⇒ 本节初稿的"用 `BuildConfig.DEBUG` 门控 + 启动断言"方案**作废** |
| 4 | 若坚持像素法：**120 fps 录**（面板是 120 Hz） | 30 fps 下这条曲线不可分辨；换成 120 fps 后 1× 也能拿到 ~9 帧 |

### 11.7 本节的不确定项

- 我只读了 `tools/captures/` 下**已有的**文件，没有运行 `measure_flip.py` / `measure_pages.py`，没有再碰 adb。
- `tt_inst/frames.csv` 显示了 VFR 的极端形态：0→1312 ms 密集（~8 ms/帧 ≈125 fps），之后 **1312 → 2976 → 4393 → 6025 → 8408 ms 只有 4 帧**。这与"曲线前倾 ⇒ 前 1.3 s 画面剧变、余下 4.7 s 变化极小"完全吻合，**是 §11.4 结论的独立旁证**；但我没有逐帧量这 164 张图去证实它。
- `flipdbg*.txt` 是**被人为截取的片段**（每个文件只有 11–17 行），不是我抓的完整 logcat；如果被截掉了关键行，我的表会不完整（但 `dur=6000` 与 `ms=6000` 两行是完整的、无歧义的）。

### 11.8 Lead 的结案（m01216）

- 本节"**用应用自己的 tick 日志当第一证据**"的做法已被 Lead 定为**交付口径：logcat 真值 > 像素反推**。
- §11.4 的判据已被采纳为"**物理限制，不是实现缺陷**"（见 §11.4 的 📌 块），验收口径据此从"≈9 帧单调下降"改为"**1× 下时序只用 logcat 判、像素只做定性**"。
- §11.5 的失效原因已被 Lead 记进已知项：`dots.csv` 的 `y_bot` 在 1734↔3177 之间跳，是**跟踪器把"点阵可见范围"当成"页面边"**造成的，**不是几何问题**（`TurnstileProjection.kt` 的投影数学我逐行读过，未发现缺陷）。
- §11.6 第 3 条按 Lead 的最终决定**反转**（删掉 `DEBUG_SLOWDOWN`），理由见该行。
- Lead 的结论：§8/§9/§10/§11 已足够作为"官方口径 + 独立审读"的存档，与 A 组 `docs/win8-tile-spec.md` §13 **两份独立材料互不引用、结论一致**。**此后不再改本文档结构**（除事实错误）。

