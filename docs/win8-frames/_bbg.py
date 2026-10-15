import cv2, numpy as np, glob, os
# The Win8.1 Start screen background is a distinct purple (24,1,83). Track ITS
# bounding box through the dismiss: that IS the Start-screen page rect.
# A rotateY would make this rect a TRAPEZOID (left/right edge heights differ);
# a scale+translate keeps it a RECTANGLE.
BG = np.array([24,1,83])
fs = sorted(glob.glob("docs/win8-frames-b/*.png"))
print(f"{'t(ms)':>8} {'bgpx':>8} {'x0':>5} {'x1':>5} {'y0':>5} {'y1':>5} {'w':>5} {'h':>5} {'aspect':>7}")
prev=None
for f in fs:
    t = int(os.path.basename(f)[2:9])
    if t < 197700 or t > 198000:  # the dismiss window
        continue
    a = cv2.imread(f)[:, :, ::-1].astype(np.int16)
    m = (np.abs(a - BG).sum(axis=2) < 24)
    n = int(m.sum())
    if n < 500:
        print(f"{t:>8} {n:>8}   (background gone)")
        continue
    ys, xs = np.nonzero(m)
    x0,x1,y0,y1 = xs.min(), xs.max(), ys.min(), ys.max()
    w,h = x1-x0+1, y1-y0+1
    # left-edge vs right-edge vertical extent => trapezoid test
    lcol = np.flatnonzero(m[:, x0+2]); rcol = np.flatnonzero(m[:, x1-2])
    lh = (lcol[-1]-lcol[0]+1) if lcol.size else 0
    rh = (rcol[-1]-rcol[0]+1) if rcol.size else 0
    print(f"{t:>8} {n:>8} {x0:>5} {x1:>5} {y0:>5} {y1:>5} {w:>5} {h:>5} {w/h:>7.3f}   "
          f"LedgeH={lh:>4} RedgeH={rh:>4} ratio={(max(lh,rh)/max(min(lh,rh),1)):.3f}")
