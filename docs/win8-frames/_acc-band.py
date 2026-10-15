# -*- coding: utf-8 -*-
"""按亮度阈值量「亮卡片带」的左右边缘高度 => 近似透视比。

为什么换阈值：app 是深色主题（整帧灰度只有 18..93），页面底色和背景几乎同亮度，
按「与背景色差」分不出旋转中的页面；但卡片内容明显更亮，所以用亮度阈值能抓住那条带。
不是原计划的整页剪影，属于降级指标 —— 报告里必须写明它的局限。

用法: python docs\\win8-frames\\_acc-band.py <capture_dir>
"""
import os
import sys
import glob
import numpy as np
from PIL import Image


def band_ratio(path):
    g = np.asarray(Image.open(path).convert("L")).astype(np.float32)
    hi = float(g.max())
    lo = float(g.min())
    thr = lo + 0.72 * (hi - lo)
    m = g > thr
    if m.sum() < 500:
        return None
    coln = m.sum(axis=0)
    cols = np.where(coln >= 4)[0]
    if cols.size < 20:
        return None
    x0, x1 = int(cols[0]), int(cols[-1])
    span = x1 - x0
    xl = x0 + int(span * 0.05)
    xr = x0 + int(span * 0.95)

    def h(x):
        r = np.where(m[:, x])[0]
        return int(r[-1] - r[0] + 1) if r.size >= 3 else None

    hl, hr = h(xl), h(xr)
    if not hl or not hr:
        return None
    return dict(x0=x0, x1=x1, hl=hl, hr=hr, ratio=hl / float(hr), npx=int(m.sum()))


def run(cap):
    files = sorted(glob.glob(os.path.join(cap, "frames", "f_*.png")))
    print("  %s  (%d frames)" % (cap, len(files)))
    print("  %-8s %-16s %-7s %-7s %-9s" % ("frame", "band x-range", "h_left", "h_right", "L/R"))
    best = None
    for f in files:
        r = band_ratio(f)
        n = os.path.basename(f)
        if r is None:
            print("  %-8s %s" % (n, "(no bright band)"))
            continue
        print("  %-8s %-16s %-7d %-7d %-9.4f"
              % (n, "%d..%d" % (r["x0"], r["x1"]), r["hl"], r["hr"], r["ratio"]))
        if r["x1"] - r["x0"] > 200:
            if best is None or abs(r["ratio"] - 1) > abs(best[1] - 1):
                best = (n, r["ratio"], r["x0"], r["x1"])
    if best:
        print("  ==> 最偏离 1.0 的宽带: %s ratio=%.4f  x=%d..%d" % best)
    print("")


if __name__ == "__main__":
    for t in (sys.argv[1:] or ["tools/captures/tt_enter", "tools/captures/tt_exit"]):
        run(t)
