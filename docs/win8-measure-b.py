# -*- coding: utf-8 -*-
"""
win8-measure-b.py  --  independent measurement harness for the Windows 8 Start-screen
tile click/tap animation.  Written by teammate `win8-ref-b`.

Every number that appears in docs/win8-tile-spec-B.md comes out of this script.
Nothing is eyeballed; there is no vision model on this machine, so the ONLY admissible
evidence is this numeric output plus the cited source files.

WHAT IT MEASURES

  REFERENCE TILE GRID : connected components of the non-background mask of the
                        reference frame = the tiles and the other screen furniture.
  TABLE 1/2  time axis + per-frame change against the reference frame
                        (mean abs diff, number of pixels that changed by > 60 levels).
  TABLE 3  tile width : for every SOLID tile found in the reference frame, the visible
                        horizontal width of that tile in this frame, as a FRACTION of
                        its reference width.  width_ratio = cos(theta) for a rotation
                        about the tile's vertical axis; theta = acos(ratio).
  TABLE 4  perspective: for each tile, the visible HEIGHT at its near (left) edge column
                        divided by the visible height at its far (right) edge column.
                        A pure 2-D scale reads 1.000; a 3-D yaw about a vertical axis
                        gives near/far > 1.000.  Both heights are given in pixels.
  TABLE 5  content box: bbox and pixel count of everything that is not the background
                        colour -- the whole-screen content-scale timeline, used to
                        characterise the screen-level exit transition.

Usage:
    python win8-measure-b.py [frame_dir]

`frame_dir` defaults to the scratch capture directory.  Frames must be named
`p_<milliseconds>.png`, which is how the capture harness writes them.
"""

import os
import sys
import glob
import collections

import numpy as np
import cv2

DEFAULT_DIR = r"C:\Users\33947\Downloads\win8b\frames"
REFERENCE = "p_0196300.png"   # t = 196300 ms, a fully static Start-screen frame
BG_TOL = 20                   # per-channel tolerance when deciding "is background"
CHANGE_TOL = 60               # per-channel tolerance for "this pixel really changed"
SOLID_MIN = 0.50              # area/(w*h) above which a component is a solid tile


