#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
win8-turnstile-oracle-b.py  —  turnstile 的**期望投影坐标表**（给实现组当验收基准）

为什么需要它：
    代码审读不能只看实现写的是不是 "600dp / 80deg"。真正要卡的是**画出来的几何**。
    本脚本按 WinJS turnstile 的声明式变换，逐帧算出页面四个角**应该**落在屏幕的哪个坐标，
    实现组的 draw 阶段输出（4 点射影矩阵）应当与这张表逐帧吻合到亚像素级。

来源（逐字，见 win8-tile-spec-B.md §8.1）：
    base.js:17958/17959  ForwardIn  perspective(600px) rotateY(80deg) -> rotateY(0deg), 300ms
    base.js:17957         cubic-bezier(0.01, 0.975, 0.4775, 0.9775)
    base.js:16251        transform-origin = (-40, innerHeight/2)  ← 屏幕左外侧 40dp、纵向居中

单位语义（§8.2.1）：
    WinJS 的 px 是 CSS px = 设备无关单位 = Android 的 dp。
    本机 1440px / density 3 = 480dp ⇒ 常量读作 perspective(600dp)、铰链 -40dp。
    下表默认按 dp 输出；要本机 px 就把 DENSITY 乘进去（P 1800 / 铰链 120）。

⚠️ 判据口径（Lead 已拍板，本文件的默认值就是它）：
    axis_x = -40dp（本机 -120px）、P_IN = 600dp（本机 1800px）、P_OUT = 800dp（本机 2400px）
    ⇒ θ=80° 时**目标左右边高比 = 1.7393**。
    **1.77 作废**（那是"P 缩了、铰链没缩"的半缩口径：铰链固定在屏左缘外 40px = 13.3dp）。
    实现侧 ProgramScreen.kt:1261/1264/1267 = (-40).dp / 600.dp / 800.dp，正是这一档。
    验收夹具 docs/flip-harness.md 与 tools/measure_pages.py 的 model @80deg 同步为 1.7393 / axis -120px。
    ⚠️ 若本文件被喂 1366/768/600 这组参数（A 的桌面口径），会打印 3.1009 —— 那**只作对照**，不是判据。

判据强度（§9）：
    「左右边高比」是**硬判据**（height = H·P/(P-Z) 与消失点 cy 无关，对 perspective-origin 免疫）。
    「可见宽度 8.72dp / 左边缘 x=-33.48 / 起始帧可见比 1.1527」是**软判据** —— 只有实现声明
    复刻 CSS perspective() **函数**形式（消失点在铰链上）时才判；本实现走 setPolyToPoly
    四点射影（ProgramScreen.kt:485），消失点由它自己的矩阵定义，按"自洽"判，不拿 CSS 的水平摆放卡它。

几何：
    元素内一点 (x, y)，距铰链 d = x + 40。
    rotateY(theta) 后 3D 位置 (d*cos, y - H/2, -d*sin)，透视投影除以 (1 + d*sin/P)：
        X(x, y) = -40 + d*cos(theta) * P / (P + d*sin(theta))
        Y(x, y) = (y - H/2) * P / (P + d*sin(theta)) + H/2
    theta = 0 时 X = x、Y = y，自洽。

用法：
    python win8-turnstile-oracle-b.py                 # dp 口径，480x754（★默认即判据口径）
    python win8-turnstile-oracle-b.py 1440 2262 1800  # 本机 px 口径（W H P，应与 dp 表同比值）
    python win8-turnstile-oracle-b.py 1366 768 600    # A 的桌面口径，仅对照（得 3.1009）
