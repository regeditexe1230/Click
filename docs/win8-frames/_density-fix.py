# -*- coding: utf-8 -*-
"""density 勘误复算：把「本机 = 480dp」这个错误假设换成本机真值，并给出修正后的 80° 左右边高比。

真值（Lead 实测）：
    adb shell wm size     -> 1440x3200 (px)
    adb shell wm density  -> Physical density: 560  (=> scale 3.5)
    => density scale = 3.5
    => 本机宽 = 1440 / 3.5 = 411.4286 dp      （不是之前记的 480 dp）

模型（与 TurnstileProjection.kt 一致）：
    d(x)     = x - axis                       # 到转轴的距离；axis 在屏幕左外侧 40dp
    scale(x) = P / (P + d(x) * sin θ)         # θ>0 = 右边缘转进屏幕里
    左右边高比 = scale(左边缘) / scale(右边缘)   # 左边缘近 => 比 > 1

输出全部走 UTF-8 文件；控制台只印 ASCII（控制台是 GBK）。
"""
import math
import pathlib

PX_W, PX_H = 1440.0, 3200.0
DENSITY = 3.5                     # 实测（原按 3.0 记）
DENSITY_OLD = 3.0

W_DP = PX_W / DENSITY             # 411.4286 dp  <- 本机真值
W_DP_OLD = PX_W / DENSITY_OLD     # 480.0 dp     <- 之前错记的

AXIS_DP = -40.0                   # = TURNSTILE_ORIGIN_X = (-40).dp
P_DP = 600.0                      # = TURNSTILE_PERSPECTIVE_IN = 600.dp
THETA = 80.0

SIN = math.sin(math.radians(THETA))


def ratio(W, axis, P, theta_deg=THETA):
    s = math.sin(math.radians(theta_deg))
    d_left = 0.0 - axis           # 左边缘到轴的距离
    d_right = W - axis            # 右边缘到轴的距离
    sl = P / (P + d_left * s)
    sr = P / (P + d_right * s)
    return sl, sr, sl / sr, d_left, d_right


out = []
out.append("density 真值 = %.1f   本机宽 = %.4f dp   (原错记 density %.1f => %.1f dp)"
           % (DENSITY, W_DP, DENSITY_OLD, W_DP_OLD))
out.append("")

# --- 1) 官方 WP8 参考机（W = 480 dp, P = 600, axis = -40）---
sl, sr, r, dl, dr = ratio(480.0, AXIS_DP, P_DP)
out.append("[参考机口径] W = 480.0000 dp  P = 600 dp  axis = -40 dp  theta = 80 deg")
out.append("    d_left  = %.4f   scale_left  = %.6f" % (dl, sl))
out.append("    d_right = %.4f   scale_right = %.6f" % (dr, sr))
out.append("    ratio   = %.6f   => %.4f" % (r, r))
out.append("")

# --- 2) 本机（W = 411.4286 dp）---
sl2, sr2, r2, dl2, dr2 = ratio(W_DP, AXIS_DP, P_DP)
out.append("[本机口径] W = %.4f dp  P = 600 dp  axis = -40 dp  theta = 80 deg" % W_DP)
out.append("    d_left  = %.4f   scale_left  = %.6f" % (dl2, sl2))
out.append("    d_right = %.4f   scale_right = %.6f" % (dr2, sr2))
out.append("    ratio   = %.6f   => %.4f" % (r2, r2))
out.append("")

# --- 3) 代码实际算的东西（px 空间；应当与 (2) 完全一致）---
P_PX = P_DP * DENSITY
AXIS_PX = AXIS_DP * DENSITY
sl3, sr3, r3, dl3, dr3 = ratio(PX_W, AXIS_PX, P_PX)
out.append("[代码 px 空间] P = %.1f px (=600dp*3.5)  axis = %.1f px (=-40dp*3.5)" % (P_PX, AXIS_PX))
out.append("    d_left  = %.4f   scale_left  = %.6f" % (dl3, sl3))
out.append("    d_right = %.4f   scale_right = %.6f" % (dr3, sr3))
out.append("    ratio   = %.6f   => %.4f" % (r3, r3))
out.append("    与 [本机口径] 的偏差 = %.4f%%" % (abs(r3 - r2) / r2 * 100.0))
out.append("")

# --- 4) 若按 density 3.0（错误前提）会算出什么 ---
sl4, sr4, r4, _, _ = ratio(1440.0, -120.0, 1800.0)
out.append("[旧错误前提] W=1440px 当 480dp -> axis=-120px, P=1800px")
out.append("    scale_left = %.6f  scale_right = %.6f  ratio = %.6f => %.4f"
           % (sl4, sr4, r4, r4))
out.append("    ^ 这才是之前写进文档的 1.7393；它对应的是 480dp 宽的屏，不是本机")
out.append("")

# --- 5) 关键比值对比 ---
out.append("[比值对比]")
out.append("    P/W  参考机 = %.4f      本机 = %.4f" % (P_DP / 480.0, P_DP / W_DP))
out.append("    |axis|/W 参考机 = %.4f%%  本机 = %.4f%%"
           % (40.0 / 480.0 * 100, 40.0 / W_DP * 100))
out.append("    80deg 左右边高比  参考机 = %.4f  本机 = %.4f" % (r, r2))
out.append("")
out.append("结论：官方字面量 P=600px/axis=-40px 就是【固定绝对值】；本机是 411.43dp 宽的屏，")
out.append("      所以同一套官方常量落在本机上的比值本来就是 1.6337 —— 代码用固定的 600.dp/(-40).dp，")
out.append("      正好等价于「官方 CSS 跑在 411.43dp 宽的视口上」，因此仍然是逐位复刻。")

pathlib.Path("docs/win8-frames/density-fix.txt").write_text("\n".join(out), encoding="utf-8")

print("density = %.1f  W_this_device = %.4f dp  (old wrong: %.1f dp)"
      % (DENSITY, W_DP, W_DP_OLD))
print("ratio 480dp (reference) = %.4f" % r)
print("ratio this device       = %.4f   <== ANSWER" % r2)
print("ratio code px space     = %.4f   (dev %.4f%%)" % (r3, abs(r3 - r2) / r2 * 100))
print("ratio old wrong premise = %.4f" % r4)
print("full -> docs/win8-frames/density-fix.txt")
