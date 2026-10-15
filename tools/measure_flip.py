#!/usr/bin/env python3
"""Measure the program-tile flip frame by frame: timeline, angle, perspective, size.

WHAT IS BEING MEASURED (ground truth read from the source, 2026-10-15 revision,
app/src/main/java/com/yjc/click/ui/program/ProgramScreen.kt:548-703):

  flipGrow(p)        window growth 0..1, two FastOutSlowIn segments split at HANDOFF
  flipRect(p)        axis-aligned window rect: card rect -> window rect (0,0,W,H),
                     each corner grown independently by g = flipGrow(p)
  FlipFront          drawn while p < HANDOFF, laid out at flipRect(p); the draw scope
                     feeds src=(0,0,w,0,w,h,0,h) and
                       theta = 90deg * p/HANDOFF , halfW = w/2 , halfH = h/2
                       d = F * w , sNear = d/(d - halfW*sin) , sFar = d/(d + halfW*sin)
                       far = sFar/sNear            (near edge normalised to 1)
                     through android.graphics.Matrix.setPolyToPoly(...,4), so the
                     painted quad has corners
                       TL = (halfW(1-cos), 0)              (near/left edge, height h)
                       TR = (halfW(1+cos*far), halfH(1-far))
                       BR = (halfW(1+cos*far), halfH(1+far))
                       BL = (halfW(1-cos), h)              (far/right edge, height h*far)
  GraphReveal        drawn while p >= HANDOFF, clipPath(flipRect(p)) over the graph

  => per frame the pixels carry:
       left  edge height  hL = h            (the un-rotated window height)
       right edge height  hR = h * far
       projected width    wm = (w/2)*cos(theta)*(1+far)
       ratio r = hL/hR = 1/far = (F + 0.5 sin)/(F - 0.5 sin)
     so
       theta_from_ratio = asin( 2F (r-1)/(r+1) )            [needs F]
       theta_from_geom  = acos( 2 wm / (w*(1+far)) )         [does NOT need F]
     and comparing the two per frame is an independent check of the source's
     PERSPECTIVE_DISTANCE_FACTOR.  Because r <= (F+0.5)/(F-0.5) is reached only at
     theta=90deg, the largest ratio ever measured gives a model-light bound on F.

Vertex heights are obtained by a ROBUST LINE FIT of the quad's top and bottom
boundaries (middle 70% of the columns, one outlier pass) and extrapolating to the
mask's x extremes, so the 16dp rounded corners do not bias the edge heights.

Frames that cannot be measured are reported as state=unmeasured with a note; nothing
is interpolated, nothing is invented, and no frame is silently dropped.

usage:
  python tools/measure_flip.py --dir tools/captures/20261015_120000_open
  python tools/measure_flip.py --baseline base.png --seed 720,460 --window-rect 0,0,1440,3200 \
      --flip-ms 600 --slow 8 --dir <capdir>
  python tools/measure_flip.py --detect-card base.png [--roi x,y,w,h]
  python tools/measure_flip.py --detect-window graph.png
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sys

import numpy as np

LUMA = np.array([77, 150, 29], dtype=np.int16)  # 0.299/0.587/0.114 in 8.8 fixed point
ZERO_ROW = np.array([0, 8, 8])


def die(msg: str, code: int = 2):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(code)


def load_rgb(path: str) -> np.ndarray:
    from PIL import Image

    return np.asarray(Image.open(path).convert("RGB"))


def parse_rect(text: str, what: str):
    if not text:
        return None
    parts = [p.strip() for p in text.replace(";", ",").split(",") if p.strip()]
    if len(parts) != 4:
        die("%s must be x,y,w,h (got %r)" % (what, text))
    try:
        x, y, w, h = (int(round(float(p))) for p in parts)
    except ValueError:
        die("%s must be four numbers (got %r)" % (what, text))
    return (x, y, w, h)


def parse_point(text: str, what: str):
    if not text:
        return None
    parts = [p.strip() for p in text.replace(";", ",").split(",") if p.strip()]
    if len(parts) != 2:
        die("%s must be x,y (got %r)" % (what, text))
    return tuple(int(round(float(p))) for p in parts)


def parse_rgb(text: str, what: str):
    if not text:
        return None
    parts = [p.strip() for p in text.split(",") if p.strip()]
    if len(parts) != 3:
        die("%s must be r,g,b (got %r)" % (what, text))
    return tuple(int(float(p)) for p in parts)


def clip_rect(rect, shape):
    x, y, w, h = rect
    x0 = max(0, min(int(x), shape[1] - 1))
    y0 = max(0, min(int(y), shape[0] - 1))
    x1 = max(x0 + 1, min(int(x + w), shape[1]))
    y1 = max(y0 + 1, min(int(y + h), shape[0]))
    return (x0, y0, x1 - x0, y1 - y0)


def luma_of(img: np.ndarray) -> np.ndarray:
    a = img.astype(np.int16)
    return (a[:, :, 0] * 77 + a[:, :, 1] * 150 + a[:, :, 2] * 29) >> 8


def modal_luma(img: np.ndarray, lo: int = 0, hi: int = 256) -> int:
    hist = np.bincount(luma_of(img).ravel().clip(0, 255), minlength=256)
    hist[:lo] = 0
    hist[hi:] = 0
    return int(np.argmax(hist))


def largest_component(mask: np.ndarray, min_area: int, close_iters: int = 2,
                      seed=None, exclude_border_touch: bool = False):
    """largest connected component of `mask` (holes filled).

    If `seed=(x,y)` is given (mask-local coordinates) only the component covering the
    seed is considered -- that is what keeps the *other* program cards, the node cards
    of the revealed graph and the FAB from being mistaken for the flipping tile.
    """
    try:
        from scipy import ndimage
    except Exception:  # pragma: no cover
        die("scipy is required (ndimage.label)")

    m = mask
    if close_iters > 0:
        m = ndimage.binary_closing(m, structure=np.ones((3, 3), bool), iterations=close_iters)
    m = ndimage.binary_fill_holes(m)
    lab, n = ndimage.label(m, structure=np.ones((3, 3), bool))
    if n == 0:
        return None, 0, 0, "empty mask"
    sizes = np.bincount(lab.ravel())
    sizes[0] = 0
    if seed is not None:
        sx, sy = int(seed[0]), int(seed[1])
        if not (0 <= sy < lab.shape[0] and 0 <= sx < lab.shape[1]):
            return None, 0, n, "seed outside frame"
        sl = int(lab[sy, sx])
        if sl == 0:
            return None, 0, n, "seed pixel is not tile-coloured (no tile here)"
        if exclude_border_touch:
            return None, 0, n, "border hint not supported with seed"
        return (lab == sl), int(sizes[sl]), n, ""
    if exclude_border_touch:
        edge = np.concatenate([lab[0, :], lab[-1, :], lab[:, 0], lab[:, -1]])
        for e in np.unique(edge):
            if e != 0:
                sizes[e] = 0
    idx = int(sizes.argmax())
    if sizes[idx] == 0:
        return None, 0, n, "no component kept"
    return (lab == idx), int(sizes[idx]), n, ""


def fit_edges(comp: np.ndarray, x0: int, x1: int, band: float = 0.15):
    """robust line fit of the quad's top/bottom boundary -> edge heights at both ends.

    Two guards matter on real frames:

      * the component can pick up stray attached pixels (a sliver of the page behind the
        tile, an antialiased edge merging with the dot grid), which stretches the x range
        far past the tile and makes the extrapolation explode -- measured on device: a
        544 px wide quad reported a 2649 px left edge.  Only the region where the quad is
        actually tall (>= 25% of the tallest column) is fitted.
      * the fitted heights are rejected when they leave a sane band around the tallest
        column (<0.1x or >1.4x).  A real trapezoid never tapers below ~1/3 of the near
        edge (F=1.5 at 90 deg gives far=0.5), so a wild number is a broken fit.

    Returns None when the frame cannot be measured -- callers mark it unmeasured, they
    never interpolate over it.
    """
    col = comp[:, x0:x1 + 1]
    occ = col.any(axis=0)
    if occ.sum() < 6:
        return None
    tops_all = np.where(occ, col.argmax(axis=0), np.nan).astype(float)
    bots_all = np.where(occ, (col.shape[0] - 1) - col[::-1].argmax(axis=0), np.nan).astype(float)
    heights_all = bots_all - tops_all
    hmax = float(np.nanmax(heights_all))
    if not np.isfinite(hmax) or hmax < 4:
        return None
    idx = np.flatnonzero(np.isfinite(heights_all) & (heights_all >= 0.25 * hmax))
    if idx.size < 6:
        return None
    xa, xb = int(idx[0]), int(idx[-1])
    width = xb - xa + 1
    if width < 6:
        return None
    xs = np.arange(xa, xb + 1)
    tops, bots, heights = tops_all[xa:xb + 1], bots_all[xa:xb + 1], heights_all[xa:xb + 1]
    good = np.isfinite(tops) & np.isfinite(bots) & (heights >= 2)
    if good.sum() < 5:
        return None
    sel = np.zeros_like(good)
    lo = int(xa + band * width)
    hi = int(xb - band * width)
    sel[max(lo, xa):max(hi, lo + 1)] = True
    sel &= good
    if sel.sum() < 5:
        sel = good & (heights >= np.nanpercentile(heights[good], 40))
        if sel.sum() < 5:
            return None
    ax, at, ab = xs[sel].astype(float), tops[sel], bots[sel]
    for _ in range(2):  # one outlier pass
        ct = np.polyfit(ax, at, 1)
        cb = np.polyfit(ax, ab, 1)
        rt = at - np.polyval(ct, ax)
        rb = ab - np.polyval(cb, ax)
        keep = (np.abs(rt) <= max(3.0, 2.5 * rt.std())) & (np.abs(rb) <= max(3.0, 2.5 * rb.std()))
        if keep.all() or keep.sum() < 5:
            break
        ax, at, ab = ax[keep], at[keep], ab[keep]
    ct = np.polyfit(ax, at, 1)
    cb = np.polyfit(ax, ab, 1)
    h_left = float(np.polyval(cb, xa) - np.polyval(ct, xa))
    h_right = float(np.polyval(cb, xb) - np.polyval(ct, xb))
    if not (0.10 * hmax <= h_left <= 1.40 * hmax) or not (0.10 * hmax <= h_right <= 1.40 * hmax):
        return None
    resid = np.concatenate([at - np.polyval(ct, ax), ab - np.polyval(cb, ax)])
    return {
        "h_left": h_left,
        "h_right": h_right,
        "rms": float(np.sqrt((resid ** 2).mean())),
        "top_slope": float(ct[0]),
        "bot_slope": float(cb[0]),
        "n": int(ax.size),
        "x_left": xa,
        "x_right": xb,
        "h_max": hmax,
    }


def color_mask(sub: np.ndarray, color, tol: int) -> np.ndarray:
    diff = np.abs(sub.astype(np.int16) - np.array(color, dtype=np.int16)).max(axis=2)
    return diff <= tol


def detect_card(png: str, roi=None, min_area: int = 4000, bg_gap: int = 6,
                verbose: bool = True, min_aspect: float = 1.8, show_all: bool = False,
                border_tol: int = 8):
    """the program card at rest: brightest large blob that *looks* like a card.

    The navigation bar and the top bar are also bright, and the navigation bar is
    bigger than a card, so "brightest blob" alone picks the wrong thing (verified on
    device 2026-10-15: it returned the 1436x334 nav bar, area 479627 > the card's
    454176).  Three filters do the work:

      * the blob must keep away from the frame border by at least ``border_tol`` px.
        The nav bar is inset only 2 px from the frame edge -- it does NOT touch it
        pixel-wise, which is why an exact touch test is not enough (it passed and was
        reported as the card).  With the tolerance it is rejected.
      * aspect >= ``min_aspect`` and width >= 35% of the frame: keeps cards, drops the
        square FAB and small icon/text blobs.
      * height <= 35% of the frame: drops full-screen surfaces.

    When several cards qualify, the biggest wins and ties go to the topmost card, so a
    run picks the same tile every time (that tile is the one to tap).
    """
    try:
        from scipy import ndimage
    except Exception:  # pragma: no cover
        die("scipy is required (ndimage.label)")

    img = load_rgb(png)
    r = clip_rect(roi, img.shape) if roi else (0, 0, img.shape[1], img.shape[0])
    sub = img[r[1]:r[1] + r[3], r[0]:r[0] + r[2]]
    bg = modal_luma(sub)
    for gap in (bg_gap, 3):
        mask = luma_of(sub) > bg + gap
        mask = ndimage.binary_closing(mask, structure=np.ones((3, 3), bool), iterations=2)
        mask = ndimage.binary_fill_holes(mask)
        lab, n = ndimage.label(mask, structure=np.ones((3, 3), bool))
        if n == 0:
            continue
        sizes = np.bincount(lab.ravel())
        cands = []
        for idx in np.argsort(sizes)[::-1]:
            if idx == 0 or sizes[idx] < min_area:
                continue
            comp = lab == idx
            rows = np.flatnonzero(comp.any(axis=1))
            cols = np.flatnonzero(comp.any(axis=0))
            w = int(cols[-1] - cols[0] + 1)
            h = int(rows[-1] - rows[0] + 1)
            aspect = w / float(h)
            touches = bool(comp[0, :].any() or comp[-1, :].any() or
                           comp[:, 0].any() or comp[:, -1].any())
            x0, y0 = int(cols[0]), int(rows[0])
            sh, sw = sub.shape[0], sub.shape[1]
            near = (x0 <= border_tol or y0 <= border_tol or
                    x0 + w >= sw - border_tol or y0 + h >= sh - border_tol)
            why = []
            if touches or near:
                why.append(
                    "hugs the frame border (<= %dpx, %s) - bar/background, not a card"
                    % (border_tol, "touching" if touches else "inset"))
            if aspect < min_aspect:
                why.append("aspect %.2f < %.2f (not a card)" % (aspect, min_aspect))
            if w < 0.35 * sub.shape[1]:
                why.append("too narrow for a card")
            if h > 0.35 * sub.shape[0]:
                why.append("too tall for a card")
            cands.append({
                "idx": int(idx), "area": int(sizes[idx]),
                "rect": [r[0] + x0, r[1] + y0, w, h],
                "aspect": round(aspect, 2), "touches_border": bool(touches or near),
                "why": why,
            })
            if len(cands) >= 12:
                break
        keep = [c for c in cands if not c["why"]]
        if not keep:
            continue
        # biggest first; ties (equal-size cards on one screen) -> topmost, deterministic
        keep.sort(key=lambda c: (-c["area"], c["rect"][1]))
        best = keep[0]
        comp = lab == best["idx"]
        inside = sub[comp]
        out = {
            "png": png,
            "roi": list(r),
            "rect": best["rect"],
            "card_color": [int(v) for v in np.median(inside, axis=0)],
            "bg_color": [int(v) for v in sub[0, 0]],
            "bg_luma": bg,
            "luma_gap": gap,
            "area": best["area"],
            "components": int(n),
            "why": "",
            "cards": len(keep),
        }
        if show_all:
            out["candidates"] = cands
        if verbose:
            print(json.dumps(out, ensure_ascii=False, indent=2))
        return out
    if verbose:
        print("ERROR: no card-like blob found in %s" % png, file=sys.stderr)
    return None


def detect_window(png: str, bar_color=None, verbose: bool = True):
    """content-area box of a fully revealed graph page: top/bottom bar bands removed.

    The bars are the horizontal bands whose dominant colour differs from the canvas
    background (the modal colour of the middle of the image).  Reported as x,y,w,h so
    it can be passed straight to --window-rect.
    """
    img = load_rgb(png)
    h, w = img.shape[:2]
    mid = img[h // 3: 2 * h // 3, :, :]
    bg = np.array([int(v) for v in np.median(mid.reshape(-1, 3), axis=0)])
    if bar_color is None:
        # bar band: the modal colour of the top 5% of rows
        top = img[0: max(1, h // 20)].reshape(-1, 3)
        bar_color = [int(v) for v in np.median(top, axis=0)]
    bar = np.array(bar_color, dtype=np.int16)
    rowdiff = np.abs(img.astype(np.int16) - bar).max(axis=2).mean(axis=1)
    bgr = np.abs(img.astype(np.int16) - bg).max(axis=2).mean(axis=1)
    is_bar = (rowdiff < 6) & (bgr > 6)
    top = 0
    while top < h - 1 and is_bar[top]:
        top += 1
    bot = h - 1
    while bot > top and is_bar[bot]:
        bot -= 1
    rect = (0, int(top), int(w), int(bot - top + 1))
    out = {"png": png, "window_rect": list(rect), "canvas_bg": [int(v) for v in bg],
           "bar_color": [int(v) for v in bar]}
    if verbose:
        print(json.dumps(out, ensure_ascii=False, indent=2))
    return out


def fast_out_slow_in(t: float) -> float:
    """Compose's FastOutSlowInEasing = cubic bezier (0.4,0,0.2,1)."""
    if t <= 0.0:
        return 0.0
    if t >= 1.0:
        return 1.0
    x1, y1, x2, y2 = 0.4, 0.0, 0.2, 1.0
    lo, hi = 0.0, 1.0
    for _ in range(48):
        u = 0.5 * (lo + hi)
        x = 3 * (1 - u) ** 2 * u * x1 + 3 * (1 - u) * u * u * x2 + u ** 3
        if x < t:
            lo = u
        else:
            hi = u
    u = 0.5 * (lo + hi)
    return 3 * (1 - u) ** 2 * u * y1 + 3 * (1 - u) * u * u * y2 + u ** 3


