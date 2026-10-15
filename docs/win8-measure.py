#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
win8-measure.py
===============
Windows 8 / WinJS 磁贴动画 —— 参考几何重建 + 逐帧轮廓测量工具

存在两个用途，互不干扰：

A) probe 模式  : 测量任意真实截图里的磁贴轮廓几何
     每个切片的左侧高度 h_left、右侧高度 h_right、水平宽度 w，
     以及由 cos(theta) = w / w0 反解出的旋转角 theta。
   -> 如果将来拿到 Win8 实拍/录屏，直接用这个模式跑真帧。
      python win8-measure.py probe <图片或目录>

B) synth 模式（默认）: 用微软官方源码里的常量重建姿态并逐帧测量
     - TiltEffect   : 按下时绕自身中心的双轴倾斜 + Z 方向压深
     - pointerDown  : WinJS 开发者 API 的 2.5% 缩放
     - enterPage / exitPage : Win8 页面进出场
     - enterContent / exitContent
   重建帧写到 docs/win8-frames/sim/，然后**用与 probe 完全相同的代码**去测量，
   从而自校验重建是否自洽（测量值 vs 解析值）。

重要声明
--------
synth 模式产出的帧是**按官方常量重建的仿真帧，不是 Win8 实拍**。
本机网络无法取得任何 Win8 开始屏幕点击录像（详见 docs/win8-frames/README.md），
因此本文件中所有标 [实测] 的位置均标注为「未取得」，
synth 模式的数字一律标 [推算]（由文档常量推导），不得当作实测。

官方常量出处
------------
[1] TiltEffect.cs（微软官方 Control Tilt Effect Sample 源码全文转载）
    https://www.cnblogs.com/wgscd/articles/4883187.html
    MaxAngle = 0.3 (弧度), MaxDepression = 25 (像素),
    TiltReturnAnimationDelay = 200ms, TiltReturnAnimationDuration = 100ms
[2] Peter Torr (MSFT) "Updated Tilt Effect"
    https://learn.microsoft.com/en-us/archive/blogs/ptorr/updated-tilt-effect
    全局相机：TranslateTransform 到容器中心 + PlaneProjection.GlobalOffsetX/Y 抵消
[3] WinJS 4.4.3 实际发行源码 js/base.js
    https://unpkg.com/winjs@4.4.3/js/base.js
    pointerDown/pointerUp/enterPage/exitPage/enterContent/exitContent/
    addToList/deleteFromList/expand/collapse 的 delay/duration/timing/scale
[4] "Animating pointer clicks (HTML)"
    https://learn.microsoft.com/en-us/previous-versions/windows/apps/hh465242(v=win.10)
