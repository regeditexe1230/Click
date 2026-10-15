"""Turnstile / continuum 转场的几何模型与时间轴表。

**为什么需要这个脚本**

`_vercheck.py` + `_dumpnav.py` 在 WinJS 官方发行源码里发现了 Win8 的
**页面导航转场族**，其中 `turnstileForwardIn` 的实现体原文是：

    from: "perspective(600px) rotateY(80deg)",
    to:   "perspective(600px) rotateY(0deg)"

并且旋转原点由 `collectTurnstileTransformOrigins()` 给出：

    var offsetLeftLTR = -(40 + itemBoundingBox.left);
    var totalOffsetY = ((_Global.innerHeight / 2) - itemBoundingBox.top);

换算成页面坐标是 **x = -40px（视口左边缘再往左 40px）、y = 视口垂直中心**。

也就是说：**整页绕一条位于屏幕左侧外面 40px 处的竖直轴，做 80° 的 3D 旋转**，
透视距离显式写死为 600px。这跟「磁贴按压（TiltEffect）」完全是两码事 ——
TiltEffect 的透视极弱（近/远比最多 1.3467），而 turnstile 因为旋转轴在屏幕外很远处，
透视强得多。

用法：python docs\\win8-frames\\_turnstile.py
输出：docs\\win8-frames\\turnstile-timeline.md
"""

import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "turnstile-timeline.md")

# ---- 从官方源码抄来的常量（出处见 winjs-transitions.txt）----
PERSPECTIVE_IN = 600.0    # turnstileForwardIn  / turnstileForwardOut
PERSPECTIVE_BACKOUT = 800.0  # turnstileBackwardOut（源码里就是 800，不一致，如实记录）
AXIS_OFFSET_PX = 40.0     # collectTurnstileTransformOrigins: -(40 + left) -> 页面 x = -40
ANGLE_IN_FROM = 80.0      # turnstileForwardIn  from rotateY(80deg)
ANGLE_IN_TO = 0.0
ANGLE_OUT_TO = -50.0      # turnstileForwardOut to rotateY(-50deg)
DURATION_IN = 300         # ms
DURATION_OUT = 128        # ms
STAGGER_EXTRA = 50        # staggerDelay(0, 50, 1, 1000)
STAGGER_CAP = 1000

CURVE_IN = (0.01, 0.975, 0.4775, 0.9775)     # turnstileForwardIn
CURVE_OUT = (0.4925, 0.01, 0.7675, -0.01)    # turnstileForwardOut

# 典型视口宽度（用于把「距离轴的像素数」算成近/远边高度比）
#
# **这个数是一个假设，不是从源码里读出来的。** 源码只写了字面量
# `perspective(600px)`，从没说明它是为多宽的视口调的。两个候选读法：
#   A. 绝对读法：600px 就是 600px，那么在 1366 宽的桌面上 θ=80° 的近/远比是 3.11
#   B. 比例读法：600px 是为 480 宽视口调的 -> k = P/W = 1.25，任何宽度都是 ~1.74-1.78
# 取 480 是因为 Windows Phone 8.1 的逻辑视口就是 480 宽，且 turnstile 是手机式导航；
# 但**没有证据**，详见 win8-tile-spec.md §11.1。换读法只改这一行 + 同步那个文件。
#
# 注意 40px 是**绝对值**，不随 W 缩放；所以「只保持 P/W 不变」并不严格等价，
# 只有在 P、W、axis 三者同乘一个系数时才严格不变。
VIEWPORT_W = 480.0


# ---------------- 三次贝塞尔求值（与浏览器一致）----------------
def _bez(t, p1, p2):
    """一维三次贝塞尔，控制点为 (0, p1, p2, 1)。"""
    mt = 1.0 - t
    return 3.0 * mt * mt * t * p1 + 3.0 * mt * t * t * p2 + t * t * t


def bezier_y_for_x(x, curve, iters=80):
    """给定输出时间 x∈[0,1]，求 cubic-bezier 的输出进度 y∈[0,1]。

    用二分法反解参数 t —— 与 win8-measure.py 里的实现同一套做法。
    """
    x1, y1, x2, y2 = curve
    lo, hi = 0.0, 1.0
    for _ in range(iters):
        mid = (lo + hi) / 2.0
        if _bez(mid, x1, x2) < x:
            lo = mid
        else:
            hi = mid
    t = (lo + hi) / 2.0
    return _bez(t, y1, y2)


