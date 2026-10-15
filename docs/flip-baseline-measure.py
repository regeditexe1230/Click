# -*- coding: utf-8 -*-
"""磁贴翻转动画的逐帧剪影测量（基线实测用）。

用法:
    python docs/flip-baseline-measure.py <帧目录> <times.txt> [band_top] [band_bottom] [inset] [bg_x] [bg_y]

帧目录里是 f01.png f02.png ...（adb 连拍产物，第一帧必须是动画开始前的静止列表，当作参考帧），
times.txt 是设备端 /proc/uptime 记的「帧号 起始秒 结束秒」。脚本对每帧算两件事的**交集**：
  1) 这一帧和页面底色（在 <bg_x,bg_y> 采样，通常是列表页的 #111418）明显不同的像素；
  2) 这一帧和**参考帧（f01，静止列表）**明显不同的像素。
只有同时满足才算「这一帧的剪影」—— 这样静态的列表卡片、FAB 都被排掉，剩下的正好是
翻转中的正面卡片（p<0.5）或正在被揭开的节点图窗口（p>=0.5）：
这两者任意时刻只会有一个存在，所以交集就是那一个的轮廓。

输出每帧：外接矩形、宽高、左右两侧内缩 inset 像素处的竖直高度（近大远小比 = 左/右）、
13 点宽度剖面（判断是射影梯形还是整体缩放）。时间同时给设备绝对时间戳和以首个运动帧为零点的
相对毫秒（相对值属于 [估计]，不确定度 ±1 帧 ≈ ±350ms，因为连拍每帧约 350ms）。
"""
import sys, os, glob
import numpy as np
from PIL import Image

THRESH = 25      # 与底色 / 参考帧的通道差阈值（diff/tile 模式）
THRESH_BG = 12   # bg 模式专用：卡片底色 (39,42,47) 和页面底色 (17,20,24) 只差 22，用 25 会把整张卡片漏掉
MIN_ROW_PX = 4   # 一行里少于这么多像素就当噪声，不算剪影
TILE_RGB = (39, 42, 47)   # ProgramTile 的 surfaceContainerHigh 底色（这一版主题）
TILE_TOL = 8
# tile 模式下要排掉的静止磁贴：列表里没被点的那张卡片（设备 1440x3200 上的实测矩形，x0,y0,x1,y1）
STATIC_TILES = [(56, 762, 1384, 1104)]


def load_mask_tile(path, band_top, band_bot):
    """tile 模式：只认「卡片底色」的像素，再挖掉静止的那张卡片。
    翻转中（p<0.5）画面上只有被点的这张卡片是卡片底色，所以它的外接矩形就是正面剪影。"""
    im = np.asarray(Image.open(path).convert('RGB'), dtype=np.int16)
    a = im[band_top:band_bot, :, :]
    m = np.abs(a - np.array(TILE_RGB)[None, None, :]).max(axis=2) <= TILE_TOL
    for (x0, y0, x1, y1) in STATIC_TILES:
        ty0, ty1 = max(y0 - band_top, 0), max(y1 - band_top, 0)
        if ty1 > ty0:
            m[ty0:ty1, x0:x1] = False
    rows = m.sum(axis=1)
    m[rows < MIN_ROW_PX, :] = False
    return m


def load_mask_magenta(path, band_top, band_bot):
    """magenta 模式：测量构建里把卡片底色临时换成 #FF00FF，只认这个颜色。
    这样无论列表、顶栏底栏是什么颜色都不会混进剪影，正面卡片的轮廓最干净。"""
    im = np.asarray(Image.open(path).convert('RGB'), dtype=np.int16)
    a = im[band_top:band_bot, :, :]
    m = np.abs(a - np.array([255, 0, 255])[None, None, :]).max(axis=2) <= 60
    rows = m.sum(axis=1)
    m[rows < MIN_ROW_PX, :] = False
    return m


def load_mask_bg(path, bg, band_top, band_bot):
    """bg 模式：只要「和页面底色不同」就算剪影。
    用于列表被临时隐藏（listAlpha=0）的测量构建：这时画面上除了底色，只剩下翻转中的正面卡片
    （p<0.5）或被揭开的节点图窗口（p>=0.5），两者都是非底色，且任意时刻只存在一个 —— 轮廓最干净。"""
    im = np.asarray(Image.open(path).convert('RGB'), dtype=np.int16)
    a = im[band_top:band_bot, :, :]
    m = np.abs(a - bg[None, None, :]).max(axis=2) > THRESH_BG
    rows = m.sum(axis=1)
    m[rows < MIN_ROW_PX, :] = False
    return m


