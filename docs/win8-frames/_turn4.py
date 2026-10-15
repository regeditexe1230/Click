# -*- coding: utf-8 -*-
"""
turnstile 四支常量 —— 逐帧数值表生成器

对应 WinJS 4.4.3 base.js:
  turnstileForwardIn   L17934-17969
  turnstileForwardOut  L17972-18007
  turnstileBackwardIn  L18010-18045
  turnstileBackwardOut L18048-18083
  animRotationTransform        L16191-16202
  collectTurnstileTransformOrigins L16251-16267

投影模型（领导复核后确认）：
  scale(d, th) = P / (P + d * sin(th))        d = x - axisX（轴上 d=0，不缩放）
轴：屏幕绝对坐标 x = -40（源码 `offsetLeftLTR = -(40 + bbox.left)`，对全屏元素 bbox.left=0）
    屏幕绝对坐标 y = innerHeight / 2 = 1600（源码 `totalOffsetY = innerHeight/2 - bbox.top`）

输出：stdout 表格 + 同目录 turnstile-4branches.md
"""
import math
import os
import sys

# ---------- 本机屏幕（领导决定 2） ----------
W = 1440.0          # 屏宽 px
H = 3200.0          # 屏高 px
AXIS_X = -40.0      # 转轴的屏幕绝对 x（源码字面量 40，改成屏外左侧）
AXIS_Y = H / 2.0    # 转轴的屏幕绝对 y = 1600

# 透视距离：P/W = 600/480 = 1.25（源码字面量，对 480 宽视口的比例）
P_FWD = 1.25 * W            # 1800
# BackwardOut 源码用 800px；同一比例口径 => 800/480 * W
P_BWD_OUT = (800.0 / 480.0) * W   # 2400


def bezier(p1x, p1y, p2x, p2y):
    """返回 f(t) -> y，t 为归一化时间。牛顿迭代解 x(u)=t 再取 y(u)。"""
    def x_of(u):
        return 3 * (1 - u) ** 2 * u * p1x + 3 * (1 - u) * u ** 2 * p2x + u ** 3

    def y_of(u):
        return 3 * (1 - u) ** 2 * u * p1y + 3 * (1 - u) * u ** 2 * p2y + u ** 3

    def dx_of(u):
        return 3 * (1 - u) ** 2 * p1x + 6 * (1 - u) * u * (p2x - p1x) + 3 * u ** 2 * (1 - p2x)

    def f(t):
        if t <= 0:
            return 0.0
        if t >= 1:
            return 1.0
        u = t
        for _ in range(60):
            err = x_of(u) - t
            if abs(err) < 1e-13:
                break
            d = dx_of(u)
            if abs(d) < 1e-13:
                break
            u -= err / d
            u = min(1.0, max(0.0, u))
        return y_of(u)

    return f


def scale_at(x, theta_deg, P):
    """元素上屏幕坐标 x 处、绕轴转过 theta 度时的横向缩放。"""
    d = x - AXIS_X
    s = math.sin(math.radians(theta_deg))
    return P / (P + d * s)


CURVE_LINEAR = bezier(0, 0, 1, 1)
CURVE_IN = bezier(0.01, 0.975, 0.4775, 0.9775)      # 进场 transform（300ms）
CURVE_OUT = bezier(0.4925, 0.01, 0.7675, -0.01)     # 退场 transform（128ms）
CURVE_OP_IN = bezier(0.0, 2.0, 0.0, 2.0)            # 进场 opacity（300ms）会超过 1
CURVE_OP_OUT = bezier(1.0, -0.42, 0.995, -0.425)    # 退场 opacity（128ms）会低于 0

BRANCHES = [
    # key, 中文名, 起角, 末角, 时长ms, transform缓动, opacity缓动, 透视P, opacity起, opacity末
    ("ForwardIn",   "进场 · 节点图出现",   +80.0,   0.0, 300, CURVE_IN,     CURVE_OP_IN,  P_FWD,     0.0, 1.0),
    ("ForwardOut",  "退场 · 列表离开",     0.0,  -50.0, 128, CURVE_OUT,    CURVE_OP_OUT, P_FWD,     1.0, 0.0),
    ("BackwardIn",  "进场 · 列表回来",   -50.0,    0.0, 300, CURVE_IN,     CURVE_OP_IN,  P_FWD,     0.0, 1.0),
    ("BackwardOut", "退场 · 节点图离开",   0.0,  +80.0, 128, CURVE_OUT,    CURVE_OP_OUT, P_BWD_OUT, 1.0, 0.0),
]

