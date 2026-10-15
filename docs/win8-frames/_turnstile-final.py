import math
def bez(p1,p2,p3,p4,x,it=60):
    lo,hi=0.0,1.0
    for _ in range(it):
        t=(lo+hi)/2
        xt=3*(1-t)**2*t*p1 + 3*(1-t)*t*t*p3 + t**3
        if xt<x: lo=t
        else: hi=t
    t=(lo+hi)/2
    return 3*(1-t)**2*t*p2 + 3*(1-t)*t*t*p4 + t**3
# transform-origin is at x = -axis (outside the LEFT edge), so for a point at
# screen x, d = x - (-axis) = x + axis.  d_left = axis, d_right = W + axis.
def sc(P,d,th):
    den = P + d*math.sin(math.radians(th))
    return None if den<=0 else P/den
def row(P,W,ax,th):
    l=sc(P,ax,th); r=sc(P,W+ax,th)
    if l is None or r is None: return None
    return l,r,max(l,r)/min(l,r)
E=[0.01,0.975,0.4775,0.9775]; X=[0.4925,0.01,0.7675,-0.01]
print("=== sanity: W=480 P=600 th=80 -> lead expects 0.9384 / 0.5395 / 1.74 ===")
l,r,rt=row(600,480,40,80); print(f"  left {l:.5f}  right {r:.5f}  ratio {rt:.5f}")
print("=== sanity: W=1440 P=1800 th=80 -> lead expects 0.9786 / 0.5526 / 1.77 ===")
l,r,rt=row(1800,1440,40,80); print(f"  left {l:.5f}  right {r:.5f}  ratio {rt:.5f}")
print("\n=== CORRECTED 10.3 entry: 300ms, 80->0, W=480 P=600 ===")
print(f"{'t':>5} {'eased':>7} {'theta':>8} {'left':>8} {'right':>8} {'near/far':>9}")
for t in (0,0.10,0.25,0.50,0.75,0.90,1.0):
    e=bez(*E,t); th=80*(1-e); l,r,rt=row(600,480,40,th)
    print(f"{t*100:>4.0f}% {e:>7.4f} {th:>7.2f}° {l:>8.4f} {r:>8.4f} {rt:>9.4f}")
print("\n=== CORRECTED 10.3 exit: 128ms, 0->-50, W=480 P=600 ===")
print(f"{'t':>5} {'eased':>7} {'theta':>8} {'left':>8} {'right':>8} {'near/far':>9}")
for t in (0,0.25,0.50,0.70,0.90,1.0):
    e=bez(*X,t); th=-50*e; l,r,rt=row(600,480,40,th)
    print(f"{t*100:>4.0f}% {e:>7.4f} {th:>7.2f}° {l:>8.4f} {r:>8.4f} {rt:>9.4f}")
print("\n=== theta=80° near/far vs W (P=1.25W) ===")
for W in (320,480,720,1024,1366,1440,1920):
    l,r,rt=row(1.25*W,W,40,80); print(f"  W={W:>5} P={1.25*W:>6.0f} -> {rt:.5f}")
print("\n=== camera-plane failure: max safe W = P/|sin th| - axis ===")
for P,th,ax in ((600,-50,40),(600,80,40),(800,80,40)):
    print(f"  P={P} th={th}° -> W_max = {P/abs(math.sin(math.radians(th)))-ax:.1f} px")
print("  W=480 never fails; the old §10.3 'model failure' was purely the W=1366 assumption.")