def band_edges(sub, card_color, tol, contam_top, min_span=40, band=0.15):
    """Measure the flipping tile from the band ABOVE the next card.

    Why this exists: the tile grows downward over the next card and both are painted in
    the same colour, so any connected-component measurement merges them (that is how the
    first version reported a constant 1327 px width, and 3.28 near/far ratio).
    The tile's *upper* boundary can never be contaminated -- the card below only adds
    pixels underneath -- so:

      * the longest run of columns whose upper boundary sits above the next card = the
        tile's own x extent (x_left..x_right),
      * a robust line fit of the upper boundary over the central (1-2*band) columns and
        extrapolated to both ends gives the true top corners (y_tl, y_tr); the drawn tile
        is a rounded rect, so the extreme columns sit ~corner radius px below them,
      * rise = y_tr - y_tl = (h_left - h_right)/2 for this projection, and the per-column
        heights (where the run is not cut by the next card) extrapolate to h_right at the
        right edge; h_left = h_right + 2*rise.

    Returns None when the tile cannot be identified (no run, or the height profile is
    only measurable too far from the right edge).
    """
    H, W = sub.shape[0], sub.shape[1]
    mask = color_mask(sub, card_color, tol)
    idx = np.arange(H, dtype=np.int32).reshape(-1, 1)
    top = np.where(mask, idx, np.int32(H)).min(axis=0)
    valid = top < contam_top
    bx0, bx1, span = 0, -1, 0
    x = 0
    while x < W:
        if not valid[x]:
            x += 1
            continue
        s = x
        while x + 1 < W and valid[x + 1]:
            x += 1
        if x - s + 1 > span:
            bx0, bx1, span = s, x, x - s + 1
        x += 1
    if span < min_span:
        return None
    xs = np.arange(bx0, bx1 + 1, dtype=float)
    ts = top[bx0:bx1 + 1].astype(float)
    pad = int(band * span)
    sel = (xs >= bx0 + pad) & (xs <= bx1 - pad)
    a = b = 0.0
    for _ in range(2):
        if int(sel.sum()) < 8:
            return None
        a, b = np.polyfit(xs[sel], ts[sel], 1)
        resid = np.abs(ts - (a * xs + b))
        keep = sel & (resid <= 3.0)
        if int(keep.sum()) < 8:
            return None
        if int(keep.sum()) == int(sel.sum()):
            break
        sel = keep
    rms_top = float(np.sqrt(np.mean(((a * xs[sel] + b) - ts[sel]) ** 2)))
    if rms_top > 8.0:
        return None
    y_tl, y_tr = a * bx0 + b, a * bx1 + b
    # per-column heights, only where the run is not cut by the next card
    hx, hh = [], []
    for xx in range(bx0, bx1 + 1):
        y = int(top[xx])
        if y >= H:
            continue
        yy = y
        while yy + 1 < H and mask[yy + 1, xx]:
            yy += 1
        if yy < contam_top:
            hx.append(xx)
            hh.append(yy - y)
    if len(hx) < 8:
        return None
    hx = np.asarray(hx, dtype=float)
    hh = np.asarray(hh, dtype=float)
    if hx.min() > bx0 + 0.4 * span:
        return None                      # only measurable far from the right edge
    a2, b2 = np.polyfit(hx, hh, 1)
    h_right = float(a2 * bx1 + b2)
    rise = float(y_tr - y_tl)
    h_left = h_right + 2.0 * rise
    if h_right <= 1.0 or h_left <= 1.0:
        return None
    return {"x_left": bx0, "x_right": bx1, "y_tl": y_tl, "y_tr": y_tr, "rise": rise,
            "h_left": h_left, "h_right": h_right, "span": span, "rms_top": rms_top}


