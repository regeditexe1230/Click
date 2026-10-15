#!/usr/bin/env python3
"""Measure the WinJS `turnstile` page transition (Program list <-> Node graph).

Sibling of `measure_flip.py`; same frames.csv contract (`frame,t_ms,file`), same
"never interpolate -- say unmeasured" rule.  `measure_flip.py --page-mode` calls
into here, so the tile model and the page model stay separable.

What it measures, per frame:

  * every large *page surface* blob (default colour = the app surface grey
    (39,42,47), which is both the list card and the graph toolbar): its bbox,
    the robust fits of its top AND bottom boundary across the middle
    (1 - 2*band) columns, the extrapolated left/right edge heights
    (h_left, h_right), the height ratio and the implied rotateY angle.

    A pure rotateY about a vertical axis projects a page into a trapezoid that is
    symmetric about its own centre line, so the top boundary slopes down by
    `rise` while the bottom slopes up by `rise`, and

        h_right = h_left - 2*rise          (rise = y_top(x_right) - y_top(x_left))

    Rotating about an axis `axis_x` (WinJS: 40dp OUTSIDE the left screen edge;
    this device is 3.0 px/dp, so axis_x = -120 px) with CSS perspective P
    (in px, same units as x):

        scale(u)   = P / (P + u*sin(theta))        u = distance from the axis
        h(u1)/h(u2) = (P + u2*sin) / (P + u1*sin)

    which inverts to

        sin(theta) = P*(r - 1) / (u_far - r*u_near),  r = h_near/h_far

  * status-band leakage (the user-reported bug): the topmost y of any page
    surface, plus the surface pixel count inside y < status_h.
  * static-zone motion: how many pixels changed inside the top-bar zone and the
    bottom-bar zone -- the bars must NOT animate any more, so both must stay ~0
    while the pages turn.

Geometry flags are explicit so the same recording can be scored against any
candidate model (P = 1.25*screen width, P = 1.667*screen width, ...).
"""
import argparse
import csv
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage


def die(msg):
    sys.stderr.write("error: %s\n" % msg)
    sys.exit(2)


def load_rgb(path):
    return np.asarray(Image.open(path).convert("RGB"))


def color_mask(img, color, tol):
    d = np.abs(img.astype(np.int16) - np.asarray(color, dtype=np.int16)[None, None, :])
    return d.max(axis=2) <= tol


def fit_line(xs, ys, iters=2, min_pts=8, k=2.5):
    """Robust 2-pass straight-line fit; returns (slope, intercept, rms) or None."""
    xs = np.asarray(xs, dtype=float)
    ys = np.asarray(ys, dtype=float)
    keep = np.ones(len(xs), dtype=bool)
    if len(xs) < min_pts:
        return None
    for _ in range(iters):
        if keep.sum() < min_pts:
            return None
        a, b = np.polyfit(xs[keep], ys[keep], 1)
        res = ys - (a * xs + b)
        s = float(res[keep].std())
        if s <= 1e-6:
            s = 1e-6
        keep = np.abs(res) < k * s
    if keep.sum() < min_pts:
        return None
    a, b = np.polyfit(xs[keep], ys[keep], 1)
    rms = float((ys[keep] - (a * xs[keep] + b)).std())
    return a, b, rms


def blob_geometry(sub, mask, band=0.15, min_span=40):
    """Left/right edge heights of one trapezoid, from robust fits of both
    boundaries over the middle (1-2*band) columns."""
    comp = mask
    any_col = comp.any(axis=0)
    xs = np.flatnonzero(any_col)
    if len(xs) < min_span:
        return None
    x0, x1 = int(xs[0]), int(xs[-1])
    H = comp.shape[0]
    rows = np.arange(H, dtype=np.int32)[:, None]
    top = np.where(comp, rows, np.int32(H)).min(axis=0)
    bot = np.where(comp, rows, np.int32(-1)).max(axis=0)
    cols = np.arange(x0, x1 + 1)
    ok = (top[cols] < H) & (bot[cols] >= 0) & any_col[cols]
    cols = cols[ok]
    if len(cols) < min_span:
        return None
    lo = x0 + band * (x1 - x0)
    hi = x1 - band * (x1 - x0)
    mid = cols[(cols >= lo) & (cols <= hi)]
    if len(mid) < 8:
        return None
    ft = fit_line(mid, top[mid])
    fb = fit_line(mid, bot[mid])
    if ft is None or fb is None:
        return None
    at, bt, rt = ft
    ab, bb, rb = fb
    y_tl, y_tr = at * x0 + bt, at * x1 + bt
    y_bl, y_br = ab * x0 + bb, ab * x1 + bb
    h_l = float(y_bl - y_tl)
    h_r = float(y_br - y_tr)
    rise = float(y_tr - y_tl)
    ratio = h_l / h_r if h_r > 1 else float("nan")
    return {"x_left": x0, "x_right": x1, "y_tl": float(y_tl), "y_tr": float(y_tr),
            "y_bl": float(y_bl), "y_br": float(y_br), "rise": rise,
            "h_left": h_l, "h_right": h_r, "ratio": ratio,
            "top_slope": float(at), "rms_top": rt, "rms_bot": rb,
            "span": x1 - x0 + 1}


