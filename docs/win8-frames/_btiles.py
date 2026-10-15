import cv2, numpy as np, glob, os
fs = sorted(glob.glob("docs/win8-frames-b/*.png"))
def tiles(f):
    a = cv2.imread(f); a = cv2.cvtColor(a, cv2.COLOR_BGR2RGB)
    h, w, _ = a.shape
    flat = a.reshape(-1,3)
    colors, counts = np.unique(flat, axis=0, return_counts=True)
    bg = colors[counts.argmax()]
    mask = (np.abs(a.astype(np.int16)-bg).sum(axis=2) > 40).astype(np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3,3),np.uint8))
    n, lab, stats, cent = cv2.connectedComponentsWithStats(mask, 8)
    out=[]
    for i in range(1,n):
        x,y,ww,hh,area = stats[i]
        if area < 2500 or ww < 40 or hh < 40: continue
        out.append((x,y,ww,hh,area,cent[i][0]))
    return out, w, h
print(f"{'frame':>16} {'ntile':>5} {'leftH':>16} {'rightH':>16} {'ratio':>7} {'note'}")
for f in fs:
    ts, W, H = tiles(f)
    L=[t for t in ts if t[5] < W/2]; R=[t for t in ts if t[5] >= W/2]
    if not L or not R:
        print(f"{os.path.basename(f):>16} {len(ts):>5} {'-':>16} {'-':>16} {'-':>7}  no split")
        continue
    lh = np.median([t[3] for t in L]); rh = np.median([t[3] for t in R])
    lo = min(lh,rh); hi=max(lh,rh)
    print(f"{os.path.basename(f):>16} {len(ts):>5} {lh:>16.1f} {rh:>16.1f} {hi/lo:>7.4f}  L={len(L)} R={len(R)}")
