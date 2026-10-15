import cv2, numpy as np, glob, os
# Under perspective(P) rotateY(th) about a vertical axis, the horizontal top/bottom
# edges of the plane DO slant: proj_y = (y - y_origin) * scale(d(x)).
# So if a page rotation is in progress, y_min(x) and y_max(x) are slanted lines.
def envelope(f, thr=40):
    a = cv2.imread(f); a = cv2.cvtColor(a, cv2.COLOR_BGR2RGB)
    flat = a.reshape(-1,3)
    colors, counts = np.unique(flat, axis=0, return_counts=True)
    bg = colors[counts.argmax()]
    m = (np.abs(a.astype(np.int16)-bg).sum(axis=2) > thr)
    H, W = m.shape
    ymin = np.full(W, np.nan); ymax = np.full(W, np.nan)
    for x in range(W):
        idx = np.flatnonzero(m[:, x])
        if idx.size: ymin[x], ymax[x] = idx[0], idx[-1]
    return ymin, ymax, W, H

fs = sorted(glob.glob("docs/win8-frames-b/*.png"))
print(f"{'frame':>16} {'topSlope':>9} {'botSlope':>9} {'top@L':>7} {'top@R':>7} {'bot@L':>7} {'bot@R':>7}")
for f in fs:
    ymin, ymax, W, H = envelope(f)
    xs = np.arange(W)
    ok = ~np.isnan(ymin)
    # fit only the central 80% to avoid window chrome / margins
    sel = ok & (xs > 0.10*W) & (xs < 0.90*W)
    if sel.sum() < 50:
        print(f"{os.path.basename(f):>16}   (too few columns with content)"); continue
    ts = np.polyfit(xs[sel], ymin[sel], 1)[0]
    ok2 = ~np.isnan(ymax); sel2 = ok2 & (xs > 0.10*W) & (xs < 0.90*W)
    bs = np.polyfit(xs[sel2], ymax[sel2], 1)[0] if sel2.sum() > 50 else float('nan')
    L = int(0.15*W); R = int(0.85*W)
    tl = ymin[L] if not np.isnan(ymin[L]) else -1; tr = ymin[R] if not np.isnan(ymin[R]) else -1
    bl = ymax[L] if not np.isnan(ymax[L]) else -1; br = ymax[R] if not np.isnan(ymax[R]) else -1
    print(f"{os.path.basename(f):>16} {ts:>9.4f} {bs:>9.4f} {tl:>7.0f} {tr:>7.0f} {bl:>7.0f} {br:>7.0f}")
