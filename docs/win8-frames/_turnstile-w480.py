import math
def bez(p1,p2,p3,p4,x,it=60):
    # cubic-bezier(p1,p2,p3,p4) as y(x): solve for t by bisection on x(t)
    lo,hi=0.0,1.0
    for _ in range(it):
        t=(lo+hi)/2
        xt=3*(1-t)**2*t*p1 + 3*(1-t)*t*t*p3 + t**3
        if xt<x: lo=t
        else: hi=t
    t=(lo+hi)/2
    return 3*(1-t)**2*t*p2 + 3*(1-t)*t*t*p4 + t**3
def sc(P,d,th):
    z=-d*math.sin(math.radians(th)); den=P-z
    return None if den<=0 else P/den
def row(P,W,axis,th):
    l=sc(P,0-axis,th); r=sc(P,W-axis,th)
    if l is None or r is None: return None
    lo,hi=min(l,r),max(l,r)
    return l,r,hi/lo
E=[0.01,0.975,0.4775,0.9775]; X=[0.4925,0.01,0.7675,-0.01]
print("=== 进场 turnstileForwardIn: 300ms, 80 -> 0, W=480 P=600 axis=-40 ===")
print(f"{'t':>5} {'eased':>7} {'theta':>8} {'left':>8} {'right':>8} {'ratio':>7}")
for t in (0,0.10,0.25,0.50,0.75,0.90,1.0):
    e=bez(*E,t); th=80*(1-e); l,r,rt=row(600,480,40,th)
    print(f"{t*100:>4.0f}% {e:>7.4f} {th:>7.2f}° {l:>8.4f} {r:>8.4f} {rt:>7.4f}")
print("\n=== 退场 turnstileForwardOut: 128ms, 0 -> -50 ===")
print(f"{'t':>5} {'eased':>7} {'theta':>8} {'left':>8} {'right':>8} {'ratio':>7}")
for t in (0,0.25,0.50,0.70,0.90,1.0):
    e=bez(*X,t); th=-50*e; l,r,rt=row(600,480,40,th)
    print(f"{t*100:>4.0f}% {e:>7.4f} {th:>7.2f}° {l:>8.4f} {r:>8.4f} {rt:>7.4f}")
print("\n=== 相机平面失效阈值 W_max = P/|sin th| - 40 ===")
for P,th in ((600,-50),(600,80),(800,80)):
    print(f"  P={P} th={th}°  ->  W_max = {P/abs(math.sin(math.radians(th)))-40:.1f} px")
print("  => W=480 安全（不失效）；W=1366 在 -50° 时会失效。原§10.3『模型失效』也是 W=1366 假设的产物。")
