# Windows 8 磁贴动画 —— 可编码规格

> 目标：把「进出节点图动画」做成和 Windows 8 磁贴动画一样。
>
> **本文件每一条结论都带证据等级标记。请连同标记一起阅读。**
>
> | 标记 | 含义 | 本文件条目数 |
> |---|---|---|
> | `[文档]` | 微软官方文档或**实际发行**的官方源码，逐字引用并附 URL（+ 行号） | 全部关键数字 |
> | `[实测]` | 从真实 Win8 录像帧量得 | **本文件自己 0 条 —— 未取得**（见下方补注：队友 B 组另有一份真实录像，但结论是否定的） |
> | `[推算]` | 由 `[文档]` 常量数学推导得出，不是微软公布的数 | 标了「推导」的那些 |
> | `[推测]` | 纯推理，无出处 | 仅 §8.3 明确标注 |
>
> `[实测]` 为什么是 0（**于本文件主体完成后已有部分改变，见下方补注**）：
> 本文件采集期间本机 outbound 出网被封死，拿不到任何 Win8 开始屏幕录像。
> 完整的可达性实测表与失败记录见 [`win8-frames/README.md`](win8-frames/README.md)。
> **本次没有编造任何一个实测数字。**
>
> > **补注（本文件主体写完之后）**：队友 `win8-ref-b` 用**共享浏览器**这条路
> > （bilibili 播放页 + `crossOrigin` 重建 `<video>` + canvas `toBlob` + POST 到本机
> > python `http.server`）**成功抓到了真实的 Windows 8.1 开始屏幕录像帧**，
> > 落在 [`win8-frames-b/`](win8-frames-b/PROVENANCE.md)（25 帧，源
> > <https://www.bilibili.com/video/BV1Us411f7bc>，1280×720@30fps，时间窗 t=196.300–199.600 s）。
> >
> > 他们的量测结论（`win8-measure-b.out.txt`）是**一个否定结果**：
> > **t=196.300→197.700 这 43 帧里磁贴宽度比恒为 1.0000、近/远边高比 ≈1.01–1.03，
> > 磁贴一帧都没有变形**；t=197.733 整屏一次性硬切。
> >
> > **这个否定结果正好独立印证了本文件 §2 的 `[文档]` 结论**：
> > 点开始屏幕磁贴**不会**让磁贴翻面或倾斜 —— 走的是 `enterPage`（纯位移 + 淡入，无旋转）。
> > 但它**不能**用来否定 §3 的 TiltEffect：TiltEffect 的按下是 **0 ms 瞬时**的
> > （见 §3.4），30 fps 采样（33.3 ms/帧）**很可能整个错过**；
> > 而且 §3 里「点击磁贴正中 = 0°、四个角才到 17.19°」，本身幅度就很小。
> > **两者的关系是互补，不是矛盾。**

---

# ★ TL;DR —— 给实现方直接抄的 `turnstile` 四支常量

> 本节放在最前面，因为它是最实用的一节。
> 全部数字来自**实际发行的 WinJS 4.4.3 源码**（`[文档]`），
> 本地副本 `docs/win8-frames/_vercheck_cache/winjs-4.4.3-base.js`，逐字摘录见
> [`win8-frames/winjs-transitions.txt`](win8-frames/winjs-transitions.txt) L264–441。
> 透视距离已按**领导决定 2**（`P = 1.25 × 屏宽`）换算到本项目屏幕 **1440 × 3200**（density 3 ⇒ **480 × 1067 dp**）。
> ⚠️ **单位口径（2026-… 独立验收后更正）**：官方源码里的 `600px` / `800px` / `40px` 都是 **CSS px —— 设备无关单位**，
> 在 Android 上应映射成 **dp**，故 `axisX = −40dp = −120px`、`P_in = 600dp = 1800px`、`P_out = 800dp = 2400px`。
> 本机屏宽 **480 dp 正好等于**官方 WP8 那个 480 宽的视口 ⇒ `P/W = 1.25`、`axis/W = −0.08333` **同时与官方相等**，
> 投影形状**逐位一致**（近/远比 **1.7393**，与官方实测数完全相同，偏差 **0.0000%**）。
> **本节 T2/T5/T6/T7 的表已按此口径重算**（早前按「40 当原始像素」算出的 1.7710 是错的，偏 +1.82%，见 §10.2）。
> 逐帧数值表：[`win8-frames/turnstile-4branches.md`](win8-frames/turnstile-4branches.md)
> （生成器 [`win8-frames/_turn4.py`](win8-frames/_turn4.py)）。

## T1. 四支常量表（一行一个相位）

| 相位 | 源码函数（base.js） | 角度 | 时长 | transform 缓动 | opacity 轨道 | 透视 |
|---|---|---|---|---|---|---|
| **打开 · 节点图进场** | `turnstileForwardIn` **L17934** | `rotateY(+80°)` → `0°` | **300 ms** | `cubic-bezier(0.01,0.975,0.4775,0.9775)` | `cubic-bezier(0,2,0,2)`，`0→1` | `perspective(1800px)` |
| **打开 · 列表退场** | `turnstileForwardOut` **L17972** | `rotateY(0°)` → `-50°` | **128 ms** | `cubic-bezier(0.4925,0.01,0.7675,-0.01)` | `cubic-bezier(1,-0.42,0.995,-0.425)`，`1→0` | `perspective(1800px)` |
| **退出 · 列表进场** | `turnstileBackwardIn` **L18010** | `rotateY(-50°)` → `0°` | **300 ms** | `cubic-bezier(0.01,0.975,0.4775,0.9775)` | `cubic-bezier(0,2,0,2)`，`0→1` | `perspective(1800px)` |
| **退出 · 节点图退场** | `turnstileBackwardOut` **L18048** | `rotateY(0°)` → `+80°` | **128 ms** | `cubic-bezier(0.4925,0.01,0.7675,-0.01)` | `cubic-bezier(1,-0.42,0.995,-0.425)`，`1→0` | **`perspective(2400px)`** |
| **转轴（四支相同）** | `collectTurnstileTransformOrigins` **L16251** | 屏幕绝对坐标 **x = −40，y = 1600** | — | — | — | — |

**方向交叉验证**：微软官方对 `turnstileForwardIn` 的英文描述是
**"Rotates an element clockwise onto the page."**
（<https://learn.microsoft.com/en-us/previous-versions/windows/apps/dn607927(v=win.10)>）
—— 与上表进场 `+80° → 0°` 的符号**一致**，可确认方向没搞反。

**官方原值 vs 本表**：官方三个前向支写死 `600px`、`BackwardOut` 写死 `800px`，
**没有任何官方文档解释这两个数**（见 §11.5）。本表按 `P/W = 1.25` / `1.6667` 换算，理由见 §11.1。

## T2. ⚠️ 最容易被漏掉的一条：`transform-origin` 是**元素相对**坐标，不是屏幕坐标

**漏了这条，整个动画就完全不像。** 但**它的坐标系必须说准**，否则会算错 ——
源码 `collectTurnstileTransformOrigins`（base.js **L16251–16267**）逐字是：

```js
function collectTurnstileTransformOrigins(elements) {
    var origins = [];
    for (var i = 0, len = elements.length; i < len; i++) {
        var itemBoundingBox = elements[i].getBoundingClientRect();
        var offsetLeftLTR = -(40 + itemBoundingBox.left);
        var offsetLeftRTL = 40 + (_Global.innerWidth - itemBoundingBox.right);
        var totalOffsetY = ((_Global.innerHeight / 2) - itemBoundingBox.top);
        origins.push({
            ltr: offsetLeftLTR + "px " + totalOffsetY + "px",
            rtl: offsetLeftRTL + "px " + totalOffsetY + "px"
        });
    }
    return origins;
}
```

**读法（这条极关键）**：CSS 的 `transform-origin` 是**相对元素自身边框盒**的，
而源码想要的轴是**屏幕上的固定位置**（`x = −40`、`y = 屏高/2`）。
所以它做了一次**坐标系换算**：

```
transformOriginX = −(40 + element.left)        // 屏幕绝对 −40 换算成元素相对
transformOriginY = (screenHeight / 2) − element.top
```

- **对全屏元素**（`left = 0, top = 0`，整屏转场就是这种情况）：
  `transformOrigin = (−40dp, 屏高/2)` —— 在本机即 `(−120px, 1600px)`。
  **此时元素相对坐标与屏幕坐标数值相同**，所以「轴在屏幕左外侧 40dp」这个说法在全屏前提下成立。
- **对非全屏元素**（卡片、局部视图）：**必须套上面的公式换算，不能直接写 −40dp**。
  例如元素 `left = 100px`，则 `transform-origin-x = −(120 + 100) = −220px`。

**Compose 落地注意**：Compose 的 `TransformOrigin` 是**比例值（0..1）**，不是像素。
对全屏元素应写：

```
TransformOrigin(pivotFractionX = -120f / 1440f,  // = −0.0833333  （即 −40dp）
                pivotFractionY = 1600f / 3200f)  // = 0.5
```

⚠️ **早前这里写的 `-40f / 1440f ≈ −0.027778` 是错的** —— 它把 `40` 当成了原始像素。
`40` 是 CSS px ≡ dp，本机 density 3 ⇒ **−120px** ⇒ 比例值 **−0.0833333**。
（项目实际实现用 `setPolyToPoly` 在 draw 阶段做真正的射影变换，**不走 `graphicsLayer` 的 `TransformOrigin`**，
所以这条对当前实现是**备查**，但口径必须写对。）

即 **x 是负数且超出 [0,1] 区间** —— **这是本项目最容易写错的一点**：
`TransformOrigin` 的默认值是 `(0.5f, 0.5f)`（元素正中），
而 turnstile 要的是**元素左侧外面**，两者观感完全相反（见 §11.4）。

**RTL 情形**（本项目用不到，记录备查）：`offsetLeftRTL = 40 + (innerWidth − right)`，
即镜像到屏幕**右边缘外 40px**。

**生命周期**：`animRotationTransform`（L16191–16202）是**先设 `transform-origin`、再跑过渡**，
`onComplete` 里 `clearAnimRotationTransform`（L16204）把
`transform-origin` / `transform` / `opacity` **全部清空** —— 即这是一次性动画，**不留下持续状态**。

## T3. ⚠️ 第二条注意：`turnstile` **不用**那条经典曲线

§1.2 里那条 `cubic-bezier(0.1, 0.9, 0.2, 1)`（WinJS 里出现 60 处）**在 turnstile 里一次都没用**。
turnstile 自己用**两条全新的、走向极端的曲线**：

| 用途 | 曲线 | 行为 |
|---|---|---|
| 进场 transform（300 ms） | `cubic-bezier(0.01,0.975,0.4775,0.9775)` | **起步即接近终点**：`+80°` 在 **t=10%（30 ms）就只剩 +32.31°**，t=20% 已 `+19.20°` |
| 退场 transform（128 ms） | `cubic-bezier(0.4925,0.01,0.7675,-0.01)` | **先几乎不动、最后暴走**：t=50% 才 `−3.32°`（全程的 6.6%），t=90% 到 `−31.77°`，最后 10% 走完剩下的 36% |
| 进场 opacity | `cubic-bezier(0,2,0,2)` | **overshoot**（y 超过 1） |
| 退场 opacity | `cubic-bezier(1,-0.42,0.995,-0.425)` | **undershoot**（y 低于 0） |

**⇒ 如果实现时套用了经典曲线 `(0.1,0.9,0.2,1)`，观感会明显不对。**

## T4. ⚠️ 第三条：opacity **不是淡入淡出**，是「瞬时到位 + 定格」

上面两条 overshoot/undershoot 曲线的数值后果（[`turnstile-4branches.md`](win8-frames/turnstile-4branches.md) 有完整表）：

| 支 | 实际行为 |
|---|---|
| **进场**（300 ms） | opacity **在 t=0.91%（2.72 ms）就到 1**，此后 **99%（297.28 ms）保持完全不透明** ⇒「**瞬间淡入，然后长时间定格**」 |
| **退场**（128 ms） | opacity **直到 t=96.18%（123.12 ms）才开始变化**，此前恒为 1.000，**最后 4.88 ms 内砸到 0** ⇒「**不做淡出，是最后一刻切掉**」 |

**⇒ 把它实现成 300 ms 的平滑淡入 / 128 ms 的平滑淡出，是明显不同的观感。**

## T5. 四支逐帧时间轴（1440 × 3200，摘要）

**进场 `ForwardIn`（80°→0，300 ms）** —— 注意衰减有多快：

| t | ms | θ | 左边缘缩放 | 右边缘缩放 | 近/远比 |
|---|---|---|---|---|---|
| 0% | 0 | **+80.00°** | 0.9384 | **0.5395** | **1.7393** |
| 10% | 30 | +32.31° | 0.9656 | 0.6834 | 1.4129 |
| 20% | 60 | +19.20° | 0.9785 | 0.7782 | 1.2575 |
| 30% | 90 | +11.86° | 0.9865 | 0.8488 | 1.1622 |
| 50% | 150 | +4.45° | 0.9949 | 0.9370 | 1.0617 |
| 70% | 210 | +1.49° | 0.9983 | 0.9780 | 1.0208 |
| 100% | 300 | +0.00° | 1.0000 | 1.0000 | 1.0000 |

**退场 `ForwardOut`（0→−50，128 ms）** —— 右边缘**放大到 2.98 倍**（朝观察者出来）：

| t | ms | θ | 左边缘缩放 | 右边缘缩放 | 近/远比 |
|---|---|---|---|---|---|
| 0% | 0 | +0.00° | 1.0000 | 1.0000 | 1.0000 |
| 50% | 64 | −3.32° | 1.0039 | 1.0529 | 0.9535 |
| 70% | 90 | −11.38° | 1.0133 | 1.2064 | 0.8400 |
| 90% | 115 | −31.77° | 1.0364 | 1.8392 | 0.5635 |
| 100% | 128 | **−50.00°** | 1.0538 | **2.9754** | **0.3542** |