def load_mask(path, bg, ref, band_top, band_bot):
    im = np.asarray(Image.open(path).convert('RGB'), dtype=np.int16)
    a = im[band_top:band_bot, :, :]
    diff_bg = np.abs(a - bg[None, None, :]).max(axis=2) > THRESH
    diff_ref = np.abs(a - ref).max(axis=2) > THRESH
    m = diff_bg & diff_ref
    # 去掉每行里零星几个像素的噪声（抗锯齿、状态栏图标残留）
    rows = m.sum(axis=1)
    m[rows < MIN_ROW_PX, :] = False
    return m


def main():
    frames_dir = sys.argv[1]
    times_path = sys.argv[2]
    band_top = int(sys.argv[3]) if len(sys.argv) > 3 else 340
    band_bot = int(sys.argv[4]) if len(sys.argv) > 4 else 2860
    inset = int(sys.argv[5]) if len(sys.argv) > 5 else 60
    bg_x = int(sys.argv[6]) if len(sys.argv) > 6 else 20
    bg_y = int(sys.argv[7]) if len(sys.argv) > 7 else 1500
    mode = sys.argv[8] if len(sys.argv) > 8 else 'diff'

    times = {}
    for line in open(times_path, encoding='utf-8'):
        parts = line.split()
        if len(parts) == 3 and parts[0].isdigit():
            times[int(parts[0])] = (float(parts[1]), float(parts[2]))

    files = sorted(glob.glob(os.path.join(frames_dir, 'f*.png')))
    if not files:
        print('no frames in', frames_dir)
        return
    ref_full = np.asarray(Image.open(files[0]).convert('RGB'), dtype=np.int16)
    bg = ref_full[bg_y, bg_x].copy()
    ref = ref_full[band_top:band_bot, :, :]
    print('ref=%s  bg=%s (at %d,%d)  band y=%d..%d  inset=%d  frames=%d'
          % (os.path.basename(files[0]), tuple(int(v) for v in bg), bg_x, bg_y,
             band_top, band_bot, inset, len(files)))

    out = []
    for path in files:
        idx = int(os.path.basename(path)[1:3])
        if mode == 'tile':
            m = load_mask_tile(path, band_top, band_bot)
        elif mode == 'bg':
            m = load_mask_bg(path, bg, band_top, band_bot)
        elif mode == 'magenta':
            m = load_mask_magenta(path, band_top, band_bot)
        else:
            m = load_mask(path, bg, ref, band_top, band_bot)
        if not m.any():
            out.append((idx, None))
            continue
        ys, xs = np.nonzero(m)
        x0, x1 = int(xs.min()), int(xs.max())
        y0, y1 = int(ys.min()) + band_top, int(ys.max()) + band_top
        colh = m.sum(axis=0)
        hl = int(colh[min(x0 + inset, m.shape[1] - 1)])
        hr = int(colh[max(x1 - inset, 0)])
        prof = [int(colh[int(x0 + (x1 - x0) * i / 12.0)]) for i in range(13)]
        # 固定屏幕列上的高度：梯形时这两列分别落在近边 / 远边里，比「bbox 内缩」更稳定
        abs_h = [int(colh[x]) if 0 <= x < m.shape[1] else 0 for x in (300, 1100)]
        out.append((idx, (x0, x1, y0, y1, x1 - x0 + 1, y1 - y0 + 1, hl, hr, prof, abs_h)))

    base_t = times.get(out[0][0], (0.0, 0.0))[0]
    first_moving = next((i for i, (_, r) in enumerate(out) if r), None)
    for k, (idx, r) in enumerate(out):
        ts = times.get(idx, (0.0, 0.0))
        rel = (ts[0] - base_t) * 1000.0
        mark = ''
        if first_moving is not None:
            if k == first_moving:
                mark = '  <== 首个运动帧 (t0)'
            elif k > first_moving:
                mark = '  +%.0fms' % ((ts[0] - times[out[first_moving][0]][0]) * 1000.0)
        if not r:
            print('f%02d  t=%8.1fms  (无剪影)%s' % (idx, rel, mark))
            continue
        x0, x1, y0, y1, bw, bh, hl, hr, prof, abs_h = r
        ratio = (hl / hr) if hr else 0.0
        ratio_abs = (abs_h[0] / abs_h[1]) if abs_h[1] else 0.0
        print('f%02d  t=%8.1fms  bbox=[%4d,%4d]x[%4d,%4d]  w=%4d h=%4d  hL%02d=%4d hR%02d=%4d  L/R=%.3f  h(x300)=%4d h(x1100)=%4d  L/R(固定列)=%.3f%s'
              % (idx, rel, x0, x1, y0, y1, bw, bh, inset, hl, inset, hr, ratio,
                 abs_h[0], abs_h[1], ratio_abs, mark))


if __name__ == '__main__':
    main()
