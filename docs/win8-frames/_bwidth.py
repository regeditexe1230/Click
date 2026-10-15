import cv2, numpy as np, glob, os
# Decisive rotateY test: a Y-axis rotation compresses the FAR side horizontally.
# So tile widths must form a monotonic gradient with x. Slope ~ 0 => no rotation.
def analyze(f):
    a = cv2.imread(f); a = cv2.cvtColor(a, cv2.COLOR_BGR2RGB)
    flat = a.reshape(-1,3)
    colors, counts = np.unique(flat, axis=0, return_counts=True)
    bg = colors[counts.argmax()]
    m = (np.abs(a.astype(np.int16)-bg).sum(axis=2) > 40).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3,3),np.uint8))
    n, lab, st, ce = cv2.connectedComponentsWithStats(m, 8)
    T=[]
    for i in range(1,n):
        x,y,w,h,area = st[i]
        if area < 1200 or w < 25 or h < 25: continue
        T.append((ce[i][0], w, h))
    return T
fs = sorted(glob.glob("docs/win8-frames-b/*.png"))
print(f"{'frame':>16} {'n':>4} {'wSlope':>9} {'R2':>6} {'w@15%':>7} {'w@85%':>7} {'ratio':>7}   verdict")
for f in fs:
    T = analyze(f)
    if len(T) < 4:
        print(f"{os.path.basename(f):>16} {len(T):>4}   (too few tiles)"); continue
    xs = np.array([t[0] for t in T]); ws = np.array([t[1] for t in T], float)
    sl, ic = np.polyfit(xs, ws, 1)
    pred = sl*xs+ic; ss = ((ws-ws.mean())**2).sum()
    r2 = 1 - ((ws-pred)**2).sum()/ss if ss > 0 else 0.0
    W = 1280
    wl = sl*0.15*W+ic; wr = sl*0.85*W+ic
    ok = min(wl,wr) > 0
    ratio = max(wl,wr)/min(wl,wr) if ok else float('nan')
    v = "NO rotation (<2%)" if ok and ratio < 1.02 else ("gradient!" if ok else "n/a")
    print(f"{os.path.basename(f):>16} {len(T):>4} {sl:>9.5f} {r2:>6.3f} {wl:>7.1f} {wr:>7.1f} {ratio:>7.4f}   {v}")