模型：`scale(x, θ) = P / (P + (x − axisX) · sin θ)`，`axisX = −120`（= −40dp，`[推算]`，见 §10.2）。

## T6. ⚠️ 第四条：退场在宽屏上会「炸」，所以 `P = 1.25 × 屏宽` 是**必须**的，不是偏好

投影分母 `P + d·sinθ` 必须在元素最右端仍为正，否则点越过相机平面、画面翻折：

```
不退化条件：  P > d_right · |sin θ|max,   d_right = 屏宽 + 120px   (= 屏宽 + 40dp)
```

| 方案 | 节点图退场（`BackwardOut`，θ=80°，P=2400） | 列表退场（`ForwardOut`，θ=50°，P=1800） |
|---|---|---|
| **本项目（dp 读法，`d_right = 1560`）** | `2400 − 1560×0.98481 = +863.7 px` ✅ 安全 | `1800 − 1560×0.76604 = +605.0 px` ✅ 安全 |
| 官方字面量 `600px` / `800px` 当原始像素直接用 | `800 − 1457.5 = −657.5 px` ❌ **炸** | `600 − 1480×0.76604 = −533.7 px` ❌ **炸** |

⇒ **若照抄官方字面量 600/800，本项目屏幕必然翻折。这就是领导决定 2 的硬理由。**
（`win8-ref-b` 在 W=1366 上独立算得不退化上限 `W ≤ P/|sinθ| − 40 = 743 px`，与本表同式。）

## T7. ⚠️ 验收时的采样陷阱：强透视只存在于**前 1–3 帧**

进场曲线起步即接近终点（T3），加上 30 fps 采样，整个 300 ms 只有 **9–10 帧**。
以下是 `ForwardIn` 在**本项目屏幕 1440 × 3200**（P=1800）上的逐帧值：

| 帧 | ms | θ | 左边缘缩放 | 右边缘缩放 | **近/远比** | > 1.5 ? |
|---|---|---|---|---|---|---|
| **1** | **0.0** | **+80.00°** | 0.9384 | **0.5395** | **1.7393** | **✅ 是** |
| 2 | 33.3 | +30.36° | 0.9674 | 0.6954 | 1.3912 | — |
| 3 | 66.7 | +17.23° | 0.9806 | 0.7957 | 1.2324 | — |
| 4 | 100.0 | +10.11° | 0.9884 | 0.8679 | 1.1388 | — |
| 5 | 133.3 | +5.88° | 0.9932 | 0.9184 | 1.0815 | — |
| 6 | 166.7 | +3.33° | 0.9961 | 0.9521 | 1.0463 | — |
| 9 | 266.7 | +0.39° | 0.9995 | 0.9941 | 1.0055 | — |
| 10 | 300.0 | +0.00° | 1.0000 | 1.0000 | 1.0000 | — |

**⇒ 判据「近/远比 > 1.5」只在第 1 帧（t=0）成立**，第 2 帧已降到 **1.3912**。
**验收脚本若从第 3 帧之后才开始抽，会误判成「没有透视」。**

**独立交叉验证（角度轴）**：`win8-ref-b` 在**另一套屏宽口径**（P=600 / W=1366）上独立算出
ms33 → θ=30.4°、ms67 → θ=17.2°、ms100 → θ=10.1° ——
**角度时间轴与本表逐点一致**（因为角度只由缓动曲线决定，与屏幕无关，是一条屏无关的轴）。
两者**比值**不同（他们 2.112 / 1.660 / 1.394）纯粹因为屏宽不同，**这反过来印证了：
角度轴是屏无关的、比值是屏相关的**。两套独立算子互证无误。

⇒ **判据「近/远比 > 1.5」只会在第 1–2 帧成立**；
如果验收脚本只抽了第 3 帧之后，会**误判为「没有透视」**。
**这条同时解释了队友 B 组那段录像为什么量不到**（他们采的窗口里根本没有 turnstile，见 §12）。

---

## §0 结论先行 —— 直接回答六个问题

| # | 问题 | 答案 | 依据 |
|---|---|---|---|
| 1 | 总时长 | **分两段，不是一个数**：按下 = **0 ms**（瞬时）；松开回弹 = **200 ms 延迟 + 100 ms 动画**。若走 WinJS 开发者 API 则是 **167 ms**。进应用那一段见 §2 | `[文档]` |
| 2 | 转轴 | **没有单一转轴，取决于哪套动画**：①点按磁贴 = 绕**竖直中轴 + 水平中轴**同时倾斜（Progressive，两轴各分多少**由点击位置决定**）；②`enterPage`/`exitPage`（进应用）= **完全不旋转**，只有 2D 位移 + 淡入淡出；③**`turnstile` 页面转场 = 绕屏幕外竖直轴的纯 `rotateY`，这才是 Win8 里真正带旋转的「进出」动画** —— 见 §10 | `[文档]` |
| 3 | 总转角 | **不是 90°，也不是 180°。** 点按最大 **17.1887°**（只在点四角时达到，点正中 = **0°**，见 §3）；`turnstile` 是 **进场 +80°→0°、退场 0°→−50°**（见 §10） | `[文档]` |
| 4 | 缓动曲线 | **`cubic-bezier(0.1, 0.9, 0.2, 1)`** —— 领导此前的猜测**正确**，已在实际发行的 WinJS 4.4.3 源码中逐字确认 | `[文档]` |
| 5 | 透视强度 | θ 最大仅 17.1887°，**k=1 时近/远比也只有 1.3467**，k=3~8（常见 CSS perspective）只有 **1.0609~1.1037**。要做出本项目现有的 **1.582**，等效相机距离只有 **0.656 倍磁贴宽**，比 Win8 极端情况还夸张。完整表见 §5 | `[推算]` |
| 6 | 正反对称性 | **绝对不是严格倒放。** `enterPage` 1000 ms 招牌曲线 vs `exitPage` **117 ms 线性**，**快 8.5 倍且换了缓动族**；`expand`/`collapse` 连**顺序**都相反；`addToList`/`deleteFromList` 连**曲线**都不同 | `[文档]` |

**最需要领导注意的一条：** Windows 8 的磁贴动画里**没有任何 3D 翻转**。
整个 Windows 8 动画库里没有 90°/180° 翻转，没有正反面。
本项目现在做的「绕竖直中轴 600 ms 翻转」在 Win8 里**找不到对应物**——
Win8 里最接近「进出节点图」语义的是 **`enterPage` / `exitPage`**（§2）和 **`expand` / `collapse`**（§4）。

**但有一条重要修正（m00418 之后才挖到，务必先看 §10）：**
Win8 里**确实有**一套「绕竖直轴旋转 + 强透视」的进出场动画，叫 **`turnstile`**，
参数是 `perspective(600px) rotateY(80deg)` → `0°`（进场 300 ms）/ `0°` → `rotateY(-50deg)`（退场 128 ms）。
用户要的「进出」如果指**转场进出**而不是「磁贴按压」，**`turnstile` 才是那个正主**，
而本项目现有翻转动画的透视强度（1.582）比 `turnstile` 进场起始（**1.7393**，见 §10.2 的 corrigendum）**略弱约 9%**。
即：**要做「一模一样」，透视不是要减弱，而是要略微增强；真正缺的是「转轴在元素外面 40px」这个几何，而不是把相机拉近。**

---

## §1 常量总表 —— 每个数字都有出处

> 领导要直接贴给用户看，所以**每个值后面都跟了 URL 和行号**。拿不到出处的写「未取得」。

### 1.1 TiltEffect（点按磁贴的「压下去」姿态）

出处 A：微软官方 Control Tilt Effect Sample 的 `TiltEffect.cs` 源码全文转载
<https://www.cnblogs.com/wgscd/articles/4883187.html>
出处 B：Peter Torr（Microsoft，`TiltEffect` 作者）官方博客
<https://learn.microsoft.com/en-us/archive/blogs/ptorr/updated-tilt-effect>
出处 C：微软官方文档 <https://learn.microsoft.com/en-us/previous-versions/windows/apps/ff941094(v=vs.105)>

| 常量名 | 值 | 单位 | 出处 | 等级 |
|---|---|---|---|---|
| `MaxAngle` | **0.3** | **弧度** = 17.1887338° | 出处 A，`TiltEffect.cs` | `[文档]` |
| `MaxDepression` | **25** | 像素（Z 方向后退） | 出处 A，`TiltEffect.cs` | `[文档]` |
| `TiltReturnAnimationDelay` | **200** | ms | 出处 A，`TiltEffect.cs` | `[文档]` |
| `TiltReturnAnimationDuration` | **100** | ms | 出处 A，`TiltEffect.cs` | `[文档]` |
| `UseLogarithmicEase` | **false**（默认） | — | 出处 A，`TiltEffect.cs` | `[文档]` |
| 对数缓动公式 | `Math.Log(t + 1) / 0.693147181` | — | 出处 A（= ln(t+1)/ln2） | `[文档]` |
| 回弹默认缓动 | **linear**（`DoubleAnimation` 未设 `EasingFunction`） | — | 出处 A | `[文档]` |
| 默认可倾斜控件类型 | `ButtonBase`, `ListBoxItem` | — | 出处 C，`TiltEffect` 静态构造 | `[文档]` |
| 全局相机做法 | `TranslateTransform` 平移到容器中心 + `PlaneProjection.GlobalOffsetX/Y` 抵消 | — | 出处 B 原文：*"each object tilts not only around its own centre, but is also angled towards the middle of the screen"* | `[文档]` |

源码注释原文（出处 A）：`// These constants are the same as the built-in effects`

### 1.2 两条缓动曲线

出处：**实际发行**的 WinJS 4.4.3 —— <https://unpkg.com/winjs@4.4.3/js/base.js> 与 <https://unpkg.com/winjs@4.4.3/js/ui.js>

| 曲线 | 值 | 出现次数 | 位置 | 等级 |
|---|---|---|---|---|
| **Win8 招牌曲线** | `cubic-bezier(0.1, 0.9, 0.2, 1)` | **60 处**（base.js 52 + ui.js 8） | base.js L16288, 16299, 16324, 16335, 16359, 16384, 16405, 16450, 16506, 16523, 16571, 16607, 16631, 16689, 16694, 17003, 17043, 17088, 17128, 17174, **17235**, 17265, 17300, 17307, 17316, 17361, 17368, 17379, 17390, 17435, 17443, 17455, 17465, 17558, 17589, 17621, 17632, 17666, 17677, 17720, **17758**, 17768, 17918, 17927, 18508, 18516, 18543, 18551, 18578, 18586, 18613, 18621；ui.js L11963, 13339, 13454, 33228, 35766, 35774, 46018, 46046 | `[文档]` |
| **退场专用曲线** | `cubic-bezier(0.11, 0.5, 0.24, .96)` | **恰好 2 处**，**只用于 `deleteFromList`** | base.js **L16430**, **L16481** | `[文档]` |

> **计数口径（很重要，不然你 grep 出来的数会和我对不上）**：
> 上表的 60 是**不区分空格**数的（正则 `cubic-bezier\(\s*0\.1\s*,\s*0\.9\s*,\s*0\.2\s*,\s*1\s*\)`）。
> WinJS 源码里这条曲线**有两种写法**：带空格的 `"cubic-bezier(0.1, 0.9, 0.2, 1)"`
> 和不带空格的 `"cubic-bezier(0.1,0.9,0.2,1)"`。
> **如果你直接 grep 带空格的字符串，只会得到 49 处（base.js 44 + ui.js 5）**，
> 差的 11 处全是不带空格那种（base.js L18508/18516/18543/18551/18578/18586/18613/18621，
> ui.js L33228/35766/35774）—— **不是文档写错了。**
> 自查脚本见 `docs/win8-frames/_verify-lines.py`（同时校验行号与计数）。

### 1.3 WinJS 动画参数全表

出处：<https://unpkg.com/winjs@4.4.3/js/base.js>（下述行号均为该文件行号，可逐行复核）

