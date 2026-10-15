import cv2, numpy as np, glob, os
BG = np.array([24,1,83])
def comps(f):
    a = cv2.imread(f)[:, :, ::-1].astype(np.int16)
    m = (np.abs(a-BG).sum(axis=2) > 40).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((3,3),np.uint8))
    n, lab, st, ce = cv2.connectedComponentsWithStats(m, 8)
    return [(st[i][0],st[i][1],st[i][2],st[i][3],st[i][4],ce[i][0],ce[i][1]) for i in range(1,n)
            if st[i][4]>=1200 and st[i][2]>=25 and st[i][3]>=25]
print("=== all components, 197700 -> 197800 ===")
for nm in ["p_0197700.png","p_0197733.png","p_0197767.png","p_0197800.png","p_0197833.png"]:
    f = os.path.join("docs/win8-frames-b", nm); C = comps(f)
    hs = np.array([c[3] for c in C])
    print(f"\n{nm}  n={len(C)}  h values: {sorted(set(hs.tolist()))[:10]}")
    for c in sorted(C, key=lambda c: (c[1], c[0]))[:8]:
        print(f"    x={c[0]:>4} y={c[1]:>4} w={c[2]:>4} h={c[3]:>4} area={c[4]:>7}")
    if len(C):
        ys = np.array([c[6] for c in C]); xs = np.array([c[5] for c in C])
        print(f"    centroid: x={xs.mean():.1f} y={ys.mean():.1f}   bbox y {ys.min():.0f}..{ys.max():.0f}")
