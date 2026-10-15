#!/usr/bin/env python3
"""Track the node-graph page during a turnstile transition by its DOT-GRID signature.

Why this exists: the generic page-surface tracker (measure_pages.py) keys on the card
colour (39,42,47)+-8, which the graph page does not have (its toolbar spans the full
width and is excluded by the border guard; its canvas is (17,18,23)).  The dot grid
(60,63,70) is graph-page-only at tol 0: 0 px on a settled list page, ~127k px on a
settled graph page.

Per frame it reports, for the dot-grid blob:
  x_left/x_right/y_top/y_bot  bbox
  h_left/h_right              vertical dot extent in the outermost 15% column bands
  ratio                       h_left/h_right   (turnstile: near edge = left, expect 1.7393 at 80deg)
  ratio_span                  the same but using the WHOLE blob width (no edge bands)
  y_top_left/y_top_right      top boundary at the two ends -> a slanted top edge proves a rotation
  slant_px                    y_top_right - y_top_left
  width_px                    projected width
  theta_deg                   angle solved from width_px under the pivot/projective model
  state                       graph / no-graph / too-few-dots
"""
import argparse, csv, math, os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import measure_flip as mf  # load_rgb

SCREEN_W, SCREEN_H = 1440, 3200


def projected_width(theta_deg, origin_x=-120.0, page_w=1560.0, persp=1800.0):
    """CSS perspective(P) rotateY(theta) about a vertical axis at origin_x, page 1560px wide."""
    t = math.radians(theta_deg)
    u_far = page_w
    s_far = persp / (persp + u_far * math.sin(t))
    return u_far * math.cos(t) * s_far


def theta_from_width(width_px, origin_x=-120.0, page_w=1560.0, persp=1800.0):
    best, best_err = None, None
    for i in range(0, 901):
        th = i / 10.0
        err = abs(projected_width(th, origin_x, page_w, persp) - width_px)
        if best_err is None or err < best_err:
            best, best_err = th, err
    return best


def band_extent(ys, xs, x0, x1):
    sel = (xs >= x0) & (xs <= x1)
    if sel.sum() < 4:
        return None
    yy = ys[sel]
    return int(yy.min()), int(yy.max())


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--frames-csv", default=None)
    ap.add_argument("--dot-color", default="60,63,70")
    ap.add_argument("--tol", type=int, default=0)
    ap.add_argument("--status-h", type=int, default=110)
    ap.add_argument("--edge-frac", type=float, default=0.15)
    ap.add_argument("--edge-px", type=int, default=40,
                    help="fixed-width edge band in px (do NOT use a %% of a compressed width)")
    ap.add_argument("--min-cols", type=int, default=2,
                    help="minimum distinct dot columns inside a band for its ratio to count")
    ap.add_argument("--min-px", type=int, default=200)
    ap.add_argument("--tmax", type=float, default=1e9)
    ap.add_argument("--persp", type=float, default=1800.0)
    ap.add_argument("--origin-x", type=float, default=-120.0)
    ap.add_argument("--out", default=None)
    a = ap.parse_args(argv)

    d = a.dir
    fc = a.frames_csv or os.path.join(d, "frames.csv")
    dot = np.array([int(v) for v in a.dot_color.split(",")], dtype=np.int16)
    rows = list(csv.DictReader(open(fc, encoding="utf-8-sig")))
    out = a.out or os.path.join(d, "dots.csv")
    cols = ["frame", "t_ms", "state", "dots_px", "x_left", "x_right", "y_top", "y_bot",
            "width_px", "h_left", "h_right", "ratio", "ratio_span", "y_top_left", "y_top_right",
            "slant_px", "theta_deg", "sb_px", "file"]
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            t = float(r["t_ms"])
            if t > a.tmax:
                continue
            img = mf.load_rgb(os.path.join(d, r["file"]))
            m = np.abs(img.astype(np.int16) - dot).max(axis=2) <= a.tol
            n = int(m.sum())
            rec = {"frame": r["frame"], "t_ms": t, "file": r["file"], "dots_px": n}
            if n < a.min_px:
                rec["state"] = "no-graph"
                w.writerow(rec)
                continue
            ys, xs = np.where(m)
            x0, x1 = int(xs.min()), int(xs.max())
            rec.update(x_left=x0, x_right=x1, y_top=int(ys.min()), y_bot=int(ys.max()),
                       width_px=x1 - x0 + 1, sb_px=int((ys < a.status_h).sum()),
                       state="graph")
            # Fixed-pixel edge bands.  A percentage of a COMPRESSED page width lands
            # inside the page, where the slanted top edge keeps rising -> h_right is
            # over-read and the ratio can exceed the model's 90deg ceiling (1.8667).
            # A band is only usable when it really contains several dot columns.
            band = a.edge_px

            def ext(lo, hi):
                sel = (xs >= lo) & (xs <= hi)
                if sel.sum() < 4 or len(np.unique(xs[sel])) < a.min_cols:
                    return None
                return int(ys[sel].min()), int(ys[sel].max())

            L = ext(x0, x0 + band)
            R = ext(x1 - band, x1)
            S = ext(x0, x1)
            if S:
                rec["ratio_span"] = round((S[1] - S[0]) / max(1, (S[1] - S[0])), 4)
            if L and R:
                hl, hr = L[1] - L[0], R[1] - R[0]
                rec.update(h_left=hl, h_right=hr, ratio=round(hl / max(1, hr), 4),
                           y_top_left=L[0], y_top_right=R[0], slant_px=R[0] - L[0],
                           ratio_span=round(hl / max(1, hr), 4))
            if rec.get("width_px"):
                rec["theta_deg"] = round(theta_from_width(rec["width_px"], a.origin_x,
                                                          1560.0, a.persp), 1)
            w.writerow(rec)
        # summary
    got = [r for r in csv.DictReader(open(out, encoding="utf-8-sig"))]
    g = [r for r in got if r["state"] == "graph"]
    print("frames        : %d   graph=%d  no-graph=%d" % (len(got), len(g), len(got) - len(g)))
    if g:
        first = g[0]
        print("first graph   : frame %s t=%sms  width=%spx theta=%s deg  ratio=%s  slant=%spx"
              % (first["frame"], first["t_ms"], first["width_px"], first["theta_deg"],
                 first.get("ratio"), first.get("slant_px")))
        wmax = max(float(r["width_px"]) for r in g)
        spans = [r for r in g if float(r["width_px"]) > 0.97 * SCREEN_W]
        print("full-width    : %d/%d graph frames span >97%% of the screen width (no rotation)"
              % (len(spans), len(g)))
        rr = [float(r["ratio"]) for r in g if r.get("ratio")]
        if rr:
            print("ratio         : first=%s max=%.4f  (turnstile @80deg expects 1.7393, no-rotation 1.0)"
                  % (rr[0], max(rr)))
        print("status leak   : %d/%d graph frames have dots inside y<%d (worst=%d px)"
              % (sum(1 for r in g if int(r.get("sb_px") or 0) > 0), len(g), a.status_h,
                 max(int(r.get("sb_px") or 0) for r in g)))
    print("wrote %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