| 动画 | 位移 duration | 位移 timing | 位移 delay | 透明度 duration | 透明度 timing | 关键 from→to | 行号 | 等级 |
|---|---|---|---|---|---|---|---|---|
| `pointerDown` | **167** | `cubic-bezier(0.1,0.9,0.2,1)` | 0 | — | — | `""` → **`scale(0.975, 0.975)`** | **L17211–17238** | `[文档]` |
| `pointerUp` | **167** | `cubic-bezier(0.1,0.9,0.2,1)` | 0 | — | — | `scale(0.975,0.975)` → `""` | **L17241–17268** | `[文档]` |
| **`enterPage`** | **1000** | `cubic-bezier(0.1,0.9,0.2,1)` | `staggerDelay(0, 83, 1, 333)` | **170** | `cubic-bezier(0.1,0.9,0.2,1)` | `translate(0, 28px)` → `none`；opacity 0→1 | **L17726–17773** | `[文档]` |
| **`exitPage`** | **117** | **linear** | 0 | **117** | **linear** | `none` → offset；opacity →0 | **L17776–17823** | `[文档]` |
| `enterContent` | **550** | `cubic-bezier(0.1,0.9,0.2,1)` | 0 | **170** | `cubic-bezier(0.1,0.9,0.2,1)` | `translate(0, 28px)` → `none`；opacity 0→1 | **L17427–17471** | `[文档]` |
| `exitContent` | **117** | **linear** | 0 | **117** | **linear** | `none` → offset；opacity →0 | **L17474–17520** | `[文档]` |
| `expand` | **367** | `cubic-bezier(0.1,0.9,0.2,1)` | **0** | **167** | `cubic-bezier(0.1,0.9,0.2,1)` | 被影响元素位移 →`none`；revealed 用 `WinJS-opacity-in` | **L16740** | `[文档]` |
| `collapse` | **367** | `cubic-bezier(0.1,0.9,0.2,1)` | **有 hidden 时 167，否则 0** | **167** | `cubic-bezier(0.1,0.9,0.2,1)` | 被影响元素位移 →`none`；hidden 用 `WinJS-opacity-out` | **L16763** | `[文档]` |
| `addToList` | **120** | `cubic-bezier(0.1,0.9,0.2,1)` | **有 affected 时 240，否则 0** | **120** | **linear** | **`scale(0.85)` → `none`**；opacity 0→1；被影响元素位移 400ms/delay 0 | **L16368–16413** | `[文档]` |
| `deleteFromList` | **120** | **`cubic-bezier(0.11, 0.5, 0.24, .96)`** | 0 | **120** | **linear** | **`none` → `scale(0.85)`**；opacity 1→0；剩余元素位移 400ms/delay **有 deleted 时 60，否则 0** | **L16415–16458** | `[文档]` |
| `reposition` | **367** | `cubic-bezier(0.1,0.9,0.2,1)` | `staggerDelay(0, 33, 1, 250)` | — | — | →`none` | **L16345–16366** | `[文档]` |
| `fadeIn` | — | — | 0 | **250** | **linear** | opacity 0→1 | 见 §1.3 表 | `[文档]` |
| `fadeOut` | — | — | 0 | **167** | **linear** | opacity →0 | 同上 | `[文档]` |
| `showEdgeUI` / `hideEdgeUI` | **367** | `cubic-bezier(0.1,0.9,0.2,1)` | 0 | — | — | offset 位移 | 同上 | `[文档]` |
| `showPanel` / `hidePanel` | **550** | `cubic-bezier(0.1,0.9,0.2,1)` | 0 | — | — | offset 位移 | 同上 | `[文档]` |
| `showPopup` | **367** | `cubic-bezier(0.1,0.9,0.2,1)` | 0 | **83** | **linear** | 位移；opacity 0→1，**opacity delay 83** | 同上 | `[文档]` |
| `hidePopup` | — | — | — | **83** | **linear** | opacity →0 | 同上 | `[文档]` |
| `dragSourceStart` | **240** | `cubic-bezier(0.1,0.9,0.2,1)` | 0 | **240** | `cubic-bezier(0.1,0.9,0.2,1)` | 拖拽源 →**`scale(1.05)`**、opacity →**0.65**；被影响元素 →**`scale(0.95)`** | **L17271–17321** | `[文档]` |

### 1.4 `expand` / `collapse` 的 delay 规则（领导点名要）

逐字抄自 <https://unpkg.com/winjs@4.4.3/js/base.js>：

```js
// expand.execute()  —— L16740 起
[{ keyframe: "WinJS-opacity-in", property: "opacity",
   delay: this.affectedArray.length > 0 ? 200 : 0, duration: 167,
   timing: "cubic-bezier(0.1, 0.9, 0.2, 1)" }]
// 位移 track
[{ property: transformNames.cssName, delay: 0, duration: 367,
   timing: "cubic-bezier(0.1, 0.9, 0.2, 1)", to: "" }]

// collapse.execute() —— L16763 起
[{ keyframe: "WinJS-opacity-out", property: "opacity",
   delay: 0, duration: 167, timing: "cubic-bezier(0.1, 0.9, 0.2, 1)" }]
// 位移 track
[{ property: transformNames.cssName,
   delay: this.hiddenArray.length > 0 ? 167 : 0, duration: 367,
   timing: "cubic-bezier(0.1, 0.9, 0.2, 1)", to: "" }]
```

**读法：进场时「先淡入（等 200ms 让位），再位移」；退场时「先位移，再淡出」。两者顺序相反，不是倒放。**

### 1.5 `staggerDelay` 的实现（用于 `enterPage` / `reposition`）

逐字抄自 base.js **L16103**：

```js
function staggerDelay(initialDelay, extraDelay, delayFactor, delayCap) {
    return function (i) {
        var ret = initialDelay;
        for (var j = 0; j < i; j++) { extraDelay *= delayFactor; ret += extraDelay; }
        if (delayCap) { ret = Math.min(ret, delayCap); }
        return ret;
    };
}
```

`enterPage` 用 `staggerDelay(0, 83, 1, 333)`，代入得
**`delay_i = min(83 * i, 333)` 毫秒** → `[0, 83, 166, 249, 332, 333, 333, ...]` `[推算]`
`reposition` 用 `staggerDelay(0, 33, 1, 250)` → `delay_i = min(33 * i, 250)` `[推算]`

### 1.6 `PlaneProjection` 的旋转中心默认值

出处：<https://learn.microsoft.com/en-us/previous-versions/windows/silverlight/dotnet-windows-silverlight/dd470131(v=vs.95)>

| 属性 | 默认值 | 含义 | 等级 |
|---|---|---|---|
| `CenterOfRotationX` | **0.5** | 0 = 左边缘，1 = 右边缘，0.5 = 正中 | `[文档]` |
| `CenterOfRotationY` | **0.5** | 0 = 上边缘，1 = 下边缘 | `[文档]` |
| `CenterOfRotationZ` | **0** | 旋转轴穿过元素所在平面 | `[文档]` |
| `RotationX` 轴 | 物体的**水平**轴 | — | `[文档]` |
| `RotationY` 轴 | 物体的**竖直**轴 | — | `[文档]` |
| `RotationZ` 轴 | 穿过物体平面 | — | `[文档]` |

→ `TiltEffect` 未改旋转中心，所以**默认绕磁贴自身中心旋转**，与 §3 公式一致。`[推算]`

---

## §2 【核心】点磁贴之后「进应用」的那一段是什么动画

### 2.1 官方原文（这是本节最重要的一句）

出处：**"Animating page transitions (HTML)"**
<https://learn.microsoft.com/en-us/previous-versions/windows/apps/jj655417(v=win.10)>
（最后更新 2015-08-31）

> **"Use page transition animations to display the first page of a newly launched app**, or to transition between pages within an app. **The new page slides into place from an offset as it fades in.** The existing content **fades out** as the new content fades in. The new page is **split into regions, and each region moves onto the screen separately, with staggered timings.** You specify how the incoming page is split into regions."

**这句话直接回答了领导的第 1 问：**
- 「显示**新启动应用的第一页**」= 官方明确说这就是 app launch 用的动画，**就是 `enterPage`**。
- 视觉构成 = **slide from an offset（位移滑入）+ fade in（淡入）+ 分区错峰（stagger）**。
- **没有缩放，没有旋转，没有 3D，没有翻转。**

同一页还有两句设计指引：

> "The recommended direction is the opposite of the reading order."

注意：这是**推荐**方向；而**发行源码里的默认 offset 是纯垂直的 `translate(0, 28px)`，水平分量为 0**（见 §1.3）。
两者并不一致，实现时应以源码默认值为准，或按需显式传 offset。`[文档]`+`[推算]`

### 2.2 它的确切参数（WinJS 真源码）

`enterPage`，<https://unpkg.com/winjs@4.4.3/js/base.js> **L17726–17773**：

| 项目 | 值 | 等级 |
|---|---|---|
| 默认 offset | `[{ top: "28px", left: "0px", rtlflip: false }]` | `[文档]` |
| 位移 duration | **1000 ms** | `[文档]` |
| 位移 timing | **`cubic-bezier(0.1, 0.9, 0.2, 1)`** | `[文档]` |
| 位移 delay | `staggerDelay(0, 83, 1, 333)` → `min(83*i, 333)` ms | `[文档]`+`[推算]` |
| 位移 from → to | `translate(left, top)` → `none`（即从偏移滑到原位） | `[文档]` |
| 透明度 duration | **170 ms** | `[文档]` |
| 透明度 timing | **`cubic-bezier(0.1, 0.9, 0.2, 1)`** | `[文档]` |
| 透明度 delay | 与位移同一 stagger | `[文档]` |
| 透明度 from → to | `0` → `1` | `[文档]` |

**注意这个组合非常反直觉但很关键：位移要 1000 ms，透明度只要 170 ms。**
即**先快速淡入（170ms 内完成），再慢慢滑到位（1000ms）**。这是 Win8 观感的核心来源之一。

`exitPage`，同文件 **L17776–17823**：

| 项目 | 值 | 等级 |
|---|---|---|
| 默认 offset | `[{ top: "0px", left: "0px" }]`（即默认**只有淡出，没有位移**） | `[文档]` |
| 位移 duration | **117 ms**，仅当传入 offset 时才执行 | `[文档]` |
| 位移 timing | **linear** | `[文档]` |
| 位移 from → to | `none` → `translate(left, top)` | `[文档]` |
| 透明度 duration | **117 ms** | `[文档]` |
| 透明度 timing | **linear** | `[文档]` |
| 透明度 from → to | (当前) → `0` | `[文档]` |

### 2.3 从点击到应用出现，实际发生了几段

把 §3（点按）和 §2.2（进应用）串起来，Win8 的真实序列是：`[文档]`+`[推算]`

| 阶段 | 动画 | 时长 | 触发 |
|---|---|---|---|
| 1 | `TiltEffect` 倾斜 + 压深 | **0 ms**（瞬时到位） | 手指/鼠标按下 |
| 2 | 保持倾斜 | 按住多久算多久 | 按住 |
| 3 | `TiltEffect` 回弹 | **200 ms 延迟 + 100 ms 线性** | 抬起 |
| 4 | 开始屏幕退场 + 应用 `enterPage` | **1000 ms**（其中前 **170 ms** 完成淡入） | 应用首帧就绪 |

**第 4 段就是领导问的「进应用那段」，答案是 `enterPage`，不是缩放，不是翻转。**

### 2.4 搜索到的相关官方文档（备查）

| 文档 | URL |
|---|---|
| Animating page transitions (HTML) | <https://learn.microsoft.com/en-us/previous-versions/windows/apps/jj655417(v=win.10)> |
| Animating your UI（动画库总目录） | <https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh465165(v=win.10)> |
| Animating content transitions | <https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh465253(v=win.10)> |
| Animating pointer clicks (HTML) | <https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh465242(v=win.10)> |
| Control tilt effect for Windows Phone 8 | <https://learn.microsoft.com/en-us/previous-versions/windows/apps/ff941094(v=vs.105)> |
| Updated Tilt Effect（Peter Torr, MSFT） | <https://learn.microsoft.com/en-us/archive/blogs/ptorr/updated-tilt-effect> |
| 3-D Effects (Perspective Transforms) | <https://learn.microsoft.com/en-us/previous-versions/windows/silverlight/dotnet-windows-silverlight/dd470131(v=vs.95)> |
| PlaneProjection Class | <https://learn.microsoft.com/en-us/previous-versions/windows/silverlight/dotnet-windows-silverlight/dd502829(v=vs.95)> |
| WinJS 4.4.3 发行源码 base.js | <https://unpkg.com/winjs@4.4.3/js/base.js> |
| WinJS 4.4.3 发行源码 ui.js | <https://unpkg.com/winjs@4.4.3/js/ui.js> |
| TiltEffect.cs 官方样例源码转载 | <https://www.cnblogs.com/wgscd/articles/4883187.html> |

---

## §3 点按磁贴的姿态 —— TiltEffect 完整公式

### 3.1 微软官方文档对它的描述 `[文档]`

出处 <https://learn.microsoft.com/en-us/previous-versions/windows/apps/ff941094(v=vs.105)>：

> "Instead of having standard pressed or un-pressed states, controls with the tilt effect provide motion during manipulation. The result is a '**tilt**'-like response when the control is touched."

出处 <https://learn.microsoft.com/en-us/archive/blogs/ptorr/updated-tilt-effect>（原文注明该算法 **"direct from the shell team"**，即 Windows Phone 外壳团队本人的代码）：

> "This version uses a **global camera effect** to ensure that each object tilts not only around its own centre, but is **also angled towards the middle of the screen**."

### 3.2 姿态公式 `[文档]`（逐字对应源码）

```csharp
// normalizedPoint = 点击点在磁贴内的归一化坐标，nx / ny ∈ [0, 1]
double xMagnitude = Math.Abs(normalizedPoint.X - 0.5);
double yMagnitude = Math.Abs(normalizedPoint.Y - 0.5);
double angleMagnitude = xMagnitude + yMagnitude;              // ∈ [0, 1]
double xAngleContribution = xMagnitude / (xMagnitude + yMagnitude);

double angle = angleMagnitude * MaxAngle * 180 / Math.PI;     // MaxAngle = 0.3 弧度

projection.RotationY = angle * xAngleContribution * xDirection;        // xDirection = -Sign(nx - 0.5)
projection.RotationX = angle * (1 - xAngleContribution) * yDirection;  // yDirection =  Sign(ny - 0.5)
projection.GlobalOffsetZ = -(1 - angleMagnitude) * MaxDepression;      // MaxDepression = 25
```

以及**全局相机**（出处 B）：

```csharp
TranslateTransform transform = new TranslateTransform();
transform.X = centerDelta.X;                    // 把元素平移到容器中心
transform.Y = centerDelta.Y;
element.RenderTransform = transform;

PlaneProjection projection = new PlaneProjection();
projection.GlobalOffsetX = -1 * centerDelta.X;  // 再在 3D 里抵消回来
projection.GlobalOffsetY = -1 * centerDelta.Y;
element.Projection = projection;
```

**这带来一个非常容易被忽略、但视觉上很重要的后果：**
磁贴不只是绕自己中心转，而是**朝向屏幕正中倾斜**。
所以同一块屏幕左上角的磁贴和右下角的磁贴，被点同一个相对位置时，**朝向是不同的**。
如果只做「绕自身中心旋转」，做不出 Win8 的观感。`[文档]`

### 3.3 角度 / 压深对照表 `[推算]`（常量 `[文档]`）

