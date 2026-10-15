import cv2, numpy as np, glob, os
# Confound removed: the Win8 Start screen mixes 1x1, 2x2, 4x2 tiles, so a width-vs-x
# regression over ALL tiles is meaningless. Compare only IDENTICAL tiles (same h) instead.
def tiles(f, thr=40):
    a = cv2.imread(f); a = cv2.cvtColor(a, cv2.COLOR_BGR2RGB)
    flat = a.reshape(-1,3)
    colors, counts = np.unique(flat, axis=0, return_counts=True)
    bg = colors[counts.argmax()]
    m = (np.abs(a.astype(np.int16)-bg).sum(axis=2) > thr).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3,3),np.uint8))
    n, lab, st, ce = cv2.connectedComponentsWithStats(m, 8)
    return [(st[i][0], st[i][1], st[i][2], st[i][3], ce[i][0]) for i in range(1,n)
            if st[i][4] >= 1200 and st[i][2] >= 25 and st[i][3] >= 25]

fs = sorted(glob.glob("docs/win8-frames-b/*.png"))[:12]   # pre-cut + the cut itself
for f in fs:
    T = tiles(f)
    # cluster by height -> same tile row-type
    hs = sorted(set(t[3] for t in T))
    groups = {}
    for t in T:
        for h in hs:
            if abs(t[3]-h) <= 2: groups.setdefault(h, []).append(t); break
    best = max(groups.items(), key=lambda kv: len(kv[1]))
    h, G = best
    if len(G) < 3:
        print(f"{os.path.basename(f):>16}  h={h} only {len(G)} same-size tiles"); continue
    xs = np.array([g[4] for g in G], float); ws = np.array([g[2] for g in G], float)
    sl, ic = np.polyfit(xs, ws, 1)
    pred = sl*xs+ic; ss = ((ws-ws.mean())**2).sum()
    r2 = 1-((ws-pred)**2).sum()/ss if ss>0 else 0
    print(f"{os.path.basename(f):>16}  n={len(G):>2} h={h:>3}  width {ws.min():.0f}..{ws.max():.0f} "
          f"(spread {ws.max()-ws.min():.0f}px)  slope={sl:>8.5f}  R2={r2:.3f}  "
          f"{'FLAT -> no rotation' if abs(ws.max()-ws.min())<=2 else 'varies'}")
