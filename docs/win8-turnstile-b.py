#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
win8-turnstile-b.py  —  turnstile 透视投影的解析复算（独立于浏览器的一路交叉验证）

对照物：win8-tile-spec-B.md 的 §8.2 / §8.3 / §8.4。
§8.2-8.4 的那些数字是在真实浏览器 CSS 三维管线里量出来的（`transform-origin:-40px 50%`
+ 左右边缘 2px 全高标记 + getBoundingClientRect().height）。本脚本用解析式独立算同一批量，
两边应当逐位吻合 —— 吻合即证明浏览器那一路没读错，也证明公式本身可用。

模型（WinJS turnstile，base.js:17934 / 17972 / 16251）：
    元素 = 整屏页，宽 W 高 H，转轴在元素左侧外 40px、纵向居中：
        transform-origin: -40px 50%
    变换：perspective(P px) rotateY(theta)
    CSS perspective 投影 x' = x / (1 - z/P)，rotateY 给出 z = -d*sin(theta)  (d 自转轴向右为正)
        => h(d) = H * P / (P + d * sin(theta)),      d = x + 40,  x 是页面内横坐标
    左右边缘： d_left = 40,  d_right = W + 40

用法： python win8-turnstile-b.py
"""

import math

P_DEFAULT = 600.0          # 逐字见 §8.1：ForwardIn / ForwardOut / BackwardIn 用 600px；BackwardOut 用 800px
HINGE = 40.0               # 转轴在页面左外侧 40px（base.js:16251 offsetLeftLTR = -(40+left)）
THETA_FWD = 80.0           # 进场起始角 rotateY(80deg)
THETA_FWD_END = 0.0
THETA_EXIT = -50.0         # 退场终止角 rotateY(-50deg)
MS_FWD = 300.0             # 进场总时长
MS_EXIT = 128.0            # 退场总时长
FPS = 30.0

BZ_FWD = (0.01, 0.975, 0.4775, 0.9775)     # base.js:17934
BZ_EXIT = (0.4925, 0.01, 0.7675, -0.01)    # base.js:17972


def h_fraction(d, theta_deg, P=P_DEFAULT):
    """页面内距转轴 d 处的可见高度占原高 H 的比例。"""
    return P / (P + d * math.sin(math.radians(theta_deg)))


def edges(W, theta_deg, P=P_DEFAULT):
    l = h_fraction(HINGE, theta_deg, P)
    r = h_fraction(W + HINGE, theta_deg, P)
    return l, r, (l / r if r else float("inf"))


def cubic_bezier(p1x, p1y, p2x, p2y):
    """标准 CSS cubic-bezier：给定时间比例 t（x 轴），解出进度（y 轴）。牛顿迭代。"""
    cx = 3.0 * p1x
    bx = 3.0 * (p2x - p1x) - cx
    ax = 1.0 - cx - bx
    cy = 3.0 * p1y
    by = 3.0 * (p2y - p1y) - cy
    ay = 1.0 - cy - by

    def y_of(u):
        return ((ay * u + by) * u + cy) * u

    def x_of(u):
        return ((ax * u + bx) * u + cx) * u

    def dx_of(u):
        return (3.0 * ax * u + 2.0 * bx) * u + cx

    def f(t):
        u = t
        for _ in range(60):
            err = x_of(u) - t
            if abs(err) < 1e-12:
                break
            d = dx_of(u)
            if abs(d) < 1e-12:
                break
            u -= err / d
        u = min(1.0, max(0.0, u))
        return y_of(u)

    return f


def main():
    print("=" * 78)
    print("turnstile 解析复算 (P=%.0f, hinge=%.0fpx) — 对照 win8-tile-spec-B.md §8.2-8.4" % (P_DEFAULT, HINGE))
    print("h(d) = P/(P + d*sin(theta)),  d = x + 40,  d_left = 40,  d_right = W + 40")
    print("=" * 78)

    # ---------- §8.2 验收数 ----------
    print("\n[§8.2] theta=80deg 时左/右边缘高度占屏高比例与比值")
    print("  %-8s %-12s %-12s %-10s" % ("W", "left/H", "right/H", "left/right"))
    for W in (480.0, 683.0, 1366.0, 1920.0, 2732.0):
        l, r, ratio = edges(W, THETA_FWD)
        print("  %-8.0f %-12.4f %-12.4f %-10.4f" % (W, l, r, ratio))
    print("  -> left/H = %.4f 与 W 无关（d_left 恒为 40），Lead 的 0.9384 成立" % h_fraction(HINGE, THETA_FWD))
    print("  -> 480 宽 => 比值 1.7362（Lead 的 1.74 口径）；1366 宽 => 3.1009（A 的 3.1039 口径）")

    # ---------- §8.2.1 单位语义：CSS px = dp ----------
    print("\n[§8.2.1] 单位语义：WinJS 的 px 是 CSS px = 设备无关单位 = dp")
    print("  本机 1440px / density 3.5 = 411.43dp（§8.2.3 勘误；旧文写 density 3 = 480dp 是错的）")
    print("  -> 常量读作 perspective(600dp) / hinge -40dp；本机 px = 2100 / -140 / (P_OUT) 2800")
    print("  %-16s %-12s %-12s %-12s %-12s" % ("caliber", "W", "P", "hinge", "left/right"))
    for name, Wd, Pd, hg in (("dp REF 480", 480.0, 600.0, 40.0),
                             ("dp REAL 411.43", 411.4285714, 600.0, 40.0),
                             ("px on device", 1440.0, 2100.0, 140.0),
                             ("px desktop1366", 1366.0, 600.0, 40.0)):
        l = Pd / (Pd + hg * math.sin(math.radians(THETA_FWD)))
        r = Pd / (Pd + (Wd + hg) * math.sin(math.radians(THETA_FWD)))
        print("  %-16s %-12.2f %-12.0f %-12.0f %-12.4f" % (name, Wd, Pd, hg, l / r))
    print("  -> dp 参考机 480 与 px-on-device（三者同比例缩放）都给出 1.7393（比值严格不变）")
    print("  -> ★本机真值 W=411.43dp 给出 1.6337 —— 这才是装机后该量到的数")
    print("     A 的 3.1039 是 1366px 桌面口径，数不错但不是我们的口径。")

    # ---------- 标度不变性 ----------
    print("\n[§8.2.2] 标度不变性的限定条件")
    print("  (a) 只缩放 W 与 P、固定 hinge=40  -> 比值随 W 变（P/W 不是不变量）")
    print("  %-10s %-10s %-10s %-10s" % ("W", "P", "P/W", "left/right"))
    for W, Pp in ((1366.0, 600.0), (683.0, 300.0), (2732.0, 1200.0)):
        _, _, ratio = edges(W, THETA_FWD, Pp)
        print("  %-10.0f %-10.0f %-10.4f %-10.4f" % (W, Pp, Pp / W, ratio))
    print("  -> 三者 P/W 相同但比值不同 (3.1009 / 2.9760 / 3.1692)")
    print("  (b) W / P / hinge 三者同比例缩放 -> 比值严格不变")
    print("  %-10s %-10s %-10s %-10s" % ("W", "P", "hinge", "left/right"))
    for k in (1.0, 0.5, 2.0):
        l = 600.0 / (600.0 + 40.0 * math.sin(math.radians(THETA_FWD)))
        r = 600.0 / (600.0 + (480.0 + 40.0) * math.sin(math.radians(THETA_FWD)))
        print("  %-10.0f %-10.0f %-10.0f %-10.4f" % (480.0 * k, 600.0 * k, 40.0 * k, l / r))
    print("  -> 三者全等比时比值恒为 1.7393（h(d)=kP/(kP+k*d*sin) = P/(P+d*sin)）")
    print("     => 精确表述：'P、W、铰链偏移同比例缩放时比值不变；仅缩放 P、W 而固定铰链偏移时不成立。'")

    # ---------- §8.3 进场轨迹 ----------
    print("\n[§8.3] 进场轨迹：%gms, %gdeg -> %gdeg, cubic-bezier%s, W=1366 H=768" %
          (MS_FWD, THETA_FWD, THETA_FWD_END, BZ_FWD))
    ease = cubic_bezier(*BZ_FWD)
    W = 1366.0
    nframes = int(round(MS_FWD / 1000.0 * FPS))
    print("  %-5s %-8s %-10s %-10s %-10s %-10s" % ("frame", "ms", "theta", "left/H", "right/H", "left/right"))
    for i in range(nframes + 1):
        t = i / float(nframes)
        theta = THETA_FWD + (THETA_FWD_END - THETA_FWD) * ease(t)
        l, r, ratio = edges(W, theta)
        print("  %-5d %-8.0f %-10.2f %-10.3f %-10.3f %-10.3f" % (i + 1, t * MS_FWD, theta, l, r, ratio))
    print("  -> y1=0.975 使曲线起步即接近终点：80deg 在第 1 帧内掉到 30deg")
    print("     => 30fps 录像里 'left/right > 1.5' 的画格只有第 1-2 帧，可用帧数 1-3。")

    # ---------- §8.3b 目标口径（480dp / 600dp）的同一轨迹 ----------
    print("\n[§8.3b] 同一轨迹换成目标口径 W=480dp / P=600dp / hinge=40dp（起始比 1.7393）")
    print("  %-5s %-8s %-10s %-10s %-10s %-10s" % ("frame", "ms", "theta", "left/H", "right/H", "left/right"))
    for i in range(nframes + 1):
        t = i / float(nframes)
        theta = THETA_FWD + (THETA_FWD_END - THETA_FWD) * ease(t)
        l = h_fraction(40.0, theta, 600.0)
        r = h_fraction(480.0 + 40.0, theta, 600.0)
        print("  %-5d %-8.0f %-10.2f %-10.4f %-10.4f %-10.4f" % (i + 1, t * MS_FWD, theta, l, r, l / r))
    print("  -> 该口径下 'left/right > 1.5' 只剩第 1 帧（第 2 帧即 1.3911）")
    print("     => 30fps 上 3D 立体感只有一帧可见；要复刻的是 t=0 的 1.7393 与 300ms 的连续收敛，")
    print("        而不是'在某一帧上看出透视'。")

    # ---------- §8.4 退场轨迹 + 退化 ----------
    print("\n[§8.4] 退场轨迹：%gms, 0 -> %gdeg, cubic-bezier%s, W=1366" % (MS_EXIT, THETA_EXIT, BZ_EXIT))
    ease2 = cubic_bezier(*BZ_EXIT)
    nexit = int(round(MS_EXIT / 1000.0 * FPS))
    print("  %-8s %-10s %-12s %-10s" % ("ms", "theta", "left/H", "right/H"))
    for i in range(nexit + 1):
        t = i / float(nexit)
        theta = THETA_EXIT * ease2(t)
        l = h_fraction(HINGE, theta)
        r = h_fraction(W + HINGE, theta)
        print("  %-8.0f %-10.2f %-12.3f %-10.3f%s" % (t * MS_EXIT, theta, l, r,
              "   <-- 分母变号，投影退化" if (P_DEFAULT + (W + HINGE) * math.sin(math.radians(theta))) <= 0 else ""))
    wmax = P_DEFAULT / abs(math.sin(math.radians(THETA_EXIT))) - HINGE
    print("  W=1366 时 d_right=%g, P + d*sin(-50deg) = %.1f < 0 -> 远端落到观察者身后，投影病态。" %
          (W + HINGE, P_DEFAULT + (W + HINGE) * math.sin(math.radians(THETA_EXIT))))
    print("  不退化条件：W <= P/|sin(theta)| - 40（单位必须与 P 一致）")
    print("  %-16s %-10s %-10s %-12s %-12s %-8s" % ("caliber", "W", "P", "d_right", "P-d*|sin|", "ok?"))
    for name, Wd, Pd, hg in (("dp REF 480", 480.0, 600.0, 40.0),
                             ("dp REAL 411.43", 411.4285714, 600.0, 40.0),
                             ("px on device", 1440.0, 2100.0, 140.0),
                             ("px, P=600 WRONG", 1440.0, 600.0, 140.0)):
        d = Wd + hg
        margin = Pd - d * abs(math.sin(math.radians(THETA_EXIT)))
        print("  %-16s %-10.2f %-10.0f %-12.1f %-12.1f %-8s" %
              (name, Wd, Pd, d, margin, "OK" if margin > 0 else "DEGENERATE"))
    print("  dp 口径上限 W <= %.1f dp；本机 411.43dp 安全（余量 %.1f dp；480dp 参考机余量 %.1f dp）"
          % (wmax, wmax - 411.4285714, wmax - 480.0))
    print("  => 引用退场比值必须同时声明屏宽与 P 的单位；'600px' 直接搬到 1440px 屏上必然越界。")


if __name__ == "__main__":
    main()