| 点击位置 (nx, ny) | `angleMagnitude` | 总转角 | `RotationY` | `RotationX` | Z 压深 |
|---|---|---|---|---|---|
| (0.50, 0.50) 正中 | 0.000 | **0.0000°** | 0.0000° | 0.0000° | **−25.00 px** |
| (1.00, 0.50) 右边缘中点 | 0.500 | **8.5944°** | −8.5944° | 0.0000° | −12.50 px |
| (0.50, 1.00) 下边缘中点 | 0.500 | **8.5944°** | 0.0000° | +8.5944° | −12.50 px |
| (0.25, 0.75) 四分点 | 0.500 | **8.5944°** | +4.2972° | +4.2972° | −12.50 px |
| (0.00, 0.00) 左上角 | 1.000 | **17.1887°** | +8.5944° | −8.5944° | **0.00 px** |
| (1.00, 1.00) 右下角 | 1.000 | **17.1887°** | −8.5944° | +8.5944° | **0.00 px** |

**这张表里藏着 Win8 观感的关键：旋转与后退是此消彼长的，不是同时最大。**
- 点正中 → 完全不转，退得最远（25px）
- 点四角 → 转得最多（17.19°），完全不退（0px）
- 点边中点 → 各一半

### 3.4 时间轴

| 事件 | 时长 | 缓动 | 等级 |
|---|---|---|---|
| 按下 → 到位 | **0 ms**（瞬时赋值，无动画） | — | `[文档]` |
| 按住 | 不定 | — | — |
| 松开 → 等待 | **200 ms** | — | `[文档]` |
| 等待后 → 回弹到位 | **100 ms** | **linear**（默认），可选 `ln(t+1)/ln 2` | `[文档]` |

即**松开后不是立刻回弹，而是先停 200 ms**。这是 Win8 手感的一部分，很容易漏做。

### 3.5 WinJS 那条线（应用内磁贴 / 列表项）

如果实现的是**应用内**可点元素（不是操作系统外壳），Win8 给开发者的是缩放而非倾斜：

| 动画 | duration | timing | 效果 | 行号 |
|---|---|---|---|---|
| `pointerDown` | **167 ms** | `cubic-bezier(0.1,0.9,0.2,1)` | → **`scale(0.975, 0.975)`**（缩 2.5%） | base.js L17211–17238 |
| `pointerUp` | **167 ms** | `cubic-bezier(0.1,0.9,0.2,1)` | → `""`（回原状） | base.js L17241–17268 |

微软官方文档的措辞 `[文档]`（<https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh465242(v=win.10)>）：

> "The pointer down animation is played when a user taps or clicks on an item. **The animation slightly shrinks the item to indicate that it is pressed.** The pointer up animation is played when the tap or click is released. **This animation restores the item to its original size**, to indicate that it has been released."

Stack Overflow 上有人对着微软官方 pointerDown 演示视频逐帧看过，原话 `[文档]`（第三方观察，非微软）：
> "the 2nd tile **scales down immediately, stays scaled-down for few milliseconds then comes up to normal state**"
> —— <https://stackoverflow.com/questions/18611046>

**注意：`pointerDown`/`pointerUp` 是严格对称的**（同为 167 ms 同曲线，互为反向）。与 §4 的进出场不同。`[文档]`

---

## §4 进出场动画族全表（含正反对称性）

| 语义 | 进场 | 退场 | 对称吗 |
|---|---|---|---|
| 页面级进出 | `enterPage` 位移 **1000ms** 招牌曲线 + 淡入 **170ms** 招牌曲线，stagger `min(83i,333)` | `exitPage` **117ms linear**（位移+淡出），默认无位移 | ❌ **快 8.5 倍，且曲线族不同** |
| 内容级进出 | `enterContent` 位移 **550ms** + 淡入 **170ms**，均招牌曲线 | `exitContent` **117ms linear** | ❌ 同上 |
| 展开/收起 | `expand` 淡入 delay **200/0** + **167ms**；位移 delay **0** + **367ms** | `collapse` 淡入→淡出 delay **0** + **167ms**；位移 delay **167/0** + **367ms** | ❌ **顺序相反** |
| 列表增删 | `addToList` delay **240/0**，**120ms**，`scale(0.85)`→`none`，**招牌曲线** | `deleteFromList` delay **0**，**120ms**，`none`→`scale(0.85)`，**`cubic-bezier(0.11,0.5,0.24,.96)`** | ❌ **曲线不同** |
| 重排 | `reposition` **367ms** 招牌曲线，stagger `min(33i,250)` | — | — |
| 点按 | `pointerDown` **167ms** 招牌曲线 → `scale(0.975)` | `pointerUp` **167ms** 招牌曲线 → 原状 | ✅ **唯一严格对称的一对** |

**结论（回答第 6 问）：除了点按反馈，Windows 8 的进出场动画几乎都不是倒放关系。**
退场普遍**更快、更线性、更简单**——微软的设计意图是「退场不要拖沓」。

---

## §5 透视强度

### 5.1 官方文档对 `PlaneProjection` 相机距离的回答：**未取得** `[文档]`

领导第 2 问的答案是**否定式的，但这是有据可查的否定**：

`PlaneProjection`（Silverlight / Windows Phone，`TiltEffect` 依赖的正是它）
**根本没有 `PerspectiveDistance` 属性**。

出处 <https://learn.microsoft.com/en-us/previous-versions/windows/silverlight/dotnet-windows-silverlight/dd502829(v=vs.95)>
该页「Properties」表列出的**全部**属性只有 12 个：

`CenterOfRotationX`, `CenterOfRotationY`, `CenterOfRotationZ`,
`GlobalOffsetX`, `GlobalOffsetY`, `GlobalOffsetZ`,
`LocalOffsetX`, `LocalOffsetY`, `LocalOffsetZ`,
`RotationX`, `RotationY`, `RotationZ`
（另有只读的 `ProjectionMatrix`、继承来的 `Dispatcher`）

官方 "3-D Effects (Perspective Transforms)" 概览页
<https://learn.microsoft.com/en-us/previous-versions/windows/silverlight/dotnet-windows-silverlight/dd470131(v=vs.95)>
同样**通篇没有提到相机距离 / perspective distance**，只在最后说：

> "You can use the **Matrix3DProjection** and **Matrix3D** types for more complex semi-3D scenarios than are possible with the PlaneProjection. ... making it possible to apply **arbitrary model transformation matrices and perspective matrices** to Silverlight elements."

**因此：`PlaneProjection` 的透视是固定内部实现，微软从未公开其相机距离。`TiltEffect.cs` 里也没有显式设置它（因为它不存在）。**

> **这个值标为「未取得」。** 任何人给出一个具体数字（例如「1024px」）都应当被要求出示出处——
> 在官方文档里它不存在。需要精确控制透视时，唯一有文档支撑的做法是改用 `Matrix3DProjection` 自己给矩阵。

### 5.2 但透视强度可以直接算出来 `[推算]`（曲线 `[文档]`）

绕竖直中轴旋转 θ 时，近边 / 远边视觉高度比：

```
h_near / h_far = (k + sin(θ)/2) / (k - sin(θ)/2)        k = 相机距离 P / 磁贴宽度 W
```

（推导：CSS 式透视 `s(z) = P/(P-z)`；绕 Y 轴旋转后 `z(x) = -x·sin θ`，
故左边缘 `s_L = P/(P - (W/2)sinθ)`、右边缘 `s_R = P/(P + (W/2)sinθ)`。完整推导与代码见 `win8-measure.py` 的 `tall_ratio()`。）

| k = P/W | θ=4° | θ=8.594° | θ=12° | **θ=17.1887°（Win8 最大值）** |
|---|---|---|---|---|
| 0.500 | 1.0724 | 1.1592 | 1.2264 | **1.3576** |
| 0.655 | 1.0546 | 1.1190 | 1.1695 | **1.2681** |
| **1.000** | 1.0354 | 1.0766 | 1.1087 | **1.3467** |
| 1.500 | 1.0234 | 1.0502 | 1.0711 | **1.2224** |
| **2.000** | 1.0175 | 1.0374 | 1.0527 | **1.1596** |
| 3.000 | 1.0116 | 1.0247 | 1.0348 | **1.1037** |
| 4.000 | 1.0087 | 1.0185 | 1.0260 | **1.0767** |
| 5.000 | 1.0069 | 1.0148 | 1.0207 | **1.0609** |
| 8.000 | 1.0043 | 1.0092 | 1.0129 | **1.0377** |

**核心结论：θ 最大只有 17.1887°，所以 Win8 的透视畸变天生很弱。**
即便相机贴到 k=1（相机距离 = 1 倍磁贴宽），近/远比也只有 **1.3467**。

### 5.3 与本项目现有实现的直接对比 `[推算]`

本项目基线（`docs/flip-baseline.md`）：**近/远比 1.582，总时长 600 ms，绕竖直中轴，右边缘转进屏幕**。

反解 `k = sin(θ)/2 · (r+1)/(r-1)`：

| 目标近/远比 | 需要 k | 相当于 CSS perspective（磁贴宽 200px） |
|---|---|---|
| 1.05 | 6.0422 | 1208 px |
| 1.10 | 3.0963 | 619 px |
| 1.20 | 1.6242 | 325 px |
| 1.347（Win8 在 k=1 的理论极限） | 1.0000 | 200 px |
| **1.582（本项目现状）** | **0.6555** | **131 px** |
| 2.0 | 0.4433 | 89 px |
| 3.0 | 0.2955 | 59 px |

**换句话说：本项目现在的透视强度（1.582）等效于把相机放在只有 0.66 倍磁贴宽的地方，
比 Win8 在最大倾角下最夸张的情况（1.3467 @ k=1）还要强，而且是 Win8 常见取值（k=3~8）的 3~7 倍。**

如果目标是「和 Win8 磁贴动画一模一样」，**透视要大幅减弱，而不是加强**。

### 5.4 旋转引起的宽度收缩 `[推算]`

| θ | 轮廓宽度 / 原宽（k=2，磁贴 200px） | `cos θ` |
|---|---|---|
| 0° | 1.00000 | 1.00000 |
| 4° | 0.99758 | 0.99756 |
| 8.594° | 0.98877 | 0.98877 |
| 12° | 0.97819 | 0.97815 |
| **17.1887°** | **0.95535** | **0.95535** |

k=2 时透视的宽度增益与 `cos θ` 几乎完全抵消，所以
**「由轮廓宽度反解转角」是可行的**：`θ ≈ arccos(w / w₀)`。
`probe` 模式因此同时输出 `h_left` / `h_right` / `w` 三个原始量，让使用者自选反解路径。

---

## §6 角度 / 时间采样表

### 6.1 `cubic-bezier(0.1, 0.9, 0.2, 1)` 输出进度 `[推算]`

用与浏览器相同的三次贝塞尔求解（代码见 `win8-measure.py` 的 `bezier_y_for_x()`）：

| 时间进度 | 输出进度 | 与线性的差 |
|---|---|---|
| 0% | 0.0000 | +0.0000 |
| 10% | 0.2497 | +0.1497 |
| 20% | 0.4643 | +0.2643 |
| 30% | 0.6389 | +0.3389 |
| 40% | 0.7764 | +0.3764 |
| 50% | 0.8761 | +0.3761 |
| 60% | 0.9416 | +0.3416 |
| 70% | 0.9793 | +0.2793 |
| 80% | 0.9964 | +0.1964 |
| 90% | 0.9998 | +0.0998 |
| 100% | 1.0000 | 0.0000 |

**这是典型的「强起步、长收尾」曲线**：前 20% 的时间就走完 **46.4%** 的行程，
一半时间走完 **87.6%**。剩下的一半时间只走 12.4%，制造「稳稳停住」的手感。

### 6.2 退场曲线 `cubic-bezier(0.11, 0.5, 0.24, .96)` `[推算]`

| 时间进度 | 输出进度 |
|---|---|
| 0% | 0.0000 |
| 10% | 0.1160 |
| 20% | 0.2495 |
| 30% | 0.3925 |
| 40% | 0.5360 |
| 50% | 0.6698 |
| 60% | 0.7852 |
| 70% | 0.8773 |
| 80% | 0.9441 |
| 90% | 0.9860 |
| 100% | 1.0000 |

比招牌曲线**平缓得多**，几乎是线性偏一点。配合 117 ms 的短时长，「嗖地一下就没了」。`[推算]`

### 6.3 `enterPage` 的分区错峰时间轴 `[推算]`（公式 `[文档]`）

| 分区 index | delay `min(83i, 333)` | 位移结束时刻 | 淡入结束时刻 |
|---|---|---|---|
| 0 | 0 ms | 1000 ms | 170 ms |
| 1 | 83 ms | 1083 ms | 253 ms |
| 2 | 166 ms | 1166 ms | 336 ms |
| 3 | 249 ms | 1249 ms | 419 ms |
| 4 | 332 ms | 1332 ms | 502 ms |
| ≥5 | 333 ms（封顶） | 1333 ms | 503 ms |

**整段动画最长 1333 ms，但视觉上 170 ms 内主内容就已经可见了。**

---

## §7 与本项目现状的差异清单

| 维度 | 本项目现状（`docs/flip-baseline.md`） | Windows 8 | 差异 |
|---|---|---|---|
| 动画类型 | 绕竖直中轴 3D **翻转** | **无翻转**；进出 = 2D 位移 + 淡入淡出 | **根本性差异** |
| 转轴 | 单一竖直中轴（右边缘转进屏幕） | 点按：双轴（竖直 + 水平）按点击位置分配；进出：**无旋转** | 不同 |
| 总转角 | （翻转类动画，通常 ≥90°） | **最大 17.1887°**，且点正中为 **0°** | **量级差异** |
| 总时长 | **600 ms** | 进出：`enterPage` **1000 ms**（位移）/ **170 ms**（淡入）；`exitPage` **117 ms** | 时长需拆成两条轨道 |
| 缓动 | （未记录） | **`cubic-bezier(0.1, 0.9, 0.2, 1)`** | 可直接采用 |
| 透视 | 近/远比 **1.582**（等效 k=0.656，相机仅 131px） | k=3~8 时 **1.0377~1.1037** | **本项目透视强 3~7 倍** |
| 退场 | （未记录） | **117 ms linear**，比进场快 **8.5 倍** | 若做成对称倒放则不对 |
| 全局相机 | 无 | 磁贴**朝向屏幕中心**倾斜（`GlobalOffsetX/Y` 抵消） | 缺失这一层就没有 Win8 观感 |

