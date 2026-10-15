# -*- coding: utf-8 -*-
"""
独立验收测量：直接从 tt_enter / tt_exit 的原始 PNG 帧里量「页面剪影」的左右边缘高度比。

为什么不用 tools/captures/*/pages.csv：
  那份 csv 是队友的测量器产出的，它在 tt_enter 里 40 帧中有 34 帧 n_blobs=0（整页丢失），
  在 tt_exit 里最大偏差只有 4.2%，且唯一拟合出的 theta 只有 0.35°。
  作为独立验收方，我直接量原始像素。

方法（不依赖颜色语义，只用「与背景不同」）：
  1. 背景色 = 四角取样中出现最多的那个颜色。
  2. 对每一列 x，找最上 / 最下的「非背景」像素行 => 该列剪影高度 h(x)。
  3. 取 x = 左 5% / 右 95%（剪影自身的 x 范围）两处高度，算比值 left/right。
  4. turnstile 进场 +80° 时轴在屏外左侧 => 左边缘近、更高 => 比值 > 1。

用法: D:\\python3.14\\python.exe docs\\win8-frames\\_acc-measure.py <capture_dir>
"""
import os
import sys
import glob
import numpy as np
from PIL import Image

BG_TOL = 24          # 与背景色差超过这个数就算「内容」
MIN_COL_PIX = 8      # 一列至少要有这么多内容像素才算有效列


def page_profile(path):
    im = Image.open(path).convert("RGB")
    a = np.asarray(im).astype(np.int16)
    H, W, _ = a.shape

    # 背景色：四角 40x40 区域里出现最多的颜色（量化到 8 级）
    corners = np.concatenate([
        a[0:40, 0:40].reshape(-1, 3),
        a[0:40, W - 40:W].reshape(-1, 3),
        a[H - 40:H, 0:40].reshape(-1, 3),
        a[H - 40:H, W - 40:W].reshape(-1, 3),
    ])
    q = (corners // 8)
    keys, counts = np.unique(q, axis=0, return_counts=True)
    bg = keys[counts.argmax()] * 8 + 4

    # 与背景的差异
    diff = np.abs(a - bg.reshape(1, 1, 3)).sum(axis=2)
    mask = diff > BG_TOL

    colcount = mask.sum(axis=0)
    cols = np.where(colcount >= MIN_COL_PIX)[0]
    if cols.size < 20:
        return None

    x0, x1 = int(cols[0]), int(cols[-1])
    span = x1 - x0
    xl = x0 + int(span * 0.05)
    xr = x0 + int(span * 0.95)

    def h_at(x):
        rows = np.where(mask[:, x])[0]
        if rows.size < MIN_COL_PIX:
            return None
        return int(rows[-1] - rows[0] + 1)

    hl, hr = h_at(xl), h_at(xr)
    if not hl or not hr:
        return None
    return dict(W=W, H=H, x0=x0, x1=x1, xl=xl, xr=xr, hl=hl, hr=hr,
                ratio=hl / float(hr), ncols=int(cols.size))


def run(cap):
    fdir = os.path.join(cap, "frames")
    files = sorted(glob.glob(os.path.join(fdir, "f_*.png")))
    if not files:
        print("  (no frames)")
        return
    print("  capture = %s   frames = %d" % (cap, len(files)))
    print("  %-8s %-24s %-8s %-8s %-9s %s" % ("frame", "x-range", "h_left", "h_right", "L/R", "note"))
    best = None
    for f in files:
        n = os.path.basename(f)
        r = page_profile(f)
        if r is None:
            print("  %-8s %-24s %-8s %-8s %-9s %s"
                  % (n, "-", "-", "-", "-", "-", "no page"))
            continue
        note = ""
        if best is None or abs(r["ratio"] - 1.0) > abs(best[1] - 1.0):
            best = (n, r["ratio"])
            note = "<-- max deviation"
        print("  %-8s %-24s %-8d %-8d %-9.4f %s"
              % (n, "%d..%d" % (r["x0"], r["x1"]), r["hl"], r["hr"], r["ratio"], note))
    if best:
        print("  ==> max |L/R - 1| at %s : ratio = %.4f" % (best[0], best[1]))
    print("")


if __name__ == "__main__":
    targets = sys.argv[1:] or ["tools/captures/tt_enter", "tools/captures/tt_exit"]
    for t in targets:
        run(t)
