import cv2, numpy as np, glob, os
# Coarse ASCII render so a human (or a text model) can see the layout.
PAL = " .:-=+*#%@"
def render(f, CW=96, CH=30):
    a = cv2.imread(f); a = cv2.cvtColor(a, cv2.COLOR_BGR2RGB)
    sm = cv2.resize(a, (CW, CH), interpolation=cv2.INTER_AREA)
    lum = sm.mean(axis=2)
    lo, hi = lum.min(), lum.max()
    q = ((lum-lo)/max(hi-lo,1) * (len(PAL)-1)).astype(int)
    print(f"--- {os.path.basename(f)}  lum {lo:.0f}..{hi:.0f} ---")
    for r in q:
        print("   " + "".join(PAL[v] for v in r))
for name in ["p_0196300.png","p_0197700.png","p_0197733.png","p_0197800.png","p_0197933.png","p_0198300.png","p_0199600.png"]:
    p = os.path.join("docs/win8-frames-b", name)
    if os.path.exists(p): render(p)
