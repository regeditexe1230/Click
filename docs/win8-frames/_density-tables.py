# -*- coding: utf-8 -*-
"""按 density 3.5 的真值重算 spec 里所有受影响的表（T5 / T6 / T7）。

口径（本机真值）：
    屏 1440 x 3200 px, density 3.5  =>  W = 411.4286 dp
    TURNSTILE_ORIGIN_X = (-40).dp   =>  axis  = -140 px  (px 空间)  /  -40 dp
    TURNSTILE_PERSPECTIVE_IN  = 600.dp => P = 2100 px              /  600 dp
    TURNSTILE_PERSPECTIVE_OUT = 800.dp => P = 2800 px              /  800 dp

    scale(x, th) = P / (P + (x - axis) * sin th)
    左右边高比   = scale(0) / scale(W)      # 左边缘近 => > 1

缓动（与 ProgramScreen.kt L1289-1292 逐字相同）：
    IN  = CubicBezierEasing(0.01, 0.975, 0.4775, 0.9775)   # ForwardIn:  theta = 80 * (1 - IN(q))
    OUT = CubicBezierEasing(0.4925, 0.01, 0.7675, -0.01)   # ForwardOut: theta = -50 * OUT(q)

输出 -> docs/win8-frames/density-tables.md ；控制台只印 ASCII。
"""
import math
import pathlib

W = 1440.0
AXIS = -140.0          # = -40 dp * 3.5
P_IN = 2100.0          # = 600 dp * 3.5
P_OUT = 2800.0         # = 800 dp * 3.5
AXIS_REF = -40.0       # 参考机 480dp（px == dp）
W_REF = 480.0


# ---------- cubic-bezier ----------
def _bezier_x(t, x1, x2):
    return 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3


def _bezier_y(t, y1, y2):
    return 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t * t * y2 + t ** 3


def _bezier_dx(t, x1, x2):
    return (3 * (1 - t) ** 2 * x1
            + 6 * (1 - t) * t * (x2 - x1)
            + 3 * t * t * (1 - x2))


def bezier(q, x1, y1, x2, y2):
    """给定时间进度 q∈[0,1]，返回缓动后的进度。牛顿 + 二分兜底。"""
    q = min(max(q, 0.0), 1.0)
    t = q
    for _ in range(12):
        err = _bezier_x(t, x1, x2) - q
        if abs(err) < 1e-12:
            break
        d = _bezier_dx(t, x1, x2)
        if abs(d) < 1e-9:
            break
        t -= err / d
    t = min(max(t, 0.0), 1.0)
    lo, hi = 0.0, 1.0
    for _ in range(60):
        if _bezier_x(t, x1, x2) < q:
            lo = t
        else:
            hi = t
        t = (lo + hi) / 2
    return _bezier_y(t, y1, y2)


IN = (0.01, 0.975, 0.4775, 0.9775)
OUT = (0.4925, 0.01, 0.7675, -0.01)


# ---------- 几何 ----------
def scales(theta_deg, w=W, axis=AXIS, P=P_IN):
    s = math.sin(math.radians(theta_deg))
    sl = P / (P + (0.0 - axis) * s)
    sr = P / (P + (w - axis) * s)
    return sl, sr, sl / sr


def theta_in(ms):
    return 80.0 * (1.0 - bezier(ms / 300.0, *IN))


def theta_out(ms):
    return -50.0 * bezier(ms / 128.0, *OUT)


o = []
o.append("# density 3.5 真值重算表（生成自 `docs/win8-frames/_density-tables.py`）")
o.append("")
o.append("W = 1440 px, axis = **−140 px** (= −40 dp), P_in = **2100 px** (= 600 dp), P_out = **2800 px** (= 800 dp)")
o.append("")

# ---- T5 进场 ----
o.append("## T5-a 进场 `ForwardIn`（80°→0，300 ms）—— 本机口径")
o.append("")
o.append("| t | ms | θ | 左边缘缩放 | 右边缘缩放 | 近/远比 |")
o.append("|---|---|---|---|---|---|")
for pct, ms in ((0, 0), (10, 30), (20, 60), (30, 90), (50, 150), (70, 210), (100, 300)):
    th = theta_in(ms)
    sl, sr, r = scales(th)
    o.append("| %d%% | %d | **%+.2f°** | %.4f | %.4f | **%.4f** |"
             % (pct, ms, th, sl, sr, r))
o.append("")

# ---- T5 退场 ----
o.append("## T5-b 退场 `ForwardOut`（0→−50，128 ms）—— 本机口径")
o.append("")
o.append("| t | ms | θ | 左边缘缩放 | 右边缘缩放 | 近/远比 |")
o.append("|---|---|---|---|---|---|")
for pct, ms in ((0, 0), (50, 64), (70, 90), (90, 115), (100, 128)):
    th = theta_out(ms)
    sl, sr, r = scales(th)
    o.append("| %d%% | %d | **%+.2f°** | %.4f | %.4f | **%.4f** |"
             % (pct, ms, th, sl, sr, r))