### 建议的改造方向（供领导决策，非本文件结论）

如果目标是「**一模一样**」：
1. **放弃 3D 翻转。** Win8 里没有这个东西。
2. 进场改成 **`enterPage` 语义**：位移 `translate(0, 28px)` → `0`，
   **1000 ms** `cubic-bezier(0.1,0.9,0.2,1)`；同时 opacity `0→1` **170 ms** 同曲线；
   多个分区用 `delay_i = min(83i, 333)` 错峰。
3. 退场改成 **`exitPage` 语义**：**117 ms linear** 淡出（默认无位移）。
4. 如果节点图的进出更像「一块东西展开成整页」，用 **`expand`/`collapse`**：
   淡入 **167 ms**（进场 delay 200ms）+ 位移 **367 ms**；退场顺序反过来。
5. 如果还想要「点一下有反馈」，用 **`pointerDown`/`pointerUp`**：
   **167 ms** `cubic-bezier(0.1,0.9,0.2,1)`，`scale(0.975)`，严格对称。
6. 若坚持保留 3D 感，**至少把透视降到 k=3~8**（近/远比 1.04~1.10），
   并把转角压到 **≤17.19°**，同时补上「朝向屏幕中心」的全局相机。

---

## §8 未取得项与不确定性（如实列出）

### 8.1 完全未取得

| 项目 | 状态 | 说明 |
|---|---|---|
| **任何 Win8 实拍帧** | **本文件未取得；队友 B 组取得了** | 本机出网被封死，YouTube/archive.org/giphy/imgur/github 均不可达；Wikimedia Commons 无 Win8 磁贴点击录像；本机无 ffmpeg。详见 `win8-frames/README.md`。**后来队友 `win8-ref-b` 走共享浏览器路线抓到了真实录像**（`win8-frames-b/`，bilibili `BV1Us411f7bc`，1280×720@30fps）；他们的量测是**否定结果**：43 帧内磁贴宽度比恒为 1.0000、近/远边高比 ≈1.01–1.03，**磁贴没有变形**，随后整屏硬切。**这独立印证了 §2，但不否定 §3**（TiltEffect 按下 0 ms 瞬时，30 fps 很可能整个错过） |
| **`PlaneProjection` 的相机距离** | **未取得** | 该属性在官方 API 中不存在（§5.1），微软从未公开 |
| **Win8 开始屏幕外壳自身「启动应用」的确切动画曲线** | **部分未取得** | 官方文档明确说 app launch 用 page transition（§2.1），但**外壳（explorer.exe 的 Start screen）内部实现参数不是公开 API**，无法从文档取得 |

### 8.2 已用官方样例源码旁证的项（可信但非「产品参数」）

- §3 的 `TiltEffect` 常量来自**微软官方发布的样例**，源码注释自称
  `// These constants are the same as the built-in effects`，
  且 Peter Torr 博客注明算法 **"direct from the shell team"**。
  这**强有力地支持**这些数就是 Win8/WP8 实际使用的值，但严格说它是「官方样例」而非「产品二进制里 dump 出来的常量」。
- `MaxAngle = 0.3` 的单位是**弧度**，这是本次最容易读错的地方——
  0.3 若当角度理解会是灾难性的错误，务必注意（0.3 rad = 17.1887°）。

### 8.3 `[推测]` 条目（无出处，仅推理）

- **Windows 8 桌面开始屏幕的磁贴是否与 Windows Phone 用完全同一套 `TiltEffect` 常量**：
  算法来自「shell team」，且 Stack Overflow 上有人报告桌面版观感与手机版一致
  （<https://stackoverflow.com/questions/27821464>，原话 *"On both windows and windows phone, the tiles on the start screen animate when the user clicks on them... the tile gets 'pushed back' depending on where the tile was clicked"*），
  但**我没有找到微软明确说「桌面版就是这个常量」的文档**。标 `[推测]`。
- **Windows Phone 的 live tile 前后翻转（`FlipTileTemplate`）是否存在、轴向与角度为何**：
  本文件**没有**覆盖这一项。它是磁贴**内容定时轮换**的动画，不是点击反馈，也不是页面进出。
  如果领导要的其实是这个，需要另开一轮调研。**不要用本文件的数字去实现它。**

### 8.4 已纠正的错误

- 领导中间报告里我曾写招牌曲线「共 86 处」。**正确数字是 60 处**（base.js 52 + ui.js 8），
  已按实测计数更正（见 §1.2）。退场曲线 `cubic-bezier(0.11,0.5,0.24,.96)` 是**恰好 2 处**，仅 `deleteFromList` 使用。
- 早期笔记曾把 `enterContent` 的默认 offset 记作 `{top:"0px", left:"40px"}`。
  **直接读源码后确认是 `[{ top: "28px", left: "0px", rtlflip: false }]`**（base.js L17427），已更正。

---

## §9 复现方式

```powershell
# 重新生成测量报告 + 全部重建帧
python docs\win8-measure.py

# 测量真实截图（将来拿到 Win8 录像后用这个）
python docs\win8-measure.py probe <图片或目录>
```

产出：
- `docs/win8-frames/measure-report.md` —— 逐帧几何测量报告
- `docs/win8-frames/sim/*.png` —— 16 张重建帧
- 脚本自带自校验：把重建帧再测一遍，与解析值对比，当前偏差 ≤ **0.44%**（亚像素栅格化误差）。

---

## §10 【重要修正】`turnstile` —— Win8 里真正的「绕轴旋转进出」动画
> **本节是后来补上的，优先级高于 §1–§8 里所有「Win8 没有旋转」的说法。**
> 那些说法针对的是**磁贴按压**（TiltEffect）和 **WinJS 动画库**（`enterPage` 等），
> 在本节所讲的**导航转场族**里并不成立：
> Win8 的页面转场**确实有绕 Y 轴的 3D 旋转，并且官方源码里显式写明了透视距离**。
>
> 出处：<https://unpkg.com/winjs@4.4.3/js/base.js>（WinJS 4.4.3 发行版，未压缩）
> 实现体逐字导出见 `docs/win8-frames/winjs-transitions.txt`，
> 时间轴与透视表见 `docs/win8-frames/turnstile-timeline.md`。

## 10.1 四个函数的逐字参数 `[文档]`

四个函数在源码里是**成对**的，`Forward` = 「进入下一层」，`Backward` = 「返回上一层」。

| 函数 | 行号 | 变换 `from` → `to` | 时长 | 缓动曲线 |
|---|---|---|---|---|
| `turnstileForwardIn` | L17934 | `perspective(600px) rotateY(80deg)` → `rotateY(0deg)` | **300 ms** | `cubic-bezier(0.01,0.975,0.4775,0.9775)` |
| `turnstileForwardOut` | L17972 | `perspective(600px) rotateY(0deg)` → `rotateY(-50deg)` | **128 ms** | `cubic-bezier(0.4925,0.01,0.7675,-0.01)` |
| `turnstileBackwardIn` | L18010 | `perspective(600px) rotateY(-50deg)` → `rotateY(0deg)` | **300 ms** | `cubic-bezier(0.01,0.975,0.4775,0.9775)` |
| `turnstileBackwardOut` | L18048 | `perspective(800px) rotateY(0deg)` → `rotateY(80deg)` | **128 ms** | `cubic-bezier(0.4925,0.01,0.7675,-0.01)` |

四条都不只是旋转，**每一条都同时挂了一条 opacity 轨道**：

| 函数 | opacity | 时长 | 缓动曲线 |
|---|---|---|---|
| `turnstileForwardIn` / `turnstileBackwardIn` | 0 → 1 | 300 ms | `cubic-bezier(0, 2, 0, 2)` |
| `turnstileForwardOut` / `turnstileBackwardOut` | 1 → 0 | 128 ms | `cubic-bezier(1,-0.42,0.995,-0.425)` |

两条轨道共用同一个 delay：`staggerDelay(0, 50, 1, 1000)`。
按 §1.5 的 `staggerDelay` 实现，即 **第 i 个元素延迟 `min(50·i, 1000)` ms**。

## 10.2 转轴在哪里 `[文档]`

转轴由 `collectTurnstileTransformOrigins`（L16251）算出来，源码原文：

```js
var itemBoundingBox = elements[i].getBoundingClientRect();
var offsetLeftLTR = -(40 + itemBoundingBox.left);
var offsetLeftRTL = 40 + (_Global.innerWidth - itemBoundingBox.right);
var totalOffsetY = ((_Global.innerHeight / 2) - itemBoundingBox.top);
```

`transform-origin` 是**元素自身坐标系**里的值，所以换算回页面坐标：

| | 元素局部坐标 | 换算到页面坐标 |
|---|---|---|
| LTR 的 X | `-(40 + element.left)` | `element.left - (40 + element.left)` = **−40** |
| RTL 的 X | `40 + (innerWidth − element.right)` | `element.right + 40 + innerWidth − element.right` = **`innerWidth + 40`** |
| Y（两个方向都一样） | `innerHeight/2 − element.top` | **`innerHeight / 2`** |

**结论：转轴是一条竖直线，位于视口外侧 40px 处（LTR 在左、RTL 在右），Y 取视口垂直中心。
而且这个轴是「页面绝对坐标」，与元素自身位置、宽度都无关** ——
所有被动画的元素共用同一条轴，所以它们会像**同一扇旋转门上的不同板条**一样一起转。这就是 "turnstile"（旋转门）这个名字的来历。

轴由 `animRotationTransform`（L16191）实际写入，它逐元素设 `transform-origin`，
动画结束后在 `clearAnimRotationTransform`（L16204）里把 `transform-origin` / `transform` / `opacity` 一起清空。

## 10.3 透视强度（本项目最需要的一个数）`[推算]`（公式与曲线 `[文档]`）

把上面的几何代入 CSS 透视公式即可算。**完整逐帧表见 `docs/win8-frames/turnstile-timeline.md`**，此处只给结论。

### 10.3.0 先修正一处早期错误（2026-10-15 复核）

> **本节早先给出的 `3.1039` 是错的，正确值是 `1.7393`。**
> 公式本身没错，错在**参考视口宽 `W` 的取值**：早先用了 `W=1366`（桌面），
> 而 turnstile 的 `perspective(600px)` 隐含的参考视口是 **480**（见 §10.3.2）。
> 复核者（领导）按 CSS 矩阵独立重推并指出该值偏大，**复核意见成立**：
> 用 `W=1366` 会把右边缘压到 `0.3023`，从而把比值抬到 3.10。
> 但需澄清一点：**这不是「多乘了一次因子」** —— 两侧代入的是**同一个** `scale(d) = P/(P + d·sinθ)`，
> 左边缘在两种 W 下都算出 `0.9384`（与复核值一致），差异**全部**来自右边缘的 `d = W + 40`。
>
> 复核给的两组数现已**逐位复现**：
> `W=480, P=600, θ=80°` → 左 `0.93839` / 右 `0.53952` / 比 `1.73931` ✅
> `W=1440, P=1800, θ=80°` → 左 `0.97858` / 右 `0.55257` / 比 `1.77097` —— ⚠️ **这一行后来被更正，见下**
>
> ⚠️ **再更正（独立验收后，2026-…）**：上面第二行把 `axis` 当成**原始像素** `40px`，
> 于是 `axis/W = 40/1440 = 2.78%`，与官方的 `40/480 = 8.33%` **不等** —— 形状因此走样，`1.77097` 是**错的**。
> **CSS px 是设备无关单位**，本机屏宽 **480 dp** ⇒ `40px ≡ 40dp = 120px`、`600px ≡ 600dp = 1800px`，
> **两者必须同时按 density 缩放**。按此（`axis = −120`）复算：
> `W=1440, P=1800, θ=80°` → 左 `0.93839` / 右 `0.53952` / 比 **`1.73931`** ✅
> ⇒ **与官方逐位相同，偏差 `0.0000%`**。即「比例读法」若把 `axis` 一起缩，**在任何屏宽下都精确等于官方几何**，
> 是官方几何的**精确相似变换**，不存在「二阶误差」。
> 验证脚本：[`win8-frames/_verify-impl.py`](win8-frames/_verify-impl.py)（三种读法并排 + 不退化余量）。
>
> 复算脚本：`docs/win8-frames/_turnstile-final.py`（含两侧 sanity 断言）。

### 10.3.1 `scale(d)` 的推法 `[推算]`

设转轴在屏幕左边缘外 `axis = 40px` 处，`perspective-origin` 默认在容器中心。
变换链是 `T(origin) · perspective(P) · rotateY(θ) · T(−origin)`，对平面上任一点取 `d = x − x_axis`：

```
rotateY:   (x,0,0)  ->  (x·cosθ, 0, −x·sinθ)          # +z 朝观察者
perspective: 深度 z 的缩放因子 = P / (P − z)
代入 z = −d·sinθ:
        scale(d) = P / (P + d·sinθ)
左边缘  d = axis      (在轴右侧 40px)
右边缘  d = W + axis
```

**`perspective-origin` 取在哪，不影响「左右边缘高度比」** —— 因为每个点的高度缩放因子只取决于该点自身深度 `z`，
`perspective-origin` 只决定投影中心落在屏幕何处（即整体平移），不改变同深度点之间的相对比例 `[推算]`。

### 10.3.2 比值曲线（`W=480, P=600, axis=40`）

