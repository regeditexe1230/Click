# -*- coding: utf-8 -*-
"""把一帧渲染成粗 ASCII，用来看画面上到底有什么（无视觉模型时的替代）。

用法: python docs\\win8-frames\\_acc_ascii.py <png> [cols] [rows]
"""
import sys
import numpy as np
from PIL import Image

RAMP = " .:-=+*#%@"


def main():
    path = sys.argv[1]
    cols = int(sys.argv[2]) if len(sys.argv) > 2 else 72
    rows = int(sys.argv[3]) if len(sys.argv) > 3 else 44
    im = Image.open(path).convert("L").resize((cols, rows), Image.BILINEAR)
    a = np.asarray(im).astype(np.float32)
    lo, hi = float(a.min()), float(a.max())
    rng = max(hi - lo, 1.0)
    print("%s   %dx%d  gray range %.0f..%.0f"
          % (path.split("\\")[-1].split("/")[-1], im.width, im.height, lo, hi))
    for r in range(rows):
        line = []
        for c in range(cols):
            v = (a[r, c] - lo) / rng
            line.append(RAMP[min(int(v * len(RAMP)), len(RAMP) - 1)])
        print("  |" + "".join(line) + "|")


if __name__ == "__main__":
    main()