def measure_frame(path, roi, card_color, tol, min_area, close_iters, seed, mode_luma=False,
                  contam_top=None):
    img = load_rgb(path)
    r = clip_rect(roi, img.shape) if roi else (0, 0, img.shape[1], img.shape[0])
    sub = img[r[1]:r[1] + r[3], r[0]:r[0] + r[2]]
    if mode_luma or card_color is None:
        bg = modal_luma(sub)
        mask = luma_of(sub) > bg + 4
    else:
        mask = color_mask(sub, card_color, tol)
    lseed = None
    if seed is not None:
        lseed = (int(seed[0]) - r[0], int(seed[1]) - r[1])
    comp, area, ncomp, why = largest_component(mask, min_area, close_iters, seed=lseed)
    row = {
        "card_width_px": "", "left_edge_height_px": "", "right_edge_height_px": "",
        "near_far_ratio": "", "theta_deg_est": "", "scale_est": "", "is_handoff": 0,
        "theta_ratio_deg": "", "theta_geom_deg": "", "theta_naive_deg": "",
        "F_est": "", "grow_frac_left": "", "grow_g_est": "", "w_rect_pred_px": "",
        "top_slope": "", "fit_rms_px": "", "fit_cols": "",
        "area_px": area, "bbox": "", "x_left_px": "", "x_right_px": "",
        "top_left_x_px": "", "top_left_y_px": "", "top_right_y_px": "", "top_rise_px": "",
        "band_y_max": "", "state": "unmeasured", "note": why,
    }
    if contam_top is not None and card_color is not None:
        # --- band mode: the tile only, measured above the next card (see band_edges) ---
        b = band_edges(sub, card_color, tol, contam_top - r[1])
        if b is None:
            row["state"] = "no-card"
            row["note"] = "no tile-coloured run above y=%d (band)" % contam_top
            return row
        y_tl = r[1] + b["y_tl"]
        row.update({
            "card_width_px": b["x_right"] - b["x_left"] + 1,
            "x_left_px": r[0] + b["x_left"], "x_right_px": r[0] + b["x_right"],
            "top_left_x_px": r[0] + b["x_left"], "top_left_y_px": "%.1f" % y_tl,
            "top_right_y_px": "%.1f" % (r[1] + b["y_tr"]), "top_rise_px": "%.1f" % b["rise"],
            "band_y_max": contam_top, "state": "card",
            "left_edge_height_px": "%.1f" % b["h_left"],
            "right_edge_height_px": "%.1f" % b["h_right"],
            "top_slope": "%.4f" % (b["rise"] / max(1.0, b["x_right"] - b["x_left"])),
            "fit_rms_px": "%.2f" % b["rms_top"],
            "fit_cols": b["span"],
            "bbox": "%d,%d,%d,%d" % (r[0] + b["x_left"], int(round(y_tl)),
                                     b["x_right"] - b["x_left"] + 1,
                                     int(round(b["y_tr"] - b["y_tl"]))),
        })
        if min(b["h_left"], b["h_right"]) <= 1.0:
            row["state"] = "unmeasured"
            row["note"] = "degenerate edge height (band)"
        return row
    if comp is None:
        row["state"] = "no-card"
        row["note"] = why or "no component >= %d px" % min_area
        return row
    cols = np.flatnonzero(comp.any(axis=0))
    rows = np.flatnonzero(comp.any(axis=1))
    x0, x1 = int(cols[0]), int(cols[-1])
    y0, y1 = int(rows[0]), int(rows[-1])
    fit = fit_edges(comp, x0, x1)
    row.update({
        "card_width_px": x1 - x0 + 1,
        "x_left_px": r[0] + x0, "x_right_px": r[0] + x1,
        "bbox": "%d,%d,%d,%d" % (r[0] + x0, r[1] + y0, x1 - x0 + 1, y1 - y0 + 1),
        "state": "card",
    })
    if ncomp > 1:
        row["note"] = ((row["note"] + "; ") if row["note"] else "") + "components=%d" % ncomp
    if fit is None:
        row["state"] = "unmeasured"
        row["note"] = ((row["note"] + "; ") if row["note"] else "") + \
            "edge fit failed/rejected (stray pixels or degenerate quad)"
        return row
    hl, hr = fit["h_left"], fit["h_right"]
    row.update({
        "left_edge_height_px": "%.1f" % hl, "right_edge_height_px": "%.1f" % hr,
        "top_slope": "%.4f" % fit["top_slope"], "fit_rms_px": "%.2f" % fit["rms"],
        "fit_cols": fit["n"],
        # the fitted span, not the raw mask span: stray pixels must not define the width
        "card_width_px": fit["x_right"] - fit["x_left"] + 1,
        "x_left_px": r[0] + fit["x_left"], "x_right_px": r[0] + fit["x_right"],
    })
    if min(hl, hr) <= 1.0:
        row["state"] = "unmeasured"
        row["note"] = ((row["note"] + "; ") if row["note"] else "") + "degenerate edge height"
    return row