| 时刻 | 缓动进度 | 转角 θ | 左边缘高 | 右边缘高 | **近/远比** |
|---|---|---|---|---|---|
| 进场 t=0（80°） | 0.0000 | +80.00° | 0.9384 | 0.5395 | **1.7393** |
| 进场 t=10% | 0.5961 | +32.31° | 0.9656 | 0.6834 | **1.4129** |
| 进场 t=25% | 0.8116 | +15.07° | 0.9830 | 0.8161 | **1.2044** |
| 进场 t=50% | 0.9444 | +4.45° | 0.9949 | 0.9370 | **1.0617** |
| 进场 t=75% | 0.9862 | +1.10° | 0.9987 | 0.9836 | **1.0153** |
| 进场 t=90% | 0.9956 | +0.35° | 0.9996 | 0.9948 | **1.0049** |
| 进场 t=100% | 1.0000 | 0° | 1.0000 | 1.0000 | **1.0000** |
| 退场 t=50% | 0.0664 | −3.32° | 1.0039 | 1.0529 | **1.0488** |
| 退场 t=70% | 0.2277 | −11.38° | 1.0133 | 1.2064 | **1.1905** |
| 退场 t=90% | 0.6353 | −31.77° | 1.0364 | 1.8392 | **1.7746** |
| 退场 t=100% | 1.0000 | −50.00° | 1.0538 | 2.9754 | **2.8234** |

注意方向：**进场右边缘在远处（缩小），退场右边缘转到近处（放大）** —— 因为进场是 `+80° → 0`，
退场是 `0 → −50°`，**符号相反**，所以同一侧在两个方向上一个是远边、一个是近边。

### 10.3.3 关于相机平面（修正早先的「模型失效」）`[推算]`

透视投影在 `P + d·sinθ ≤ 0` 时无定义。把 `d` 的最大值 `W + axis` 代入：

```
穿越相机平面的条件:  W > P / |sinθ| − axis      （仅当 θ < 0 时可能发生）
θ = −50°, P = 600, axis = 40  ->  W_max = 743.2 px
```

- **`W=1366` 会失效**（`1366 > 743`）—— 这正是早先「退场最后 10% 算不出来」的原因，
  也就是说**那条「模型失效」结论同样是 `W=1366` 假设的产物，不是 Win8 的固有性质**。
- **`W=480` 永不失效**（`480 < 743`），所以 §10.3.2 的退场行是完整的。
- **θ > 0（进场方向）永远安全**：此时 `d` 恒为正，`P + d·sinθ` 恒大于 `P`。

只有在**参考视口宽确实很大**（>743px）的实现里，才需要担心 −50° 时右边缘穿到相机背后；
官方源码把 `−50°` 和快速淡出绑在一起，本就是一种回避。**若按 §10.3.2 的 W=480 标尺实现，这个坑不存在。**

## 10.4 正反对称性 —— 又一次「**不是**严格倒放」 `[文档]`

| 对比项 | 进场 | 退场 | 差异 |
|---|---|---|---|
| 时长 | 300 ms | **128 ms** | 退场快 **2.34 倍** |
| 缓动曲线 | `cubic-bezier(0.01,0.975,0.4775,0.9775)` | `cubic-bezier(0.4925,0.01,0.7675,-0.01)` | **完全不同的曲线** |
| 转角 | 80° → 0° | 0° → −50° | **幅度也不对称**（80° vs 50°） |
| 透视距离 | 600 px | 600 px（ForwardOut）/ **800 px**（BackwardOut） | Backward 的退场换了相机距离 |

这与 §1.2 / §1.3 里 `enterPage` 1000 ms vs `exitPage` 117 ms 是**同一种设计取向**：
**进入要慢、要有仪式感；退出要快、要干脆。** Win8 里没有任何一组进出是严格镜像的。

值得单独注意：**进场的曲线是一条强 ease-out**（`y1=0.975`），
即 300ms 里角度**在前 10% 的进度内就走完了 59.6%**（见 10.3 表第 2 行）；
而退场的曲线 `y1=0.01, y2=-0.01` 是**先慢后快**，最后 10% 才走完 36% 的角度。
**两条曲线的「手感」方向是相反的。**

## 10.5 与本项目现有进出动画的对比 `[推算]` + 本项目自测

> 本节 `1.582` 等数字是**本项目自己连拍量出来的**，不是 Win8 实测，
> 所以**不标 `[实测]`** —— 按本文档开头的定义，`[实测]` 专指「从真实 Win8 录像帧量得」，仍是 **0 条**。

本项目代码里现在有**两档**（`ProgramScreen.kt` L1007 `FLIP_PROFILE`，当前默认 `WIN8`）：

| 项 | Win8 `turnstile` | 本项目 `FLIP` 档（旧的翻转） | 本项目 `WIN8` 档（已实现，默认） |
|---|---|---|---|
| 旋转轴 | **视口外侧 40px 的竖直线** | 元素自身竖直中轴 | **无旋转** |
| 转角 | 80° → 0°（进）/ 0° → −50°（退） | 0° → 90° → 0°（翻面） | **0°** |
| 透视距离 | **显式 `600px`** | 等效约 131 px（设计值 `1.5 × 宽`） | **不做透视** |
| **近/远比（峰值）** | **1.7393**（⚠️ 见 §10.2 corrigendum；早前写的 3.1039 是错的） | **1.582**（本项目连拍自测） | **1.0000** |
| 是否有背面 | 没有，始终同一个面 | 有正反面 | 没有 |
| 时长 | 300 ms（进）/ 128 ms（退） | 600 ms（两个方向相同） | 367 ms（进）/ 534 ms（退） |
| 内容 | 随元素一起转 + opacity 淡入/淡出 | 翻面 | 长大 + 节点图淡入 |

数字出处：`FLIP_MS = 600`（L1012）、`PERSPECTIVE_DISTANCE_FACTOR = 1.5f`（L1085）、
`WIN8_ENTER_MS = 367` / `WIN8_EXIT_MS = 534` / `WIN8_MOVE_MS = 367` / `WIN8_FADE_MS = 167`
（L1034–L1041）、`WIN8_EASE = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)`（L1046）、
`WIN8_EASE_EXIT = CubicBezierEasing(0.11f, 0.5f, 0.24f, 0.96f)`（L1049）；
`1.582` 来自 `docs/flip-baseline.md` 的逐帧实测。

**一句话结论（给领导）：**
用户说「和 Windows 8 磁贴动画一模一样」。Win8 里对应「进出」的语言**不止一种**，
而它们对「要不要旋转 / 要不要透视」的回答是**相反**的：

| 如果用户的「进出」指的是… | 正主 | 有旋转吗 | 透视强度 |
|---|---|---|---|
| ① 点磁贴那一瞬间的按压反馈 | §3 `TiltEffect` | 有，但只有 ≤17.19° | 极弱（≤1.35） |
| ② 一块东西「长大成整屏」 | §4 `expand` / `collapse` | **没有** | **没有** |
| ③ 列表 ↔ 整屏的**转场进出** | **§10 `turnstile`** | **有，80°** | **强（1.7393）** |

**本项目的结构（卡片列表 → 全屏节点图）在 Win8 里对应的是 ③「页面导航」**，
`turnstile` 在源码里的注册名就叫 `WinJS.UI.PageNavigationAnimation.turnstile`（base.js **L15422**，文档注释 L15419）。
所以**如果用户要的是「进出」这个转场本身，正主是 `turnstile`**：
本项目现有的 1.582 已达它的 **91%**（1.582 / 1.7393），而现在的 `WIN8` 档是 **1.0000（完全没有透视）**。
⇒ **真正缺的不是「更强的透视」，而是「转轴在元素外面」这个几何**：两者数值接近，
但一个是绕自身中轴翻（左右对称压窄），一个是绕屏外轴转（左右一边压窄一边几乎不变），**观感完全不同**。
**要「一模一样」，透视不是要减弱，而是要显著增强，并且旋转轴要从元素中心挪到容器外侧 40px。**

**但如果用户要的是「磁贴被按住的那个手感」，那 `WIN8` 档是对的**（§3 + §4），
`turnstile` 会被用错地方。**这一条必须由领导找用户确认，两条路的技术方案完全不同。**

## 10.6 本节的不确定项（如实列出）

1. **`turnstile` 是「页面导航转场」，不是「点磁贴开应用」的动画。** `[文档]`
   §2 已确认点磁贴开应用走的是 `enterPage`（无旋转）。
   `turnstile` 用于**应用内**页面之间的前进/后退导航。
   **用户要的到底是哪一个，仍需领导与用户确认** —— 这是本节最大的不确定项，
   也是最可能改变实现方向的一条。
2. **`cubic-bezier(0, 2, 0, 2)`（进场 opacity 曲线）的 y 控制点超出 [0,1]**，
   意味着中间会**冲过 1 再回落**（`y1=2`）。对 opacity 而言会被 clamp 到 1，
   所以实际观感是「很快到不透明然后保持在 1」。这是 `[文档]`（源码原文如此），但**实际渲染行为未实测**。
3. `turnstile` 的 `staggerDelay(0, 50, 1, 1000)` 是**逐元素错峰**（第 i 个延迟 `min(50i, 1000)` ms），
   本项目是**单个卡片**动画，没有元素数组 —— **错峰这一层在本项目里用不上** `[推测]`。
4. 与 §5.1 一样：**本节的 `[实测]` 条目为 0** —— 全部结论来自**官方发行源码原文 + 数学推算**。
   本机出网被封死；队友 `win8-ref-b` 另行取到了真实录像，其 `[实测]` 结论见 `docs/win8-b-frames/`。

---

## 11. 如果选 ③ turnstile：可直接落地的参数

> 本节是给**实现者**的，不是新证据。所有数字都能回溯到 §10 的源码行号；
> 其中「标尺不变性」是本节新增的数学结论（`docs/win8-frames/_scaleinv.py` 可复跑）。

### 11.1 透视距离怎么定 —— 一个**未解决**的二选一 `[推测]`（重要）

先说**能严格成立**的那条 `[推算]`：

```
投影形状只取决于两个无量纲量：  k = P / W     和     a = axis / W      （外加 θ）
严格的不变性 = 把 P、W、axis 三者同时乘同一个系数（此时 k、a 都不变）。
```

数值验证到小数点后 12 位：`(P,W,axis)` 与 `(kP, kW, k·axis)` 给出的近/远比**完全相同**
（脚本 `docs/win8-frames/_scaleinv.py`）。

> **但 WinJS 源码里 `40px` 是写死的绝对值，不是 `W` 的百分比** ——
> 所以**把 W 换掉而不同时缩放 40px，形状是会变的**。这一点必须说清楚，
> 否则会误以为「只要 P/W 对就行」。

于是有两种读法，**源码本身不能区分，我也没有找到任何官方文档说明参考视口宽**：

| 读法 | 规则 | W=480 时 | W=1366 时 | W=1440 时 | 依据 |
|---|---|---|---|---|---|
| **A. 绝对值**（字面） | `P = 600px` 恒定，axis 也恒 `40px` | 比 1.7393 | 比 **3.11** | 比 **3.11** | 源码就是字面量 `perspective(600px)` |
| **B. 比例值**（领导采用） | `P = 1.25 × W` **且** `axis = 40 × (W/480)` | 比 1.7393 | 比 **1.7393** | 比 **1.7393** | 假定 600 是为 480 宽视口调的 |
| ~~B'. 只缩 P 不缩 axis~~（**错**） | `P = 1.25 × W`，axis 恒 `40px` | 比 1.7393 | 比 1.7701 | 比 **1.7710** | —— 这是**算错**的读法，已被独立验收推翻 |

> ⚠️ **关键更正（独立验收后）**：早前把 B 写成「只缩 `P`、`axis` 仍取 40px」，
> 得到 1.7710 并把它描述成「与官方 1.7393 的二阶差异」。
> **那是错的**：只要 `axis` 一起按同一比例缩放，B **在任何屏宽下都精确等于官方几何**（比恒为 1.7393），
> 是官方几何的**精确相似变换**，**没有任何误差**。
> 而「axis 随 W 缩放」正是 **CSS px ≡ 设备无关单位** 的正确读法 ——
> 本机屏宽 **480 dp**，所以 `40px ≡ 40dp = 120px`、`600px ≡ 600dp = 1800px`，**两边同时缩放**。
> **项目实际实现采用的正是这一条**（`TURNSTILE_ORIGIN_X = (-40).dp`、`TURNSTILE_PERSPECTIVE_IN = 600.dp`），
> 独立复算偏差 **0.0000%**。验证脚本：[`win8-frames/_verify-impl.py`](win8-frames/_verify-impl.py)。

**两种读法在手机宽度下一致，在大屏上差近 2 倍（1.77 vs 3.11）** —— 这不是舍入差异，是观感差异。
`[推测]` 我倾向 **A**：`perspective(600px)` 是硬编码字面量，同一份 WinJS 要跑在 480 的手机和 1366 的桌面上，
若作者想按比例缩放就会写成媒体查询或运行时计算，而源码里没有。
但这只是**倾向**，**没有证据**，请领导按项目目标屏（1440×3200）自行定夺。

**如果按 B 走（领导工单里的 `1440×3200 ⇒ P=1800`）**，则 axis 若仍取 40px，
`a = 40/1440 = 2.78%`，与 480 宽下的 `8.33%` 不同 —— 严格复刻应让 axis 也随宽度缩放：
`axis = 40 × (W / 480)`，即 W=1440 时 axis ≈ 120px。**否则转轴贴边程度会不一样。**

### 11.1b 为什么「绑视口」比「绑卡片」重要 `[实测]`（基于本项目源码）

无论选 A 还是 B，有一条是确定的：**P 必须绑视口（或全屏容器），不能绑卡片自身宽度。**
理由见 §11.2。

### 11.2 反面案例：本项目当前做法会让透视在动画中途变掉 `[实测]`（基于本项目源码）

本项目现在用 `PERSPECTIVE_DISTANCE_FACTOR = 1.5` **乘卡片自身宽度**。
卡片从 340px 长到 1366px 的过程中，透视距离跟着从 510px 长到 2049px，
于是 80° 时的近/远比从 **3.4486 一路掉到 1.6442**：

| 卡片宽 | P = 1.5 × 卡片宽 | 80° 时近/远比 |
|---|---|---|
| 340px | 510px | **3.4486** |
| 700px | 1050px | **2.2349** |
| 1366px | 2049px | **1.6442** |