o.append("")

# ---- T7 30fps ----
o.append("## T7 30 fps 逐帧（进场，本机口径）")
o.append("")
o.append("| 帧 | ms | θ | 左边缘缩放 | 右边缘缩放 | **近/远比** | > 1.5 ? |")
o.append("|---|---|---|---|---|---|---|")
for f, ms in ((1, 0.0), (2, 33.3), (3, 66.7), (4, 100.0), (5, 133.3),
              (6, 166.7), (9, 266.7), (10, 300.0)):
    th = theta_in(ms)
    sl, sr, r = scales(th)
    o.append("| %d | %.1f | %+.2f° | %.4f | %.4f | **%.4f** | %s |"
             % (f, ms, th, sl, sr, r, "**✅ 是**" if r > 1.5 else "—"))
o.append("")

# ---- T6 不退化 ----
o.append("## T6 不退化余量（d_right = 1440 + 140 = 1580 px）")
o.append("")
d_right = W - AXIS
for name, th, P in (("节点图退场 BackwardOut", 80.0, P_OUT),
                    ("列表退场 ForwardOut", 50.0, P_IN)):
    margin = P - d_right * math.sin(math.radians(th))
    o.append("- **%s**（θ=%.0f°, P=%.0f px）: `%.0f − %.0f×%.5f = %+.1f px` %s"
             % (name, th, P, P, d_right, math.sin(math.radians(th)), margin,
                "✅ 安全" if margin > 0 else "❌ 炸"))
o.append("- 不退化上限：`屏宽 < P/|sinθ| − 40dp`；BackwardOut 时 `2800/0.98481 − 140 = 2703 px`；"
         "ForwardOut 时 `2100/0.76604 − 140 = 2601 px` ⇒ 本机 1440 px 余量充足")
o.append("")

# ---- 口径对比 ----
o.append("## 口径对比（同一套官方常量，两个视口宽）")
o.append("")
o.append("| 口径 | W | axis | P | θ=80° 左 | θ=80° 右 | **θ=80° 比** |")
o.append("|---|---|---|---|---|---|---|")
sl, sr, r = scales(80.0, w=480.0, axis=-40.0, P=600.0)
o.append("| **参考机（WP8 WVGA）** | 480 dp | −40 dp | 600 dp | %.4f | %.4f | **%.4f** |" % (sl, sr, r))
sl, sr, r = scales(80.0)
o.append("| **本机（density 3.5）** | 411.4286 dp | −40 dp | 600 dp | %.4f | %.4f | **%.4f** |" % (sl, sr, r))
sl, sr, r = scales(80.0, w=1440.0, axis=-120.0, P=1800.0)
o.append("| ~~旧错记（density 3.0）~~ | 480 dp | −40 dp | 600 dp | %.4f | %.4f | **%.4f** |" % (sl, sr, r))
o.append("")
o.append("P/W：参考机 %.4f，本机 %.4f ；|axis|/W：参考机 %.2f%%，本机 %.2f%%"
         % (600.0 / 480.0, 600.0 / (1440.0 / 3.5),
            40.0 / 480.0 * 100, 40.0 / (1440.0 / 3.5) * 100))

pathlib.Path("docs/win8-frames/density-tables.md").write_text("\n".join(o), encoding="utf-8")

print("W = %.4f dp   axis = %.1f px   P_in = %.1f px   P_out = %.1f px"
      % (1440.0 / 3.5, AXIS, P_IN, P_OUT))
print("")
print("T5 in   t=0%%: theta=%+.2f  sl=%.4f sr=%.4f ratio=%.4f" % ((theta_in(0),) + scales(theta_in(0))))
for pct, ms in ((10, 30), (30, 90), (50, 150), (100, 300)):
    th = theta_in(ms)
    sl, sr, r = scales(th)
    print("T5 in   t=%3d%%: theta=%+.2f  sl=%.4f sr=%.4f ratio=%.4f" % (pct, th, sl, sr, r))
print("")
for pct, ms in ((50, 64), (100, 128)):
    th = theta_out(ms)
    sl, sr, r = scales(th)
    print("T5 out  t=%3d%%: theta=%+.2f  sl=%.4f sr=%.4f ratio=%.4f" % (pct, th, sl, sr, r))
print("")
for f, ms in ((1, 0.0), (2, 33.3), (3, 66.7), (4, 100.0)):
    th = theta_in(ms)
    sl, sr, r = scales(th)
    print("T7  fr=%2d  : theta=%+.2f  sl=%.4f sr=%.4f ratio=%.4f" % (f, th, sl, sr, r))
print("")
d_right = W - AXIS
for name, th, P in (("BackwardOut", 80.0, P_OUT), ("ForwardOut", 50.0, P_IN)):
    print("T6  %-12s margin = %+.1f px" % (name, P - d_right * math.sin(math.radians(th))))
print("")
print("full -> docs/win8-frames/density-tables.md")
