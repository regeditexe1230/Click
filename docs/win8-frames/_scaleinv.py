# 验证「透视强度对标尺不变」：整体等比缩放后 近/远比 是否完全一致。
# 这对实现很关键 —— 说明只需要 P = 0.4393 * 视口宽，不必关心设备像素密度。
import math

def ratio(theta_deg, P, W, axis):
    s = math.sin(math.radians(theta_deg))
    dl = P + axis * s
    dr = P + (W + axis) * s
    if dl <= 0 or dr <= 0:
        return None
    hl, hr = P / dl, P / dr
    lo, hi = min(hl, hr), max(hl, hr)
    return hi / lo

W0, P0, A0 = 1366.0, 600.0, 40.0
print(f"Win8 原始标尺: W={W0} P={P0} axis={A0}  -> P/W = {P0/W0:.4f}, axis/W = {A0/W0:.4f}")
print(f"{'θ':>6} {'原始':>12} {'×0.5':>12} {'×2':>12} {'×0.293(≈393dp)':>16}  一致?")
for th in (80, 60, 40, 20, 10, 5, 0):
    a = ratio(th, P0, W0, A0)
    ks = [0.5, 2.0, 0.2930]
    vals = [ratio(th, P0*k, W0*k, A0*k) for k in ks]
    same = all(v is None or a is None or abs(v - a) < 1e-12 for v in vals)
    fmt = lambda x: "—" if x is None else f"{x:.9f}"
    print(f"{th:>5}° {fmt(a):>12} {fmt(vals[0]):>12} {fmt(vals[1]):>12} {fmt(vals[2]):>16}  {'YES' if same else 'NO'}")

# 反例：如果 P 跟着**卡片**宽度走（本项目现在的做法），强度就会随放大而变。
print("\n反例（本项目当前做法 P = 1.5 * 卡片宽）——卡片从 340px 长到 1366px：")
for card_w in (340.0, 700.0, 1366.0):
    P = 1.5 * card_w
    r = ratio(80.0, P, 1366.0, 40.0)
    print(f"  卡片宽 {card_w:>7.0f}px -> P={P:>7.0f}px -> 80° 时近/远比 = {'—' if r is None else f'{r:.4f}'}")
