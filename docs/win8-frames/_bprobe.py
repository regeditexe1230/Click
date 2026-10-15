from PIL import Image
import numpy as np, glob, os
fs = sorted(glob.glob("docs/win8-frames-b/*.png"))
print(f"frames: {len(fs)}")
for f in (fs[0], fs[8], fs[9], fs[10], fs[12], fs[16], fs[20], fs[-1]):
    a = np.asarray(Image.open(f).convert("RGB")).astype(np.int16)
    h, w, _ = a.shape
    # modal background = most common exact colour
    flat = a.reshape(-1, 3)
    colors, counts = np.unique(flat, axis=0, return_counts=True)
    bg = colors[counts.argmax()]
    diff = np.abs(a - bg).sum(axis=2)
    mask = diff > 30
    cols = mask.sum(axis=0)          # content px per column
    print(f"{os.path.basename(f)}  {w}x{h}  bg={tuple(bg)}  bgshare={counts.max()/len(flat):.3f}  "
          f"cols_with_content={int((cols>0).sum())}  mean_col={cols.mean():.1f}")