def theta_from_ratio(r, axis_x, x_near, x_far, P):
    """sin(theta) from the near/far edge-height ratio of a rotating page."""
    if not np.isfinite(r) or r <= 1.0:
        return None
    u_near = x_near - axis_x
    u_far = x_far - axis_x
    num = P * (r - 1.0)
    den = u_far - r * u_near
    if abs(den) < 1e-9:
        return None
    s = num / den
    if not (-1.0 <= s <= 1.0):
        return None
    return float(np.degrees(np.arcsin(s)))


def ratio_model(theta_deg, axis_x, x_near, x_far, P):
    s = np.sin(np.radians(theta_deg))
    return (P + (x_far - axis_x) * s) / (P + (x_near - axis_x) * s)


def measure_frame(img, args, prev):
    H, W = img.shape[:2]
    m = color_mask(img, args.color, args.tol)
    m[:args.top_min, :] = False                      # never count the status band
    m[H - args.nav_h:, :] = False                    # never count the nav bar band
    lab, n = ndimage.label(m)
    parts = []
    if n:
        sizes = ndimage.sum(m, lab, range(1, n + 1))
        order = np.argsort(-sizes)
        for k in order:
            if sizes[k] < args.min_area:
                break                     # sizes are sorted: everything after is smaller
            comp = lab == (k + 1)
            g = blob_geometry(img, comp, band=args.band)
            if g is None:
                continue
            g["area"] = int(sizes[k])
            g["_mask"] = comp
            parts.append(g)
            if len(parts) >= args.components:
                break
    out = {"t_ms": 0.0}
    for i, g in enumerate(parts, 1):
        th = theta_from_ratio(g["ratio"], args.axis_x, g["x_left"], g["x_right"],
                              args.persp_px)
        out.update({
            "c%d_area" % i: g["area"],
            "c%d_bbox" % i: "%d,%d,%d,%d" % (g["x_left"], int(g["y_tl"]),
                                             g["x_right"], int(g["y_br"])),
            "c%d_h_left" % i: round(g["h_left"], 1),
            "c%d_h_right" % i: round(g["h_right"], 1),
            "c%d_rise" % i: round(g["rise"], 2),
            "c%d_ratio" % i: round(g["ratio"], 4) if np.isfinite(g["ratio"]) else "",
            "c%d_theta" % i: round(th, 2) if th is not None else "",
            "c%d_slope" % i: round(g["top_slope"], 4),
            "c%d_rms" % i: round(max(g["rms_top"], g["rms_bot"]), 2),
        })
    for i in range(len(parts) + 1, args.components + 1):
        pass
    # status-bar leakage: the topmost surface pixel and how many inside the band
    full = color_mask(img, args.color, args.tol)
    ys, xs = np.where(full)
    out["sb_min_y"] = int(ys.min()) if len(ys) else ""
    out["sb_px"] = int((ys < args.status_h).sum()) if len(ys) else 0
    # graph-only signature: the canvas dot grid is (60,63,70) in this theme and
    # does not exist on the program list, so its topmost row IS the "does graph
    # content leak into the status bar" answer (the user-reported bug).
    gc = np.asarray(args.graph_color)
    gd = np.abs(img.astype(np.int16) - gc[None, None, :]).max(axis=2) <= args.graph_tol
    gy, gx = np.where(gd)
    out["gc_px"] = int(len(gy))
    out["gc_min_y"] = int(gy.min()) if len(gy) else ""
    out["gc_sb_px"] = int((gy < args.status_h).sum()) if len(gy) else 0
    # static-zone motion (bars must be still)
    if prev is None:
        out["chg_px"] = 0
        out["chg_bbox"] = ""
        out["chg_top_zone"] = 0
        out["chg_bot_zone"] = 0
    else:
        ch = np.abs(img.astype(np.int16) - prev.astype(np.int16)).max(axis=2) > args.chg_tol
        out["chg_px"] = int(ch.sum())
        if ch.any():
            cy, cx = np.where(ch)
            out["chg_bbox"] = "%d,%d,%d,%d" % (cx.min(), cy.min(), cx.max(), cy.max())
        else:
            out["chg_bbox"] = ""
        out["chg_top_zone"] = int(ch[args.status_h:args.status_h + args.bar_zone, :].sum())
        out["chg_bot_zone"] = int(ch[H - args.nav_h - args.bar_zone:H - args.nav_h, :].sum())
    out["n_blobs"] = len(parts)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir")
    ap.add_argument("--frames-csv")
    ap.add_argument("--out", help="csv path (default <dir>/pages.csv)")
    ap.add_argument("--stride", type=int, default=1)
    ap.add_argument("--tmin", type=float)
    ap.add_argument("--tmax", type=float)
    ap.add_argument("--color", default="39,42,47",
                    help="r,g,b of the page surface (default: app surface grey)")
    ap.add_argument("--tol", type=int, default=8)
    ap.add_argument("--graph-color", default="60,63,70",
                    help="r,g,b of a graph-only pixel signature (the canvas dot grid); "
                         "used for the 'no graph content in the status bar' criterion")
    ap.add_argument("--graph-tol", type=int, default=0,
                    help="0 is calibrated: at tol 0 the settled list page has 134 such pixels "
                         "(0 in the status band) while the settled graph page has 127452 "
                         "(18 in-band).  tol 6 is too loose -- the list page then shows 379.")
    ap.add_argument("--min-area", type=int, default=20000)
    ap.add_argument("--components", type=int, default=2,
                    help="how many page surfaces to track at once (list + graph = 2)")
    ap.add_argument("--band", type=float, default=0.15,
                    help="fraction trimmed at each end before fitting (rounded corners)")
    ap.add_argument("--top-min", type=int, default=120,
                    help="ignore rows above this y (status band + toolbar chrome)")
    ap.add_argument("--status-h", type=int, default=110)
    ap.add_argument("--nav-h", type=int, default=90)
    ap.add_argument("--bar-zone", type=int, default=200,
                    help="height of the top/bottom bar zones watched for motion")
    ap.add_argument("--chg-tol", type=int, default=8)
    ap.add_argument("--screen", default="1440,3200", help="w,h of the screen")
    ap.add_argument("--axis-x", type=float, default=-120.0,
                    help="rotation axis x in screen px (WinJS turnstile: -40dp; "
                         "this device 3.0 px/dp -> -120). model @80deg must read 1.7393")
    ap.add_argument("--persp-f", type=float, default=1.25,
                    help="P = persp_f * screen width (official 600px/480px viewport)")
    ap.add_argument("--persp-px", type=float,
                    help="explicit P in px (overrides --persp-f)")
    ap.add_argument("--digits", type=int, default=2)
    args = ap.parse_args(argv)

    if not args.dir and not args.frames_csv:
        die("need --dir or --frames-csv")
    d = args.dir or os.path.dirname(os.path.abspath(args.frames_csv))
    frames_csv = args.frames_csv or os.path.join(d, "frames.csv")
    if not os.path.exists(frames_csv):
        die("no frames.csv at %s" % frames_csv)
    args.color = tuple(int(v) for v in args.color.split(","))
    args.graph_color = tuple(int(v) for v in args.graph_color.split(","))
    W, H = (int(v) for v in args.screen.split(","))
    args.persp_px = args.persp_px if args.persp_px else args.persp_f * W

    raw = list(csv.DictReader(open(frames_csv, encoding="utf-8-sig")))
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

    cols = ["frame", "t_ms", "n_blobs"]
    for i in (1, 2):
        cols += ["c%d_area" % i, "c%d_bbox" % i, "c%d_h_left" % i, "c%d_h_right" % i,
                 "c%d_rise" % i, "c%d_ratio" % i, "c%d_theta" % i, "c%d_slope" % i,
                 "c%d_rms" % i]
    cols += ["chg_px", "chg_bbox", "chg_top_zone", "chg_bot_zone", "sb_min_y", "sb_px",
             "gc_px", "gc_min_y", "gc_sb_px"]
    cols = [c for c in cols if not c.endswith(("1_area", "1_bbox", "1_h_left", "1_h_right",
                                               "1_rise", "1_ratio", "1_theta", "1_slope",
                                               "1_rms")) or True]

    out_rows = []
    prev = None
    for r in rows:
        path = r["file"] if os.path.isabs(r["file"]) else os.path.join(d, r["file"])
        if not os.path.exists(path):
            die("missing frame %s" % path)
        img = load_rgb(path)
        m = measure_frame(img, args, prev)
        m["frame"] = int(r["frame"])
        m["t_ms"] = round(float(r["t_ms"]), 1)
        out_rows.append(m)
        prev = img

    out_path = args.out or os.path.join(d, "pages.csv")
    with open(out_path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for m in out_rows:
            w.writerow(m)

    # ---------------- summary ----------------
    n = args.digits
    def f(v):
        return format(v, ".%df" % n)

    print("frames          : %d  (%s)" % (len(out_rows), frames_csv))
    print("page surface    : %s +-%d   P=%.0f px (%.3f x width)  axis_x=%.0f"
          % (str(args.color), args.tol, args.persp_px, args.persp_px / W, args.axis_x))
    moving = [m for m in out_rows if m["chg_px"] > args.min_area // 20]
    if moving:
        print("first motion    : t=%s ms   last motion: t=%s ms"
              % (f(moving[0]["t_ms"]), f(moving[-1]["t_ms"])))
    both = [m for m in out_rows
            if m.get("c1_rise") not in (None, "") and m.get("c2_rise") not in (None, "")
            and abs(float(m["c1_rise"])) > 2 and abs(float(m["c2_rise"])) > 2]
    if both:
        print("both pages turning: t=%s..%s ms  n=%d"
              % (f(both[0]["t_ms"]), f(both[-1]["t_ms"]), len(both)))
    else:
        print("both pages turning: NONE (only one surface showed a sloped boundary)")
    th = [(float(m["t_ms"]), float(m["c1_theta"])) for m in out_rows
          if m.get("c1_theta") not in (None, "")]
    if th:
        print("theta timeline  : c1 max=%s deg (%d frames)  first/last=%s/%s"
              % (f(max(v for _, v in th)), len(th), f(th[0][1]), f(th[-1][1])))
    rr = [(float(m["t_ms"]), float(m["c1_ratio"])) for m in out_rows
          if m.get("c1_ratio") not in (None, "")]
    if rr:
        print("c1 height ratio : max=%s at t=%s ms  (model @80deg = %s, @0deg = 1.000)"
              % (f(max(v for _, v in rr)),
                 f(max(rr, key=lambda kv: kv[1])[0]),
                 f(ratio_model(80.0, args.axis_x, 0.0, W, args.persp_px))))
    bad = [m for m in out_rows if m["sb_px"] and int(m["sb_px"]) > 0
           and m["sb_min_y"] != "" and int(m["sb_min_y"]) < args.status_h]
    gys = [int(m["gc_min_y"]) for m in out_rows if m["gc_min_y"] != ""]
    gsb = [int(m["gc_sb_px"]) for m in out_rows if m["gc_sb_px"] != ""]
    print("graph sig in bar: %d/%d frames have dot-grid pixels inside y<%d "
          "(graph topmost row min=%s; worst in-band count=%s)"
          % (sum(1 for v in gsb if v > 0), len(out_rows), args.status_h,
             min(gys) if gys else "-", max(gsb) if gsb else "-"))
    print("page-surface leak: %d/%d frames have page surface inside the status band (min y=%s)"
          % (len(bad), len(out_rows),
             min((int(m["sb_min_y"]) for m in out_rows if m["sb_min_y"] != ""), default="-")))
    tz = [int(m["chg_top_zone"]) for m in out_rows]
    bz = [int(m["chg_bot_zone"]) for m in out_rows]
    print("static zones    : top-bar max change=%d px, bottom-bar max change=%d px "
          "(bars should be still)" % (max(tz), max(bz)))
    print("wrote %s" % out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