# ---------------- turnstile 几何 ----------------
def edge_heights(theta_deg, perspective, viewport_w=VIEWPORT_W, axis=AXIS_OFFSET_PX):
    """返回 (左边缘视觉高度比, 右边缘视觉高度比, 近/远比, 哪边更近)。

    旋转轴在页面 x = -axis 处。元素（整页）左边缘在页面 x = 0，
    故左边缘距轴 d_left = axis；右边缘距轴 d_right = viewport_w + axis。

    rotateY(θ) 把距轴 d 的竖直直线映射到：
        x' = d·cosθ
        z  = -d·sinθ        （正 θ 让右侧转到屏幕里）
    透视投影的缩放因子（CSS perspective(P) 约定）：
        s(d) = P / (P - z) = P / (P + d·sinθ)
    竖直方向在这条线上只受 s 影响，所以视觉高度 h(d) = H·s(d)。

    注意 θ<0 时 sinθ<0，右边缘反而更近 —— 所以**必须取 max/min 才是「近/远比」**，
    直接写 s_left/s_right 在退场段会得到 <1 的数（那是「左/右比」，不是「近/远比」）。
    第一版脚本就犯了这个错，输出 −0.8379 这种负数比值。

    **还一个陷阱**：θ<0 且元素足够宽时，`P + d·sinθ` 会变成负数，
    意味着那一侧已经**转到了相机平面背后**（z > P），透视投影在此处无定义。
    此时返回 valid=False，调用方必须把该行标为无效，而不是把负数当比值报出去。
    发生条件：`d > P / |sinθ|`。
    """
    import math
    s = math.sin(math.radians(theta_deg))
    denom_left = perspective + axis * s
    denom_right = perspective + (viewport_w + axis) * s
    if denom_left <= 0 or denom_right <= 0:
        # 越过相机平面，模型失效
        return float("nan"), float("nan"), float("nan"), "—", False
    h_left = perspective / denom_left
    h_right = perspective / denom_right
    nearer = "左" if h_left >= h_right else "右"
    lo, hi = min(h_left, h_right), max(h_left, h_right)
    ratio = (hi / lo) if lo else float("inf")
    return h_left, h_right, ratio, nearer, True


def degenerate_width_limit(theta_deg, perspective, axis=AXIS_OFFSET_PX):
    """返回该角度下**仍不越过相机平面**的最大元素宽度（px），无限制则 None。

    越过条件：`denom = P + d·sinθ <= 0`。
    - θ >= 0 时 sinθ >= 0，denom >= P > 0 **恒成立**，没有宽度上限 → 返回 None。
    - θ < 0 时 sinθ < 0，denom <= 0 等价于 `d >= P/|sinθ|`，故 `W_max = P/|sinθ| − axis`。

    第一版忘了判 θ 的符号，给 +80° 也算出「上限 569px」——
    但 +80° 那与 d 无关地恒有效（表格里 1366px 宽照样算出 3.1039）。
    """
    import math
    s = math.sin(math.radians(theta_deg))
    if s >= 0:
        return None
    d_max = perspective / abs(s)
    return max(0.0, d_max - axis)


def timeline(curve, duration, angle_from, angle_to, perspective, samples=11):
    """按缓动曲线采样，返回 [(pct, ms, progress, θ, h_left, h_right, ratio, nearer), ...]"""
    rows = []
    for k in range(samples):
        p = k / (samples - 1)
        y = bezier_y_for_x(p, curve) if 0.0 < p < 1.0 else p
        theta = angle_from + (angle_to - angle_from) * y
        rows.append((p, p * duration, y, theta) + edge_heights(theta, perspective))
    return rows