def main():
    # The WinJS turnstile page transition needs a different model (whole-page
    # rotateY + perspective, not the tile homography), so it lives in its own
    # module.  `--page-mode` forwards the whole command line to it: flags this
    # script does not know (--axis-x, --persp-f, --components, ...) go straight
    # through, the shared ones (--dir/--frames-csv/--stride/--tmin/--tmax/--out)
    # keep the same meaning.
    if "--page-mode" in sys.argv[1:]:
        sys.argv = [a for a in sys.argv if a != "--page-mode"]
        import measure_pages
        return measure_pages.main(sys.argv[1:])
    ap = argparse.ArgumentParser(description="measure the tile flip from captured frames")
    ap.add_argument("--dir", help="capture directory (frames.csv + frames/)")
    ap.add_argument("--frames-csv", help="default <dir>/frames.csv")
    ap.add_argument("--detect-card", metavar="PNG")
    ap.add_argument("--detect-window", metavar="PNG")
    ap.add_argument("--show-all", action="store_true",
                    help="with --detect-card: list every candidate blob and why it was rejected")
    ap.add_argument("--border-tol", type=int, default=8,
                    help="a blob within this many px of the frame edge counts as a bar, not a "
                         "card (the nav bar is inset only 2px, so it never 'touches')")
    ap.add_argument("--baseline", help="png of the list at rest (card rect + tile colour)")
    ap.add_argument("--seed", help="x,y of the tapped point in screen pixels (keeps the "
                                   "measurement on the flipping tile only)")
    ap.add_argument("--roi", help="measure only inside x,y,w,h (default: whole frame)")
    ap.add_argument("--window-rect", help="window (content area) x,y,w,h that the tile grows "
                                          "into; enables the F-independent theta and g")
    ap.add_argument("--card-rect", help="card rect x,y,w,h (default: from --baseline)")
    ap.add_argument("--card-color", help="r,g,b of the tile surface (default: from --baseline)")
    ap.add_argument("--tol", type=int, default=8, help="max channel distance to card colour")
    ap.add_argument("--luma", action="store_true", help="mask by brightness instead of colour")
    ap.add_argument("--min-area", type=int, default=3000)
    ap.add_argument("--close-iters", type=int, default=2)
    ap.add_argument("--flip-ms", type=float, default=600.0, help="FLIP_MS in the source")
    ap.add_argument("--slow", type=float, default=1.0, help="animator_duration_scale used")
    ap.add_argument("--F", type=float, default=1.5, help="PERSPECTIVE_DISTANCE_FACTOR")
    ap.add_argument("--handoff-grow", type=float, default=0.16, help="HANDOFF_GROW in the source")
    ap.add_argument("--contam-top", type=int,
                    help="screen y of the top of the card below the tapped one; measure the "
                         "tile only in the band above it (fixes the same-colour merge)")
    ap.add_argument("--stride", type=int, default=1, help="measure every Nth frame")
    ap.add_argument("--tmin", type=float, help="skip frames before this t_ms")
    ap.add_argument("--tmax", type=float, help="skip frames after this t_ms")
    ap.add_argument("--out", help="csv path (default <dir>/measure.csv)")
    ap.add_argument("--digits", type=int, default=4)
    args = ap.parse_args()

    def fnumd(v):
        """fixed-point string.  NB: the obvious '%.%df' % digits % v is a ValueError --
        Python parses '%.%' before it ever gets to the second %."""
        return format(v, ".%df" % args.digits)

    if args.detect_card:
        detect_card(args.detect_card, parse_rect(args.roi, "--roi") if args.roi else None,
                    show_all=args.show_all, border_tol=args.border_tol)
        return
    if args.detect_window:
        detect_window(args.detect_window)
        return
    if not args.dir and not args.frames_csv:
        die("need --dir or --frames-csv")

    d = args.dir or os.path.dirname(os.path.abspath(args.frames_csv))
    frames_csv = args.frames_csv or os.path.join(d, "frames.csv")
    if not os.path.exists(frames_csv):
        die("no frames.csv at %s" % frames_csv)

    base = detect_card(args.baseline, None, verbose=False,
                       border_tol=args.border_tol) if args.baseline and \
        os.path.exists(args.baseline) else None
    card_rect = parse_rect(args.card_rect, "--card-rect") if args.card_rect else None
    if card_rect is None and base:
        card_rect = tuple(base["rect"])
    card_color = parse_rgb(args.card_color, "--card-color") if args.card_color else None
    if card_color is None and base:
        card_color = tuple(base["card_color"])
    roi = parse_rect(args.roi, "--roi") if args.roi else None
    window_rect = parse_rect(args.window_rect, "--window-rect") if args.window_rect else None
    seed = parse_point(args.seed, "--seed") if args.seed else None
    if seed is None and card_rect:
        seed = (card_rect[0] + card_rect[2] // 2, card_rect[1] + card_rect[3] // 2)

    w0 = float(card_rect[2]) if card_rect else None
    h0 = float(card_rect[3]) if card_rect else None
    W_win = float(window_rect[2]) if window_rect else None
    H_win = float(window_rect[3]) if window_rect else None

    with open(frames_csv, "r", encoding="utf-8") as fh:
        raw = list(csv.DictReader(fh))
    if not raw:
        die("frames.csv has no rows")
    rows = []
    for i, r in enumerate(raw):
        if i % max(1, args.stride):
            continue
        t = float(r["t_ms"])
        if args.tmin is not None and t < args.tmin:
            continue
        if args.tmax is not None and t > args.tmax:
            continue
        rows.append(r)
    if not rows:
        die("no frames left after --stride/--tmin/--tmax")

    out_rows = []
    for r in rows:
        path = r["file"] if os.path.isabs(r["file"]) else os.path.join(d, r["file"])
        if not os.path.exists(path):
            die("missing frame %s" % path)
        m = measure_frame(path, roi, card_color, args.tol, args.min_area, args.close_iters,
                          seed, mode_luma=args.luma, contam_top=args.contam_top)
        m["frame"] = int(r["frame"])
        m["t_ms"] = round(float(r["t_ms"]), 1)
        try:
            w = float(m["card_width_px"])
        except (TypeError, ValueError):
            out_rows.append(m)
            continue
        hl = float(m["left_edge_height_px"]) if m["left_edge_height_px"] != "" else None
        hr = float(m["right_edge_height_px"]) if m["right_edge_height_px"] != "" else None
        if w0:
            m["scale_est"] = fnumd(w / w0)
            m["theta_naive_deg"] = "%.2f" % math.degrees(
                math.acos(max(-1.0, min(1.0, min(1.0, w / w0)))))
        if hl and h0:
            m["grow_frac_left"] = fnumd((hl - h0) / h0)
        # --- ratio -> theta (needs F only)
        if hl and hr and hr > 1.0 and hl > 1.0:
            r_ratio = max(hl, hr) / min(hl, hr)
            m["near_far_ratio"] = fnumd(r_ratio)
            s = 2.0 * args.F * (r_ratio - 1.0) / (r_ratio + 1.0)
            if s > 1.0:
                m["note"] = ((m["note"] + "; ") if m["note"] else "") + \
                    "ratio %.3f exceeds the max F=%.2f allows (2F/(F-1)=%.2f) -> F too small" % (
                        r_ratio, args.F, 1.0 + 2.0 * args.F / (args.F - 1.0) if args.F > 1 else 0.0)
                s = 1.0
            m["theta_ratio_deg"] = "%.2f" % math.degrees(math.asin(max(0.0, min(1.0, s))))
            m["theta_deg_est"] = m["theta_ratio_deg"]
            if hr > hl:
                m["note"] = ((m["note"] + "; ") if m["note"] else "") + \
                    "right edge taller than left (expected the other way round)"
        # --- growth from the near edge height
        if hl and h0 and H_win and H_win > h0:
            g = (hl - h0) / (H_win - h0)
            g = max(0.0, min(1.0, g))
            m["grow_g_est"] = fnumd(g)
            if w0 and W_win:
                w_rect = w0 + g * (W_win - w0)
                m["w_rect_pred_px"] = "%.1f" % w_rect
                if m["near_far_ratio"] and float(m["near_far_ratio"]) > 1.0:
                    far = 1.0 / float(m["near_far_ratio"])
                    cosT = 2.0 * w / (w_rect * (1.0 + far)) if w_rect > 0 else 2.0
                    cosT = max(0.0, min(1.0, cosT))
                    th_geom = math.degrees(math.acos(cosT))
                    m["theta_geom_deg"] = "%.2f" % th_geom
                    thv = math.radians(th_geom)
                    r_ratio = float(m["near_far_ratio"])
                    if r_ratio > 1.0005 and math.sin(thv) > 0.02:
                        F_est = 0.5 * math.sin(thv) * (r_ratio + 1.0) / (r_ratio - 1.0)
                        m["F_est"] = "%.3f" % F_est
                        if th_geom < 8 or th_geom > 86:
                            m["note"] = ((m["note"] + "; ") if m["note"] else "") + \
                                "F_est ill-conditioned near 0/90 deg"
        out_rows.append(m)

    # --- which theta estimator is meaningful for *this* build?
    # theta_ratio needs vertical perspective (the far edge shorter than the near one).  A
    # build that flips as a plain horizontal squeeze keeps both edge heights equal: measured
    # on device 2026-10-15, r stayed 1.0000 while the width went 1327 -> 45 px, and the
    # ratio-based fit then produced a nonsense T_est of 7515 ms for a 3000 ms flip.  In that
    # case acos(w/w0) *is* the rotation angle -- the height does not grow, so g = 0.
    ratios = [float(m["near_far_ratio"]) for m in out_rows
              if m["state"] == "card" and m["near_far_ratio"] not in ("", None)]
    spread = (max(ratios) - min(ratios)) if ratios else 0.0
    theta_source = ("ratio(near/far edge)" if spread > 0.02
                    else "width cos(theta)=w/w0 (no perspective signal)")
    if spread <= 0.02:
        for m in out_rows:
            if m["state"] == "card" and m["theta_naive_deg"] not in ("", None):
                m["theta_deg_est"] = m["theta_naive_deg"]
    for m in out_rows:
        if m["state"] == "card":
            m["theta_source"] = theta_source

    # --- handoff: first frame after a card frame that shows no card at all
    seen = False
    for i, m in enumerate(out_rows):
        if m["state"] == "card":
            seen = True
        elif seen and m["state"] == "no-card":
            m["is_handoff"] = 1
            prev = out_rows[i - 1] if i else None
            if prev is not None:
                m["note"] = ((m["note"] + "; ") if m["note"] else "") + \
                    "front face gone at this frame (theta=90 handoff), last card frame t=%.0f ms" \
                    % prev["t_ms"]
            break

    out_path = args.out or os.path.join(d, "measure.csv")
    cols = ["frame", "t_ms", "card_width_px", "left_edge_height_px", "right_edge_height_px",
            "near_far_ratio", "theta_deg_est", "theta_source", "scale_est", "is_handoff",
            "theta_ratio_deg", "theta_geom_deg", "theta_naive_deg", "F_est",
            "grow_frac_left", "grow_g_est", "w_rect_pred_px", "top_slope", "fit_rms_px",
            "fit_cols", "area_px", "bbox", "x_left_px", "x_right_px",
            "top_left_x_px", "top_left_y_px", "top_right_y_px", "top_rise_px", "band_y_max",
            "state", "note"]
    with open(out_path, "w", newline="", encoding="utf-8") as fh:
        wtr = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        wtr.writeheader()
        for m in out_rows:
            wtr.writerow(m)

    # ---------------------------------------------------------------- console summary
    cards = [m for m in out_rows if m["state"] == "card"]
    nocards = [m for m in out_rows if m["state"] == "no-card"]
    unmeas = [m for m in out_rows if m["state"] == "unmeasured"]
    print("frames measured : %d   card=%d  no-card=%d  unmeasured=%d"
          % (len(out_rows), len(cards), len(nocards), len(unmeas)))
    print("card rect       : %s   window rect: %s" % (card_rect, window_rect))
    print("tile colour      : %s   seed: %s" % (card_color, seed))
    print("theta source     : %s   ratio spread=%.4f over %d card frames"
          % (theta_source, spread, len(ratios)))
    if cards:
        print("front (card) frames: t=%.0f..%.0f ms  n=%d" % (cards[0]["t_ms"], cards[-1]["t_ms"], len(cards)))
    tl = [(float(m["t_ms"]), float(m["top_left_y_px"])) for m in cards if m["top_left_y_px"] != ""]
    if tl:
        ys = [y for _, y in tl]
        y0_rest = float(card_rect[1]) if card_rect else None
        print("tile top-left y   : min=%.0f max=%.0f first=%.0f last=%.0f  drift=%+.0f px over %d frames"
              % (min(ys), max(ys), ys[0], ys[-1], ys[-1] - ys[0], len(ys)))
        if y0_rest is not None:
            print("                   rest position would be %.0f px (card top x (1-g), g in [0,0.16] "
                  "-> %.0f..%.0f px); a frame frozen at the tap keeps it inside that band"
                  % (y0_rest, y0_rest * 0.84, y0_rest))
        print("                   y@t: %s" % " ".join("%.0f@%.0f" % (y, t) for t, y in tl))
    if nocards:
        print("no-card frames     : t=%.0f..%.0f ms  n=%d" % (nocards[0]["t_ms"], nocards[-1]["t_ms"], len(nocards)))
    ho = [m for m in out_rows if m["is_handoff"]]
    if ho:
        print("HANDOFF (front gone) at t=%.0f ms" % ho[0]["t_ms"])
    ratios = [float(m["near_far_ratio"]) for m in cards if m["near_far_ratio"] != ""]
    if ratios:
        rmax = max(ratios)
        F_from_rmax = 0.5 * (rmax + 1.0) / (rmax - 1.0) if rmax > 1.0 else float("inf")
        print("near/far ratio   : max=%.4f  -> F <= %.3f (upper bound; equals F only if a frame "
              "reached 90 deg)   [source F=%.2f]" % (rmax, F_from_rmax, args.F))
    fs = [float(m["F_est"]) for m in cards if m["F_est"] != ""
          and float(m["near_far_ratio"] or 0) > 1.02
          and 8.0 <= float(m["theta_geom_deg"] or 0) <= 80.0]
    if fs:
        print("F_est from pixels: median=%.3f  min=%.3f  max=%.3f  n=%d  [source F=%.2f]"
              % (float(np.median(fs)), min(fs), max(fs), len(fs), args.F))
    ths = [float(m["theta_deg_est"]) for m in cards if m["theta_deg_est"] != ""]
    if ths:
        print("theta timeline   : %s  max=%.1f deg over %d frames; first/last = %.1f/%.1f deg"
              % (theta_source, max(ths), len(ths), ths[0], ths[-1]))
    # --- easing/duration fit: theta(t) = 180 * FastOutSlowIn((t-t0)/T) for the front half
    # (theta = 90 deg exactly at p = HANDOFF = 0.5, so the front half spans ease 0 -> 0.5)
    ser = [(float(m["t_ms"]), float(m["theta_deg_est"])) for m in cards
           if m["theta_deg_est"] != "" and float(m["theta_deg_est"]) <= 88.0]
    if len(ser) >= 4:
        exp = args.flip_ms * args.slow
        best = None
        t_first = min(t for t, _ in ser)
        for T in np.arange(max(50.0, exp * 0.25), exp * 4.0 + 100.0, max(5.0, exp / 200.0)):
            for t0 in np.arange(t_first - 2.0 * T, t_first + 0.6 * T, max(2.0, T / 100.0)):
                err = 0.0
                for t, th in ser:
                    x = (t - t0) / T
                    if x < 0 or x > 1:
                        err += (th ** 2) * 4
                        continue
                    err += (180.0 * fast_out_slow_in(x) - th) ** 2
                if best is None or err < best[2]:
                    best = (T, t0, err)
        if best:
            T, t0, err = best
            rms = math.sqrt(err / len(ser))
            print("easing fit       : T_est=%.0f ms  t0_est=%.0f ms  rms=%.2f deg over %d frames"
                  % (T, t0, rms, len(ser)))
            print("                   expected FLIP_MS*slow = %.0f ms (FLIP_MS=%.0f x slow=%.2f) "
                  "-> ratio %.3f" % (exp, args.flip_ms, args.slow, T / exp if exp else 0.0))
    if unmeas:
        print("unmeasured frames: %s" % ", ".join(str(int(m["frame"])) for m in unmeas[:20]))
    print("wrote %s" % out_path)


if __name__ == "__main__":
    main()
