import cv2, numpy as np, glob, os
fs = sorted(glob.glob("docs/win8-frames-b/*.png"))
prev=None; prevn=None
print(f"{'from -> to (ms)':>26} {'dt(ms)':>7} {'meanabs':>9} {'changed>30':>11}  note")
rows=[]
for f in fs:
    a = cv2.imread(f).astype(np.int16)
    t = int(os.path.basename(f)[2:9])
    if prev is not None:
        d = np.abs(a-prev).sum(axis=2)
        rows.append((prevn,t,t-prevn,float(d.mean()),int((d>30).sum())))
    prev, prevn = a, t
for p,t,dt,m,c in rows:
    note = "*** HARD CUT ***" if c>50000 else ("settling" if c>2000 else "static")
    print(f"{p:>10} -> {t:<10} {dt:>7} {m:>9.2f} {c:>11}  {note}")
tot=[r[4] for r in rows]
for i,r in enumerate(rows):
    if r[4]==max(tot): print(f"\npeak change: {r[1]} ms with {r[4]} changed px")
pre=[r[4] for r in rows if r[1]<=197700]
post=[r[4] for r in rows if r[1]>197700]
print(f"pre-197700  max changed px = {max(pre) if pre else 0}")
print(f"post-197700 max changed px = {max(post) if post else 0}")