def fmt_table(rows, with_curve, duration):
    """把采样行渲染成 markdown 表。"""
    x1, y1, x2, y2 = with_curve
    out = []
    out.append("| t（时间进度） | 实际 ms | 缓动输出进度 | 角度 θ | 左边缘高度 | 右边缘高度 | **近/远比** | 哪边更近 |")
    out.append("|---|---|---|---|---|---|---|---|")
    for p, ms, y, theta, h_left, h_right, r, nearer, valid in rows:
        if not valid:
            out.append(
                f"| {p * 100:.0f}% | {ms:.0f} ms | {y:.4f} | {theta:.2f}° "
                f"| — | — | **模型失效¹** | — |"
            )
            continue
        out.append(
            f"| {p * 100:.0f}% | {ms:.0f} ms | {y:.4f} | {theta:.2f}° "
            f"| {h_left:.4f} | {h_right:.4f} | **{r:.4f}** | {nearer} |"
        )
    return "\n".join(out)


def main():
    lines = []
    lines.append("# Turnstile 转场时间轴与透视强度\n")
    lines.append("本文件由 `docs/win8-frames/_turnstile.py` 生成。\n")
    lines.append("## 模型来源（全部为官方发行源码，可逐行复核）\n")
    lines.append(
        "出处：<https://unpkg.com/winjs@4.4.3/js/base.js>\n\n"
        "> **行号口径**：以下行号按 unpkg 原文的换行计数。\n"
        "> 本仓库缓存文件是 `\\r\\r\\n` 结尾（下载时被文本模式二次转换），\n"
        "> 用 PowerShell `Select-String` 读取会**把行号整整翻一倍**（Python 的\n"
        "> universal newlines 同样会把 `\\r\\r\\n` 当成两个换行）——\n"
        "> 本文档第一版就因此把 `turnstileForwardIn` 标成了 L35867（真实 L17934）。\n"
        "> 复算前必须先归一化 `\\r\\r\\n → \\n`，见 `_dumpnav.py` 的 `normalize()`。\n\n"
        "| 量 | 值 | 源码原文 |\n|---|---|---|\n"
        f"| 透视距离（ForwardIn / ForwardOut） | **{PERSPECTIVE_IN:.0f} px** | "
        '`from: "perspective(600px) rotateY(80deg)"` (L17958，`turnstileForwardIn` L17934)；'
        '退场 `to: "perspective(600px) rotateY(-50deg)"` (L17997) |\n'
        f"| 透视距离（BackwardIn / BackwardOut） | **{PERSPECTIVE_BACKOUT:.0f} px** | "
        '`from: "perspective(800px) rotateY(0deg)"` (L18072，`turnstileBackwardOut` L18048) |\n'
        f"| 旋转轴 X（页面坐标） | **−{AXIS_OFFSET_PX:.0f} px** | `var offsetLeftLTR = -(40 + itemBoundingBox.left);` |\n"
        "| 旋转轴 Y（页面坐标） | 视口垂直中心 | `var totalOffsetY = ((_Global.innerHeight / 2) - itemBoundingBox.top);` |\n"
        f"| 进场角度 | **+{ANGLE_IN_FROM:.0f}° → {ANGLE_IN_TO:.0f}°** | `turnstileForwardIn` |\n"
        f"| 退场角度 | **{ANGLE_IN_TO:.0f}° → {ANGLE_OUT_TO:.0f}°** | `turnstileForwardOut` |\n"
        f"| 进场时长 | **{DURATION_IN} ms** | `duration: 300` |\n"
        f"| 退场时长 | **{DURATION_OUT} ms** | `duration: 128` |\n"
        f"| 错峰 | `min({STAGGER_EXTRA}·i, {STAGGER_CAP})` ms | `staggerDelay(0, 50, 1, 1000)` |\n"
    )
    lines.append("### 几何模型\n")
    lines.append(
        "旋转轴在页面 `x = −40px`（视口左边缘再往左 40px），\n"
        "整页左边缘距轴 `d_left = 40px`，右边缘距轴 `d_right = 视口宽 + 40px`。\n\n"
        "```\n"
        "rotateY(θ)：距轴 d 的竖直直线 →  x' = d·cosθ ,  z = −d·sinθ\n"
        "CSS perspective(P) 的缩放：      s(d) = P / (P − z) = P / (P + d·sinθ)\n"
        "竖直方向只受 s 影响，故：        h(d) = H · s(d)\n"
        "近边 = 左边缘(d=40)，远边 = 右边缘(d=宽+40)\n"
        "```\n\n"
        f"下表按 **视口宽 {VIEWPORT_W:.0f}px** 计算。换视口宽只需改本脚本顶部的 `VIEWPORT_W`。\n"
    )

    lines.append("---\n")
    lines.append(f"## 1. `turnstileForwardIn` —— 进场（{DURATION_IN}ms，{ANGLE_IN_FROM:.0f}° → {ANGLE_IN_TO:.0f}°）\n")
    lines.append(
        "缓动 `cubic-bezier(0.01, 0.975, 0.4775, 0.9775)`"
        "（透明度轨道另用 `cubic-bezier(0, 2, 0, 2)`，0→1）\n"
    )
    rows_in = timeline(CURVE_IN, DURATION_IN, ANGLE_IN_FROM, ANGLE_IN_TO, PERSPECTIVE_IN)
    lines.append(fmt_table(rows_in, CURVE_IN, DURATION_IN))
    lines.append("")
    lines.append(
        f"**进场起始瞬间（θ={ANGLE_IN_FROM:.0f}°）的近/远比 = "
        f"{rows_in[0][6]:.4f}** —— 这是 Win8 转场里透视最强的时刻。\n"
    )

    lines.append("---\n")
    lines.append(f"## 2. `turnstileForwardOut` —— 退场（{DURATION_OUT}ms，{ANGLE_IN_TO:.0f}° → {ANGLE_OUT_TO:.0f}°）\n")
    lines.append("缓动 `cubic-bezier(0.4925, 0.01, 0.7675, -0.01)`（透明度轨道 `cubic-bezier(1,-0.42,0.995,-0.425)`，1→0）\n")
    rows_out = timeline(CURVE_OUT, DURATION_OUT, ANGLE_IN_TO, ANGLE_OUT_TO, PERSPECTIVE_IN)
    lines.append(fmt_table(rows_out, CURVE_OUT, DURATION_OUT))
    lines.append("")
    lines.append(
        f"**退场结束（θ={ANGLE_OUT_TO:.0f}°）**：见下表，该处已越过相机平面，**无有效近/远比**（见脚注 1）。\n"
        "注意退场是往 **负角**（−50°）走，与进场方向相反；\n"
        "且**退场 128ms 比进场 300ms 快 2.3 倍** —— 与 `enterPage`/`exitPage` 同样的「退场要快」设计取向。\n"
    )

    lines.append("---\n")
    lines.append("## 3. 与 TiltEffect / 本项目现状的对照\n")
    l80, r80h, r80, near80, _v80 = edge_heights(80.0, PERSPECTIVE_IN)
    l50, r50h, r50, near50, v50 = edge_heights(-50.0, PERSPECTIVE_IN)
    lim50 = degenerate_width_limit(-50.0, PERSPECTIVE_IN)
    lim80 = degenerate_width_limit(80.0, PERSPECTIVE_IN)
    out50 = (
        f"{l50:.4f} | {r50h:.4f} | **{r50:.4f}** |"
        if v50
        else "— | — | **模型失效¹** |"
    )
    lines.append(
        "| 场景 | 透视距离 | 转角 | 左边缘高度 | 右边缘高度 | **近/远比** |\n"
        "|---|---|---|---|---|---|\n"
        f"| turnstile 进场起始 | 600 px | +80° | {l80:.4f} | {r80h:.4f} | **{r80:.4f}** |\n"
        f"| turnstile 退场结束 | 600 px | −50° | {out50}\n"
        "| turnstile 任意终点 | 600 px | 0° | 1.0000 | 1.0000 | **1.0000**（无畸变） |\n"
        "| TiltEffect 极限 | 未公开 | 17.1887° | — | — | **≈1.35**（绕自身中心，透视极弱） |\n"
        "| 本项目现状（翻转） | 等效 131 px | — | — | — | **1.582** |\n"
    )
    lines.append(
        "\n**结论：Win8 里透视强度差异极大，取决于动画种类。**\n"
        "- **磁贴按压（TiltEffect）**：绕自身中心转 ≤17.19°，透视极弱（≤1.35）\n"
        f"- **页面转场（turnstile）**：绕屏幕外 40px 的轴转 80°，透视极强（进场起始 **{r80:.4f}**）\n\n"
        "本项目如果要做「和 Win8 磁贴动画一模一样」的进出效果，"
        "**对应的就是 turnstile**，透视不但不该减弱，反而应该用 `perspective(600px)` "
        "并把旋转轴放到容器外侧。\n"
    )
    lines.append(
        "### 脚注 1：为什么退场那一行没有数值\n"
        "在 `perspective(600px)` 下把**整屏宽**元素绕屏幕外 40px 的竖轴转 −50°，"
        "右边缘的 z 位移达到 `(W+40)·sin50° ≈ 1077px`，**已经超过相机距离 600px**，"
        "即那部分转到了相机平面背后，透视投影无定义。\n\n"
        f"不越过相机平面的最大元素宽度：**−50° 时 {lim50:.0f}px**；"
        "+80° 恒有效，无宽度上限。\n"
        "公式：θ<0 时 `W_max = P/|sinθ| − 40`。\n\n"
        "**这条本身就是一条有用的推断 [推测]：** 官方 turnstile 连整屏宽的页面都这么写，"
        "说明它的典型使用对象是**接近整屏宽的元素**，而真实运行时浏览器会在相机平面处裁剪，"
        "−50° 只出现在**元素已经淡出到 opacity 0 的那 128ms 里**（`turnstileForwardOut` 的 opacity "
        "用 `cubic-bezier(1,-0.42,0.995,-0.425)` 压到很晚才归零），所以人眼看不到穿帮。\n"
        "**复刻时的含义：退场不要单独把 −50° 当可见关键帧来调，它必须和快速淡出绑定。**\n"
    )

    lines.append("---\n")
    lines.append("## 4. `continuum` 对照（无 perspective！）\n")
    lines.append(
        "`continuumForwardIn` 是**三段式**，逐字抄自 L14 起：\n\n"
        "| 作用对象 | 变换 | duration | timing |\n"
        "|---|---|---|---|\n"
        "| `incomingPage` | `scale(0.5,0.5)` → `scale(1.0,1.0)` | 350 | `cubic-bezier(0.33, 0.18, 0.11, 1)` |\n"
        "| `incomingItemRoot` | `translate(0px, 225px)` → `translate(0px, 0px)` | 350 | `cubic-bezier(0.24,1.15,0.11,1.1575)` |\n"
        "| `incomingItemContent` | `rotateX(80deg) scale(1.5,1.5)` → `rotateX(0deg) scale(1.0,1.0)` | 350 | `cubic-bezier(0,0.62,0.8225,0.9625)` |\n"
        "| 以上每段的 `opacity` | `0` → `1` | 350 | `cubic-bezier(0, 2, 0, 2)` |\n\n"
        "`incomingItemContent` 的旋转原点：`animRotationTransform(incomingItemContent, "
        "{ ltr: \"0px 50%\", rtl: \"100% 50%\" }, ...)` —— **左边缘、垂直中心**。\n\n"
        "**关键差异：continuum 的 transform 串里没有 `perspective(...)`，"
        "turnstile 的串里有 `perspective(600px)`。**\n"
        "所以在 Win8 的转场族里，**continuum 绕元素的横向中轴翻（X 轴、无透视畸变），"
        "turnstile 绕屏幕外的竖直轴转（Y 轴、有 600px 透视）** —— 两者是正交的两套「进出」语言。\n"
    )

    lines.append("---\n")
    lines.append("## 5. 复现\n")
    lines.append("```powershell\npython docs\\win8-frames\\_dumpnav.py   # 先导出源码原文\npython docs\\win8-frames\\_turnstile.py # 再算本文件的表\n```\n")

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    # 注意：Windows 控制台是 GBK，print() 里不能用 U+2212 MINUS SIGN（会 UnicodeEncodeError），
    # 只能用 ASCII 的 "-"。写进 UTF-8 文件里的 "−" 则没问题。
    print(f"-> {OUT}")
    print(f"\nturnstile 进场起始（+80°）近/远比 = {r80:.4f}")
    if v50:
        print(f"turnstile 退场结束（-50°）近/远比 = {r50:.4f}")
    else:
        print(
            f"turnstile 退场结束（-50°）近/远比 = 模型失效"
            f"（整屏宽元素在该角度越过相机平面；最大有效宽度 {lim50:.0f}px）"
        )


if __name__ == "__main__":
    main()