Win8 在整个 300ms 里透视强度**只由 θ 决定**（见表 10.3.2），不会因为元素变大而自己变弱。
**这是「一模一样」一个容易被忽略但肉眼可见的差异点** —— 把 `P` 改成绑视口宽即可对齐
（具体取 A 读法还是 B 读法见 §11.1）。

### 11.3 落地参数清单（Compose `graphicsLayer` 对应关系）

| Win8 源 | 值 | Compose 写法 |
|---|---|---|
| `perspective(600px)` | 见 §11.1：A 读法取恒定 600，B 读法取 `1.25 × 视口宽` | `cameraDistance = <按 §11.1 定>`；**不要绑卡片宽** |
| 转轴 `W + 40px` | 左边缘外 40px（**绝对值**；若按 B 读法严格等比，应取 `40 × W/480`） | `transformOrigin = TransformOrigin(pivotX / width, 0.5f)`，`pivotX = -axisPx` |
| `rotateY(80deg)` → `0deg` | 进场 300ms | `rotationY = 80f → 0f` |
| `cubic-bezier(0.01, 0.975, 0.4775, 0.9775)` | 进场曲线 | 见 §10.4 的采样表；Compose 无内置等价物，需自建 `Easing` 或按表分段 |
| `rotateY(0deg)` → `-50deg` | 退场 128ms | `rotationY = 0f → -50f` |
| `cubic-bezier(0.35, 0.15, 0.65, 0.98)` | 退场曲线 | 同上 |
| `WinJS-opacity-in`: 667ms `cubic-bezier(0,2,0,2)` | 迟到 opacity | `alpha` 动画，起始延迟 333ms（`staggerDelay(0,333,1,333)`） |
| `WinJS-opacity-out`: 128ms `linear` | 退场淡出 | 与退场旋转同起同止 |

> **注意 opacity 的两个坑** `[文档]`：
> 1. 进场 opacity 用 `cubic-bezier(0, 2, 0, 2)`，y 控制点 = 2 **超出 [0,1]**，会冲过 1 再回落；
>    对 alpha 而言被 clamp，观感是「很快不透明然后保持」。
> 2. 进场 opacity 的 delay 是 `staggerDelay(0, 333, 1, 333)` = **恒定 333ms，不随索引增长**
>    （factor=1 时循环第一轮就让 extraDelay 撞上 cap）。所以是「先转、1/3 处才开始淡入」。
> 本项目只有单个卡片，错峰层用不上（§10.5 第 3 条）。

### 11.4 与用户原话的对应关系 `[推测]`

用户说「进出节点图动画」。「进」= 卡片 → 节点图（对应 `turnstileForwardIn` 的角色），
「出」= 节点图 → 卡片（对应 `turnstileBackwardIn` / `turnstileForwardOut` 的角色）。
但如 §10.6 第 1 条所述，Win8 里**没有**「小卡片放大成整页」这个动作 ——
turnstile 是**等大页面的左右交叠**。所以「出入节点图」要完全复刻，
需在 **A. 退成 enterPage 的位移+淡入** 与 **B. 保留卡片放大的隐喻但套用 turnstile 的旋转/透视/曲线** 之间选一个。
**这条得领导拿主意，我不能替用户决定。**

### 11.5 参考视口宽 —— **未取得**（领导问题 1 的直接答复）

领导问：`perspective(600px)` 是不是对 480 宽视口定的？`800px` 那档有没有对应的视口/DPI 语境？

**答：查不到，两个都没有出处。** 我核过的官方页面里**一个像素值都没提**：