"""

import os
import sys
import math
import json

try:
    import numpy as np
    from PIL import Image, ImageDraw, ImageFont
except Exception as e:  # pragma: no cover
    print("需要 numpy 与 Pillow: " + str(e))
    raise

# ----------------------------------------------------------------------------
# 0. 路径
# ----------------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
FRAME_DIR = os.path.join(HERE, "win8-frames")
SIM_DIR = os.path.join(FRAME_DIR, "sim")
REPORT_PATH = os.path.join(FRAME_DIR, "measure-report.md")

# ----------------------------------------------------------------------------
# 1. 官方常量（逐字抄自出处，勿改）
# ----------------------------------------------------------------------------
# [1] TiltEffect.cs
MAX_ANGLE_RAD = 0.3                      # const double MaxAngle = 0.3;  单位：弧度
MAX_DEPRESSION_PX = 25.0                 # const double MaxDepression = 25;
TILT_RETURN_DELAY_MS = 200.0             # TiltReturnAnimationDelay
TILT_RETURN_DURATION_MS = 100.0          # TiltReturnAnimationDuration

# [3] WinJS 4.4.3 js/base.js —— Win8 招牌曲线
WIN8_CURVE = (0.1, 0.9, 0.2, 1.0)        # cubic-bezier(0.1, 0.9, 0.2, 1)
WIN8_CURVE_OUT = (0.11, 0.5, 0.24, 0.96)  # cubic-bezier(0.11, 0.5, 0.24, .96)

MAX_ANGLE_DEG = MAX_ANGLE_RAD * 180.0 / math.pi   # 17.1887338...deg

# WinJS 动画参数表： name -> (transform_ms, transform_timing, extra)
WINJS_ANIMS = {
    "pointerDown":   dict(dur=167,  timing=WIN8_CURVE,     to="scale(0.975, 0.975)"),
    "pointerUp":     dict(dur=167,  timing=WIN8_CURVE,     to="none"),
    "enterPage":     dict(dur=1000, timing=WIN8_CURVE,     to="none",
                          off=(0.0, 28.0), opacity_ms=170, opacity_timing=WIN8_CURVE,
                          stagger=dict(initial=0, extra=83, factor=1, cap=333)),
    "exitPage":      dict(dur=117,  timing="linear",       to="offset",
                          off=(0.0, 0.0), opacity_ms=117, opacity_timing="linear"),
    "enterContent":  dict(dur=550,  timing=WIN8_CURVE,     to="none",
                          off=(0.0, 28.0), opacity_ms=170, opacity_timing=WIN8_CURVE),
    "exitContent":   dict(dur=117,  timing="linear",       to="offset",
                          off=(0.0, 0.0), opacity_ms=117, opacity_timing="linear"),
    "expand":        dict(dur=367,  timing=WIN8_CURVE,     to="none",
                          opacity_ms=167, opacity_timing=WIN8_CURVE),
    "collapse":      dict(dur=367,  timing=WIN8_CURVE,     to="none",
                          opacity_ms=167, opacity_timing=WIN8_CURVE),
    "addToList":     dict(dur=120,  timing=WIN8_CURVE,     to="none",
                          scale_from=0.85, opacity_ms=120, opacity_timing="linear"),
    "deleteFromList":dict(dur=120,  timing=WIN8_CURVE_OUT, to="scale(0.85)",
                          scale_to=0.85, opacity_ms=120, opacity_timing="linear"),
    "reposition":    dict(dur=367,  timing=WIN8_CURVE,     to="none",
                          stagger=dict(initial=0, extra=33, factor=1, cap=250)),
    "fadeIn":        dict(dur=250,  timing="linear",       opacity_ms=250, opacity_timing="linear"),
    "fadeOut":       dict(dur=167,  timing="linear",       opacity_ms=167, opacity_timing="linear"),
    "showEdgeUI":    dict(dur=367,  timing=WIN8_CURVE,     to="none"),
    "hideEdgeUI":    dict(dur=367,  timing=WIN8_CURVE,     to="offset"),
    "showPanel":     dict(dur=550,  timing=WIN8_CURVE,     to="none"),
    "hidePanel":     dict(dur=550,  timing=WIN8_CURVE,     to="offset"),
    "showPopup":     dict(dur=367,  timing=WIN8_CURVE,     to="none",
                          opacity_ms=83, opacity_timing="linear", opacity_delay=83),
    "hidePopup":     dict(dur=0,    timing="linear",       opacity_ms=83, opacity_timing="linear"),
    "dragSourceStart": dict(dur=240, timing=WIN8_CURVE,    to="scale(1.05)",
                            opacity_ms=240, opacity_timing=WIN8_CURVE),
    "crossFade":     dict(dur=167,  timing=WIN8_CURVE,),   # 仅 opacity，见 base.js
}

# ----------------------------------------------------------------------------
# 2. cubic-bezier 求解（与浏览器/WAAPI 同一套数学）
# ----------------------------------------------------------------------------
def _bez(a, b, t):
    """三次贝塞尔单分量，P0=0, P1=a, P2=b, P3=1"""
    mt = 1.0 - t
    return 3.0 * mt * mt * t * a + 3.0 * mt * t * t * b + t * t * t


def bezier_y_for_x(x, curve, eps=1e-7, iters=80):
    """给定进度 x∈[0,1]，返回 cubic-bezier(*curve) 的输出 y∈[0,1]。"""
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    x1, y1, x2, y2 = curve
    lo, hi = 0.0, 1.0
    t = x
    for _ in range(iters):
        cx = _bez(x1, x2, t)
        if abs(cx - x) < eps:
            break
        if cx < x:
            lo = t
        else:
            hi = t
        t = 0.5 * (lo + hi)
    return _bez(y1, y2, t)


def progress_series(curve, n=11):
    """返回 [0,10,...,100]% 对应的缓动输出表。"""
    out = []
    for i in range(n):
        x = i / (n - 1.0)
        y = x if curve == "linear" else bezier_y_for_x(x, curve)
        out.append((x * 100.0, y))
    return out


# ----------------------------------------------------------------------------
# 3. 透视几何：近边/远边高度比
# ----------------------------------------------------------------------------
def tall_ratio(theta_deg, k):
    """
    绕竖直中轴旋转 theta（度）时，近边高度 / 远边高度。
    k = 相机距离 P / 磁贴宽度 W   （k 越小透视越强）

    推导：CSS 式透视 屏幕缩放 s(z) = P / (P - z)，相机在 z=+P 朝 -z 看。
    绕 Y 轴旋转 theta 后，磁贴上 x 处的深度 z(x) = -x*sin(theta)。
    故 s(x) = P / (P + x*sin(theta))。
      右边缘 x=+W/2 : s_R = P/(P + (W/2)sin) < 1（远）
      左边缘 x=-W/2 : s_L = P/(P - (W/2)sin) > 1（近）
    高度比 h_near/h_far = s_L/s_R = (k + sin(theta)/2) / (k - sin(theta)/2)
    """
    s = math.sin(math.radians(abs(theta_deg)))
    d = s / 2.0
    if k <= d:
        return float("inf")
    return (k + d) / (k - d)


def k_for_ratio(theta_deg, ratio):
    """反解：要达到指定的近远比，需要多大的 k = P/W。"""
    s = math.sin(math.radians(abs(theta_deg))) / 2.0
    # (k+s)/(k-s) = r  ->  k = s*(r+1)/(r-1)
    if ratio <= 1.0:
        return float("inf")
    return s * (ratio + 1.0) / (ratio - 1.0)


def width_ratio(theta_deg, k, W):
    """
    绕竖直中轴旋转后，投影轮廓的水平宽度 / 原宽度。
    两个边缘点的屏幕 X：X(x) = x*cos(theta) * P/(P + x*sin(theta))
    """
    th = math.radians(theta_deg)
    P = k * W

    def px(x):
        z = -x * math.sin(th)
        return x * math.cos(th) * (P / (P - z))

    return (px(W / 2.0) - px(-W / 2.0)) / W


# ----------------------------------------------------------------------------
# 4. 姿态 -> 四角投影
# ----------------------------------------------------------------------------
def tilt_pose(nx, ny):
    """
    TiltEffect 的姿态计算，逐字对应官方源码：
        double xMagnitude = Math.Abs(normalizedPoint.X - 0.5);
        double yMagnitude = Math.Abs(normalizedPoint.Y - 0.5);
        double angleMagnitude = xMagnitude + yMagnitude;
        double xAngleContribution = xMagnitude / (xMagnitude + yMagnitude);
        projection.RotationY = angle * xAngleContribution * xDirection;
        projection.RotationX = angle * (1 - xAngleContribution) * yDirection;
        projection.GlobalOffsetZ = -(1 - angleMagnitude) * MaxDepression;
    其中 angle = angleMagnitude * MaxAngle * 180 / PI，单位「度」。

    nx, ny : 点击点在磁贴内的归一化坐标 ∈ [0,1]
    返回 dict(rotX, rotY, depression) —— rotX/rotY 单位为度
    """
    xm = abs(nx - 0.5)
    ym = abs(ny - 0.5)
    mag = xm + ym                                  # ∈ [0, 1]，四角=1，边中点=0.5，正中=0
    if mag == 0.0:
        # 正中：不旋转，只后退满额
        return dict(rotX=0.0, rotY=0.0, depression=MAX_DEPRESSION_PX,
                    magnitude=0.0, angle_deg=0.0)
    xdir = -1.0 if (nx - 0.5) > 0 else 1.0
    ydir = 1.0 if (ny - 0.5) > 0 else -1.0
    contrib = xm / mag
    angle_deg = mag * MAX_ANGLE_DEG
    rotY = angle_deg * contrib * xdir
    rotX = angle_deg * (1.0 - contrib) * ydir
    dep = -(1.0 - mag) * MAX_DEPRESSION_PX         # 源码是负数
    return dict(rotX=rotX, rotY=rotY, depression=-dep,
                magnitude=mag, angle_deg=angle_deg)


def project_quad(W, H, k, rotX_deg=0.0, rotY_deg=0.0, depression=0.0):
    """
    把磁贴四角投影到屏幕。
    坐标：磁贴中心在原点，x 向右，y 向下，z 指向观察者。相机在 z=+P 朝 -z 看。
    变换顺序：先绕 Y 轴（竖直中轴）旋转 rotY，再绕 X 轴（水平中轴）旋转 rotX，
                 再沿 z 平移 -depression（压深 = 远离观察者）。
    屏幕投影：s(z) = P/(P - z)  =>  X = x*s, Y = y*s
    返回 [(x,y) x4] 顺序 左上 右上 右下 左下
    """
    P = k * W
    th = math.radians(rotY_deg)
    ph = math.radians(rotX_deg)
    ct, st = math.cos(th), math.sin(th)
    cp_, sp = math.cos(ph), math.sin(ph)

    corners = [(-W / 2.0, -H / 2.0), (W / 2.0, -H / 2.0),
               (W / 2.0, H / 2.0), (-W / 2.0, H / 2.0)]
    out = []
    for (x, y) in corners:
        # 绕 Y 轴
        x1 = x * ct
        y1 = y
        z1 = -x * st
        # 绕 X 轴
        y2 = y1 * cp_ - z1 * sp
        z2 = y1 * sp + z1 * cp_
        # 压深
        z3 = z2 - depression
        s = P / (P - z3)
        out.append((x1 * s, y2 * s))
    return out


# ----------------------------------------------------------------------------
# 5. 渲染一帧
# ----------------------------------------------------------------------------
BG = (18, 18, 20)
TILE = (0, 120, 215)          # Windows 8 强调色 #0078D7
TILE_EDGE = (255, 255, 255)
TILE_BACK = (60, 60, 66)


def render_frame(path, W, H, quad, label="", sub="", back_alpha=1.0, fill=TILE):
    """把投影后的四边形画成一张 PNG。背景纯色，磁贴实心，便于轮廓分割。"""
    pad = 140
    xs = [p[0] for p in quad]
    ys = [p[1] for p in quad]
    cw = int(max(240, (max(xs) - min(xs)) + 2 * pad))
    ch = int(max(240, (max(ys) - min(ys)) + 2 * pad + 60))
    cx = cw / 2.0 - (max(xs) + min(xs)) / 2.0
    cy = ch / 2.0 - 30 - (max(ys) + min(ys)) / 2.0
    img = Image.new("RGB", (cw, ch), BG)
    d = ImageDraw.Draw(img)
    pts = [(p[0] + cx, p[1] + cy) for p in quad]
    if back_alpha >= 1.0:
        d.polygon(pts, fill=fill, outline=TILE_EDGE)
    else:
        # 简单模拟半透明：与背景按 alpha 混合
        col = tuple(int(round(fill[i] * back_alpha + BG[i] * (1 - back_alpha))) for i in range(3))
        d.polygon(pts, fill=col, outline=TILE_EDGE)
    # 画中轴线，方便肉眼确认旋转方向
    mid_l = ((pts[0][0] + pts[3][0]) / 2.0, (pts[0][1] + pts[3][1]) / 2.0)
    mid_r = ((pts[1][0] + pts[2][0]) / 2.0, (pts[1][1] + pts[2][1]) / 2.0)
    d.line([mid_l, mid_r], fill=(255, 200, 0), width=2)
    try:
        f = ImageFont.load_default()
    except Exception:
        f = None
    if label:
        d.text((10, 8), label, fill=(240, 240, 240), font=f)
    if sub:
        d.text((10, ch - 20), sub, fill=(180, 180, 180), font=f)
    img.save(path)
    return path


# ----------------------------------------------------------------------------
# 6. 轮廓测量 —— probe 与 synth 共用同一套代码
# ----------------------------------------------------------------------------
def measure_silhouette(img_or_path, thresh=40, band=0.10):
    """
    输入一张图（路径或 PIL.Image），假设是「亮磁贴 / 暗背景」。
    输出磁贴轮廓的几何量：
        h_left   左侧 ~band 宽度带内的竖直高度（近边候选）
        h_right  右侧 ~band 宽度带内的竖直高度（远边候选）
        w        水平总宽
        h_max    全轮廓最大竖直高度
        ratio    h_left / h_right （>1 表示左边更近/更大）
        bbox     (x0,y0,x1,y1)
    """
    im = Image.open(img_or_path).convert("L") if isinstance(img_or_path, str) else img_or_path.convert("L")
    a = np.asarray(im).astype(np.float32)
    bgv = float(np.median(a))
    mask = np.abs(a - bgv) > thresh
    # 去掉顶部/底部的文字标注行：只保留最大的连通行区间
    rows = np.where(mask.any(axis=1))[0]
    if rows.size == 0:
        return None
    # 找行方向最长的连续段（磁贴本体），文字行会被过滤掉
    best = None
    start = rows[0]
    prev = rows[0]
    for r in list(rows[1:]) + [rows[-1] + 2]:
        if r != prev + 1:
            seg = (start, prev)
            if best is None or (seg[1] - seg[0]) > (best[1] - best[0]):
                best = seg
            start = r
        prev = r
    y0, y1 = best
    sub = mask[y0:y1 + 1, :]
    cols = np.where(sub.any(axis=0))[0]
    if cols.size == 0:
        return None
    x0, x1 = int(cols[0]), int(cols[-1])
    w = x1 - x0 + 1
    # 每列的竖直高度
    heights = np.zeros(w, dtype=np.float32)
    for i, x in enumerate(range(x0, x1 + 1)):
        rr = np.where(sub[:, x])[0]
        heights[i] = (rr[-1] - rr[0] + 1) if rr.size else 0.0
    nb = max(1, int(round(w * band)))
    h_left = float(np.median(heights[:nb]))
    h_right = float(np.median(heights[-nb:]))
    ratio = (h_left / h_right) if h_right > 0 else float("inf")
    return dict(h_left=h_left, h_right=h_right, w=float(w),
                h_max=float(heights.max()), ratio=ratio,
                bbox=(x0, int(y0), x1, int(y1)),
                h_profile=[float(v) for v in heights])


# ----------------------------------------------------------------------------
# 7. synth：重建姿态序列并逐帧测量
# ----------------------------------------------------------------------------
def synth_tilt_frames():
    """
    重建 TiltEffect 的三种典型点击位置：正中 / 边中点 / 四角。
    返回测量结果列表。
    """
    os.makedirs(SIM_DIR, exist_ok=True)
    W = H = 200.0
    k = 2.0                      # 相机距离 = 2 倍磁贴宽（见 spec 的透视章节）
    cases = [
        ("center", 0.50, 0.50, "点击正中"),
        ("edge-mid-x", 1.00, 0.50, "点击右边缘中点"),
        ("corner-br", 1.00, 1.00, "点击右下角"),
        ("corner-tl", 0.00, 0.00, "点击左上角"),
    ]
    res = []
    for name, nx, ny, _desc in cases:
        p = tilt_pose(nx, ny)
        quad = project_quad(W, H, k, p["rotX"], p["rotY"], p["depression"])
        sub = ("rotX=%.3f rotY=%.3f  depression=%.1fpx  angle=%.3fdeg  mag=%.2f"
               % (p["rotX"], p["rotY"], p["depression"], p["angle_deg"], p["magnitude"]))
        fp = os.path.join(SIM_DIR, "tilt-%s.png" % name)
        render_frame(fp, W, H, quad, label="Win8 TiltEffect [重建] %s" % name, sub=sub)
        m = measure_silhouette(fp)
        # 哪一边是「近边」由 RotationY 的符号决定：
        #   RotationY > 0 -> 左边缘 z 增大（靠近观察者）-> 左近右远
        #   RotationY < 0 -> 右边缘靠近                    -> 右近左远
        #   RotationY = 0 -> 无旋转，比值为 1
        if abs(p["rotY"]) < 1e-9:
            near_side = "none"
        else:
            near_side = "left" if p["rotY"] > 0 else "right"
        h_near = m["h_left"] if near_side == "left" else m["h_right"]
        h_far = m["h_right"] if near_side == "left" else m["h_left"]
        m.update(case=name, click=(nx, ny), pose=p, file=os.path.basename(fp),
                 near_side=near_side,
                 h_near=h_near, h_far=h_far,
                 measured_near_far=(h_near / h_far) if h_far > 0 else float("inf"),
                 analytic_ratio=tall_ratio(p["rotY"], k) if abs(p["rotY"]) > 1e-9 else 1.0)
        res.append(m)
    return res


def synth_timeline_frames():
    """
    重建 enterPage（1000ms 位移 + 170ms 淡入）与 exitPage（117ms 线性淡出）。
    这是 Win8 里语义上最接近「进出」的一对动画。
    """
    os.makedirs(SIM_DIR, exist_ok=True)
    W = H = 200.0
    k = 3.0
    out = []

    # --- enterPage：transform 1000ms cubic-bezier(0.1,0.9,0.2,1)，from translate(0,28px)
    for i in range(6):
        t = i / 5.0
        e = bezier_y_for_x(t, WIN8_CURVE)
        dy = 28.0 * (1.0 - e)
        op = bezier_y_for_x(min(1.0, t * (1000.0 / 170.0)), WIN8_CURVE) if t * (1000.0 / 170.0) < 1.0 else 1.0
        quad = project_quad(W, H, k, 0, 0, 0)
        quad = [(x, y + dy) for (x, y) in quad]
        fp = os.path.join(SIM_DIR, "enterPage-%02dms.png" % int(t * 1000))
        render_frame(fp, W, H, quad, label="[重建] enterPage t=%dms" % int(t * 1000),
                     sub="ease=%.3f  translateY=%.2fpx  opacity=%.3f" % (e, dy, op),
                     back_alpha=op, fill=TILE)
        out.append(dict(case="enterPage", t_ms=int(t * 1000), ease=e, offset_y=dy, opacity=op,
                        file=os.path.basename(fp)))

    # --- exitPage：117ms linear, to offset(默认 0,0) + opacity->0
    for i in range(6):
        t = i / 5.0
        op = 1.0 - t
        quad = project_quad(W, H, k, 0, 0, 0)
        fp = os.path.join(SIM_DIR, "exitPage-%03dms.png" % int(t * 117))
        render_frame(fp, W, H, quad, label="[重建] exitPage t=%dms" % int(t * 117),
                     sub="linear  opacity=%.3f" % op, back_alpha=max(op, 0.02), fill=TILE)
        out.append(dict(case="exitPage", t_ms=int(t * 117), ease=t, offset_y=0.0, opacity=op,
                        file=os.path.basename(fp)))
    return out


# ----------------------------------------------------------------------------
# 8. probe 模式
# ----------------------------------------------------------------------------
def probe(paths):
    rows = []
    for p in paths:
        m = measure_silhouette(p)
        if m is None:
            print("  !! 无法分割轮廓: %s" % p)
            continue
        # 由宽度反解 cos(theta)：需要知道未旋转时的宽度 w0
        rows.append(dict(file=os.path.basename(p), **{k: v for k, v in m.items() if k != "h_profile"}))
    print("%-34s %9s %9s %8s %8s %8s" % ("file", "h_left", "h_right", "w", "ratio", "h_max"))
    for r in rows:
        print("%-34s %9.2f %9.2f %8.2f %8.4f %8.2f"
              % (r["file"], r["h_left"], r["h_right"], r["w"], r["ratio"], r["h_max"]))
    return rows


# ----------------------------------------------------------------------------
# 9. 报告
# ----------------------------------------------------------------------------
def build_report():
    os.makedirs(FRAME_DIR, exist_ok=True)
    os.makedirs(SIM_DIR, exist_ok=True)

    L = []
    A = L.append
    A("# Win8 磁贴动画 —— 逐帧几何测量报告")
    A("")
    A("由 `docs/win8-measure.py` 自动生成。**本报告的每一项都标明证据等级。**")
    A("")
    A("| 标记 | 含义 |")
    A("|---|---|")
    A("| `[实测]` | 作者从真实 Win8 录像帧中量得 |")
    A("| `[推算]` | 由下方引用的官方常量数学推导得出 |")
    A("| `[文档]` | 微软官方文档/官方发行源码逐字引用 |")
    A("")
    A("> **`[实测]` 条目数 = 0。** 本机网络无法取得任何 Windows 8 开始屏幕点击录像，")
    A("> 抓帧来源与失败记录见 `docs/win8-frames/README.md`。")
    A("> `docs/win8-frames/sim/` 内的 PNG 是**按官方常量重建的仿真帧**，不是实拍。")
    A("")

    # ---- 1. 缓动采样表
    A("## 1. 缓动曲线采样表 `[推算]`（曲线本身 `[文档]`）")
    A("")
    A("曲线值逐字取自 WinJS 4.4.3 `js/base.js`：`cubic-bezier(0.1, 0.9, 0.2, 1)`。")
    A("下表是用与浏览器相同的三次贝塞尔求解算出的输出进度。")
    A("")
    A("| 时间进度 | `cubic-bezier(0.1,0.9,0.2,1)` 输出 | 差值（相对线性） |")
    A("|---|---|---|")
    for x, y in progress_series(WIN8_CURVE):
        A("| %.0f%% | %.4f | %+.4f |" % (x, y, y - x / 100.0))
    A("")
    A("对照的退场曲线 `cubic-bezier(0.11, 0.5, 0.24, 0.96)`（deleteFromList 专用）：")
    A("")
    A("| 时间进度 | 输出 |")
    A("|---|---|")
    for x, y in progress_series(WIN8_CURVE_OUT):
        A("| %.0f%% | %.4f |" % (x, y))
    A("")

    # ---- 2. TiltEffect 角度表
    A("## 2. TiltEffect 角度 / 压深表 `[推算]`（常量 `[文档]`）")
    A("")
    A("`MaxAngle = 0.3` 弧度 = **%.4f°**；`MaxDepression = 25` px。" % MAX_ANGLE_DEG)
    A("")
    A("```")
    A("angleMagnitude = |nx-0.5| + |ny-0.5|                 // 0..1")
    A("angle_deg      = angleMagnitude * 17.1887338")
    A("xAngleContrib  = |nx-0.5| / angleMagnitude")
    A("RotationY      = angle_deg * xAngleContrib * (-sign(nx-0.5))")
    A("RotationX      = angle_deg * (1 - xAngleContrib) * (+sign(ny-0.5))")
    A("GlobalOffsetZ  = -(1 - angleMagnitude) * 25")
    A("```")
    A("")
    A("| 点击位置 (nx, ny) | angleMagnitude | 总转角 deg | RotationY deg | RotationX deg | 压深 px |")
    A("|---|---|---|---|---|---|")
    for nx, ny, tag in [(0.5, 0.5, "正中"), (1.0, 0.5, "右边缘中点"), (0.5, 1.0, "下边缘中点"),
                        (0.0, 0.0, "左上角"), (1.0, 1.0, "右下角"), (0.25, 0.75, "四分点")]:
        p = tilt_pose(nx, ny)
        A("| (%.2f, %.2f) %s | %.3f | %.4f | %+.4f | %+.4f | %.2f |"
          % (nx, ny, tag, p["magnitude"], p["angle_deg"], p["rotY"], p["rotX"], p["depression"]))
    A("")
    A("**关键结论：这个动画没有单一转轴，也没有固定总转角。**")
    A("转轴由点击位置决定（水平偏移分给 RotationY，垂直偏移分给 RotationX），")
    A("总转角在 0° ~ 17.1887° 之间连续变化；点正中时**完全不旋转**。")
    A("")

    # ---- 3. 透视表
    A("## 3. 透视强度表 `[推算]`")
    A("")
    A("CSS 式透视下，绕竖直中轴旋转 θ 时：")
    A("")
    A("```")
    A("h_near / h_far = (k + sin(θ)/2) / (k - sin(θ)/2)     其中 k = 相机距离 P / 磁贴宽度 W")
    A("```")
    A("")
    ks = [0.5, 0.655, 1.0, 1.5, 2.0, 2.73, 3.0, 4.0, 5.0, 8.0]
    thetas = [4.0, 8.594, 12.0, MAX_ANGLE_DEG]
    A("| k = P/W | " + " | ".join("θ=%.3f°" % t for t in thetas) + " |")
    A("|" + "---|" * (len(thetas) + 1))
    for k in ks:
        A("| %.3f | " % k + " | ".join("%.4f" % tall_ratio(t, k) for t in thetas) + " |")
    A("")
    A("反解：要达到某个近/远比 r，需要 `k = sin(θ)/2 * (r+1)/(r-1)`。")
    A("")
    A("| 目标近/远比 | 需要在 θ=17.1887° 时的 k | 相当于 CSS perspective（磁贴宽 200px 时）|")
    A("|---|---|---|")
    for r in [1.05, 1.10, 1.20, 1.35, 1.582, 2.0, 3.0]:
        kk = k_for_ratio(MAX_ANGLE_DEG, r)
        A("| %.3f | %s | %s |" % (r, ("%.4f" % kk) if kk != float("inf") else "∞（不可能，θ 太小）",
                                 ("%.0f px" % (kk * 200)) if kk != float("inf") else "—"))
    A("")
    A("**这张表直接回答了「透视强度」：**")
    A("")
    A("- θ 最大只有 17.1887°，即使相机贴到 k=1（相机距离 = 1 倍磁贴宽），近/远比也只有 **%.4f**。"
      % tall_ratio(MAX_ANGLE_DEG, 1.0))
    A("- 常见的 CSS `perspective` 落在 k≈3~8，对应近/远比 **%.4f ~ %.4f**，肉眼几乎看不出透视。"
      % (tall_ratio(MAX_ANGLE_DEG, 3.0), tall_ratio(MAX_ANGLE_DEG, 8.0)))
    A("- 参照：本项目现有实现 `docs/flip-baseline.md` 的交接点近/远比是 **1.582**，")
    A("  反解出等效 k = **%.4f**，即相机距离只有 **%.0f px**（按 200px 磁贴宽）——"
      % (k_for_ratio(MAX_ANGLE_DEG, 1.582), k_for_ratio(MAX_ANGLE_DEG, 1.582) * 200))
    A("  这比 Win8 在最大倾角下的透视**还要强得多**。")
    A("")

    # ---- 4. 旋转时的宽度收缩
    A("## 4. 旋转引起的宽度收缩 `[推算]`")
    A("")
    A("| θ deg | 轮廓宽度 / 原宽 (k=2) | cos θ |")
    A("|---|---|---|")
    for t in [0.0, 4.0, 8.594, 12.0, MAX_ANGLE_DEG]:
        A("| %.3f | %.5f | %.5f |" % (t, width_ratio(t, 2.0, 200.0), math.cos(math.radians(t))))
    A("")
    A("区别很小（cos 与透视项互相抵消一部分），所以**从轮廓宽度反解转角是可行的**，")
    A("但精度依赖对 k 的假定；`probe` 模式因此同时输出 `h_left`/`h_right`/`w` 三个原始量，")
    A("让使用者自己选反解路径。")
    A("")

    # ---- 5. synth 测量
    A("## 5. 重建帧的逐帧测量 `[推算]`")
    A("")
    A("重建参数：磁贴 200×200 px，k = %.2f。测量代码与 `probe` 模式完全同一份。" % 2.0)
    A("")
    tilt = synth_tilt_frames()
    A("| 用例 | 点击 (nx,ny) | 总转角 deg | RotationY | 压深 px | h_left | h_right | w | 近边 | 量得 近/远 | 解析 近/远 |")
    A("|---|---|---|---|---|---|---|---|---|---|---|")
    for m in tilt:
        A("| %s | (%.2f, %.2f) | %.4f | %+.4f | %.2f | %.1f | %.1f | %.1f | %s | **%.4f** | %.4f |"
          % (m["case"], m["click"][0], m["click"][1], m["pose"]["angle_deg"], m["pose"]["rotY"],
             m["pose"]["depression"], m["h_left"], m["h_right"], m["w"],
             m["near_side"], m["measured_near_far"], m["analytic_ratio"]))
    A("")
    A("注意 `h_left / h_right` 只有在 RotationY > 0（左边缘靠近）时才等于近/远比；")
    A("RotationY < 0 时它是**倒数**。所以表中同时给出原始左右高度和归一化后的近/远比。")
    A("")
    tl = synth_timeline_frames()
    A("| 动画 | t ms | 缓动输出 | 位移 Y px | 不透明度 |")
    A("|---|---|---|---|---|")
    for r in tl:
        A("| %s | %d | %.4f | %.2f | %.3f |" % (r["case"], r["t_ms"], r["ease"], r["offset_y"], r["opacity"]))
    A("")
    A("重建帧位于 `docs/win8-frames/sim/`。")
    A("")
    A("---")
    A("")
    A("生成时间戳无关；重跑 `python docs/win8-measure.py` 可复现全部数字。")

    txt = "\n".join(L)
    with open(REPORT_PATH, "w", encoding="utf-8") as f:
        f.write(txt)
    return REPORT_PATH


# ----------------------------------------------------------------------------
# 10. main
# ----------------------------------------------------------------------------
def main(argv):
    if len(argv) > 1 and argv[1] == "probe":
        args = argv[2:]
        if not args:
            print("用法: python win8-measure.py probe <图片或目录> [...]")
            return 2
        paths = []
        for a in args:
            if os.path.isdir(a):
                for n in sorted(os.listdir(a)):
                    if n.lower().endswith((".png", ".jpg", ".jpeg", ".bmp", ".webp")):
                        paths.append(os.path.join(a, n))
            else:
                paths.append(a)
        print("probe 模式：测量 %d 个文件" % len(paths))
        probe(paths)
        return 0

    print("synth 模式：用官方常量重建并测量")
    print("  MaxAngle = 0.3 rad = %.4f deg   MaxDepression = 25 px" % MAX_ANGLE_DEG)
    print("  Win8 curve = cubic-bezier(0.1, 0.9, 0.2, 1)")
    rp = build_report()
    print("  报告 -> %s" % rp)
    print("  帧   -> %s" % SIM_DIR)

    # 自校验：解析值 vs 测量值
    print("\n自校验（测量值 vs 解析值）：")
    ok = True
    for m in synth_tilt_frames():
        a = m["analytic_ratio"]
        got = m["measured_near_far"]
        dev = abs(got - a) / a if a > 0 else 0.0
        flag = "OK " if dev < 0.02 else "!! "
        if dev >= 0.02:
            ok = False
        print("  %s%-12s near=%-5s 量得 %.4f  解析 %.4f  偏差 %.2f%%"
              % (flag, m["case"], m["near_side"], got, a, dev * 100))
    print("  自校验%s" % ("通过" if ok else "存在偏差 —— 检查投影约定"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
