# -*- coding: utf-8 -*-
"""
独立验收（只读）：核对 tile-flip 实现对 turnstile 投影的复刻是否与官方逐位一致。

核心争点：官方源码里的 600px / 800px / 40px 是 CSS px（**设备无关单位**）。
在 density=3 的本机上，CSS px 应该映射成 Android dp，而不是原始像素。
本脚本把三种读法并排算出来，看哪一种能复现官方比值 1.7393。

用法：D:\\python3.14\\python.exe docs\\win8-frames\\_verify-impl.py
"""
import math

SIN80 = math.sin(math.radians(80.0))
SIN50 = math.sin(math.radians(50.0))


def scales(W, axisX, P, theta_deg):
    """返回 (左边缘缩放, 右边缘缩放)。d = x - axisX；缩放 = P/(P + d*sinθ)。"""
    s = math.sin(math.radians(theta_deg))
    d_left = 0.0 - axisX
    d_right = W - axisX
    return P / (P + d_left * s), P / (P + d_right * s)


def ratio(W, axisX, P, theta_deg):
    l, r = scales(W, axisX, P, theta_deg)
    return l / r


def report(name, W, axisX, P, theta_deg):
    l, r = scales(W, axisX, P, theta_deg)
    d_left = 0.0 - axisX
    d_right = W - axisX
    print("%-34s W=%-6.0f axis=%-7.1f P=%-6.0f theta=%-5.1f" % (name, W, axisX, P, theta_deg))
    print("     d_left=%-8.1f d_right=%-8.1f" % (d_left, d_right))
    print("     left=%.5f  right=%.5f  ratio=%.5f" % (l, r, l / r))
    print("     P/W=%.5f   axis/W=%.5f" % (P / W, axisX / W))
    # 不退化检查：分母 P + d*sinθ 必须 > 0
    print("     non-degenerate margin: left=%+.1f right=%+.1f"
          % (P + d_left * math.sin(math.radians(theta_deg)),
             P + d_right * math.sin(math.radians(theta_deg))))
    print("")
    return l / r


print("=" * 78)
print("A. 官方几何（CSS px 语境：WP8 逻辑视口 480 宽）")
print("=" * 78)
rA = report("official 600/480", 480.0, -40.0, 600.0, 80.0)

print("=" * 78)
print("B. 本机实现（dp 读法：density 3，1440px = 480dp）")
print("   实际代码：TURNSTILE_ORIGIN_X = (-40).dp = -120px, TURNSTILE_PERSPECTIVE_IN = 600.dp = 1800px")
print("=" * 78)
rB = report("impl dp-reading", 1440.0, -120.0, 1800.0, 80.0)

print("=" * 78)
print("C. 我 spec 早前的 px 读法（axis 当原始像素，不随密度缩放）")
print("=" * 78)
rC = report("spec px-reading (WRONG)", 1440.0, -40.0, 1800.0, 80.0)

print("=" * 78)
print("结论")
print("=" * 78)
print("官方 ratio            = %.5f" % rA)
print("实现 dp 读法 ratio    = %.5f   偏差 = %+.4f%%" % (rB, (rB - rA) / rA * 100.0))
print("spec px 读法 ratio    = %.5f   偏差 = %+.4f%%" % (rC, (rC - rA) / rA * 100.0))
print("")
print("dp 读法同时保住 P/W 与 axis/W：")
print("  official P/W=%.5f axis/W=%.5f" % (600.0 / 480.0, -40.0 / 480.0))
print("  impl     P/W=%.5f axis/W=%.5f" % (1800.0 / 1440.0, -120.0 / 1440.0))
print("  spec-px  P/W=%.5f axis/W=%.5f   <-- axis/W 对不上" % (1800.0 / 1440.0, -40.0 / 1440.0))
print("")

print("=" * 78)
print("D. 退场相位的不退化余量（决定 2 的硬理由）")
print("=" * 78)
print("ForwardOut  theta=-50, P=600.dp=1800px, d_right=1560")
print("   margin = 1800 - 1560*sin50 = %+.1f  => %s"
      % (1800 - 1560 * SIN50, "OK" if 1800 - 1560 * SIN50 > 0 else "DEGENERATE"))
print("BackwardOut theta=+80, P=800.dp=2400px, d_right=1560")
print("   margin = 2400 - 1560*sin80 = %+.1f  => %s"
      % (2400 - 1560 * SIN80, "OK" if 2400 - 1560 * SIN80 > 0 else "DEGENERATE"))
print("反例：若把 600 当原始像素（P=600px）用在本机 1440 宽屏上")
print("   ForwardOut margin = 600 - 1480*sin50 = %+.1f  => %s"
      % (600 - 1480 * SIN50, "OK" if 600 - 1480 * SIN50 > 0 else "DEGENERATE(翻折)"))
print("")
print("E. Compose TransformOrigin 比例值（全屏层）")
print("   实现: originX=-120px / 1440 = %.8f" % (-120.0 / 1440.0))
print("   spec 早前写的 -40/1440      = %.8f   <-- 错" % (-40.0 / 1440.0))
print("   注意实现是用 setPolyToPoly 在 draw 阶段做，不走 graphicsLayer 的 TransformOrigin。")