SAMPLES = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]
FINE = [i / 20.0 for i in range(21)]
TAIL = [0.94, 0.96, 0.97, 0.98, 0.99, 0.995, 0.999, 1.0]


def clamp01(v):
    return min(1.0, max(0.0, v))


def main():
    out = []
    out.append("# turnstile 四支 —— 逐帧数值表（自动生成，勿手改）\n")
    out.append("生成器：`docs/win8-frames/_turn4.py`\n")
    out.append("屏幕设定：**%d x %d**，转轴屏幕绝对坐标 **x = %g, y = %g**，"
               "透视 P = **%g**（Forward 三支）/ **%g**（BackwardOut）\n"
               % (W, H, AXIS_X, AXIS_Y, P_FWD, P_BWD_OUT))
    out.append("模型：`scale(x, th) = P / (P + (x - axisX) * sin th)` —— 轴上点恒不缩放。\n")
    out.append("\n> 左边缘 = 屏幕 x=0（离轴 40px，几乎不缩）；"
               "右边缘 = 屏幕 x=%g（离轴 %gpx，缩得最狠）。\n" % (W, W - AXIS_X))

    for key, cn, a0, a1, dur, tc, oc, P, o0, o1 in BRANCHES:
        out.append("\n## %s —— %s\n" % (key, cn))
        out.append("| 项 | 值 |\n|---|---|")
        out.append("| 角度 | `%+g°` → `%+g°` |" % (a0, a1))
        out.append("| 时长 | **%d ms** |" % dur)
        out.append("| 透视 | `perspective(%gpx)` |" % P)
        out.append("| transform 缓动 | `%s` |" % (
            "cubic-bezier(0.01,0.975,0.4775,0.9775)" if tc is CURVE_IN
            else "cubic-bezier(0.4925,0.01,0.7675,-0.01)"))
        out.append("| opacity 缓动 | `%s` |" % (
            "cubic-bezier(0,2,0,2)" if oc is CURVE_OP_IN
            else "cubic-bezier(1,-0.42,0.995,-0.425)"))
        out.append("| opacity | `%g` → `%g` |" % (o0, o1))
        out.append("\n| t | ms | 角度 θ | 左边缘缩放 | 右边缘缩放 | **近/远比** | opacity 轨道 |")
        out.append("|---|---|---|---|---|---|---|")
        rows = []
        for t in SAMPLES:
            th = a0 + (a1 - a0) * tc(t)
            sl = scale_at(0.0, th, P)
            sr = scale_at(W, th, P)
            ratio = sl / sr
            op = clamp01(o0 + (o1 - o0) * oc(t))
            out.append("| %.0f%% | %.0f | %+7.2f° | %.4f | %.4f | **%.4f** | %.4f |"
                       % (t * 100, t * dur, th, sl, sr, ratio, op))
            rows.append((t, th, sl, sr, ratio))
        # 比值极值（注意：进场支从大收敛到 1，退场支从 1 发散出去 —— 不是"峰值"）
        ratios = [r[4] for r in rows]
        out.append("\n**近/远比范围 = %.4f .. %.4f**（t=0%% 时 %.4f，t=100%% 时 %.4f；"
                   "轴上点恒为 1.0000）\n"
                   % (min(ratios), max(ratios), rows[0][4], rows[-1][4]))

        # opacity 细采样（退场支的曲线是先恒 1、最后一刻才砸到 0，粗步长看不出来）
        out.append("\n**opacity 细时间轴**（步长 5%）：\n")
        out.append("| t | " + " | ".join("%d%%" % (s * 100) for s in FINE) + " |")
        out.append("|---|" + "---|" * len(FINE))
        out.append("| opacity | " + " | ".join(
            "%.3f" % clamp01(o0 + (o1 - o0) * oc(s)) for s in FINE) + " |")

        # 尾部超细采样 —— 退场 opacity 在最后几个百分点内从 1 砸到 0
        out.append("\n**opacity 尾部超细**（最后 4%）：\n")
        out.append("| t | " + " | ".join("%.1f%%" % (s * 100) for s in TAIL) + " |")
        out.append("|---|" + "---|" * len(TAIL))
        out.append("| opacity | " + " | ".join(
            "%.4f" % clamp01(o0 + (o1 - o0) * oc(s)) for s in TAIL) + " |")
        out.append("| 对应 ms | " + " | ".join(
            "%.1f" % (s * dur) for s in TAIL) + " |")

        # opacity 的特征时刻（二分法，两种方向含义不同，必须分开判）
        if o1 > o0:
            # 淡入：找它"到达 1"的时刻
            lo, hi = 0.0, 1.0
            for _ in range(60):
                mid = (lo + hi) / 2
                if clamp01(o0 + (o1 - o0) * oc(mid)) >= o1 - 0.001:
                    hi = mid
                else:
                    lo = mid
            t_hit = hi
            out.append("\n> **opacity 在 t=%.2f%%（%.2f ms / 共 %d ms）就到 1**，"
                       "此后 **%.0f%% 的时间（%.2f ms）一直保持完全不透明** —— "
                       "即「**瞬间淡入，然后长时间定格**」。\n"
                       % (t_hit * 100, t_hit * dur, dur,
                          (1 - t_hit) * 100, dur - t_hit * dur))
        else:
            # 淡出：找它"离开 1"的时刻
            lo, hi = 0.0, 1.0
            for _ in range(60):
                mid = (lo + hi) / 2
                if clamp01(o0 + (o1 - o0) * oc(mid)) <= o0 - 0.02:
                    hi = mid
                else:
                    lo = mid
            t_leave = hi
            out.append("\n> **opacity 直到 t=%.2f%%（%.2f ms / 共 %d ms）才开始变化**"
                       "（此前恒为 %.3f），即 **%.0f%% 的时间里透明度一点不动，"
                       "最后 %.2f ms 内砸到 0** —— 即「**不做淡出，是最后一刻切掉**」。\n"
                       % (t_leave * 100, t_leave * dur, dur, o0,
                          t_leave * 100, dur - t_leave * dur))

    # ---- 30 fps 验收采样表（ForwardIn，本机屏幕） ----
    FPS = 30.0
    out.append("\n---\n\n## 附：30 fps 验收采样表 —— `ForwardIn` 在 %d x %d 上\n" % (W, H))
    out.append("整个 300 ms 只有 **9 帧**（30 fps ⇒ 33.33 ms/帧）。"
               "强透视只存在于前 1–2 帧。\n")
    out.append("\n| 帧 | ms | θ | 左边缘缩放 | 右边缘缩放 | **近/远比** | 比值 > 1.5 ? |")
    out.append("|---|---|---|---|---|---|---|")
    fps_rows = []
    for k in range(10):
        ms = k * 1000.0 / FPS
        if ms > 300:
            break
        th = 80.0 - 80.0 * CURVE_IN(ms / 300.0)
        sl = scale_at(0.0, th, P_FWD)
        sr = scale_at(W, th, P_FWD)
        r = sl / sr
        fps_rows.append((k + 1, ms, th, r))
        out.append("| %d | %.1f | %+7.2f° | %.4f | %.4f | **%.4f** | %s |"
                   % (k + 1, ms, th, sl, sr, r, "✅ 是" if r > 1.5 else "—"))
    over = [row for row in fps_rows if row[3] > 1.5]
    out.append("\n> ⇒ 判据「近/远比 > 1.5」只在 **%d 帧**上成立（%s）；"
               "第 2 帧已降到 %.4f，第 3 帧 %.4f。\n"
               % (len(over),
                  "、".join("第 %d 帧(%.0fms)=%.4f" % (a, b, d) for a, b, _, d in over) or "无",
                  fps_rows[1][3] if len(fps_rows) > 1 else float("nan"),
                  fps_rows[2][3] if len(fps_rows) > 2 else float("nan")))
    out.append("> **验收脚本若从第 3 帧之后才开始抽，会误判成「没有透视」。**\n")

    txt = "\n".join(out)

    dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "turnstile-4branches.md")
    with open(dst, "w", encoding="utf-8") as f:
        f.write(txt + "\n")

    # 控制台可能是 GBK，正文一律只写进 UTF-8 文件（读文件，不要读控制台）
    print("[OK] wrote %s (%d chars, %d lines)"
          % (dst.encode("ascii", "replace").decode("ascii"),
             len(txt), txt.count("\n") + 1))


if __name__ == "__main__":
    main()