# --------------------------------------------------------------------------- helpers
def load(path):
    img = cv2.imread(path, cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit("cannot read " + path)
    return img


def ts_of(name):
    """p_0196300.png -> 196300 (ms)"""
    return int(os.path.basename(name).split('_')[1].split('.')[0])


def background_colour(img):
    """modal colour of the frame, quantised to 8 levels per channel"""
    q = (img.reshape(-1, 3) // 8).astype(np.int32)
    key = q[:, 0] * 4096 + q[:, 1] * 64 + q[:, 2]
    k = collections.Counter(key.tolist()).most_common(1)[0][0]
    return np.array([(k // 4096) * 8 + 4, ((k // 64) % 64) * 8 + 4, (k % 64) * 8 + 4],
                    dtype=np.int32)


def content_mask(img, bg):
    """True where the pixel is NOT the background colour"""
    return (np.abs(img.astype(np.int32) - bg).max(axis=2) > BG_TOL)


def find_tiles(mask, min_area=1500, min_side=20):
    """connected components of the non-background mask = candidate tiles"""
    m = cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    n, lab, stats, cent = cv2.connectedComponentsWithStats(m, 8)
    out = []
    for i in range(1, n):
        x, y, w, h, a = (int(v) for v in stats[i])
        if a < min_area or w < min_side or h < min_side:
            continue
        out.append((x, y, w, h, a, a / float(w * h)))
    out.sort(key=lambda t: (t[0], t[1]))
    return out


def tile_extent(mask, box, pad=24):
    """Visible extent of the component inside `box`, measured on its own mask.

    Returns (width, left_height, right_height) where
      width      = widest contiguous run of content pixels on the component's centre row
      *_height   = height of the content column at that run's left / right end
    `pad` grows the probe window so an edge that moved outward is still captured.
    """
    H, W = mask.shape[:2]
    x, y, w, h = box[0], box[1], box[2], box[3]
    x0, y0 = max(0, x - pad), max(0, y - pad)
    x1, y1 = min(W, x + w + pad), min(H, y + h + pad)
    sub = mask[y0:y1, x0:x1]
    if not sub.any():
        return 0, 0, 0

    cy = (y + h // 2) - y0
    row = sub[max(0, cy - 1):cy + 2].any(axis=0)
    idx = np.flatnonzero(row)
    if idx.size == 0:
        return 0, 0, 0
    splits = np.split(idx, np.flatnonzero(np.diff(idx) > 1) + 1)
    run = max(splits, key=len)
    if run.size < 8:
        return 0, 0, 0
    xs_lo, xs_hi = int(run[0]), int(run[-1])

    def edge_height(cx):
        col = sub[:, max(0, cx - 1):cx + 2].any(axis=1)
        j = np.flatnonzero(col)
        if j.size == 0:
            return 0
        sp = np.split(j, np.flatnonzero(np.diff(j) > 1) + 1)
        best = min(sp, key=lambda s: abs((s[0] + s[-1]) / 2.0 - cy))
        return int(best[-1] - best[0] + 1)

    return xs_hi - xs_lo + 1, edge_height(xs_lo), edge_height(xs_hi)


def bbox_row(m):
    ys, xs = np.nonzero(m)
    return "(%4d,%4d,%4d,%4d)" % (xs.min(), ys.min(),
                                  xs.max() - xs.min() + 1, ys.max() - ys.min() + 1)


# --------------------------------------------------------------------------- main
def main():
    d = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    files = sorted(glob.glob(os.path.join(d, "p_*.png")), key=ts_of)
    if not files:
        raise SystemExit("no p_*.png frames in " + d)
    ref_path = os.path.join(d, REFERENCE)
    if not os.path.exists(ref_path):
        raise SystemExit("reference frame %s missing from %s" % (REFERENCE, d))

    ref = load(ref_path)
    t_ref = ts_of(ref_path)
    bg = background_colour(ref)
    ref_mask = content_mask(ref, bg)

    print("=" * 104)
    print("win8-measure-b.py   frame_dir = %s" % d)
    print("frames = %d   reference = %s (t=%d ms)   background BGR = %s"
          % (len(files), REFERENCE, t_ref, tuple(int(v) for v in bg)))
    print("=" * 104)

    comps = find_tiles(ref_mask)
    print("\nREFERENCE COMPONENTS of the non-background mask (%d, area >= 1500 px):" % len(comps))
    print("  #  | solid |    x,   y |   w x  h |   area")
    solid = []
    for i, (x, y, w, h, a, s) in enumerate(comps):
        flag = "TILE " if s >= SOLID_MIN else " ui  "
        print("  %2d | %s | %4d,%4d | %4d x%4d | %7d  solidity=%.3f"
              % (i, flag, x, y, w, h, a, s))
        if s >= SOLID_MIN:
            solid.append((i, (x, y, w, h)))
    print("  -> %d components classified as solid tiles; the rest are screen furniture"
          % len(solid))

    base = {i: tile_extent(ref_mask, b) for i, b in solid}

    # ------------------------------------------------------------- TABLE 1 + 2
    print("\n" + "-" * 104)
    print("TABLE 1/2  time axis and per-frame change against the reference frame")
    print("-" * 104)
    print(" frame          t(ms)   dt(ms) |    mad |  px>%d | changed bbox (x,y,w,h)"
          % CHANGE_TOL)

    frames = []
    for f in files:
        img = load(f)
        d32 = np.abs(img.astype(np.int32) - ref.astype(np.int32))
        mad = float(d32.mean())
        m = d32.max(axis=2) > CHANGE_TOL
        npx = int(m.sum())
        print(" %-14s %7d %8d | %6.1f | %6d | %s"
              % (os.path.basename(f), ts_of(f), ts_of(f) - t_ref, mad, npx,
                 bbox_row(m) if npx else "-"))
        frames.append((os.path.basename(f), img, ts_of(f), content_mask(img, bg)))

    # ------------------------------------------------------------- TABLE 3
    print("\n" + "-" * 104)
    print("TABLE 3  per-frame visible horizontal WIDTH of each solid tile, as a fraction of")
    print("         its width in the reference frame.  width_ratio = cos(theta) for a")
    print("         rotation about the tile's vertical axis; theta = acos(ratio) in degrees.")
    print("         A tile that does not deform reads ratio 1.000 / theta 0.00.")
    print("-" * 104)
    print(" frame          t(ms) |" + "".join("  #%-3d w     th   |" % i for i, _ in solid))

    theta_log = {i: [] for i, _ in solid}
    for name, img, t, mk in frames:
        line = " %-14s %7d |" % (name, t)
        for i, b in solid:
            wid, lh, rh = tile_extent(mk, b)
            bw = base[i][0]
            if bw == 0 or wid == 0:
                line += "    n/a           |"
                continue
            r = max(-1.0, min(1.0, wid / float(bw)))
            th = float(np.degrees(np.arccos(r)))
            theta_log[i].append((t, r, th))
            line += "  %5.3f %6.2f |" % (r, th)
        print(line)

    # ------------------------------------------------------------- TABLE 4
    print("\n" + "-" * 104)
    print("TABLE 4  perspective strength: visible HEIGHT of the tile at its near (left)")
    print("         edge column / visible height at its far (right) edge column, in px.")
    print("         Pure 2-D scale -> 1.000; a 3-D yaw about the vertical axis -> > 1.000.")
    print("-" * 104)
    print(" frame          t(ms) |" + "".join("  #%-3d near far  ratio |" % i for i, _ in solid))

    peak = {i: (1.0, None) for i, _ in solid}
    for name, img, t, mk in frames:
        line = " %-14s %7d |" % (name, t)
        for i, b in solid:
            wid, lh, rh = tile_extent(mk, b)
            if lh == 0 or rh == 0:
                line += "       n/a             |"
                continue
            ratio = lh / float(rh)
            if ratio > peak[i][0]:
                peak[i] = (ratio, t)
            line += "  %4d %4d %6.3f |" % (lh, rh, ratio)
        print(line)

    print("\nPEAK near/far edge-height ratio per tile over the whole sequence:")
    for i, b in solid:
        r, t = peak[i]
        print("  #%-3d %-22s peak = %.4f  at t = %s"
              % (i, str(b), r, ("%d ms" % t) if t else "n/a"))

    # ------------------------------------------------------------- TABLE 6
    print("\n" + "-" * 104)
    print("TABLE 6  THE KEY TABLE.  For every frame, the most extreme deformation found on")
    print("         ANY solid tile: the smallest visible-width fraction (most yaw) and the")
    print("         largest near/far edge-height ratio (most perspective).  If a tile press")
    print("         animation existed in this footage it would show up as a row where")
    print("         min_width_fraction drops below 1.000 while the screen is otherwise still.")
    print("-" * 104)
    print(" frame          t(ms) | screen px>60 | tiles measured | min width frac"
          " | max near/far")
    for name, img, t, mk in frames:
        d32 = np.abs(img.astype(np.int32) - ref.astype(np.int32))
        npx = int((d32.max(axis=2) > CHANGE_TOL).sum())
        wr, pf, n = [], [], 0
        for i, b in solid:
            wid, lh, rh = tile_extent(mk, b)
            bw = base[i][0]
            if bw and wid:
                wr.append(wid / float(bw))
                n += 1
            if lh and rh:
                pf.append(lh / float(rh))
        print(" %-14s %7d | %12d | %14d | %14s | %s"
              % (name, t, npx, n,
                 ("%.4f" % min(wr)) if wr else "n/a",
                 ("%.4f" % max(pf)) if pf else "n/a"))

    # ------------------------------------------------------------- TABLE 5
    print("\n" + "-" * 104)
    print("TABLE 5  screen-level content box (everything that is not background colour).")
    print("         fraction = content pixels / 921600; the box gives position and overall")
    print("         scale of the whole screen content.")
    print("-" * 104)
    print(" frame          t(ms) | fraction | content bbox (x,y,w,h)")
    for name, img, t, mk in frames:
        n = int(mk.sum())
        print(" %-14s %7d | %8.4f | %s"
              % (name, t, n / float(mk.size), bbox_row(mk) if n else "-"))

    # ------------------------------------------------------------- SUMMARY
    print("\n" + "=" * 104)
    print("SUMMARY")
    print("=" * 104)
    worst_w, worst_t = 1.0, None
    for i in theta_log:
        for t, r, th in theta_log[i]:
            if r < worst_w:
                worst_w, worst_t = r, t
    max_def = float(np.degrees(np.arccos(worst_w)))
    pk_i = max(peak, key=lambda k: peak[k][0])
    print("  minimum visible-width fraction over all solid tiles and frames : %.4f" % worst_w)
    print("  => maximum inferred yaw (rotation about the vertical axis)     : %.2f deg" % max_def)
    print("     (at t = %s)" % (("%d ms" % worst_t) if worst_t else "n/a"))
    print("  maximum near/far edge-height ratio over all solid tiles        : %.4f (tile #%d)"
          % (peak[pk_i][0], pk_i))
    if worst_w > 0.995 and peak[pk_i][0] < 1.005:
        print("  VERDICT: no solid tile in this footage changes its visible width or its")
        print("           edge heights by more than 0.5%.  This sequence contains NO tile")
        print("           press / tilt / scale animation whatsoever.")
    else:
        print("  VERDICT: measurable tile deformation is present -- see the tables above.")


if __name__ == '__main__':
    main()