- [`WinJS.UI.PageNavigationAnimation` 枚举](https://learn.microsoft.com/en-us/previous-versions/windows/apps/dn624866(v=win.10))（最后更新 2017-01-26）——
  确认页面导航动画**只有四个成员**：`turnstile` / `slide` / `enterPage` / `continuum`，
  原文 "Specifies what animation type is used for new pages entering and exiting the viewport"，
  并注明 "For Windows, this member is available starting with the WinJS 3.0 library"。
  **全文没有任何尺寸/视口/DPI 信息。**
- [`WinJS.UI.Animation.turnstileForwardIn` 函数页](https://learn.microsoft.com/en-us/previous-versions/windows/apps/dn607927(v=win.10))——
  描述只有一句 **"Rotates an element clockwise onto the page."**，
  参数说明只说 `incoming` 可以是单元素/数组/NodeList/HTMLCollection。
  **同样没有任何像素值，也没有 800px 那档的语境说明。**

`600` 与 `800` 都只存在于源码字面量里（`turnstileForwardIn/ForwardOut/BackwardIn` 用 600，
`turnstileBackwardOut` 用 800），**没有任何文档解释为什么这两个数不一样**。
所以 §11.1 的二选一**只能由项目自行决定**，我不编造依据。

**但有一条 `[推测]` 值得记下（标注清楚，不是出处）**：
官方那两个页面**都带同一个图标** `hh464906.windows_and_phone(en-us,win.10).png`
—— 即 `turnstile` 是**同时面向 Windows 和 Windows Phone** 的动画。
而 Windows Phone 7/8 的基础逻辑分辨率是 **480 × 800**（WVGA）。
`600 / 480 = 1.25` **正好是一个整数比**。
⇒ **`[推测]`**：`perspective(600px)` 很可能是**按 480 宽的逻辑视口定的**，
这也解释了为什么它在 1366 宽上会退化成数学病态（见 T6）。
**这是推测，不是文档 —— 我没有找到任何官方文字确认它。**

（附带一条可用的官方措辞：`turnstileForwardIn` 官方说是 **"clockwise"（顺时针）** ——
与 §10.3.2 里「进场 `+80°→0`」的符号一致，可用来交叉验证方向没搞反。）

---

## 12. 真实录像的 `[实测]` 结果（独立复测队友素材）

队友 `win8-ref-b` 取到了真实 Win8.1 录像并落盘为 `docs/win8-frames-b/`（25 帧）。
领导要求「量一下真实录像里页面进场的左右边缘高度比」以**独立证伪**上面的推算。
我用**自己的脚本**重新量了一遍（未改动队友任何文件），结论如下。

### 12.1 素材与口径 `[实测]`

| 项 | 值 |
|---|---|
| 出处 | <https://www.bilibili.com/video/BV1Us411f7bc>（`windows8.1的动画有多好`） |
| 分辨率 / 帧率 | 1280 × 720 / **30 fps**（帧间隔 33.33 ms） |
| 本目录窗口 | t = 196.300 s → 199.600 s，共 25 帧 |
| 我的复测脚本 | `docs/win8-frames/_bmotion.py`, `_btiles.py`, `_bwidth.py`, `_bsamesize.py`, `_benvelope.py`, `_bcomps.py`, `_bascii.py` |

### 12.2 第一项：整段素材的动静时间轴 `[实测]`

逐帧算「与前一帧差异 > 30 的像素个数」：

| 区间 | 最大变化像素 | 判定 |
|---|---|---|
| t = 196.300 → **197.700** | **6 010** | **基本静止**（只有鼠标/光标微动） |
| **197.700 → 197.733** | **279 531** | **突变**（整屏切换第一帧） |
| 197.733 → 197.900 | 232 162 ~ 323 179 | 剧烈变化中 |
| 197.900 → 199.600 | 122 973 ~ 160 782 | 仍在变化，逐步收敛 |

**关键：197.700 之前整段是静止的**（最大仅 6 010 px），
而 197.700→197.733 一帧之内就变了 279 531 px —— 这是**一次突变，不是渐变**。

### 12.3 第二项：磁贴尺寸在整段里**逐位不变** `[实测]`

对 t=196.300→197.767 的帧，只取**同高度的磁贴**（排除 Win8 开始屏幕本身混排 1×1/2×2/4×2 的干扰），
量到的宽度范围**每一帧都完全相同**：

| 帧 | 同尺寸磁贴数 | 宽度范围 | 回归斜率 | R² |
|---|---|---|---|---|
| 196300 … 197700（**连续 10 帧**） | 16 | **80 .. 167（跨度 87px）** | **−0.12619**（十帧一模一样） | 0.261 |
| 197733 | 18 | 80 .. 168（跨度 88px） | −0.11989 | 0.233 |
| 197767 | 16 | 80 .. 168（跨度 88px） | −0.08005 | 0.134 |

- **前 10 帧的宽度分布、斜率、R² 完全相同** ⇒ 这段画面**完全静止**，没有任何动画在跑。
- 斜率看着是负的，但 **R² 只有 0.26**，**不是线性梯度** ——
  那是 80px 单块与 167px 双块粘连造成的**多峰分布**，**不是透视压缩**。
  （我第一版脚本把所有尺寸混在一起回归，得到 R²≈0.1、「斜率 0.296」的假梯度，
  差点当成透视证据 —— **这里记一笔，避免后人重蹈**。）

### 12.4 第三项：切换过程中磁贴**既不缩放也不旋转** `[实测]`

跟踪切换各帧里同一个磁贴的包围盒：

| 帧 | 磁贴包围盒（x, y, w, h） | 尺寸是否变 |
|---|---|---|
| 197700 | (80, 140, **166, 81**) / (79, **310**, **82, 82**) | — |
| 197733 | (80, **61**, **166, 81**) / (79, **232**, **82, 82**) | **否，w/h 完全相同** |
| 197767 | (79, **68**, **82, 82**) / (165, 68, 81, 81) / (250, 68, 81, 81) | **否** |
| 197800 | (79, **11**, **82, 83**) / (250, 11, 82, 82) | **否** |

**读法**：磁贴的宽高在整段切换里**保持 82×82 / 166×81 不变**（缩放比恒为 1.000），
而 **y 坐标一路向上移动：310 → 232 → 68 → 11**，**x 坐标恒为 79/80 不动**。

⇒ **这是一次纯竖直平移（整屏内容上移），不是 `rotateY`，也不是缩放。**
如果是 §10 的 turnstile，应当看到：磁贴宽度沿 x 形成**单调梯度**（远边压窄），
且左右边缘**高度不等**（左右比 ≈1.74）。**两者在素材里都不存在。**

### 12.5 结论：素材**无法**证伪 turnstile 模型 `[实测]`

| 领导要求 | 结果 |
|---|---|
| 量出「新一轮页面进场时右边缘/左边缘高度比随时间」曲线 | **取不到 —— 该曲线在这段素材里不存在** |
| 独立证伪 §10 的推算法 | **做不到**。素材里没有任何 3D 旋转帧，既不能证实也不能证伪 |

**必须说清楚的三点限制：**
1. 视频全长 **345.8 s**，队友只采了 **196.3–199.6 s 这 3.3 s**。
   很可能**这段窗口里恰好没有 turnstile 导航**，而不是「Win8 没有这个动画」。
   **不能说「实测证明 Win8 不转」。**
2. 素材里的这次切换是**硬切 + 竖直平移**，与 turnstile 是**不同的动画**，
   拿它去比 turnstile 的数没有意义。
3. 本机没有视觉模型（`modlens_read_image` 不可用），我是用 **Python/numpy 数值分析 + ASCII 渲染**看图的，
   没有「目视」过原始像素。上述结论都是**可复跑的数值**，不是目测。

**这条对用户目标的影响**：用户说「沿着左边翻，全部元素翻完后节点图淡入」——
那个描述与 `turnstile`（轴在屏幕左外 40px + 独立 opacity 轨道）**高度吻合**，
但**目前的真实素材里没有拍到它**。

### 12.6 队友 `win8-ref-b` 的全片扫描：**全片无 turnstile**（推翻 §12.5 的限制 1）

我在 §12.5 限制 1 里说「很可能这段窗口里恰好没有 turnstile」。**队友随后跑了全片，证实了这个猜测。**
（我的 §12.5 只覆盖 3.3 s；他们的覆盖全片。）

**扫描规模**：0.2 s 步长帧差（1728 点）、0.5 s 饱和度/色相（692 点）、
**11 个候选窗口 30 fps 逐帧（941 帧 ≈ 31 s 素材）**、约 20 帧 ASCII 数值化目视。
**结果：没有任何一帧呈现「整页绕屏幕左外侧轴旋转」的梯形轮廓。**

**判据 1（同尺寸块宽度沿 x 的单调梯度）全片结果：**

| 观察 | 结果 |
|---|---|
| 全片能提出 ≥6 个平面块的帧 | 只有 **32 / 207**，**全部是开始屏幕帧** |
| 同高度组的 `wspread`（最大宽 / 最小宽） | 恒为 **1.000–1.006** ⇒ **同尺寸块之间宽度根本不变化，谈不上梯度** |
| 唯一高相关 `r_h = 0.811` | **在所有开始屏幕帧上取值完全相同** ⇒ 正是 §12.3 里那个 **1×1(82px) 与 2×2(167px) 混排的假梯度**。**帧不变量不可能是旋转。** |
| t=166 的 `r_h = 0.887` | 但 `hspread = 23×`，远超透视能产生的范围（最多 1.74–3.1 倍）⇒ 是异类 UI 堆叠，**不是 turnstile** |

> **检测器的最大局限（必须记下）**：应用界面里**没有纯色平面块**，
> 所以「宽度梯度」这个判据**只在开始屏幕上有效**。**它扫不到应用内的页面导航。**

**一个我差点重犯、也提醒后人不要犯的假阳性**：
队友最初用「**运动量沿 x 的斜率/皮尔逊 r**」当左铰链判据，
在 t=262.25（slope 2.20 / r 0.75）、t=275.47（slope 2.75 / r 0.79）得到**很强**的信号。
逐帧 ASCII 一看：t=275 那帧**左侧 0–907px 的彩虹画面逐像素完全不动**，
只有**右侧 907–1280px 从灰变白**。
⇒ **该指标测的是「变化集中在屏幕哪一侧」，与旋转几何无关，不能当 turnstile 判据。**

**素材性质（重要，避免过度解读）**：0–10s 亮灰；10–65s Win8 启动画面；71–143s 彩虹/米色渐变图；
146–196s 开始屏幕；197.733s 整屏切到小图标网格；199s 后是杂糅片段（贴靠/分屏、应用界面、彩虹图反复出现）。
**这是混剪，不是单一应用的导航录屏。**
⇒ **「这段录像里没有 turnstile」≠「Win8 没有 turnstile」。**
`turnstile` 的存在性依据仍然是 §10 的 `[文档]`（官方源码 + 官方函数页），
**而不是**这盘录像。**本文件的 `[实测]` 仍然是 0 条 —— 这条结论要一直挂着。**

### 12.7 两条给后续抓帧的人的操作警告（来自 `win8-ref-b` 的实测）

1. **必须用「播放 + `requestVideoFrameCallback`」抓，不能用 `seek` 抓。**
   他们实测：`seek` 到 t 与 t+1/30 有时落到**同一帧**（呈 **0.1 s 周期性重复**），
   **`seek` 采样有混叠**；而 rVFC 那版 180 帧**零重复**。抓 turnstile 时这尤其致命 ——
   见 T7，**强透视只存在于前 1–2 帧**，混叠会直接把它吞掉。
2. **落盘口径**：页面内 `seek`→`canvas.drawImage`→`toBlob`→POST 到本机 `http.server`（默认 `127.0.0.1:8765`）。
   抓帧前必须先用 `fetch(src, {headers:{Range:'bytes=0-1024'}, mode:'cors'})` 确认
   **HTTP 206 + `content-type: video/mp4`**，再把 `<video>` 重建为 `crossOrigin='anonymous'`
   —— 否则 `canvas.getImageData()` 抛
   `SecurityError: … The canvas has been tainted by cross-origin data.`

---

## §13 独立验收记录（2026-10-15 晚，验收方 `win8-ref`）

本节记录对**本项目已落地实现**的独立验收结论，不改任何 Kotlin 源码。

### 13.1 验收对象的版本（哈希口径）

| 文件 | 大小 | SHA256 前 16 位 | LastWrite |
|---|---|---|---|
| `app/src/main/java/com/yjc/click/ui/program/TurnstileProjection.kt` | **2313 B / 46 行** | **`9638D64A56D50945`** | **22:50:39** |
| `app/src/main/java/com/yjc/click/ui/program/ProgramScreen.kt` | **86956 B** | **`8D54CE293090227B`** | **23:04:13** |
| `app/src/main/java/com/yjc/click/ui/shell/MainScaffold.kt` | 36537 B | `332BE0A3309CB29A` | 22:33:07 |
| 装机包 | — | `lastUpdateTime = 2026-10-15 22:44:28` | — |

验收过程见到的三个中间版本，保留以便追溯：`TurnstileProjection.kt` 1537 B / 38 行 / `DA1B04A909E57364`（22:43:09）
→ 2186 B / 48 行（22:48:27，加护栏）→ 2313 B / 46 行（22:50:39，护栏改写）；`ProgramScreen.kt`
84766 B / `5E52BC9251781397`（22:43:09）→ 85466 B（22:47:20）→ 86956 B / `8D54CE293090227B`（23:04:13）。

**⚠️ 装机时刻 22:44:28 早于护栏的 22:48:27 ⇒ 录进 `tt_enter` / `tt_exit` 的那版没有护栏。**
**本节下述行号已于 23:07 按 23:04:13 版 `ProgramScreen.kt` 与 22:50:39 版 `TurnstileProjection.kt` 重新核对。**

### 13.2 常量逐条核对：全部通过 `[文档]`

| 项 | 实现位置（23:04:13 版行号） | 期望（WinJS 源码） | 结果 |
|---|---|---|---|
| 转轴 x | `ProgramScreen.kt` L1282 `(-40).dp`，L445 / L1107 传入 | LTR `-(40 + box.left)`，全屏 ⇒ −40 | ✅ −120 px |
| 转轴 y | L446 / L1108 `flip.window.height / 2f` | `innerHeight/2 - box.top`，全屏 ⇒ innerHeight/2 | ✅ |
| 进场角度/时长 | L1273 `300`、L1275 `80f`、L1105 | ForwardIn `300 ms`、`80deg → 0deg` | ✅ |
| 退场角度/时长 | L1274 `128`、L1276 `-50f` | ForwardOut `128 ms`、`0deg → -50deg` | ✅ |
| 800 px 档 | L1288 `800.dp`；L1109 `if (flip.entering) IN else OUT` | **只有** `turnstileBackwardOut` 用 800 | ✅ 只作用于退场图页 |
| 四条缓动 | L1289–1292 | base.js L17934/17972/18010/18048 | ✅ 逐位相同 |
| 投影公式 | `TurnstileProjection.kt` L37 `P/(P + (x − originX)·sinT)` | `scale(d) = P/(P + d·sinθ)`，见 §10.3.1 | ✅ 偏差 **0.0000 %** |
| 矩阵落地 | L493 `setPolyToPoly` + L495 `concat`，非近似缩放 | — | ✅ |

投影比复算：官方 `W=480, P=600, axis=−40` ⇒ 左 `0.93839` / 右 `0.53952` / 比 **1.73931**；
本项目 `W=1440, P=1800, axis=−120` ⇒ 左 `0.93839` / 右 `0.53952` / 比 **1.73931`（+0.0000 %）**。
验证脚本：`docs/win8-frames/_verify-impl.py`。

### 13.3 退化护栏：已加 ✅（但不在装机版里）

`TurnstileProjection.kt` **L29–36**（22:50:39 版；护栏在 22:48:27 首次加入，随后被改写成下面这个更直白的形式）：

```kotlin
val denomAtLeft  = perspective + (0f - originX) * sinT
val denomAtRight = perspective + (width - originX) * sinT
if (minOf(denomAtLeft, denomAtRight) <= 0f) return null
```

**取法正确**：分母 `P + d·sinθ` 中 `d = x − originX` 对 `x` **线性**，所以最小值必在区间端点取得 ——
两端一起取 `minOf` 比原先按 `sinT >= 0f` 分支更直白，且不依赖 θ 的符号，**两者数学等价**。
调用方 `ProgramScreen.kt` **L482–485** 有 `if (quad == null) { drawContent(); return@drawWithContent }` 兜底。
不退化上限：**窗口宽 < `P/|sinθ| − 40dp` = 2230 px = 743 dp**（`P = 1800`、`θ = 50°`）；
`BackwardOut`（`P = 2400`、`θ = 80°`）余量 **+863.7 px**，`ForwardOut`（`P = 1800`、`θ = 50°`）余量 **+605.0 px**。
护栏注释里现已写明「1440px 宽配 600px 这种把 CSS px 当 px 用的写法，在 −50° 时右端分母
`600 − 1560·sin50 = −595` 已经是负的」—— 即护栏不仅防平板，也**兜住了单位读错的情形**。

### 13.4 渲染路径三条结论（读代码得出，非实测）

1. **图页层与列表层是同胞、不是嵌套。** `MainScaffold.kt` L467 `ProgramFlipOverlay(programFlip)`
   在**外层 Box 最后绘制**（L464–466 注释「画在最上面」）；列表层所在 page Box 的 `.zIndex(1f)`（L343）
   只在**其所在 Column 内**把页面抬到 TopBar/BottomBar 之上，**不会盖住 overlay**。
   ⇒ **绘制顺序无缺陷**，图页在最上层。（行号 23:07 复核：L343 / L352 / L353 / L467 均未变。）
2. **`programListTurnstile` 无条件使用 `TURNSTILE_PERSPECTIVE_IN`（600 dp）（L447）不是缺陷。**
   列表页进场走 `turnstileForwardOut`、退场走 `turnstileBackwardIn`，**两支皆为 `perspective(600px)`**；
   需要 600/800 切换的只有图页，而图页切了（L1109）。✅
3. ~~⚠️ 一处不对称~~ → **已修复 ✅**。本条原先是：列表层传入**实测**的 `pageOrigin.x/y`
   （`MainScaffold.kt` L352–353），而图页层写死 `layerLeft = 0f, layerTop = 0f`；而 `turnstileLayer`
   的文档（现 L458）明确要求 `originX/originY` 是**窗口坐标**、必须减去该层自己的窗口左上角 ——
   **列表层量了、图页层假设了**，若 overlay 的 Box 不在窗口 `(0, 0)` 转轴即偏移。
   **现 `ProgramScreen.kt` 23:04:13 版已补上对称做法**（L1096–1103）：

   ```kotlin
   // 这一层自己的窗口左上角：turnstileLayer 要的是图层局部坐标里的轴心，
   // 写死 (0,0) 只在「overlay 真的贴在窗口原点」时才对 —— 以实测为准（tick 日志里会打出来）
   var layerOrigin by remember { mutableStateOf(Offset.Zero) }
   ...
       .onGloballyPositioned { layerOrigin = it.boundsInRoot().topLeft }
       .turnstileLayer(..., layerLeft = layerOrigin.x, layerTop = layerOrigin.y)
   ```

   ⇒ 两层现在**都用 `boundsInRoot().topLeft` 实测**，做法对称。**13.6 的第 2 个可能解释随之被排除。**

### 13.5 录像打分：**无法完成**，原因是测量而非动画

几何上不存在「不像的帧」——见 13.2，实现与官方逐位一致。测不出来的原因：

- **队友自己的追踪器**：`tools/captures/tt_enter/pages.csv` 40 帧中 **34 帧 `n_blobs=0`**（整页丢失）；
  `tt_exit/pages.csv` 最大偏离 1.0 仅 **4.2 %**（f=12，`0.9578`），唯一拟合出 θ 的是 f=27（**0.35°**）。
- **本验收方三种独立方法全部失败**：
  1. `_acc-measure.py`（与四角背景色差做剪影）—— app 为深色主题，**整帧灰度只有 18–93**，
     页面底色与外壳相差约 10 个灰阶，无法分离；
  2. `_acc_ascii.py`（ASCII 降采样）—— 分辨率不足以判透视；
  3. `_acc-band.py`（亮度阈值抓亮色带）—— 整列上下界被状态栏/导航栏撑满（`h_right ≈ 2594`），为伪影。
- **结构性原因**：turnstile 的页面在 `θ = +80°` 时投影到 `x ∈ [−100.5, +26.1]`，
  在 `θ = −50°` 时投影到 `x ∈ [−38.7, +2864.7]` —— **要么窄成一条缝、要么溢出屏幕数倍**，
  因此它**从来不是一个可分离的剪影**。这是测量方法学的限制，**不是对动画的判决**。

### 13.6 唯一一处硬矛盾（建议在屏幕上直接目视确认）

`tt_enter` 中唯一有 blob 的三帧（f=4/5/6）位于 **`x 1230–1438`、`y 336–989`**（**屏幕右上角**，
约 208×400）；而按 13.2 的模型，图页在 `+80°` 必须塌成 **屏幕最左侧**一条 **127 px 宽**的竖缝
（投影 `x ∈ [−100.5, +26.1]`）。**两者在屏幕的两侧。**
原先有两个可能解释：① 那三帧的 blob 根本不是图页；② 图页层 `layerLeft = 0f` 假设不成立。
**②已随 13.4 第 3 条的修复而排除**（两层现在都实测 `boundsInRoot().topLeft`），
所以**剩下的解释是 ①**，或另有未查明的渲染路径问题。**仍未断言为缺陷。**

### 13.7 帧率建议（可执行）

`ForwardIn` 曲线极陡：`θ` 在 **30 ms 时只剩 +32.31°**；透视比
帧 1（0 ms）`1.7393` → 帧 2（33 ms）`1.3912` → 帧 3（67 ms）`1.2324` → 帧 4（100 ms）`1.1388`。
而 `tt_enter` 只有 **13.5 fps（74 ms/帧）** ⇒ **整段 300 ms 仅 4 帧，`t = 0` 那一帧基本抓不到**。
所以要慢放取证的话：

> 建议**临时**把 `ProgramScreen.kt` 的 `TURNSTILE_IN_MS`（L1273）/ `TURNSTILE_OUT_MS`（L1274）
> 各乘 20，录完后**立刻改回并回读确认**。

**不要用 `DEBUG_SLOWDOWN` 之类的全局慢放开关做这件事 —— 那个常量已被 Lead 决定删除
（该决定见 `docs/win8-tile-spec-B.md` §11.6 第 3 条），且 `app/src/**` 全树已 0 命中。
不要恢复它。** 理由（原话转述，非我的判断）：用户手上的包**就是 debug APK**
（`versionName=5.1-beta`、`install -r app-debug.apk`），所以 `BuildConfig.DEBUG` 门控**挡不住**，
「留一个会让用户手机变慢动作的开关」的代价大于「下次验收改一行常量」。
今晚已经两次把慢放版装到用户手机上，**照旧指令操作就是在重开这个坑**。

**⚠️ 另注**：`docs/win8-tile-spec-B.md` L1425 写「步骤已写进 `flip-harness.md`」——
经 23:07 核查，**`docs/flip-harness.md` 里并没有这段慢放取证流程**（grep `慢放|slowdown|各乘|× 20`
在该文件只命中 L87 一句关于「scale>1 粗测」的说明）。该文件是队友的，我未改动；
**本节的写法是自足的，不依赖那个指针。**

### 13.8 仍未取得

- **透视比的 `[实测]` 值：仍然没有。** 本文档未编造任何实测数字。
- ~~图页层 `layerLeft = 0f` 是否成立~~ —— **已随 13.4 第 3 条修复而关闭**（改为实测 `layerOrigin`）。
- 13.6 那个右上角 blob 的归属：**不确定**。
- `[实测]` 的绝对时长/角度：**未取得** —— 现有 `tt_enter` / `tt_exit` 录的是 22:44:28 那份包，
  而任何时长结论都必须先钉死被测二进制（见 `docs/win8-tile-spec-B.md` §10 / §11.6）。
