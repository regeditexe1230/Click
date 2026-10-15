# -*- coding: utf-8 -*-
"""
按 **dp 读法**（CSS px == Android dp，density 3）重算 spec 正文里那几张表。

正确参数（与实现一致）：
    W = 1440 px, axisX = -40dp = -120 px, P_IN = 600dp = 1800 px, P_OUT = 800dp = 2400 px
    d_left = 120, d_right = 1560

旧表用的是 px 读法（axisX = -40），比值 1.7710 —— 错的，本脚本产出替换值。

只输出 ASCII，避免 GBK 控制台 UnicodeEncodeError。
"""
import math

W = 1440.0
AXIS = -120.0
P_IN = 1800.0
EASE_IN = (0.01, 0.975, 0.4775, 0.9775)
EASE_OUT = (0.4925, 0.01, 0.7675, -0.01)

# ---------- cubic-bezier 求解（二分，x 单调） ----------


def _bez(a, b, s):
    return 3.0 * (1.0 - s) ** 2 * s * a + 3.0 * (1.0 - s) * s * s * b + s ** 3


def bezier_y(t, x1, y1, x2, y2):
    if t <= 0.0:
        return 0.0
    if t >= 1.0:
        return 1.0
    lo, hi = 0.0, 1.0
    for _ in range(80):
        mid = (lo + hi) / 2.0
        if _bez(x1, x2, mid) < t:
            lo = mid
        else:
            hi = mid
    return _bez(y1, y2, (lo + hi) / 2.0)


def scale_at(x, theta_deg, P):
    return P / (P + (x - AXIS) * math.sin(math.radians(theta_deg)))


def lr(theta_deg, P):
    l = scale_at(0.0, theta_deg, P)
    r = scale_at(W, theta_deg, P)
    return l, r, l / r


def fmt(v):
    return "%+.2f" % v


print("=" * 74)
print("T2 correction: Compose TransformOrigin fraction")
print("=" * 74)
print("  axisX = -40dp = -120px   =>  -120/1440 = %.8f" % (AXIS / W))
print("  (old spec said -40/1440 = %.8f  -- WRONG)" % (-40.0 / 1440.0))
print("  axisY = 1600px           =>  1600/3200 = %.8f" % (1600.0 / 3200.0))
print("")

print("=" * 74)
print("T5 table 1: ForwardIn  (theta = 80 * (1 - ease_in(t)), P = 1800)")
print("=" * 74)
print("| t | ms | theta | left | right | near/far |")
for t in (0.0, 0.10, 0.20, 0.30, 0.50, 0.70, 1.0):
    y = bezier_y(t, *EASE_IN)
    th = 80.0 * (1.0 - y)
    l, r, ratio = lr(th, P_IN)
    print("| %d%% | %.0f | %s | %.4f | %.4f | %.4f |"
          % (round(t * 100), t * 300, fmt(th), l, r, ratio))
print("")

print("=" * 74)
print("T5 table 2: ForwardOut  (theta = -50 * ease_out(t), P = 1800)")
print("=" * 74)
print("| t | ms | theta | left | right | near/far |")
for t in (0.0, 0.50, 0.70, 0.90, 1.0):
    y = bezier_y(t, *EASE_OUT)
    th = -50.0 * y
    l, r, ratio = lr(th, P_IN)
    print("| %d%% | %.0f | %s | %.4f | %.4f | %.4f |"
          % (round(t * 100), t * 128, fmt(th), l, r, ratio))
print("")

print("=" * 74)
print("T7 table: 30fps sampling, ForwardIn on 1440x3200 (P=1800)")
print("=" * 74)
print("| frame | ms | theta | left | right | near/far | >1.5 |")
for i in range(0, 10):
    ms = i * 1000.0 / 30.0
    t = ms / 300.0
    y = bezier_y(min(t, 1.0), *EASE_IN)
    th = 80.0 * (1.0 - y)
    l, r, ratio = lr(th, P_IN)
    if i in (0, 1, 2, 3, 4, 5, 8, 9):
        print("| %d | %.1f | %s | %.4f | %.4f | %.4f | %s |"
              % (i + 1, ms, fmt(th), l, r, ratio, "YES" if ratio > 1.5 else "-"))
print("")

print("=" * 74)
print("T6 correction: non-degenerate margins (d_right = 1560, d_left = 120)")
print("=" * 74)
print("  BackwardOut theta=80, P=2400px : 2400 - 1560*sin80 = %+.1f  OK"
      % (2400.0 - 1560.0 * math.sin(math.radians(80))))
print("  ForwardOut  theta=50, P=1800px : 1800 - 1560*sin50 = %+.1f  OK"
      % (1800.0 - 1560.0 * math.sin(math.radians(50))))
print("  (official literals 600px/800px used raw on a 1440 screen:)")
print("    ForwardOut  : 600 - 1480*sin50 = %+.1f  DEGENERATE"
      % (600.0 - 1480.0 * math.sin(math.radians(50))))
print("    BackwardOut : 800 - 1457.5      = %+.1f  DEGENERATE"
      % (800.0 - 1457.5))
print("")

print("=" * 74)
print("Summary vs official (600/480, axis -40)")
print("=" * 74)
ol, orr, oratio = 600.0 / (600.0 + 40.0 * math.sin(math.radians(80))), \
                  600.0 / (600.0 + 520.0 * math.sin(math.radians(80))), 0.0
oratio = ol / orr
print("  official     left=%.5f right=%.5f ratio=%.5f" % (ol, orr, oratio))
il, ir, iratio = lr(80.0, P_IN)
print("  impl dp      left=%.5f right=%.5f ratio=%.5f  delta=%+.4f%%"
      % (il, ir, iratio, (iratio - oratio) / oratio * 100.0))