"""

import math
import sys

HINGE = 40.0        # base.js:16251  offsetLeftLTR = -(40 + left)  ⇒ 铰链在页左外侧 40dp
BZ_FWD = (0.01, 0.975, 0.4775, 0.9775)   # base.js:17957
THETA0 = 80.0       # base.js:17958
MS_FWD = 300.0      # base.js:17956
FPS = 30.0


def cubic_bezier(p1x, p1y, p2x, p2y):
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


def project(x, y, theta_deg, W, H, P, hinge=None):
    """元素内 (x, y) -> 屏幕坐标 (X, Y)。

    hinge=None 时取模块级 HINGE（main() 可按口径改写它）——**不要**把 HINGE 写进默认参数，
    否则 px 口径会静默沿用 dp 铰链，算出作废的 1.7710。
    """
    if hinge is None:
        hinge = HINGE
    d = x + hinge
    s = math.sin(math.radians(theta_deg))
    c = math.cos(math.radians(theta_deg))
    denom = P + d * s
    if abs(denom) < 1e-9:
        return float("nan"), float("nan")
    X = -hinge + d * c * P / denom
    Y = (y - H / 2.0) * P / denom + H / 2.0
    return X, Y


def main():
    global HINGE
    note = ""
    if len(sys.argv) >= 4:
        W, H, P = float(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3])
        if len(sys.argv) >= 5:
            HINGE = float(sys.argv[4])
            note = "铰链由第 4 个参数显式给定"
        else:
            # 不显式给铰链时**按 W 自动同比缩放**，否则会静默算出作废的 1.7710
            HINGE = 40.0 * (W / 480.0)
            note = "铰链未给，按 W/480 自动缩放 = %.2f（若要复现 A 的 1366 桌面口径，请显式传 40）" % HINGE
    else:
        W, H, P = 480.0, 754.0, 600.0
        note = "默认 = 判据口径（480dp / P=600dp / 铰链 40dp）"

    print("=" * 96)
    print("turnstile ForwardIn 期望投影坐标表  —  W=%.0f  H=%.0f  P=%.0f  hinge=%.2f" % (W, H, P, HINGE))
    print("基准：base.js:17956-17958  %gms, %gdeg -> 0deg, cubic-bezier%s" % (MS_FWD, THETA0, BZ_FWD))
    print("单位：与传入的 W/H/P 同单位（dp 口径则全为 dp；px 口径则全为 px）")
    print("口径：%s" % note)
    # 口径自检：把同一个 θ=80° 的起始比值按各种读法都打出来，防止静默用错口径
    s80 = math.sin(math.radians(THETA0))
    _r = lambda w, p, hg: (p / (p + hg * s80)) / (p / (p + (w + hg) * s80))
    print("判据：本口径 θ=80° 起始左右比 = **%.4f**" % _r(W, P, HINGE))
    if abs(_r(W, P, HINGE) - 1.7393) > 0.002:
        print("      ⚠️ 偏离拍板值 1.7393 ⇒ 你多半在用**作废口径**。参考：")
        print("         · 判据口径 480dp / P=600 / hinge=40        -> 1.7393")
        print("         · 半缩口径 1440px / P=1800 / hinge=40       -> 1.7710  ← 已作废")
        print("         · A 桌面口径 1366px / P=600 / hinge=40      -> 3.1009  ← 仅对照")
    print("=" * 96)

    ease = cubic_bezier(*BZ_FWD)
    n = int(round(MS_FWD / 1000.0 * FPS))     # 300ms @30fps = 9 帧间隔

    print("\n[A] 每帧四角屏幕坐标 + 左右边缘可见高")
    print("  %-5s %-6s %-9s | %-19s %-19s | %-9s %-9s %-9s" %
          ("帧", "ms", "theta", "左上(0,0)", "右上(W,0)", "左边缘高", "右边缘高", "左/右"))
    for i in range(n + 1):
        t = i / float(n)
        theta = THETA0 * (1.0 - ease(t))
        tl = project(0.0, 0.0, theta, W, H, P)
        tr = project(W, 0.0, theta, W, H, P)
        bl = project(0.0, H, theta, W, H, P)
        br = project(W, H, theta, W, H, P)
        hl = bl[1] - tl[1]
        hr = br[1] - tr[1]
        print("  %-5d %-6.0f %-9.3f | %-19s %-19s | %-9.2f %-9.2f %-9.4f" %
              (i + 1, t * MS_FWD, theta,
               "(%.1f, %.1f)" % tl, "(%.1f, %.1f)" % tr, hl, hr, (hl / hr) if hr else float("inf")))

    print("\n[B] 四角完整坐标（实现组 draw 阶段直接对这四个点断言）")
    for i in range(n + 1):
        t = i / float(n)
        theta = THETA0 * (1.0 - ease(t))
        pts = [project(x, y, theta, W, H, P) for x, y in ((0, 0), (W, 0), (W, H), (0, H))]
        print("  帧%-2d ms=%-4.0f theta=%7.3f  TL(%8.2f,%8.2f)  TR(%8.2f,%8.2f)  "
              "BR(%8.2f,%8.2f)  BL(%8.2f,%8.2f)" %
              (i + 1, t * MS_FWD, theta,
               pts[0][0], pts[0][1], pts[1][0], pts[1][1],
               pts[2][0], pts[2][1], pts[3][0], pts[3][1]))

    print("\n[C] 冒烟断言（实现组可直接拿去做单测）")
    tl0 = project(0.0, 0.0, THETA0, W, H, P)
    tr0 = project(W, 0.0, THETA0, W, H, P)
    bl0 = project(0.0, H, THETA0, W, H, P)
    br0 = project(W, H, THETA0, W, H, P)
    print("  1) 起始帧左边缘可见高 / 原高 = %.4f   （期望 %.4f）" % ((bl0[1] - tl0[1]) / H, P / (P + HINGE * math.sin(math.radians(THETA0)))))
    print("  2) 起始帧右边缘可见高 / 原高 = %.4f   （期望 %.4f）" % ((br0[1] - tr0[1]) / H, P / (P + (W + HINGE) * math.sin(math.radians(THETA0)))))
    exp_r = (P / (P + HINGE * s80)) / (P / (P + (W + HINGE) * s80))
    print("  3) 起始帧左/右 = %.4f   ← 关键断言；本口径期望 **%.4f**（判据口径下 = 1.7393）" %
          ((bl0[1] - tl0[1]) / (br0[1] - tr0[1]), exp_r))
    print("     参考：判据 480dp/600/40 -> 1.7393 ｜ 半缩 1440px/1800/40 -> 1.7710（作废）｜ A 桌面 1366/600/40 -> 3.1039（仅对照）")
    print("  4) 末帧（300ms）四角应回到原矩形：TL(0,0) TR(%.0f,0) BR(%.0f,%.0f) BL(0,%.0f)" % (W, W, H, H))
    print("  5) 左边缘上角 Y 应从 %.2f 收敛到 0.00（单调）；右边缘上角 Y 从 %.2f 到 0.00。" % (tl0[1], tr0[1]))
    print("  6) 全程 X 单调不减且 X(0)=%.2f -> 每帧左上角 X 必须严格递增（铰链在页左外侧，页面只能向右展开）。" %
          project(0.0, 0.0, THETA0, W, H, P)[0])

    print("\n[D] **屏幕上真正看得见的**梯形（重要：元素左边缘在大角度时是负坐标 = 在屏幕外！）")
    print("    实测（浏览器，W=1366 H=768 P=600 rotateY(80deg)）：元素左边缘落在 x=-33.48，右边缘 x=+33.78，")
    print("    整页只占 67.29px 宽；屏幕内可见的只有 x∈[0, 33.78]。⇒ 不能拿'元素左边缘'当可见左边界。")
    print("    %-5s %-6s %-8s | %-13s %-13s | %-10s %-10s %-10s" %
          ("帧", "ms", "theta", "元素左边缘X", "元素右边缘X", "可见宽", "可见左高", "可见右高"))
    for i in range(n + 1):
        t = i / float(n)
        theta = THETA0 * (1.0 - ease(t))
        xl = project(0.0, 0.0, theta, W, H, P)[0]
        xr = project(W, 0.0, theta, W, H, P)[0]
        if theta <= 1e-9:
            hvl = hvr = H
            vis_w = W
        else:
            s = math.sin(math.radians(theta))
            c = math.cos(math.radians(theta))
            denom_u = P * c - HINGE * s
            u0 = (HINGE * P / denom_u) if abs(denom_u) > 1e-9 else float("inf")
            scale0 = P / (P + u0 * s) if u0 != float("inf") else 0.0
            hvl = H * scale0
            hvr = H * P / (P + (W + HINGE) * s)
            vis_w = max(0.0, xr - max(0.0, xl))
        r_vis = (hvl / hvr) if hvr else float("inf")
        print("  %-5d %-6.0f %-8.3f | %-13.2f %-13.2f | %-10.2f %-10.2f %-10.2f  ratio=%.4f" %
              (i + 1, t * MS_FWD, theta, xl, xr, vis_w, hvl, hvr, r_vis))
    print("  ⇒ 屏幕内的左右高之比**不等于**元素边之比：元素左边缘在屏幕外，它的高度（含最夸张的透视）看不到。")
    print("     例：帧1 元素边之比 1.7393（480dp 口径），但屏幕上可见之比只有约 1.15。")
    print("     所以 §8 的 1.7393 只能用于**投影矩阵断言**，不能用于**截图断言**。")

    print("\n[D2] 退化自检（退场 -50deg 用，见 win8-tile-spec-B.md §8.4）")
    for name, Pd, Wd, hg in (("dp (ours)", P, W, HINGE),
                             ("px on device", P * 3.0, W * 3.0, HINGE * 3.0)):
        d = Wd + hg
        margin = Pd - d * abs(math.sin(math.radians(50.0)))
        print("  %-14s W=%-7.0f P=%-7.0f  P - d*|sin50| = %+8.1f  %s" %
              (name, Wd, Pd, margin, "OK" if margin > 0 else "DEGENERATE"))
    print("  ⇒ BackwardOut 用 perspective(800px)（base.js:18072/18073），比 ForwardOut 的 600px 更不易退化，")
    print("     但两条都必须满足 W <= P/|sin50deg| - 40。")


if __name__ == "__main__":
    main()
